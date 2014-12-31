(ns geschichte.p2p.fetch
  "Fetching middleware for geschichte. This middleware covers the exchange of the actual content (commits and transactions, not metadata) of repositories."
  (:require [geschichte.repo :refer [store-blob-trans-value trans-blob-id *id-fn*]] ;; TODO move hashing to hasch middleware
            [geschichte.platform-log :refer [debug info warn error]]
            [konserve.protocols :refer [-assoc-in -exists? -get-in -update-in
                                        -bget -bassoc]]
            [clojure.set :as set]
            #+clj [clojure.core.async :as async
                   :refer [<! >! >!! <!! timeout chan alt! go put!
                           filter< map< go-loop pub sub unsub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]])
  #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


(defn- possible-commits
  [meta]
  (set (keys (:causal-order meta))))


(defn- new-commits! [store metas]
  (go (->> (for [[user repos] metas
                 [repo meta] repos]
             (go [meta (<! (-get-in store [user repo]))]))
           async/merge
           (async/into [])
           <!
           (map #(set/difference (possible-commits (first %))
                                 (possible-commits (second %))))
           (apply set/union)
           (map #(go [(not (<! (-exists? store %))) %]))
           async/merge
           (filter< first)
           (map< second)
           (async/into #{})
           <!)))


(defn- not-in-store?! [store commit-values pred]
  (->> (vals commit-values)
       (mapcat :transactions)
       (filter #(-> % second pred))
       flatten
       (map #(go [(not (<! (-exists? store %))) %]))
       async/merge
       (filter< first)
       (map< second)
       (async/into #{})))

(defn- new-transactions! [store commit-values]
  (not-in-store?! store commit-values #(not= % trans-blob-id)))

(defn- new-blobs! [store commit-values]
  (go (->> (not-in-store?! store commit-values #(= % trans-blob-id))
           <!
           (filter #(not= % trans-blob-id))
           (into #{}))))

;; TODO factorize
(defn- ensure-commits-and-transactions [[in out] store pub-msg fetched-ch binary-fetched-ch]
  (let [suc-ch (chan)]
    (go
      (let [{:keys [metas peer]} pub-msg
            ncs (<! (new-commits! store metas))]
        (if (empty? ncs)
          (>! suc-ch ncs)
          (do
            (info "starting to fetch " ncs "from" peer)
            (>! out {:topic :fetch
                     :ids ncs})
            (if-let [cvs (:values (<! fetched-ch))]
              (let [ntc (<! (new-transactions! store cvs))
                    nblbs (<! (new-blobs! store cvs))]
                ;; transactions first
                (when-not (empty? ntc)
                  (debug "fetching new transactions" ntc "from" peer)
                  (>! out {:topic :fetch
                           :ids ntc})
                  (if-let [tvs (:values (<! fetched-ch))]
                    (doseq [[id val] tvs]
                      (debug "trans assoc-in" id (pr-str val))
                      (<! (-assoc-in store [id] val)))
                    ;; abort
                    (close! suc-ch)))
                ;; then blobs
                (when-not (empty? nblbs)
                  (debug "fetching new blobs" nblbs "from" peer)
                  (>! out {:topic :binary-fetch
                           :ids nblbs})
                  (<! (go-loop [to-fetch nblbs]
                        (when-not (empty? to-fetch)
                          (let [{:keys [value]} (<! binary-fetched-ch)
                                id (*id-fn* value)]
                            (if-not (to-fetch id)
                              (do
                                (error "fetched blob with wrong id" id
                                       "not in" to-fetch
                                       "first 100 bytes" (take 100 (map byte value)))
                                ;; abort
                                (close! suc-ch))
                              (if (<! (-exists? store id))
                                (do (info "fetched blob already exists for" id ", skipping.")
                                    (recur (set/difference to-fetch #{id})))
                                (do
                                  (debug "blob assoc" id)
                                  (<! (-bassoc store id value))
                                  (recur (set/difference to-fetch #{id}))))))))))

                (>! suc-ch cvs))
              ;; abort
              (close! suc-ch))))))
    (go (if-let [cvs (<! suc-ch)]
          (do
            (debug "fetching success for " cvs)
            ;; now commits
            (doseq [[id val] cvs]
              (debug "commit assoc-in" id (pr-str val))
              (<! (-assoc-in store [id] val)))
            (>! in pub-msg))
          (debug "fetching failure" pub-msg)))))


(defn- fetch-new-pub
  "Implements two phase (commits, transactions) fetching."
  [store p pub-ch [in out]]
  (go-loop [{:keys [topic metas values peer] :as m} (<! pub-ch)
            old-fetched-ch nil
            old-binary-fetched-ch nil]
    (when m
      (when old-fetched-ch
        (unsub p :fetched old-fetched-ch)
        (close! old-fetched-ch))
      (when old-binary-fetched-ch
        (unsub p :binary-fetched old-binary-fetched-ch)
        (close! old-binary-fetched-ch))
      (let [fetched-ch (chan)
            binary-fetched-ch (chan)]
        (sub p :fetched fetched-ch)
        (sub p :binary-fetched binary-fetched-ch)
        (<! (ensure-commits-and-transactions [in out] store m fetched-ch binary-fetched-ch))
        (recur (<! pub-ch) fetched-ch binary-fetched-ch)))))

(defn- fetched [store fetch-ch out]
  (go-loop [{:keys [ids peer] :as m} (<! fetch-ch)]
      (when m
        (info "fetch:" ids)
        (let [fetched (->> ids
                           (map (fn [id] (go [id (<! (-get-in store [id]))])))
                           async/merge
                           (async/into {})
                           <!)]
          (>! out {:topic :fetched
                   :values fetched
                   :peer peer})
          (debug "sent fetched:" fetched)
          (recur (<! fetch-ch))))))

(defn- binary-fetched [store binary-fetch-ch out]
  (go-loop [{:keys [ids peer] :as m} (<! binary-fetch-ch)]
    (when m
      (info "binary-fetch:" ids)
      (let [fetched (->> ids
                         (map (fn [id] (go [id (<! (-bget store id :input-stream))])))
                         async/merge
                         (async/into [])
                         <!)]
        (debug "BFETCHED:" fetched)
        (doseq [[id blob] fetched]
          (>! out {:topic :binary-fetched
                   :value blob
                   :peer peer})
          (debug "sent blob:" id (*id-fn* blob)))
        (recur (<! binary-fetch-ch))))))


(defn- fetch-dispatch [{:keys [topic] :as m}]
  (case topic
    :meta-pub :meta-pub
    :fetch :fetch
    :fetched :fetched
    :binary-fetch :binary-fetch
    :binary-fetched :binary-fetched
    :unrelated))

(defn fetch [store [in out]]
  (let [new-in (chan)
        p (pub in fetch-dispatch)
        pub-ch (chan)
        fetch-ch (chan)
        binary-fetch-ch (chan)]
    (sub p :meta-pub pub-ch)
    (fetch-new-pub store p pub-ch [new-in out])

    (sub p :fetch fetch-ch)
    (fetched store fetch-ch out)

    (sub p :binary-fetch binary-fetch-ch)
    (binary-fetched store binary-fetch-ch out)

    (sub p :unrelated new-in)
    [new-in out]))
