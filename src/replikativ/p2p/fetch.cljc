(ns replikativ.p2p.fetch
  "Fetching middleware for replikativ. This middleware covers the
  exchange of the actual content (commits and transactions, not
  metadata) of CRDTs."
  (:require [replikativ.environ :refer [store-blob-trans-id]]
            [replikativ.protocols :refer [-missing-commits -downstream]]
            [replikativ.platform-log :refer [debug info warn error]]
            [replikativ.crdt.materialize :refer [ensure-crdt]]
            #?(:clj [full.async :refer [<? <<? go-try go-for go-loop-try go-loop-try>]])
            [konserve.core :as k]
            [clojure.set :as set]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.core.async :as async
                      :refer [>! timeout chan alt! go put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [full.cljs.async :refer [<? <<? go-for go-try go-loop-try go-loop-try> alt?]]))
  #?(:clj (:import [java.io ByteArrayOutputStream])))



(defn- not-in-store?! [store transactions pred]
  (->> (go-for [tx transactions
                :when (pred (first tx))
                id tx
                :when (not (<? (k/exists? store id)))]
               id)
       (async/into #{})))


(defn- new-transactions! [store transactions]
  (not-in-store?! store transactions #(not= % store-blob-trans-id)))


(defn- new-blobs! [store transactions]
  (go-try (->> (not-in-store?! store transactions #(= % store-blob-trans-id))
               <?
               (filter #(not= % store-blob-trans-id)))))

(defn cached-crdt [store atomic-fetch-atom [user crdt-id] pub]
  (go-try
   (let [crdt (or (@atomic-fetch-atom [user crdt-id])
                  (<? (ensure-crdt store [user crdt-id] pub)))
         crdt (-downstream crdt (:op pub))]
     crdt)))

(defn fetch-commit-values!
  "Resolves all commits recursively for all nested CRDTs. Starts with commits in pub."
  [out fetched-ch store atomic-fetch-atom [user crdt-id] pub pub-id]
  (go-try (let [crdt (<? (cached-crdt store atomic-fetch-atom [user crdt-id] pub))]
            (loop [ncs (<? (-missing-commits crdt store out fetched-ch (:op pub)))
                   cvs {}]
              (if (empty? ncs) cvs
                  (do
                    (info "starting to fetch " ncs "for" pub-id)
                    (>! out {:type :fetch/edn
                             :id pub-id
                             :ids ncs})
                    (let [ncvs (merge cvs (select-keys (:values (<? fetched-ch)) ncs))
                          ncs  (->> (go-for [crdt (mapcat :crdt-refs (vals ncvs))]
                                            (let [nc (<? (-missing-commits (assoc crdt :store store)
                                                                           store
                                                                           out fetched-ch nil))]
                                              nc))
                                    <<?
                                    (apply set/union))]
                      (recur (set (filter (comp not ncvs) ncs)) ;; break crdt recursion
                             ncvs))))))))


;; TODO don't fetch too huge blocks at once, slice
(defn fetch-and-store-txs-values! [out fetched-ch store txs pub-id]
  (go-try (let [ntc (<? (new-transactions! store txs))]
            ;; transactions first
            (when-not (empty? ntc)
              (debug "fetching new transactions" ntc "for" pub-id)
              (>! out {:type :fetch/edn
                       :id pub-id
                       :ids ntc})
              (if-let [tvs (select-keys (:values (<? fetched-ch)) ntc)]
                (doseq [[id val] tvs]
                  (debug "trans assoc-in" id (pr-str val))
                  (<? (k/assoc-in store [id] val))))))))


(defn fetch-and-store-txs-blobs! [out binary-fetched-ch store txs pub-id]
  (go-try (let [nblbs (<? (new-blobs! store txs))]
            (when-not (empty? nblbs)
              (debug "fetching new blobs" nblbs "for" pub-id)
              (<? (go-loop-try [[to-fetch & r] nblbs]
                               (when to-fetch
                                 ;; recheck store to avoid double fetching of large blobs
                                 (if (<? (k/exists? store to-fetch))
                                   (recur r)
                                   (do
                                     (>! out {:type :fetch/binary
                                              :id pub-id
                                              :blob-id to-fetch})
                                     (let [{:keys [value]} (<? binary-fetched-ch)]
                                       (debug "blob assoc" to-fetch)
                                       (<? (k/bassoc store to-fetch value))
                                       (recur r)))))))))))


(defn store-commits! [store cvs]
  (go-try (<<? (go-for [[k v] cvs]
                       (<? (k/assoc-in store [k] v))))))

(defn- fetch-new-pub
  "Fetch all external references."
  [store atomic-fetch-atom err-ch p pub-ch [in out]]
  (let [fetched-ch (chan)
        binary-fetched-ch (chan)]
    (sub p :fetch/edn-ack fetched-ch)
    (sub p :fetch/binary-ack binary-fetched-ch)
    (go-loop-try> err-ch [{:keys [type downstream values peer user crdt-id] :as m} (<? pub-ch)]
                  (when m
                    ;; TODO abort complete update on error gracefully
                    (let [cvs (<? (fetch-commit-values! out fetched-ch store
                                                        atomic-fetch-atom
                                                        [user crdt-id] downstream (:id m)))
                          txs (mapcat :transactions (vals cvs))]
                      (<? (fetch-and-store-txs-values! out fetched-ch store txs (:id m)))
                      (<? (fetch-and-store-txs-blobs! out binary-fetched-ch store txs (:id m)))
                      (<? (store-commits! store cvs))
                      (swap! atomic-fetch-atom
                             assoc
                             [user crdt-id]
                             (<? (cached-crdt store atomic-fetch-atom [user crdt-id] downstream))))
                    #_(<<? (go-for [[user crdts] downstream
                                    [crdt-id pub] crdts]
                                   (let [cvs (<? (fetch-commit-values! out fetched-ch store
                                                                       atomic-fetch-atom
                                                                       [user crdt-id] pub (:id m)))
                                         txs (mapcat :transactions (vals cvs))]
                                     (<? (fetch-and-store-txs-values! out fetched-ch store txs (:id m)))
                                     (<? (fetch-and-store-txs-blobs! out binary-fetched-ch store txs (:id m)))
                                     (<? (store-commits! store cvs))
                                     (swap! atomic-fetch-atom
                                            assoc
                                            [user crdt-id]
                                            (<? (cached-crdt store atomic-fetch-atom [user crdt-id] pub))))))
                    (>! in m)
                    (recur (<? pub-ch))))))

(defn- fetched [store err-ch fetch-ch out]
  (go-loop-try> err-ch [{:keys [ids peer id] :as m} (<? fetch-ch)]
    (when m
      (info "fetch:" ids)
      (let [fetched (->> (go-for [id ids] [id (<? (k/get-in store [id]))])
                         (async/into {})
                         <?)]
        (>! out {:type :fetch/edn-ack
                 :values fetched
                 :id id
                 :peer peer})
        (debug "sent fetched:" fetched)
        (recur (<? fetch-ch))))))

(defn- binary-fetched [store err-ch binary-fetch-ch out]
  (go-loop-try> err-ch [{:keys [id peer blob-id] :as m} (<? binary-fetch-ch)]
    (when m
      (info "binary-fetch:" id)
      (>! out {:type :fetch/binary-ack
               :value (<? (k/bget store blob-id
                                 #?(:clj #(with-open [baos (ByteArrayOutputStream.)]
                                             (io/copy (:input-stream %) baos)
                                             (.toByteArray baos))
                                    :cljs identity)))
               :blob-id blob-id
               :id id
               :peer peer})
      (debug "sent blob " id ": " blob-id)
      (recur (<? binary-fetch-ch)))))


(defn- fetch-dispatch [{:keys [type] :as m}]
  (case type
    :pub/downstream :pub/downstream
    :fetch/edn :fetch/edn
    :fetch/edn-ack :fetch/edn-ack
    :fetch/binary :fetch/binary
    :fetch/binary-ack :fetch/binary-ack
    :unrelated))

(defn fetch [store atomic-fetch-atom err-ch [peer [in out]]]
  (let [new-in (chan)
        p (pub in fetch-dispatch)
        pub-ch (chan 1e6) ;; TODO merge pending downstream ops
        fetch-ch (chan)
        binary-fetch-ch (chan)]
    (sub p :pub/downstream pub-ch)
    (fetch-new-pub store atomic-fetch-atom err-ch p pub-ch [new-in out])

    (sub p :fetch/edn fetch-ch)
    (fetched store err-ch fetch-ch out)

    (sub p :fetch/binary binary-fetch-ch)
    (binary-fetched store err-ch binary-fetch-ch out)

    (sub p :unrelated new-in)
    [peer [new-in out]]))
