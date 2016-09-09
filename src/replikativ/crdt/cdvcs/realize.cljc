(ns replikativ.crdt.cdvcs.realize
  "Functions to realize the value represented by a reduction over the
  commit-history of a CDVCS."
  (:require [clojure.set :as set]
            [konserve.core :as k]
            [konserve.memory :refer [new-mem-store]]
            [replikativ.environ :refer [store-blob-trans-id store-blob-trans-value store-blob-trans]]
            [replikativ.protocols :refer [-downstream -handshake]]
            [replikativ.realize :as real]
            [replikativ.crdt.materialize :refer [ensure-crdt]]
            [replikativ.crdt.cdvcs.core :refer [multiple-heads?]]
            [replikativ.crdt.cdvcs.meta :as meta]
            [kabel.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [<? go-try]])
            #?(:clj [full.lab :refer [go-loop-super]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.async :refer [<? go-try]]
                            [full.lab :refer [go-loop-super]])))


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


(defn commit-history-values
  "Loads the values of the commits including transactions from store into memory (!).

  Returns go block to synchronize."
  [store graph commit & {:keys [to-ignore] :or {to-ignore #{}}}]
  (go-try (let [commit-hist (commit-history graph commit)]
            (loop [val '()
                   [f & r] (reverse commit-hist)]
              (if (and f (not (to-ignore f)))
                (let [cval (<? (k/get-in store [f]))
                      txs (<? (real/commit-transactions store cval))]
                  (recur (conj val (assoc cval :transactions txs :id f)) r))
                (vec val))))))


(defn commit-value
  "Realizes the value of a commit of a CDVCS in store by an
  application specific eval-fn (e.g. map from source/symbols to
  fn.). Returns go block to synchronize. Caches old values and only
  applies novelty."
  [store eval-fn graph commit]
  (real/reduce-commits store eval-fn nil (commit-history graph commit)))


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
        pub-ch (chan 10000)]
    (async/sub p :pub/downstream pub-ch)
    ;; stage is set up, now lets kick the update loop
    (go-try
     ;; trigger an update for us if the crdt is already on stage
     ;; this cdvcs is as far or ahead of the stage publications
     (let [cdvcs (<? (ensure-crdt store (<? (new-mem-store)) [u id] :cdvcs))]
       (when-not (empty? (:commit-graph cdvcs))
         (put! pub-ch {:downstream {:method :handshake
                                    :crdt :cdvcs
                                    :op (-handshake cdvcs)}
                       :user u :crdt-id id}))
       (go-loop-super [{{{new-heads :heads
                          new-commit-graph :commit-graph :as op} :op
                         method :method}
                        :downstream :as pub
                        :keys [user crdt-id]} (<? pub-ch)
                       cdvcs cdvcs
                       applied #{}]
                      (when pub
                        (debug "streaming: " (:id pub))
                        (let [{:keys [heads commit-graph] :as cdvcs} (-downstream cdvcs op)]
                          (cond (not (and (= user u)
                                          (= crdt-id id)))
                                (recur (<? pub-ch) cdvcs applied)

                                ;; TODO complicated merged case, recreate whole value for now
                                (or (and (:lca-value @val-atom)
                                         (= 1 (count heads)))
                                    (and (not (empty? (filter #(> (count %) 1) (vals new-commit-graph))))
                                         (= 1 (count heads))))
                                (let [val (<? (head-value store eval-fn cdvcs))]
                                  (reset! val-atom val)
                                  (recur (<? pub-ch) cdvcs (set (keys commit-graph))))

                                (= 1 (count heads))
                                (let [new-commits (filter (comp not applied)
                                                          (commit-history commit-graph (first heads)))]
                                  (when (zero? (count new-commit-graph))
                                    (warn "Cannot have empty pubs." pub new-commit-graph))
                                  (when (> (count new-commit-graph) 1)
                                    (warn "Batch update:" (count new-commit-graph)))
                                  (when (zero? (count new-commits))
                                    (warn "No new commits:" heads (count new-commit-graph)
                                          (count (select-keys commit-graph (keys new-commit-graph)))
                                          (count commit-graph)))
                                  (<? (real/reduce-commits store eval-fn
                                                           val-atom
                                                           new-commits))
                                  #_(swap! val-atom
                                           #(reduce (partial real/trans-apply eval-fn)
                                                    %
                                                    (filter (comp not empty?) txs)))
                                  (recur (<? pub-ch) cdvcs (set/union applied (set new-commits))))

                                :else
                                (do
                                  (reset! val-atom (<? (summarize-conflict store eval-fn cdvcs)))
                                  (recur (<? pub-ch) cdvcs applied))))))))
    pub-ch))
