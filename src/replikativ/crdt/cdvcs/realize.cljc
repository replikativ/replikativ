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
            #?(:clj [superv.async :refer [<? go-try]])
            #?(:clj [superv.lab :refer [go-loop-super]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [<? go-try]]
                            [superv.lab :refer [go-loop-super]])))


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
  [S store graph commit & {:keys [to-ignore] :or {to-ignore #{}}}]
  (go-try S (let [commit-hist (commit-history graph commit)]
            (loop [val '()
                   [f & r] (reverse commit-hist)]
              (if (and f (not (to-ignore f)))
                (let [cval (<? S (k/get-in store [f]))
                      txs (<? S (real/commit-transactions S store cval))]
                  (recur (conj val (assoc cval :transactions txs :id f)) r))
                (vec val))))))


(defn commit-value
  "Realizes the value of a commit of a CDVCS in store by an
  application specific eval-fn (e.g. map from source/symbols to
  fn.). Returns go block to synchronize. Caches old values and only
  applies novelty."
  [S store eval-fn graph commit]
  (real/reduce-commits S store eval-fn nil (commit-history graph commit)))


(defn head-value
  "Realizes the value of a staged CDVCS with help of store and an
  application specific eval-fn (e.g. map from source/symbols to
  fn.). Returns go block to synchronize."
  [S store eval-fn cdvcs]
  (go-try S
   (when (multiple-heads? cdvcs)
     (throw (ex-info "CDVCS has multiple heads!"
                     {:type :multiple-heads
                      :state cdvcs})))
   (<? S (commit-value S store eval-fn (-> cdvcs :commit-graph)
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
  [S store eval-fn cdvcs-meta]
  (go-try S
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
         history-a (<? S (commit-history-values S store graph head-a))
         history-b (<? S (commit-history-values S store graph head-b))]
     ;; TODO handle multiple lcas
     (Conflict. (<? S (commit-value S store eval-fn graph (get-in history-a [(dec offset) :id])))
                (drop offset history-a)
                (drop offset history-b)
                heads))))


(defn stream-into-identity! [stage [u id] eval-fn ident
                             & {:keys [applied-log reset-fn]
                                :or {reset-fn reset!}}]
  (let [{{[p _] :chans
          :keys [store err-ch]
          S :supervisor} :volatile} @stage
        pub-ch (chan 10000)]
    (async/sub p :pub/downstream pub-ch)
    ;; stage is set up, now lets kick the update loop
    (go-try S
     ;; trigger an update for us if the crdt is already on stage
     ;; this cdvcs version is as far or ahead of the stage publications
     ;; (no gap in publication chain)
     (let [cdvcs (<? S (ensure-crdt S store (<? S (new-mem-store)) [u id] :cdvcs))]
       (when-not (empty? (:commit-graph cdvcs))
         (put! pub-ch {:downstream {:method :handshake
                                    :crdt :cdvcs
                                    :op (-handshake cdvcs S)}
                       :user u :crdt-id id}))
       (go-loop-super S
                      [{{{new-heads :heads
                          new-commit-graph :commit-graph :as op} :op
                         method :method}
                        :downstream :as pub
                        :keys [user crdt-id]} (<? S pub-ch)
                       cdvcs cdvcs
                       applied (if applied-log
                                 (<? S (k/reduce-log store applied-log set/union #{}))
                                 #{})]
                      (when pub
                        (debug {:event :streaming :id (:id pub)})
                        (let [{:keys [heads commit-graph] :as cdvcs} (-downstream cdvcs op)]
                          (cond (not (and (= user u)
                                          (= crdt-id id)))
                                (recur (<? S pub-ch) cdvcs applied)

                                ;; TODO complicated merged case, recreate whole value for now
                                (and (not (empty? (filter #(> (count %) 1) (vals new-commit-graph))))
                                     (= 1 (count heads)))
                                (let [val (<? S (head-value S store eval-fn cdvcs))]
                                  (reset-fn ident val)
                                  (<? S (k/assoc-in store [applied-log] nil))
                                  (recur (<? S pub-ch) cdvcs (set (keys commit-graph))))

                                (= 1 (count heads))
                                (let [new-commits (filter (comp not applied)
                                                          (commit-history commit-graph (first heads)))]
                                  (when (zero? (count new-commit-graph))
                                    (warn {:event :cannot-have-empty-pubs :pub pub
                                           :new-commit-graph new-commit-graph}))
                                  (when (> (count new-commit-graph) 1)
                                    (info {:event :batch-update
                                           :new-commit-count (count new-commit-graph)}))
                                  (when (zero? (count new-commits))
                                    (info {:event :no-new-commits
                                           :heads heads
                                           :new-commit-graph-count (count new-commit-graph)
                                           :existing-count (count (select-keys commit-graph (keys new-commit-graph)))
                                           :commit-graph-count (count commit-graph)}))
                                  (when applied-log
                                    (<? S (k/append store applied-log (set new-commits))))
                                  (<? S (real/reduce-commits S store eval-fn
                                                             ident
                                                             new-commits))
                                  (recur (<? S pub-ch) cdvcs (set/union applied (set new-commits))))

                                :else
                                (do
                                  (reset-fn ident (<? S (summarize-conflict S store eval-fn cdvcs)))
                                  (<? S (k/assoc-in store [applied-log] nil))
                                  (recur (<? S pub-ch) cdvcs applied))))))))
    pub-ch))
