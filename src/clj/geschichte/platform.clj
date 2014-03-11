(ns geschichte.platform
  "Platform specific io operations."
  (:use [clojure.set :as set]
        [lamina.core :refer [enqueue read-channel wait-for-result
                             receive channel siphon map* channel->lazy-seq
                             receive-all]]
        [aleph.http :refer [websocket-client start-http-server]]
        [geschichte.protocols :refer [IAsyncKeyValueStore IByteCoercion -coerce]])
  (:require [geschichte.hash :refer :all]
            [clojure.core.async :as async
               :refer [<! >! timeout chan alt! go go-loop]])
  (:import java.security.MessageDigest
           java.nio.ByteBuffer))


(defrecord MemAsyncKeyValueStore [state]
  IAsyncKeyValueStore
  (-get-in [this key-vec] (go (get-in @state key-vec)))
  (-exists? [this key-vec] (go (not (not (get-in @state key-vec)))))
  (-assoc-in [this key-vec value] (go (swap! state assoc-in key-vec value)
                                      nil))
  (-update-in [this key-vec up-fn] (go (get-in (swap! state update-in key-vec up-fn)
                                               key-vec))))



(defn new-store []
  (MemAsyncKeyValueStore. (atom {})))

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
  (let [lchan @(websocket-client {:url (str "ws://" (str ip ":" port))})]
    (receive-all lchan #(go (let [m (read-string %)]
                              (println "client received msg:" m)
                              (>! in m))))
    (go-loop [out-msg (<! out)]
             (when out-msg
               (println "client sending msg:" out-msg)
               (enqueue lchan (str out-msg))
               (recur (<! out))))
    [in out]))

(defn start-server!
  "Starts a listening server applying dispatch-fn
   to all messages on each connection channel.
   Returns server."
  [ip port]
  (let [conns (chan)
        server (start-http-server
                (fn [lchan handshake]
                  (let [in (chan)
                        out (chan)]
                    (receive-all lchan #(go (let [m (read-string %)]
                                              (println "server received msg;" m)
                                              (>! in m))))
                    (go-loop [out-msg (<! out)]
                             (when out-msg
                               (println "server sending msg:" out-msg)
                               (enqueue lchan (str out-msg))
                               (recur (<! out))))
                    (go (>! conns [in out]))))
                {:port port
                 :websocket true})]
    {:new-conns conns
     :server server}))
