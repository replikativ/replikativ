(ns geschichte.platform
  "Platform specific io operations."
  (:use [clojure.set :as set]
        [geschichte.protocols :refer [IByteCoercion -coerce]])
  (:require [geschichte.hash :refer :all]
            [geschichte.debug-channels :as debug]
            [clojure.core.async :as async
             :refer [<! >! timeout chan alt! go go-loop]]
            [org.httpkit.server :refer :all]
            [http.async.client :as cli])
  (:import java.security.MessageDigest
           java.nio.ByteBuffer))

;; platform specific hash-functionality

(defn byte->hex [b]
  (-> b
      (bit-and 0xff)
      (+ 0x100)
      (Integer/toString 16)
      (.substring 1)))

(defn str-hash [bytes]
  (apply str (map byte->hex bytes)))

(defn sha-1 [bytes]
  (let [md (MessageDigest/getInstance "sha-1")
        sarr (into-array Byte/TYPE bytes)]
    (.update md sarr)
    (.digest md)))

(defn- bytes->long [bytes] ;endianness?
  (->> bytes
       (into-array Byte/TYPE)
       ByteBuffer/wrap
       .getLong))

(defn uuid3 []
  (java.util.UUID/randomUUID))

(defn uuid5
  "Generates a uuid5 hash from sha-1 hash.
Our hash version is coded in first 2 bits."
  [sha-hash]
  (let [high  (take 8 sha-hash)
        low (->> sha-hash (drop 8) (take 8))]
    (java.util.UUID. (-> (bytes->long high)
                         (bit-or 0x0000000000005000)
                         (bit-and 0x7fffffffffff5fff)
                         (bit-clear 63)
                         (bit-clear 62))
                     (-> (bytes->long low)
                         (bit-set 63)
                         (bit-clear 62)))))

(defn padded-coerce
  "Commutatively coerces elements of collection, padding ensures all bits
are included in the hash."
  [coll hash-fn]
  (reduce (fn padded-xor [acc elem]
            (let [[a b] (if (> (count acc)
                               (count elem))
                          [acc (concat elem (repeat (- (count acc)
                                                       (count elem))
                                                    0))]
                          [(concat acc (repeat (- (count elem)
                                                  (count acc))
                                               0)) elem])]
              (map bit-xor a b)))
          '()
          (map #(-coerce % hash-fn) (seq coll))))


;; TODO print edn, read and then coerce that, Date
(extend-protocol IByteCoercion
  java.lang.String
  (-coerce [this hash-fn] (conj (mapcat benc this)
                                (:string magics)))

  clojure.lang.Symbol
  (-coerce [this hash-fn] (conj (mapcat benc
                                        (concat (namespace this)
                                                (name this)))
                                (:symbol magics)))


  clojure.lang.Keyword
  (-coerce [this hash-fn] (conj (mapcat benc
                                        (concat (namespace this)
                                                (name this)))
                                (:keyword magics)))

  java.lang.Integer
  (-coerce [this hash-fn] (conj (mapcat benc (str this))
                                (:number magics)))

  java.lang.Long
  (-coerce [this hash-fn] (conj (mapcat benc (str this))
                                (:number magics)))

  java.lang.Float
  (-coerce [this hash-fn] (conj (mapcat benc (str this))
                                (:number magics)))

  java.lang.Double
  (-coerce [this hash-fn] (conj (mapcat benc (str this))
                                (:number magics)))

  java.util.UUID
  (-coerce [this hash-fn] (conj (mapcat benc (str this))
                                (:uuid magics)))

  java.util.Date
  (-coerce [this hash-fn] (conj (mapcat benc (str this))
                                (:inst magics)))

  clojure.lang.ISeq
  (-coerce [this hash-fn] (hash-fn (conj (mapcat #(-coerce % hash-fn) this)
                                         (:seq magics))))

  clojure.lang.IPersistentVector
  (-coerce [this hash-fn] (hash-fn (conj (mapcat #(-coerce % hash-fn) this)
                                         (:vector magics))))

  clojure.lang.IPersistentMap
  (-coerce [this hash-fn] (hash-fn (conj (padded-coerce this hash-fn)
                                         (:map magics))))

  clojure.lang.IPersistentSet
  (-coerce [this hash-fn] (hash-fn (conj (padded-coerce this hash-fn)
                                         (:set magics))))

  ;; implement tagged literal instead
  clojure.lang.IRecord
  (-coerce [this hash-fn] (conj (concat (mapcat benc (str (type this)))
                                        (padded-coerce this hash-fn))
                                (:record magics))))


(defn edn-hash
  ([val] (edn-hash val sha-1))
  ([val hash-fn]
     (let [coercion (map byte (-coerce val hash-fn))]
       (if (or (symbol? val) ;; TODO simplify?
               (keyword? val)
               (string? val)
               (number? val)
               (= (type val) java.util.UUID))
         (hash-fn coercion)
         coercion))))



(def log println)

(defn uuid
  ([] (uuid3))
  ([val] (-> val
             edn-hash
             uuid5)))

(defn now [] (java.util.Date.))


(defn client-connect!
  "Connect a client to address and return channel."
  [ip port in out]
  (let [http-client (cli/create-client)] ;; TODO use as singleton var?
    (try
      (cli/websocket http-client (str "ws://" ip ":" port)
                     :open (fn [ws] (println "ws-opened" ws)
                             (go-loop [m (<! out)]
                                      (when m
                                        (println "client sending msg to:" ip port m)
                                        (cli/send ws :text (str m))
                                        (recur (<! out)))))
                     :text (fn [ws ms]
                             (let [m (read-string ms)]
                               (println "client received msg from:" ip port m)
                               (async/put! in m)))
                     :close (fn [ws code reason]
                              (println "closing" ws code reason)
                              (async/close! in)
                              (async/close! out))
                     :error (fn [ws err] (println "ws-error" err)
                              (.printStackTrace err)))
      (catch Exception e
        (println "client-connect error:" e)))))


(defn start-server!
  [ip port]
  (let [channel-hub (atom {})
        conns (chan)
        log (atom {})
        handler (fn [request]
                  (let [in (debug/chan log [(str ip ":" port) :in])
                        out (debug/chan log [(str ip ":" port) :out])]
                    (async/put! conns [in out])
                    (with-channel request channel
                      (swap! channel-hub assoc channel request)
                      (go-loop [m (<! out)]
                               (when m
                                 (println "server sending msg:" ip port m)
                                 (send! channel (str m))
                                 (recur (<! out))))
                      (on-close channel (fn [status]
                                          (println "channel closed:" status)
                                          (swap! channel-hub dissoc channel)
                                          (async/close! in)
                                          (async/close! out)))
                      (on-receive channel (fn [data]
                                            (println "server received data:" ip port data)
                                            (async/put! in (read-string data)))))))]
    {:new-conns conns
     :channel-hub channel-hub
     :log log
     :server (run-server handler {:port port})}))


#_(def server (start-server2! "127.0.0.1" 19090))
#_((:server server))
