(ns geschichte.crdt.repo.impl
  "Implementation of the CRDT replication protocol."
  (:require [clojure.set :as set]
            [geschichte.environ :refer [*id-fn* *date-fn*]]
            [geschichte.protocols :refer [POpBasedCRDT -filter-identities -apply-downstream!
                                          PExternalValues -ensure-external]]
            [geschichte.platform-log :refer [debug info error]]
            [geschichte.crdt.repo.repo :as repo]
            [geschichte.crdt.repo.meta :refer [downstream]]
            [konserve.protocols :refer [-exists? -assoc-in -bassoc -update-in]]
            #+clj [clojure.core.async :as async
                   :refer [<! >! timeout chan alt! go put!
                           filter< map< go-loop pub sub unsub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]])

  #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


;; TODO move to datatype method
(defn- possible-commits
  [meta]
  (set (keys meta)))


(defn- new-commits! [store op causal]
  (go (->> [[(:causal-order op) causal]]
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
       (filter #(-> % first pred))
       flatten
       (map #(go [(not (<! (-exists? store %))) %]))
       async/merge
       (filter< first)
       (map< second)
       (async/into #{})))

(defn- new-transactions! [store commit-values]
  (not-in-store?! store commit-values #(not= % repo/store-blob-trans-id)))

(defn- new-blobs! [store commit-values]
  (go (->> (not-in-store?! store commit-values #(= % repo/store-blob-trans-id))
           <!
           (filter #(not= % repo/store-blob-trans-id)))))

;; TODO factorize
(defn- ensure-commits-and-transactions [causal store pub-id op out fetched-ch binary-fetched-ch]
  (let [suc-ch (chan)]
    (go
      (let [ncs (<! (new-commits! store op causal))]
        (if (empty? ncs)
          (>! suc-ch ncs)
          (do
            (info "starting to fetch " ncs "for" pub-id)
            (>! out {:topic :fetch
                     :id pub-id
                     :ids ncs})
            (if-let [cvs (:values (<! fetched-ch))]
              (let [ntc (<! (new-transactions! store cvs))
                    nblbs (<! (new-blobs! store cvs))]
                ;; transactions first
                (when-not (empty? ntc)
                  (debug "fetching new transactions" ntc "for" pub-id)
                  (>! out {:topic :fetch
                           :id pub-id
                           :ids ntc})
                  (if-let [tvs (select-keys (:values (<! fetched-ch)) ntc)]
                    (doseq [[id val] tvs]
                      (debug "trans assoc-in" id (pr-str val))
                      (<! (-assoc-in store [id] val)))
                    ;; abort
                    (close! suc-ch)))
                ;; then blobs
                (when-not (empty? nblbs)
                  (debug "fetching new blobs" nblbs "for" pub-id)
                  (<! (go-loop [[to-fetch & r] nblbs]
                        (when to-fetch
                          ;; TODO recheck store to avoid double fetching
                          (>! out {:topic :binary-fetch
                                   :id pub-id
                                   :blob-id to-fetch})
                          (let [{:keys [value]} (<! binary-fetched-ch)
                                id (*id-fn* value)]
                            (if-not (= to-fetch id)
                              (do
                                (error "fetched blob with wrong id" id
                                       "not in" to-fetch
                                       "first 100 bytes" (take 100 (map byte value)))
                                ;; abort
                                (close! suc-ch))
                              (if (<! (-exists? store id))
                                (do (info "fetched blob already exists for" id ", skipping.")
                                    (recur r))
                                (do
                                  (debug "blob assoc" id)
                                  (<! (-bassoc store id value))
                                  (recur r)))))))))

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
            true)
          (do (debug "fetching failure" pub-id)
              false)))))




;; CRDT is responsible for all writes to store!
(defrecord Repository [causal-order branches store cursor]
  POpBasedCRDT
  (-identities [this] (set (keys branches)))
  (-downstream [this op]
    (downstream this op))
  (-apply-downstream! [this op]
    (-update-in store cursor #(downstream % op)))

  PExternalValues
  (-ensure-external [this pub-id op out fetched-ch binary-fetched-ch]
    (ensure-commits-and-transactions causal-order store pub-id op out fetched-ch binary-fetched-ch)))




(comment
  (defn inducing-conflict-pull!? [atomic-pull-store [user repo branch] pulled-op]
    (go (let [[old new] (<! (-update-in atomic-pull-store [user repo]
                                        #(cond (not %) pulled-op
                                               (r/multiple-branch-heads? #_(update % new-state) branch) %
                                               :else (update % pulled-op))))]
          ;; not perfectly elegant to reconstruct the value of inside the transaction
          (and (= old new) (not= (update old pulled-op) new)))))


  (defn pull-repo!
  "Pull from user 'a' into repo of user 'b', optionally verifying integrity and optionally supplying a reordering function for merges, otherwise only pulls can move a branch forward.

  Uses store to access commit values for integrity-fn and atomic-pull-store to atomically synchronize pulls, disallowing induced conficts by default. Atomicity only works inside the stores atomicity boundaries (probably peer-wide). So when different peers with different stores pull through this middleware they might still induce conflicts although each one disallows them."
  [store atomic-pull-store
   [[a-user a-repo a-branch a-state]
    [b-user b-repo b-branch b-state]
    integrity-fn
    allow-induced-conflict?]]
  (go
    (let [branches (get-in a-state [:op :branches a-branch])
          [head-a head-b] (seq branches)]
      (if head-b
        (do (debug "Cannot pull from conflicting meta: " a-state a-branch ": " branches)
            :rejected)
        (let [pulled (try
                       (r/pull b-state b-branch (:op a-state) head-a allow-induced-conflict? false)
                       (catch #+clj clojure.lang.ExceptionInfo #+cljs ExceptionInfo e
                              (let [{:keys [type]} (ex-data e)]
                                (if (or (= type :multiple-branch-heads)
                                        (= type :not-superset)
                                        (= type :conflicting-meta)
                                        (= type :pull-unnecessary))
                                  (do (debug e) :rejected)
                                  (do (debug e) (throw e))))))
              new-commits (set/difference (-> pulled :state :causal-order keys set)
                                          (-> b-state :state :causal-order keys set))]
          (cond (= pulled :rejected)
                :rejected

                (and (not allow-induced-conflict?)
                     (<! (inducing-conflict-pull!? atomic-pull-store
                                                   [b-user b-repo b-branch]
                                                   (:state pulled))))
                (do
                  (debug "Pull would induce conflict: " b-user b-repo (:state pulled))
                  :rejected)

                (<! (integrity-fn store new-commits))
                [[b-user b-repo] (:op pulled)]

                :else
                (do
                  (debug "Integrity check on " new-commits " pulled from " a-user a-state " failed.")
                  :rejected)))))))
  )
