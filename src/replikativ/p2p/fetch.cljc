(ns replikativ.p2p.fetch
  "Fetching middleware for replikativ. This middleware covers the
  exchange of the actual content (commits and transactions, not
  metadata) of CRDTs."
  (:require [replikativ.environ :refer [store-blob-trans-id]]
            [replikativ.protocols :refer [-missing-commits -downstream]]
            [kabel.platform-log :refer [debug info warn error]]
            [replikativ.crdt.materialize :refer [ensure-crdt]]
            #?(:clj [full.async :refer [<? <<? go-try go-loop-try]])
            #?(:clj [full.lab :refer [go-for go-loop-super]])
            [konserve.core :as k]
            [clojure.set :as set]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.core.async :as async
                      :refer [>! timeout chan alt! go put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [full.async :refer [<? <<? go-try go-loop-try alt?]]
                            [full.lab :refer [go-for go-loop-super]]))
  #?(:clj (:import [java.io ByteArrayOutputStream])))

;; TODO
;; decouple fetch processes of different [user crdt-id] pairs.

;; WIP size-limited buffer:
;; maximum blob size 2 MiB
;; check edn value size
;; load values until local buffer size exceeded
;; send incrementally

(defn- not-in-store?! [store transactions pred]
  (go-loop-try [not-in-store #{}
                [tx & rtxs] transactions]
               (if-not tx
                 not-in-store
                 (if-not (pred (first tx))
                   (recur not-in-store rtxs)
                   (recur (loop [[id & rids] tx
                                 not-in-store not-in-store]
                            (if-not id
                              not-in-store
                              (recur rids (if (<? (k/exists? store id))
                                            not-in-store
                                            (conj not-in-store id)))))
                          rtxs))))
  ;; TODO faster than more declarative version:
  #_(->> (go-for [tx transactions
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

(defn fetch-values [fetched-ch]
  (go-loop-try [f (<? fetched-ch)
                vs {}]
               (let [v (:values f)]
                 (if (:final f)
                   (merge vs v)
                   (recur (<? fetched-ch) (merge vs v))))))

(defn fetch-commit-values!
  "Resolves all commits recursively for all nested CRDTs. Starts with commits in pub."
  [out fetched-ch cold-store mem-store [user crdt-id] pub pub-id ncs]
  (go-try (when-not (empty? ncs)
            (>! out {:type :fetch/edn
                     :id pub-id
                     :ids ncs})
            (<? (fetch-values fetched-ch)))))



(defn fetch-and-store-txs-values! [out fetched-ch store txs pub-id]
  (go-loop-try [ntc (<? (new-transactions! store txs))
                first true]
               (let [size 100
                     slice (take size ntc)
                     rest (drop size ntc)]
                 ;; transactions first
                 (when-not (empty? slice)
                   (info {:event :fetching :slice-count (count slice) :pub-id pub-id})
                   (when first
                     (>! out {:type :fetch/edn
                              :id pub-id
                              :ids (set slice)}))
                   ;; fetch already while we are serializing
                   (when-not (empty? rest)
                     (>! out {:type :fetch/edn
                              :id pub-id
                              :ids (set (take size rest))}))
                   (loop [f (<? fetched-ch)]
                     (if f
                       (let [tvs (:values f)]
                         (doseq [[id val] (select-keys tvs slice)]
                           (debug {:event :trans-assoc-in :id id})
                           (<? (k/assoc-in store [id] val)))
                         (when-not (:final f)
                           (recur (<? fetched-ch))))
                       (throw (ex-info "Fetching transactions disrupted."
                                       {:to-fetch slice}))))
                   (recur rest false)))))


(defn fetch-and-store-txs-blobs! [out binary-fetched-ch store txs pub-id]
  (go-try (let [nblbs (<? (new-blobs! store txs))]
            (when-not (empty? nblbs)
              (debug {:event :fetching-new-blobs :blobs nblbs :pub-id pub-id})
              (<? (go-loop-try [[to-fetch & r] nblbs]
                               (when to-fetch
                                 ;; recheck store to avoid double fetching of large blobs
                                 (if (<? (k/exists? store to-fetch))
                                   (recur r)
                                   (do
                                     (>! out {:type :fetch/binary
                                              :id pub-id
                                              :blob-id to-fetch})
                                     (if-let [{:keys [value]} (<? binary-fetched-ch)]
                                       (do
                                         (debug {:event :blob-assoc :blob-id to-fetch})
                                         (<? (k/bassoc store to-fetch value))
                                         (recur r))
                                       (throw (ex-info "Fetching bin. blob disrupted." {:to-fetch to-fetch}))))))))))))


(defn store-commits! [store cvs]
  (go-try
   (doseq [[k v] cvs]
     (<? (k/assoc-in store [k] v)))))

(defn- fetch-new-pub
  "Fetch all external references."
  [cold-store mem-store p pub-ch [in out]]
  (let [fetched-ch (chan)
        binary-fetched-ch (chan)]
    (sub p :fetch/edn-ack fetched-ch)
    (sub p :fetch/binary-ack binary-fetched-ch)
    (go-loop-super [{:keys [type downstream values user crdt-id] :as m} (<? pub-ch)]
                   (when m
                     (let [crdt (<? (ensure-crdt cold-store mem-store [user crdt-id] (:crdt downstream)))
                           ncs (<? (-missing-commits crdt cold-store out fetched-ch (:op downstream)))
                           max-commits 1000]
                       (info {:event :fetching-new-values
                              :pub-id (:id m) :crdt [user crdt-id]
                              :remote-peer (:sender m) :new-commit-count (count ncs)})
                       (loop [ncs ncs
                              left (count ncs)]
                         (when-not (empty? ncs)
                           (let [ncs-set (set (take max-commits ncs))
                                 _ (info {:event :fetching-commits
                                          :commit-count (count ncs-set)
                                          :commits-left left
                                          :pub-id (:id m)})
                                 cvs (<? (fetch-commit-values! out fetched-ch
                                                               cold-store mem-store
                                                               [user crdt-id] downstream (:id m)
                                                               ncs-set))
                                 txs (mapcat :transactions (vals cvs))]
                             (<? (fetch-and-store-txs-values! out fetched-ch cold-store txs (:id m)))
                             (<? (fetch-and-store-txs-blobs! out binary-fetched-ch cold-store txs (:id m)))
                             (<? (store-commits! cold-store cvs))
                             (recur (drop max-commits ncs) (- left max-commits))))))
                     (>! in m)
                     (recur (<? pub-ch))))))

(defn- fetched [store fetch-ch out]
  (go-loop-super [{:keys [ids id] :as m} (<? fetch-ch)]
                 (when m
                   (info {:event :fetched :pub-id id :count (count ids) :remote-peer (:sender m)})
                   (loop [ids (seq ids)]
                     ;; TODO variable sized replies
                     ;; load values and stop when too large for memory instead of fixed limit
                     (let [size 100
                           slice (take size ids)
                           rest (drop size ids)]
                       (when-not (empty? slice)
                         (info {:event :loading-slice :count size :pub-id id})
                         (>! out {:type :fetch/edn-ack
                                  :values (loop [[id & r] (seq ids)
                                                 res {}]
                                            (if id
                                              (recur r (assoc res id (<? (k/get-in store [id]))))
                                              res))
                                  :id id
                                  :final (empty? rest)})
                         (recur rest))))
                   (debug {:event :sent-all-fetched :pub-id id})
                   (recur (<? fetch-ch)))))

(defn- binary-fetched [store binary-fetch-ch out]
  (go-loop-super [{:keys [id blob-id] :as m} (<? binary-fetch-ch)]
                 (when m
                   (info {:event :binary-fetch :pub-id id})
                   (>! out {:type :fetch/binary-ack
                            :value (<? (k/bget store blob-id
                                               #?(:clj #(with-open [baos (ByteArrayOutputStream.)]
                                                          (io/copy (:input-stream %) baos)
                                                          (.toByteArray baos))
                                                  :cljs identity)))
                            :blob-id blob-id
                            :id id})
                   (debug {:type :sent-blob :pub-id id :blob-id blob-id})
                   (recur (<? binary-fetch-ch)))))


(defn- fetch-dispatch [{:keys [type] :as m}]
  (case type
    :pub/downstream :pub/downstream
    :fetch/edn :fetch/edn
    :fetch/edn-ack :fetch/edn-ack
    :fetch/binary :fetch/binary
    :fetch/binary-ack :fetch/binary-ack
    :unrelated))

(defn fetch [[peer [in out]]]
  (let [{{:keys [cold-store mem-store]} :volatile} @peer
        new-in (chan)
        p (pub in fetch-dispatch)
        pub-ch (chan 10000)
        fetch-ch (chan)
        binary-fetch-ch (chan)]
    (sub p :pub/downstream pub-ch)
    (fetch-new-pub cold-store mem-store p pub-ch [new-in out])

    (sub p :fetch/edn fetch-ch)
    (fetched cold-store fetch-ch out)

    (sub p :fetch/binary binary-fetch-ch)
    (binary-fetched cold-store binary-fetch-ch out)

    (sub p :unrelated new-in)
    [peer [new-in out]]))
