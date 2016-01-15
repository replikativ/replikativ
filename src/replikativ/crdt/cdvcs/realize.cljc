(ns replikativ.crdt.cdvcs.realize
  (:require [konserve.core :as k]
            [replikativ.environ :refer [store-blob-trans-id store-blob-trans-value store-blob-trans]]
            [replikativ.crdt.cdvcs.core :refer [multiple-heads?]]
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
             (let [cval (<? (k/get-in store [f]))
                   transactions  (<? (commit-transactions store cval))
                   ;; HACK to break stackoverflow through recursion in mozilla js
                   _ (<? (timeout 1))
                   res (reduce (partial trans-apply eval-fn)
                               (<? (commit-value store eval-fn graph commit r))
                               transactions)]
               res)))))


(defn head-value
  "Realizes the value of a staged CDVCS with help of store and an
  application specific eval-fn (e.g. map from source/symbols to
  fn.). Returns go block to synchronize."
  [store eval-fn cdvcs]
  (go-try
   (when (multiple-heads? cdvcs)
     (throw (ex-info "CDVCS has multiple heads!"
                     {:type :multiple-heads
                      :state cdvcs})))
   (<? (commit-value store eval-fn (-> cdvcs :commit-graph)
                     (first (get-in cdvcs [:heads]))))))



(defrecord Conflict [lca-value commits-a commits-b heads])

(defn- isolate-tipps
  [commit-graph cut branch-meta]
  (if (empty? cut) branch-meta
      (recur commit-graph
             (set (mapcat commit-graph cut))
             (merge branch-meta (select-keys commit-graph cut)))))

(defn summarize-conflict
  "Summarizes a conflict situation between two heads in a Conflict
  record. Returns go block to synchronize."
  [store eval-fn cdvcs-meta]
  (go-try
   (when-not (multiple-heads? cdvcs-meta)
     (throw (ex-info "Conflict missing for summary."
                     {:type :missing-conflict-for-summary
                      :state cdvcs-meta})))
   (let [heads (get-in cdvcs-meta [:heads])
         [head-a head-b] (seq heads)
         graph (:commit-graph cdvcs-meta)

         {:keys [lcas visited-a visited-b] :as lca}
         (meta/lowest-common-ancestors graph #{head-a} graph #{head-b})

         common-history (set (keys (isolate-tipps graph lcas {})))
         offset (count common-history)
         history-a (<? (commit-history-values store graph head-a))
         history-b (<? (commit-history-values store graph head-b))]
     ;; TODO handle multiple lcas
     (Conflict. (<? (commit-value store eval-fn graph (get-in history-a [(dec offset) :id])))
                (drop offset history-a)
                (drop offset history-b)
                heads))))
