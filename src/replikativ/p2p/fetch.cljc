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

;; TODO size-limited buffer:
;; maximum blob size 2 MiB
;; check edn value size
;; load values until local buffer size exceeded
;; send incrementally

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

(defn fetch-commit-values!
  "Resolves all commits recursively for all nested CRDTs. Starts with commits in pub."
  [out fetched-ch cold-store mem-store [user crdt-id] pub pub-id]
  (go-try (let [crdt (<? (ensure-crdt cold-store mem-store [user crdt-id] pub))
                ncs (<? (-missing-commits crdt cold-store out fetched-ch (:op pub)))]
            (when-not (empty? ncs)
              (info "starting to fetch " ncs "for" pub-id)
              (>! out {:type :fetch/edn
                       :id pub-id
                       :ids ncs})
              (:values (<? fetched-ch))))))


(defn fetch-and-store-txs-values! [out fetched-ch store txs pub-id]
  (go-loop-try [ntc (<? (new-transactions! store txs))]
               (let [slice (take 100 ntc)
                     rest (drop 100 ntc)]
                 ;; transactions first
                 (when-not (empty? slice)
                   (debug "fetching new transactions" slice "for" pub-id)
                   (>! out {:type :fetch/edn
                            :id pub-id
                            :ids (set slice)})
                   (if-let [tvs (<? fetched-ch)]
                     (do
                       (doseq [[id val] (select-keys (:values tvs) slice)]
                         (debug "trans assoc-in" id #_(pr-str val))
                         (<? (k/assoc-in store [id] val))))
                     (throw (ex-info "Fetching transactions disrupted." {:to-fetch slice})))
                   (recur rest)))))


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
                                     (if-let [{:keys [value]} (<? binary-fetched-ch)]
                                       (do
                                         (debug "blob assoc" to-fetch)
                                         (<? (k/bassoc store to-fetch value))
                                         (recur r))
                                       (throw (ex-info "Fetching bin. blob disrupted." {:to-fetch to-fetch}))))))))))))


(defn store-commits! [store cvs]
  #_(->> cvs
       (map (fn [[k v]] (k/assoc-in store [k] v)))
       doall
       async/merge)
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
                     (debug "fetching values for publication: " (:id m))
                     (let [cvs (<? (fetch-commit-values! out fetched-ch
                                                         cold-store mem-store
                                                         [user crdt-id] downstream (:id m)))
                           txs (mapcat :transactions (vals cvs))]
                       (<? (fetch-and-store-txs-values! out fetched-ch cold-store txs (:id m)))
                       (<? (fetch-and-store-txs-blobs! out binary-fetched-ch cold-store txs (:id m)))
                       (<? (store-commits! cold-store cvs)))
                     (>! in m)
                     (recur (<? pub-ch))))))

(defn- fetched [store fetch-ch out]
  (go-loop-super [{:keys [ids id] :as m} (<? fetch-ch)]
                 (when m
                   (info "fetch:" id ": " ids)
                   (let [fetched (loop [[id & r] (seq ids)
                                        res {}]
                                   (if id
                                     (recur r (assoc res id (<? (k/get-in store [id]))))
                                     res))
                         #_fetched #_(->> (seq ids)
                                      (map (fn [id] (go-try [id (<? (k/get-in store [id]))])))
                                      doall
                                      async/merge
                                      (async/into {})
                                      <?)
;_ (println "FETCHED" fetched)
                         #_(->> (go-for [id ids] [id (<? (k/get-in store [id]))])
                                      (async/into {})
                                      <?)]
                     (>! out {:type :fetch/edn-ack
                              :values fetched
                              :id id})
                     (debug "sending fetched:" id)
                     (recur (<? fetch-ch))))))

(defn- binary-fetched [store binary-fetch-ch out]
  (go-loop-super [{:keys [id blob-id] :as m} (<? binary-fetch-ch)]
                 (when m
                   (info "binary-fetch:" id)
                   (>! out {:type :fetch/binary-ack
                            :value (<? (k/bget store blob-id
                                               #?(:clj #(with-open [baos (ByteArrayOutputStream.)]
                                                          (io/copy (:input-stream %) baos)
                                                          (.toByteArray baos))
                                                  :cljs identity)))
                            :blob-id blob-id
                            :id id})
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
