(ns geschichte.crdt.repo.realize
  (:require [konserve.protocols :refer [-get-in -assoc-in -bget]]
            [geschichte.crdt.repo.repo :as repo]
            [geschichte.crdt.repo.meta :as meta]
            [geschichte.platform-log :refer [debug info warn]]
            [geschichte.platform :refer [<? go<? go>? go-loop>? go-loop<?]]
            #+clj [clojure.core.async :as async
                   :refer [<! <!! >! timeout chan alt! go put! filter< map< go-loop sub unsub pub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< sub unsub pub close!]]))


(defn commit-history
  "Returns the linear commit history for a repo through depth-first
linearisation. Each commit occurs once, the first time it is found."
  ([causal-order commit]
   (commit-history causal-order [] #{} [commit]))
  ([causal-order hist hist-set stack]
   (let [[f & r] stack
         children (filter #(not (hist-set %)) (causal-order f))]
     (if f
       (if-not (empty? children)
         (recur causal-order hist hist-set (concat children stack))
         (recur causal-order
                (if-not (hist-set f)
                  (conj hist f) hist)
                (conj hist-set f)
                r))
       hist))))


(defn commit-transactions [store commit-value]
  (->> commit-value
       :transactions
       (map (fn [[trans-id param-id]]
              (go<? [(<? (-get-in store [trans-id]))
                     (<? (if (= trans-id repo/store-blob-trans-id)
                           (-bget store param-id identity)
                           (-get-in store [param-id])))])))
       async/merge
       (async/into [])))

(defn commit-history-values
  "Loads the values of the commits from store. Returns go block to
synchronize."
  [store causal commit]
  (go<? (let [commit-hist (commit-history causal commit)]
          (loop [val []
                 [f & r] commit-hist]
            (if f
              (let [cval (<? (-get-in store [f]))
                    txs (<? (commit-transactions store cval))]
                (recur (conj val (assoc cval :transactions txs :id f)) r))
              val)))))


(defn trans-apply [eval-fn val [trans-fn params]]
  (try
    (if (= trans-fn repo/store-blob-trans-value)
      (repo/store-blob-trans val params)
      ((eval-fn trans-fn) val params))
    (catch Exception e
      (throw (ex-info "Cannot transact."
                      {:trans-fn trans-fn
                       :params params
                       :old val
                       :exception e})))))

#_(def commit-value-cache (atom {}))

(defn commit-value
  "Realizes the value of a commit of repository with help of store and
an application specific eval-fn (e.g. map from source/symbols to
fn.). Returns go block to synchronize. Caches old values and only applies novelty."
  ([store eval-fn causal commit]
   (commit-value store eval-fn causal commit (reverse (commit-history causal commit))))
  ([store eval-fn causal commit [f & r]]
   (go<? (when f
          (or #_(@commit-value-cache [eval-fn causal f])
              (let [cval (<? (-get-in store [f]))
                    transactions  (<? (commit-transactions store cval))
                    ;; HACK to break stackoverflow through recursion in mozilla js
                    _ (<! (timeout 1))
                    res (reduce (partial trans-apply eval-fn)
                                (<? (commit-value store eval-fn causal commit r))
                                transactions)]
                #_(swap! commit-value-cache assoc [eval-fn causal f] res)
                res))))))

#_(defn with-transactions [store eval-fn repo branch]
  (reduce (partial trans-apply eval-fn)

          (get-in repo [:transactions branch])))


(defn branch-value
  "Realizes the value of a branch of a staged repository with
help of store and an application specific eval-fn (e.g. map from
source/symbols to fn.). The metadata has the form {:state {:causal-order ...}, :transactions [[p fn]...] ...}. Returns go block to synchronize."
  [store eval-fn repo branch]
  (go<?
   (when (repo/multiple-branch-heads? (:state repo) branch)
     (throw (ex-info "Branch has multiple heads!"
                     {:type :multiple-branch-heads
                      :branch branch
                      :state (:state repo)})))
   (<? (commit-value store eval-fn (-> repo :state :causal-order)
                     (first (get-in repo [:state :branches branch]))))))



(defrecord Conflict [lca-value commits-a commits-b])

(defn summarize-conflict
  "Summarizes a conflict situation between two branch heads in a Conflict
record. Returns go block to synchronize."
  [store eval-fn repo-meta branch]
  (go<?
   (when-not (repo/multiple-branch-heads? repo-meta branch)
     (throw (ex-info "Conflict missing for summary."
                     {:type :missing-conflict-for-summary
                      :state repo-meta
                      :branch branch})))
   (let [[head-a head-b] (seq (get-in repo-meta [:branches branch]))
         causal (:causal-order repo-meta)

         {:keys [cut returnpaths-a returnpaths-b] :as lca}
         (meta/lowest-common-ancestors causal #{head-a} causal #{head-b})

         common-history (set (keys (meta/isolate-branch causal cut {})))
         offset (count common-history)
         history-a (<? (commit-history-values store causal head-a))
         history-b (<? (commit-history-values store causal head-b))]
     ;; TODO handle non-singular cut
     (Conflict. (<? (commit-value store eval-fn causal (get-in history-a [(dec offset) :id])))
                (drop offset history-a)
                (drop offset history-b)))))



(comment
  ;; from create-stage
  (let [old-val @val-atom ;; TODO not consistent !!!
        val (->> (for [[u repos] metas
                       [id repo] repos
                       [b heads] (:branches repo)]
                   [u id b repo])
                 (map (fn [[u id b repo]]
                        (let [old-meta (get-in @stage [u id :meta])
                              new-meta (meta/update (or old-meta repo) repo)]
                          (go
                            (when-not (= old-meta new-meta)
                              [u id b
                               (let [new-val (if (repo/multiple-branch-heads? new-meta b)
                                               (<! (summarize-conflict store eval-fn new-meta b))
                                               (<! (branch-value store eval-fn {:state new-meta} b)))
                                     old-abort-txs (get-in old-val [u id b :txs])]
                                 (locking stage
                                   (let [txs (get-in @stage [u id :transactions b])]
                                     (if-not (empty? txs)
                                       (do
                                         (info "aborting transactions: " txs)
                                         (swap! stage assoc-in [u id :transactions b] [])
                                         (Abort. new-val (concat old-abort-txs txs)))
                                       (if-not (empty? old-abort-txs)
                                         (Abort. new-val old-abort-txs)
                                         new-val)))))])))))
                 async/merge
                 (async/into [])
                 <!
                 (reduce #(assoc-in %1 (butlast %2) (last %2)) old-val))]
    (when-not (= val old-val)
      (info "stage: new value " val)
      (reset! val-atom val))
    (put! val-ch val))
  )
