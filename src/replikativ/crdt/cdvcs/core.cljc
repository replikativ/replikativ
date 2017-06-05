(ns replikativ.crdt.cdvcs.core
  "Implementing core CDVCS functions purely and value based. All
  operations return a new state of the CRDT record and the
  corresponding downstream operation for synchronisation."
  (:refer-clojure :exclude [merge])
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn*
                                        store-blob-trans-id store-blob-trans]]
            [replikativ.protocols :refer [PExternalValues]]
            #?(:clj [kabel.platform-log :refer [debug info]])
            [replikativ.crdt :refer [map->CDVCS]]
            [replikativ.crdt.utils :refer [extract-crdts]]
            [replikativ.crdt.cdvcs.meta :refer [consistent-graph? lowest-common-ancestors
                                                remove-ancestors]])
  #?(:cljs (:require-macros [kabel.platform-log :refer [debug info]])))


(defn new-cdvcs
  "Create a (unique) CDVCS for an initial value. Returns a map with
   new metadata and initial commit value."
  [author]
  (let [now (*date-fn*)
        commit-val {:transactions [] ;; common base commit (not allowed elsewhere)
                    :parents []
                    :crdt :cdvcs
                    :version 1
                    :ts now
                    :author author}
        commit-id (*id-fn* (select-keys commit-val #{:transactions :parents}))
        new-state {:commit-graph {commit-id []}
                   :version 1
                   :heads #{commit-id}}]
    {:state (map->CDVCS new-state)
     :prepared []
     :downstream {:crdt :cdvcs
                  :op (assoc new-state :method :handshake)}
     :new-values {commit-id commit-val}}))


(defn fork
  "Fork (clone) a remote CDVCS as your working copy."
  [remote-state]
  {:state (map->CDVCS remote-state)
   :prepared []
   :downstream {:crdt :cdvcs
                :op (assoc (into {} remote-state)
                           :method :handshake
                           :version 1)}})


(defn- raw-commit
  "Commits to CDVCS with a value for an ordered set of parents.
   Returns a map with metadata and value metadata."
  [{:keys [state prepared] :as cdvcs} parents author
   & {:keys [allow-empty-txs?]
      :or {allow-empty-txs? false}}]
  ;; TODO either remove or check whole history
  #_(when-not (consistent-graph? (:commit-graph state))
      (throw (ex-info "Graph order does not contain commits of all referenced parents."
                      {:type :inconsistent-commit-graph
                       :state state})))
  (when (and (not allow-empty-txs?) (empty? prepared))
    (throw (ex-info "No transactions to commit."
                    {:type :no-transactions
                     :cdvcs cdvcs})))
  (let [heads (get-in state [:heads])
        ts (*date-fn*)
        ;; turn trans-pairs into new-values
        trans-ids (mapv (fn [[trans-fn params]]
                          [(*id-fn* trans-fn) (*id-fn* params)]) prepared)
        commit-value {:transactions trans-ids
                      :ts ts
                      :parents (vec parents)
                      :crdt :cdvcs
                      :version 1
                      :author author}
        id (*id-fn* (select-keys commit-value #{:transactions :parents}))
        parents (vec parents)
        new-state (-> state
                      (assoc-in [:commit-graph id] parents)
                      (update-in [:heads] set/difference (set parents))
                      (update-in [:heads] conj id))
        new-values (clojure.core/merge
                    {id commit-value}
                    (zipmap (apply concat trans-ids)
                            (apply concat prepared)))
        new-heads (get-in new-state [:heads])]
    (debug {:event :commiting-to :id id :commit-value commit-value})
    (-> cdvcs
        (assoc
         :state new-state
         :downstream {:crdt :cdvcs
                      :op {:method :commit
                           :version 1
                           :commit-graph {id parents}
                           :heads new-heads}})
        (assoc-in [:prepared] [])
        (update-in [:new-values] clojure.core/merge new-values))))


(defn commit
  "Commits to a CDVCS with a value for a set of parents.
   Returns a map with metadata and value+inlined metadata."
  [cdvcs author]
  (let [heads (get-in cdvcs [:state :heads])]
    (if (= (count heads) 1)
      (raw-commit cdvcs (vec heads) author)
      (throw (ex-info "CDVCS has multiple heads."
                      {:type :multiple-heads
                       :state (:state cdvcs)
                       :heads heads})))))


(defn multiple-heads?
  "Checks whether CDVCS has multiple heads."
  [meta]
  (> (count (get-in meta [:heads])) 1))


(defn pull
  "Pull all commits from remote-tip (only its ancestors)."
  ([cdvcs remote-state remote-tip] (pull cdvcs remote-state remote-tip false false))
  ([{:keys [state] :as cdvcs} remote-state remote-tip
    allow-induced-conflict? rebase-transactions?]
   (when (get-in state [:commit-graph remote-tip])
     (throw (ex-info "No pull necessary."
                     {:type :pull-unnecessary
                      :state (dissoc state :store)
                      :remote-state (dissoc remote-state :store)
                      :remote-tip remote-tip})))
   (let [{heads :heads
          graph :commit-graph} state
         {:keys [lcas visited-a visited-b]}
         (lowest-common-ancestors (:commit-graph state) heads
                                  (:commit-graph remote-state) #{remote-tip})
         remote-graph (:commit-graph remote-state)
         new-graph (clojure.core/merge remote-graph graph)
         new-state (-> state
                       (assoc-in [:commit-graph] new-graph)
                       (assoc-in [:heads] (remove-ancestors new-graph
                                                            graph
                                                            heads
                                                            #{remote-tip})))
         new-graph (:commit-graph new-state)]
     (when (and (not allow-induced-conflict?)
                (not (set/superset? lcas heads)))
       (throw (ex-info "Remote meta is not pullable (a superset). "
                       {:type :not-superset
                        :state state
                        :remote-state remote-state
                        :remote-tip remote-tip
                        :lcas lcas})))
     (when (and (not allow-induced-conflict?)
                (multiple-heads? new-state))
       (throw (ex-info "Cannot pull without inducing conflict, use merge instead."
                       {:type :multiple-heads
                        :state new-state
                        :heads (get-in new-state [:heads])})))
     (debug {:event :pulling-lcas-from-cut :lcas lcas
             :visited-b visited-b :new-state new-state})
     (assoc cdvcs
            :state (clojure.core/merge state new-state)
            :downstream {:crdt :cdvcs
                         :op {:method :pull
                              :version 1
                              :commit-graph (select-keys (:commit-graph new-state)
                                                         visited-b)
                              :heads #{remote-tip}}}))))


(defn merge-heads
  "Constructs a vector of heads. You can reorder them."
  [meta-a meta-b]
  (let [heads-a (get-in meta-a [:heads])
        heads-b (get-in meta-b [:heads])]
    (distinct (concat heads-a heads-b))))


(defn merge
  "Merge a CDVCS either with itself, or with remote metadata and
  optionally supply the order in which parent commits should be
  supplied. Otherwise see merge-heads how to get and manipulate them."
  ([{:keys [state] :as cdvcs} author]
   (merge cdvcs author meta))
  ([{:keys [state] :as cdvcs} author remote-state]
   (merge cdvcs author remote-state (merge-heads state remote-state) []))
  ([{:keys [state] :as cdvcs} author remote-state heads correcting-transactions]
   (when-not (empty? (get-in cdvcs [:prepared]))
     (throw (ex-info "There are pending transactions, which could conflict. Either commit or drop them."
                     {:type :transactions-pending-might-conflict
                      :transactions (get-in cdvcs [:prepared])})))
   (let [source-heads (get-in state [:heads])
         remote-heads (get-in remote-state [:heads])
         heads-needed (set/union source-heads remote-heads)
         _ (when-not (= heads-needed (set heads))
             (throw (ex-info "Heads provided don't match."
                             {:type :heads-dont-match
                              :heads heads
                              :heads-needed heads-needed})))
         lcas (lowest-common-ancestors (:commit-graph state)
                                       source-heads
                                       (:commit-graph remote-state)
                                       remote-heads)
         new-graph (clojure.core/merge (:commit-graph state)
                                       (select-keys (:commit-graph remote-state)
                                                    (:visited-b lcas)))]
     (debug {:event :merging-into :author author :id (:id state) :lcas lcas})
     (assoc-in (raw-commit (-> cdvcs
                               (assoc-in [:state :commit-graph] new-graph)
                               (assoc-in [:prepared] correcting-transactions))
                           (vec heads) author
                           :allow-empty-txs? true)
               [:downstream :op :method] :merge))))
