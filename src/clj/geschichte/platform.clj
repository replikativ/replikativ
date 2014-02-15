(ns geschichte.platform
  "Platform specific io operations."
  (:refer-clojure :exclude [read-string])
  (:use [clojure.set :as set]
        [lamina.core :refer [enqueue read-channel wait-for-result
                             receive channel siphon map* channel->lazy-seq
                             receive-all]]
        [aleph.http :refer [websocket-client start-http-server]]
        [geschichte.protocols :refer [IByteCoercion -coerce]])
  (:require [geschichte.hash :refer :all])
  (:import java.security.MessageDigest
           java.nio.ByteBuffer))

;; platform specific hash-functionality

(defn byte->hex [b]
  (-> b
      (bit-and 0xff)
      (+ 0x100)
      (Integer/toString 16)
      (.substring 1)))

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

(defn uuid5
  "Generates a uuid5 hash. Our hash version is coded in first 2 bits."
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
  [coll]
  (reduce #(let [[a b] (if (> (count %1)
                              (count %2))
                         [%1 (concat %2 (repeat (- (count %1)
                                                   (count %2))
                                                0))]
                         [(concat %1 (repeat (- (count %2)
                                                (count %1))
                                             0)) %2])]
             (map bit-xor a b))
          (map -coerce (seq coll))))


(extend-protocol IByteCoercion
  java.lang.String
  (-coerce [this] (conj (mapcat benc this)
                        (:string magics)))

  clojure.lang.Symbol
  (-coerce [this] (conj (mapcat benc
                                (concat (namespace this)
                                        (name this)))
                        (:symbol magics)))


  clojure.lang.Keyword
  (-coerce [this] (conj (mapcat benc
                                (concat (namespace this)
                                        (name this)))
                        (:keyword magics)))

  ;; coerce all numeric types to 8 benc, check other hosts behaviour, JavaScript!!!!!
  java.lang.Integer
  (-coerce [this] (conj (mapcat benc (str this))
                        (:number magics)))

  java.lang.Long
  (-coerce [this] (conj (mapcat benc (str this))
                        (:number magics)))

  java.lang.Float
  (-coerce [this] (conj (mapcat benc (str this))
                        (:number magics)))

  java.lang.Double
  (-coerce [this] (conj (mapcat benc (str this))
                        (:number magics)))

  clojure.lang.ISeq
  (-coerce [this] (conj (mapcat -coerce this)
                        (:seq magics)))

  clojure.lang.IPersistentVector
  (-coerce [this] (conj (mapcat -coerce this)
                        (:vector magics)))

  clojure.lang.IPersistentMap
  (-coerce [this] (conj (padded-coerce this)
                        (:map magics)))

  clojure.lang.PersistentTreeMap
  (-coerce [this] (conj (mapcat -coerce (seq this))
                        (:sorted-map magics)))

  clojure.lang.IPersistentSet
  (-coerce [this] (conj (padded-coerce this)
                        (:set magics)))

  clojure.lang.PersistentTreeSet
  (-coerce [this] (conj (mapcat -coerce this)
                        (:sorted-set magics)))

  clojure.lang.IRecord
  (-coerce [this] (conj (concat (mapcat benc (str (type this)))
                                (padded-coerce this))
                        (:record magics))))



(def log println)

(defn uuid
  ([] (java.util.UUID/randomUUID))
  ([val] (java.util.UUID/randomUUID)))

(defn now [] (java.util.Date.))

(def read-string clojure.core/read-string)

(defn put!
  "Puts msg on channel, can be non-blocking.
   Return value undefined."
  [channel msg]
  (enqueue channel msg))

(defn take-all!
  "Take all messages on channel and apply callback.
   Return value undefined."
  [channel callback]
  (receive-all channel callback))

(defn client-connect!
  "Connect a client to address and return channel."
  [address]
   @(websocket-client {:url (str "ws://" address)}))

(defn start-server!
  "Starts a listening server applying dispatch-fn
   to all messages on each connection channel.
   Returns server."
  [this dispatch-fn]
  (start-http-server (fn [ch handshake]
                       (receive-all ch (partial dispatch-fn ch)))
                     {:port (:port @(:state this))
                      :websocket true}))
