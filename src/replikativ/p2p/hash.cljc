(ns replikativ.p2p.hash
  "Hash checksumming middleware for replikativ."
  (:require [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.protocols :refer [-commit-value]]
            [clojure.set :as set]
            #?(:clj [kabel.platform-log :refer [debug info warn error]])
            #?(:clj [superv.async :refer [go-try go-loop-try <? <<?]])
            #?(:clj [clojure.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                             :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [<? <<? go-try go-loop-try alt?]]
                            [kabel.platform-log :refer [debug info warn error]])))

(defn- check-hash [S fetched-ch new-in]
  (go-loop-try S [{:keys [values peer] :as f} (<? S fetched-ch)]
               (when f
                 (let [check-ch (chan)
                       checked-ch (chan)]
                   (async/onto-chan check-ch (seq values))
                   (async/pipeline 4 checked-ch
                                   (map (fn [[id val]]
                                          (let [val (if (and (:crdt val)
                                                             (:version val)
                                                             (:transactions val)) ;; TODO assume commit
                                                      (let [crdt (key->crdt (:crdt val))]
                                                        (-commit-value crdt val))
                                                      val)]
                                            (if (not= id (*id-fn* val))
                                              (let [msg {:event :hashing-error
                                                         :expected-id id
                                                         :hashed-id (*id-fn* val)
                                                         :value val
                                                         :remote-peer peer}]
                                                (error msg)
                                                (ex-info "Critical hashing error." msg))
                                              :checked))))
                                   check-ch)
                   (<<? S checked-ch)
                   (>! new-in f)
                   (recur (<? S fetched-ch))))))

(defn- check-binary-hash [S binary-out binary-fetched out new-in]
  (go-loop-try S [{:keys [blob-id] :as bo} (<? S binary-out)]
               (when bo
                 (>! out bo)
                 (let [{:keys [peer value] :as blob} (<? S binary-fetched)
                       val-id (*id-fn* value)]
                   (when (not= val-id blob-id)
                     (let [msg {:event :hashing-error
                                :expected-id blob-id
                                :hashed-id (*id-fn* value)
                                :first-20-bytes (take 20 (map byte value))
                                :remote-peer peer}]
                       (error msg)
                       (throw (ex-info "CRITICAL blob hashing error." msg))))
                   (>! new-in blob))
                 (recur (<? S binary-out)))))

(defn- hash-dispatch [{:keys [type]}]
  (case type
    :fetch/edn-ack :fetch/edn-ack
    :fetch/binary-ack :fetch/binary-ack
    :unrelated))

(defn- hash-out-dispatch [{:keys [type]}]
  (case type
    :fetch/binary :fetch/binary
    :unrelated))


(defn ensure-hash
  "Ensures correct uuid hashes of incoming data (commits and transactions)."
  [[S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)
        p-out (pub new-out hash-out-dispatch)
        p-in (pub in hash-dispatch)
        fetched-ch (chan)
        binary-out (chan)
        binary-fetched (chan)]
    (sub p-in :fetch/edn-ack fetched-ch)
    (check-hash S fetched-ch new-in)

    (sub p-in :fetch/binary-ack binary-fetched)
    (sub p-out :fetch/binary binary-out)
    (check-binary-hash S binary-out binary-fetched out new-in)

    (sub p-in :unrelated new-in)
    (sub p-out :unrelated out)
    [S peer [new-in new-out]]))
