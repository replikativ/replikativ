(ns replikativ.p2p.hash
  "Hash checksumming middleware for replikativ."
  (:require [replikativ.platform-log :refer [debug info warn error]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.protocols :refer [-commit-value]]
            [clojure.set :as set]
            #?(:clj [full.async :refer [go-try go-loop-try <?]])
            #?(:clj [clojure.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                             :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [full.cljs.async :refer [<? <<? go-for go-try go-loop-try go-loop-try> alt?]])))

(defn- check-hash [fetched-ch new-in]
  (go-loop-try [{:keys [values peer] :as f} (<? fetched-ch)]
               (when f
                 (doseq [[id val] values]
                   (let [val (if (and (:crdt val)
                                      (:version val)
                                      (:transactions val)) ;; TODO assume commit
                               (let [crdt (<? (key->crdt (:crdt val)))]
                                 (-commit-value crdt val))
                               val)]
                     (when (not= id (*id-fn* val))
                       (let [msg (str "CRITICAL: Fetched edn ID: "  id
                                      " does not match HASH "  (*id-fn* val)
                                      " for value " (pr-str val)
                                      " from " peer)]
                         (error msg)
                         #?(:clj (throw (IllegalStateException. msg))
                            :cljs (throw msg))))))
                 (>! new-in f)
                 (recur (<? fetched-ch)))))

(defn- check-binary-hash [binary-out binary-fetched out new-in]
  (go-loop-try [{:keys [blob-id] :as bo} (<? binary-out)]
               (>! out bo)
               (let [{:keys [peer value] :as blob} (<? binary-fetched)
                     val-id (*id-fn* value)]
                 (when (not= val-id blob-id)
                   (let [msg (str "CRITICAL: Fetched binary ID: " blob-id
                                  " does not match HASH " val-id
                                  " for value " (take 20 (map byte value))
                                  " from " peer)]
                     (error msg)
                     #?(:clj (throw (IllegalStateException. msg))
                        :cljs (throw msg))))
                 (>! new-in blob))
               (recur (<? binary-out))))

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
  [[peer [in out]]]
  (let [new-in (chan)
        new-out (chan)
        p-out (pub new-out hash-out-dispatch)
        p-in (pub in hash-dispatch)
        fetched-ch (chan)
        binary-out (chan)
        binary-fetched (chan)]
    (sub p-in :fetch/edn-ack fetched-ch)
    (check-hash fetched-ch new-in)

    (sub p-in :fetch/binary-ack binary-fetched)
    (sub p-out :fetch/binary binary-out)
    (check-binary-hash binary-out binary-fetched out new-in)

    (sub p-in :unrelated new-in)
    (sub p-out :unrelated out)
    [peer [new-in new-out]]))
