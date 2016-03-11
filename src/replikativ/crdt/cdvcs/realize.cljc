(ns replikativ.crdt.cdvcs.realize
  "Functions to realize the value represented by a reduction over the
  commit-history of a CDVCS."
  (:require [clojure.set :as set]
            [konserve.core :as k]
            [replikativ.environ :refer [store-blob-trans-id store-blob-trans-value store-blob-trans]]
            [replikativ.protocols :refer [-downstream]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.crdt.cdvcs.core :refer [multiple-heads?]]
            [replikativ.crdt.cdvcs.meta :as meta]
            [replikativ.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [<? go-try go-loop-try>]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.cljs.async :refer [<? go-try go-loop-try>]])))


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
  [store graph commit & {:keys [to-ignore] :or {to-ignore #{}}}]
  (go-try (let [commit-hist (commit-history graph commit)]
            (loop [val '()
                   [f & r] (reverse commit-hist)]
              (if (and f (not (to-ignore f)))
                (let [cval (<? (k/get-in store [f]))
                      txs (<? (commit-transactions store cval))]
                  (recur (conj val (assoc cval :transactions txs :id f)) r))
                (vec val))))))


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


(defn stream-into-atom! [stage [u id] eval-fn val-atom]
  (let [{{[p _] :chans
          :keys [store err-ch]} :volatile} @stage
        pub-ch (chan)]
    (async/sub p :pub/downstream pub-ch)
    (go-loop-try> err-ch
                  [{{{new-heads :heads
                      new-commit-graph :commit-graph} :op
                     method :method}
                    :downstream :as pub
                    :keys [user crdt-id]} (<? pub-ch)
                   applied #{}]
                  (let [cdvcs (or (get-in @stage [u id :state])
                                  (key->crdt :cdvcs))
                        {:keys [heads commit-graph] :as cdvcs} (-downstream cdvcs pub)]
                    (cond (not (and (= user u)
                                    (= crdt-id id)))
                          (recur (<? pub-ch) applied)

                          ;; TODO complicated merged case, recreate whole value for now
                          (or (and (:lca-value @val-atom)
                                   (= 1 (count heads)))
                              (and (not (empty? (filter #(> (count %) 1) (vals new-commit-graph))))
                                   (= 1 (count heads))))
                          (let [val (<? (head-value store eval-fn cdvcs))]
                            (reset! val-atom val)
                            (recur (<? pub-ch) (set (keys commit-graph))))

                          (= 1 (count heads))
                          (let [txs (mapcat :transactions (<? (commit-history-values store commit-graph
                                                                                     (first heads)
                                                                                     :to-ignore (set applied))))]
                            (swap! val-atom
                                   #(reduce (partial trans-apply eval-fn)
                                            %
                                            (filter (comp not empty?) txs)))
                            (recur (<? pub-ch) (set/union applied (keys commit-graph))))

                          :else
                          (do
                            (reset! val-atom (<? (summarize-conflict store eval-fn cdvcs)))
                            (recur (<? pub-ch) applied)))))))
