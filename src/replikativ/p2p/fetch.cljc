(ns replikativ.p2p.fetch
  "Fetching middleware for replikativ. This middleware covers the
  exchange of the actual content (commits and transactions, not
  metadata) of CRDTs."
  (:require [replikativ.environ :refer [store-blob-trans-id *id-fn*]]
            [replikativ.protocols :refer [-missing-commits -downstream]]
            #?(:clj [kabel.platform-log :refer [debug info warn error]])
            [replikativ.crdt.materialize :refer [ensure-crdt]]
            #?(:clj [superv.async :refer [<? <<? go-try go-loop-try
                                          go-for go-loop-super put?]])
            [konserve.core :as k]
            [clojure.set :as set]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.core.async :as async
                      :refer [<! >! timeout chan alt! go put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [<! >! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [<? <<? go-try go-loop-try alt?
                                                  go-for go-loop-super]]
                            [kabel.platform-log :refer [debug info warn error]]))
  #?(:clj (:import [java.io ByteArrayOutputStream])))

;; TODO
;; decouple fetch processes of different [user crdt-id] pairs.

;; WIP size-limited buffer:
;; maximum blob size 2 MiB
;; check edn value size
;; load values until local buffer size exceeded
;; send incrementally

(defn- not-in-store?! [S store transactions pred]
  (go-loop-try S [not-in-store #{}
                  [tx & rtxs] transactions]
    (if-not tx
      not-in-store
      (if-not (pred (first tx))
        (recur not-in-store rtxs)
        (recur (loop [[id & rids] tx
                      not-in-store not-in-store]
                 (if-not id
                   not-in-store
                   (recur rids (if (<? S (k/exists? store id))
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


(defn- new-transactions! [S store transactions]
  (not-in-store?! S store transactions #(not= % store-blob-trans-id)))


(defn- new-blobs! [S store transactions]
  (go-try S
    (->> (not-in-store?! S store transactions #(= % store-blob-trans-id))
         (<? S)
         (filter #(not= % store-blob-trans-id)))))

(defn fetch-values [S fetched-ch]
  (go-loop-try S [f (<? S fetched-ch)
                  vs {}]
    (if-not f
      (do
        (debug {:event :fetching-values-interrupted :fetched-values vs})
        (throw (ex-info "Fetching values interrupted."
                        {:fetched-values vs})))
      (let [v (:values f)]
        (if (:final f)
          (merge vs v)
          (recur (<? S fetched-ch) (merge vs v)))))))

(defn fetch-commit-values!
  "Resolves all commits recursively for all nested CRDTs. Starts with commits in pub."
  [S out fetched-ch cold-store mem-store [user crdt-id] pub pub-id ncs]
  (go-try S
    (when-not (empty? ncs)
      (>! out {:type :fetch/edn
               :id pub-id
               :ids ncs})
      (<? S (fetch-values S fetched-ch)))))



(defn fetch-and-store-txs-values! [S out fetched-ch store txs pub-id peer hash?]
  (go-loop-try S [ntc (<? S (new-transactions! S store txs))
                  first true]
    (let [size 1000
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
        (loop [f (<? S fetched-ch)]
          (if f
            (let [tvs (:values f)
                  to-assoc-ch (chan)
                  assoced-ch (chan)]
              (async/onto-chan to-assoc-ch (seq (select-keys tvs slice)))
              (async/pipeline-async
               8 assoced-ch
               (fn [[id val] ch]
                 (go-try S
                   (if (and hash? (not= id (*id-fn* val)))
                     (let [msg {:event :hashing-error
                                :expected-id id
                                :hashed-id (*id-fn* val)
                                :value val
                                :remote-peer peer}]
                       (error msg)
                       (>! ch  (ex-info "Critical hashing error." msg)))
                     (do
                       (debug {:event :trans-assoc-in :id id})
                       (<? S (k/assoc-in store [id] val))
                       (>! ch :assoced)))
                   (close! ch)))
               to-assoc-ch)
              (<<? S assoced-ch)
              (when-not (:final f)
                (recur (<? S fetched-ch))))
            (throw (ex-info "Fetching transactions disrupted."
                            {:to-fetch slice}))))
        (recur rest false)))))


(defn fetch-and-store-txs-blobs! [S out binary-fetched-ch store txs pub-id hash?]
  (go-try S
    (let [nblbs (<? S (new-blobs! S store txs))
          nblbs-set (set nblbs)]
      (when-not (empty? nblbs)
        (debug {:event :fetching-new-blobs :blobs nblbs :pub-id pub-id})
        (let [to-assoc-ch (chan)
              assoced-ch (chan)]
          (async/onto-chan to-assoc-ch nblbs)
          ;; NOTE: we do out of order processing to speed things up here.
          (async/pipeline-async
           50 assoced-ch
           (fn [to-fetch ch]
             (go-try S
               (debug {:event :bassoc-in :id to-fetch})
               (>! out {:type :fetch/binary
                        :id pub-id
                        :blob-id to-fetch})
               (if-let [{:keys [value]} (<? S binary-fetched-ch)]
                 (let [id (*id-fn* value)]
                   (when (and hash? (not (nblbs-set id)))
                     (throw (ex-info "Hash not requested!"
                                     {:id id
                                      :nblbs nblbs})))
                   (debug {:event :blob-assoc :blob-id id})
                   (<? S (k/bassoc store id value)))
                 (throw (ex-info "Fetching bin. blob disrupted." {})))
               (>! ch :assoced)
               (close! ch)))
           to-assoc-ch)
          (<<? S assoced-ch))))))


(defn store-commits! [S store cvs peer hash?]
  (go-try S
    (let [to-assoc-ch (chan)
          assoced-ch (chan)]
      (async/onto-chan to-assoc-ch (seq cvs))
      (async/pipeline-async
       8 assoced-ch
       (fn [[id val] ch]
         (go-try S
           (if (and hash? (not= (*id-fn* (select-keys val #{:transactions :parents})) id))
             (let [msg {:event :hashing-error
                        :expected-id id
                        :hashed-id (*id-fn* (select-keys val #{:transactions :parents}))
                        :value val
                        :remote-peer peer}]
               (error msg)
               (>! ch (ex-info "Critical hashing error." msg))
               (close! ch)))
           (do
             (<? S (k/assoc-in store [id] val))
             (>! ch :assoced)
             (close! ch))))
       to-assoc-ch)
      (<<? S assoced-ch)
      true)))

(defn- fetch-new-pub
  "Fetch all external references."
  [S cold-store mem-store p pub-ch [in out] hash?]
  (let [fetched-ch (chan)
        binary-fetched-ch (chan)]
    (sub p :fetch/edn-ack fetched-ch)
    (sub p :fetch/binary-ack binary-fetched-ch)
    (go-loop-super S [{:keys [type downstream values user crdt-id] :as m} (<? S pub-ch)]
      (when m
        (let [crdt (<? S (ensure-crdt S cold-store mem-store [user crdt-id]
                                      (:crdt downstream)))
              ncs (<? S (-missing-commits crdt S cold-store out fetched-ch
                                          (:op downstream)))
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
                    cvs (<? S (fetch-commit-values! S out fetched-ch
                                                    cold-store mem-store
                                                    [user crdt-id] downstream (:id m)
                                                    ncs-set))
                    txs (mapcat :transactions (vals cvs))]
                (<? S (fetch-and-store-txs-values! S out fetched-ch cold-store
                                                   txs (:id m) (:sender m) hash?))
                (<? S (fetch-and-store-txs-blobs! S out binary-fetched-ch cold-store
                                                  txs (:id m) hash?))
                (<? S (store-commits! S cold-store cvs (:sender m) hash?))
                (recur (drop max-commits ncs) (- left max-commits))))))
        (>! in m)
        (recur (<? S pub-ch))))))

(defn- fetched [S store fetch-ch out]
  (go-loop-super S [{:keys [ids id] :as m} (<? S fetch-ch)]
    (when m
      (info {:event :fetched :pub-id id :count (count ids) :remote-peer (:sender m)})
      (loop [ids (seq ids)]
        ;; TODO variable sized replies
        ;; load values and stop when too large for memory instead of fixed limit
        (let [size 100
              slice (take size ids)
              rest (drop size ids)
              to-get-ch (chan)
              got-ch (chan)]
          (when-not (empty? slice)
            (info {:event :loading-slice :count size :pub-id id})
            (async/onto-chan to-get-ch (seq ids))
            (async/pipeline-async 8 got-ch
                                  (fn [id ch]
                                    (go-try S
                                      (>! ch [id (<? S (k/get-in store [id]))])
                                      (close! ch)))
                                  to-get-ch)
            (>! out {:type :fetch/edn-ack
                     :values (<? S (async/into {} got-ch)) 
                     :id id
                     :final (empty? rest)})
            (recur rest))))
      (debug {:event :sent-all-fetched :pub-id id})
      (recur (<? S fetch-ch)))))

(defn- binary-fetched [S store binary-fetch-ch out]
  (go-loop-super S [{:keys [id blob-id] :as m} (<? S binary-fetch-ch)]
    (when m
      (info {:event :binary-fetch :pub-id id})
      ;; do not block here :)
      (go-try S
        (>! out {:type :fetch/binary-ack
                 :value (<? S (k/bget store blob-id
                                      #?(:clj #(with-open [baos (ByteArrayOutputStream.)]
                                                 (io/copy (:input-stream %) baos)
                                                 (.toByteArray baos))
                                         :cljs identity)))
                 :blob-id blob-id
                 :id id}))
      (debug {:type :sent-blob :pub-id id :blob-id blob-id})
      (recur (<? S binary-fetch-ch)))))


(defn- fetch-dispatch [{:keys [type] :as m}]
  (case type
    :pub/downstream :pub/downstream
    :fetch/edn :fetch/edn
    :fetch/edn-ack :fetch/edn-ack
    :fetch/binary :fetch/binary
    :fetch/binary-ack :fetch/binary-ack
    :unrelated))

(defn fetch
  ([[S peer [in out]]]
   (fetch false [S peer [in out]]))
  ([hash? [S peer [in out]]]
   (let [{{:keys [cold-store mem-store]} :volatile} @peer
         new-in (chan)
         p (pub in fetch-dispatch)
         pub-ch (chan 10000)
         fetch-ch (chan)
         binary-fetch-ch (chan)]
     (sub p :pub/downstream pub-ch)
     (fetch-new-pub S cold-store mem-store p pub-ch [new-in out] hash?)

     (sub p :fetch/edn fetch-ch)
     (fetched S cold-store fetch-ch out)

     (sub p :fetch/binary binary-fetch-ch)
     (binary-fetched S cold-store binary-fetch-ch out)

     (sub p :unrelated new-in)
     [S peer [new-in out]])))
