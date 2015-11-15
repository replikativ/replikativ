(ns replikativ.crdt.cdvcs.realize
  (:require [konserve.core :as k]
            [replikativ.environ :refer [store-blob-trans-id store-blob-trans-value store-blob-trans]]
            [replikativ.crdt.cdvcs.repo :as repo]
            [replikativ.crdt.cdvcs.meta :as meta]
            [replikativ.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [<? go-try]])
            #?(:clj [clojure.core.async :as async
                      :refer [>! timeout chan alt! put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.cljs.async :refer [<? go-try]])))


(defn commit-history
  "Returns the linear commit history for a CDVCS through depth-first
linearisation. Each commit occurs once, the first time it is found."
  ([commit-graph commit]
   (commit-history commit-graph [] #{} [commit]))
  ([commit-graph hist hist-set stack]
   (let [[f & r] stack
         parents (filter #(not (hist-set %)) (commit-graph f))]
     (if f
       (if-not (empty? parents)
         (recur commit-graph hist hist-set (concat parents stack))
         (recur commit-graph
                (if-not (hist-set f)
                  (conj hist f) hist)
                (conj hist-set f)
                r))
       hist))))


(defn commit-transactions [store commit-value]
  (->> commit-value
       :transactions
       (map (fn [[trans-id param-id]]
              (go-try [(<? (k/get-in store [trans-id]))
                     (<? (if (= trans-id store-blob-trans-id)
                           (k/bget store param-id identity)
                           (k/get-in store [param-id])))])))
       async/merge
       (async/into [])))

(defn commit-history-values
  "Loads the values of the commits from store. Returns go block to
synchronize."
  [store graph commit]
  (go-try (let [commit-hist (commit-history graph commit)]
          (loop [val []
                 [f & r] commit-hist]
            (if f
              (let [cval (<? (k/get-in store [f]))
                    txs (<? (commit-transactions store cval))]
                (recur (conj val (assoc cval :transactions txs :id f)) r))
              val)))))


(defn trans-apply [eval-fn val [trans-fn params]]
  (try
    (if (= trans-fn store-blob-trans-value)
      (store-blob-trans val params)
      ((eval-fn trans-fn) val params))
    (catch #?(:clj Exception :cljs js/Error) e
      (throw (ex-info "Cannot transact."
                      {:trans-fn trans-fn
                       :params params
                       :old val
                       :exception e})))))


(defn commit-value
  "Realizes the value of a commit of a CDVCS in store by an
  application specific eval-fn (e.g. map from source/symbols to
  fn.). Returns go block to synchronize. Caches old values and only
  applies novelty."
  ([store eval-fn graph commit]
   (commit-value store eval-fn graph commit (reverse (commit-history graph commit))))
  ([store eval-fn graph commit [f & r]]
   (go-try (when f
          (or #_(@commit-value-cache [eval-fn graph f])
              (let [cval (<? (k/get-in store [f]))
                    transactions  (<? (commit-transactions store cval))
                    ;; HACK to break stackoverflow through recursion in mozilla js
                    _ (<? (timeout 1))
                    res (reduce (partial trans-apply eval-fn)
                                (<? (commit-value store eval-fn graph commit r))
                                transactions)]
                #_(swap! commit-value-cache assoc [eval-fn graph f] res)
                res))))))


(defn branch-value
  "Realizes the value of a branch of a staged CDVCS with
help of store and an application specific eval-fn (e.g. map from
source/symbols to fn.). The metadata has the form {:state {:commit-graph ...}, :transactions [[p fn]...] ...}. Returns go block to synchronize."
  [store eval-fn cdvcs branch]
  (go-try
   (when (repo/multiple-branch-heads? (:state cdvcs) branch)
     (throw (ex-info "Branch has multiple heads!"
                     {:type :multiple-branch-heads
                      :branch branch
                      :state (:state cdvcs)})))
   (<? (commit-value store eval-fn (-> cdvcs :state :commit-graph)
                     (first (get-in cdvcs [:state :branches branch]))))))



(defrecord Conflict [lca-value commits-a commits-b])

(defn summarize-conflict
  "Summarizes a conflict situation between two branch heads in a Conflict
record. Returns go block to synchronize."
  [store eval-fn cdvcs-meta branch]
  (go-try
   (when-not (repo/multiple-branch-heads? cdvcs-meta branch)
     (throw (ex-info "Conflict missing for summary."
                     {:type :missing-conflict-for-summary
                      :state cdvcs-meta
                      :branch branch})))
   (let [[head-a head-b] (seq (get-in cdvcs-meta [:branches branch]))
         graph (:commit-graph cdvcs-meta)

         {:keys [lcas visited-a visited-b] :as lca}
         (meta/lowest-common-ancestors graph #{head-a} graph #{head-b})

         common-history (set (keys (meta/isolate-branch graph lcas {})))
         offset (count common-history)
         history-a (<? (commit-history-values store graph head-a))
         history-b (<? (commit-history-values store graph head-b))]
     ;; TODO handle multiple lcas
     (Conflict. (<? (commit-value store eval-fn graph (get-in history-a [(dec offset) :id])))
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
                          (go-try
                            (when-not (= old-meta new-meta)
                              [u id b
                               (let [new-val (if (repo/multiple-branch-heads? new-meta b)
                                               (<? (summarize-conflict store eval-fn new-meta b))
                                               (<? (branch-value store eval-fn {:state new-meta} b)))
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
                 <?
                 (reduce #(assoc-in %1 (butlast %2) (last %2)) old-val))]
    (when-not (= val old-val)
      (info "stage: new value " val)
      (reset! val-atom val))
    (put! val-ch val))
  )
