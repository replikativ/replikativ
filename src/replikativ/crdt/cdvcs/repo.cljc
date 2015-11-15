(ns replikativ.crdt.cdvcs.repo
  "Implementing core CDVCS functions purely and value based. All
  operations return a new state of the CRDT record and the
  corresponding downstream operation for synchronisation."
  (:refer-clojure :exclude [merge])
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id store-blob-trans]]
            [replikativ.protocols :refer [PExternalValues]]
            [replikativ.platform-log :refer [debug info]]
            [replikativ.crdt :refer [map->CDVCS]]
            [replikativ.crdt.utils :refer [extract-crdts]]
            [replikativ.crdt.cdvcs.meta :refer [consistent-graph? lowest-common-ancestors
                                                isolate-branch remove-ancestors]]))


(defn new-repository
  "Create a (unique) repository for an initial value. Returns a map with
   new metadata and initial commit value in branch \"master."
  [author & {:keys [branch] :or {branch "master"}}]
  (let [now (*date-fn*)
        commit-val {:transactions [] ;; common base commit (not allowed elsewhere)
                    :parents []
                    :crdt :repo
                    :version 1
                    :branch branch
                    :ts now
                    :author author
                    :crdt-refs #{}}
        commit-id (*id-fn* (select-keys commit-val #{:transactions :parents}))
        new-state {:commit-graph {commit-id []}
                   :version 1
                   :branches {branch #{commit-id}}}]
    {:state (map->CDVCS new-state)
     :prepared {branch []}
     :downstream {:crdt :repo
                  :op (assoc new-state :method :new-state)}
     :new-values {branch {commit-id commit-val}}}))


(defn fork
  "Fork (clone) a remote branch as your working copy.
   Pull in more branches as needed separately."
  [remote-state branch]
  (let [branch-meta (-> remote-state :branches (get branch))
        state {:commit-graph (isolate-branch remote-state branch)
               :branches {branch branch-meta}}]
    {:state (map->CDVCS state)
     :prepared {branch []}
     :downstream {:crdt :repo
                  :op (assoc state
                             :method :new-state
                             :version 1)}}))


(defn- raw-commit
  "Commits to meta in branch with a value for an ordered set of parents.
   Returns a map with metadata and value+inlined metadata."
  [{:keys [state prepared] :as repo} parents author branch
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
                     :repo repo
                     :branch branch})))
  (let [branch-heads (get-in state [:branches branch])
        ts (*date-fn*)
        ;; turn trans-pairs into new-values
        btrans (get prepared branch)
        trans-ids (mapv (fn [[trans-fn params]]
                          [(*id-fn* trans-fn) (*id-fn* params)]) btrans)
        commit-value {:transactions trans-ids
                      :ts ts
                      :branch branch
                      :parents (vec parents)
                      :crdt :repo
                      :version 1
                      :author author
                      :crdt-refs (extract-crdts prepared)}
        id (*id-fn* (select-keys commit-value #{:transactions :parents}))
        parents (vec parents)
        new-state (-> state
                      (assoc-in [:commit-graph id] parents)
                      (update-in [:branches branch] set/difference (set parents))
                      (update-in [:branches branch] conj id))
        new-values (clojure.core/merge
                    {id commit-value}
                    (zipmap (apply concat trans-ids)
                            (apply concat btrans)))
        new-heads (get-in new-state [:branches branch])
        #_(conj ;; does not work to trigger LCA (why?)
           #uuid "3004b2bd-3dd9-5524-a09c-2da166ffad6a" ;; root node
           )]
    (debug "committing to " branch ": " id commit-value)
    (-> repo
        (assoc
         :state new-state
         :downstream {:crdt :repo
                      :op {:method :commit
                           :version 1
                           :commit-graph {id parents}
                           :branches {branch new-heads}}})
        (assoc-in [:prepared branch] [])
        (update-in [:new-values branch] clojure.core/merge new-values))))


(defn commit
  "Commits to meta in branch with a value for a set of parents.
   Returns a map with metadata and value+inlined metadata."
  [repo author branch]
  (let [heads (get-in repo [:state :branches branch])]
    (if (= (count heads) 1)
      (raw-commit repo (vec heads) author branch)
      (throw (ex-info "Branch has multiple heads."
                      {:type :multiple-branch-heads
                       :state (:state repo)
                       :branch branch
                       :heads heads})))))


(defn branch
  "Create a new branch with parent."
  [{:keys [state] :as repo} name parent]
  (when (get-in state [:branches name])
    (throw (ex-info "Branch already exists."
                    {:type :branch-exists
                     :branch name})))
  (let [new-state (assoc-in state [:branches name] #{parent})]
    (-> repo
        (assoc :state new-state :downstream {:crdt :repo
                                             :op {:method :branch
                                                  :version 1
                                                  :branches {name #{parent}}}})
        (assoc-in [:prepared name] []))))


(defn multiple-branch-heads?
  "Checks whether branch has multiple heads."
  [meta branch]
  (> (count (get-in meta [:branches branch])) 1))


(defn pull
  "Pull all commits into branch from remote-tip (only its ancestors)."
  ([repo branch remote-state remote-tip] (pull repo branch remote-state remote-tip false false))
  ([{:keys [state] :as repo} branch remote-state remote-tip allow-induced-conflict? rebase-transactions?]
   (when (and (not allow-induced-conflict?)
              (multiple-branch-heads? state branch))
     (throw (ex-info "Cannot pull into conflicting repository, use merge instead."
                     {:type :conflicting-meta
                      :state state
                      :branch branch
                      :heads (get-in state [:branches branch])})))
   (when (get-in state [:commit-graph remote-tip])
     (throw (ex-info "No pull necessary."
                     {:type :pull-unnecessary
                      :state state
                      :branch branch
                      :remote-state remote-state
                      :remote-tip remote-tip})))
   (let [{{branch-heads branch} :branches
          graph :commit-graph} state
          {:keys [lcas visited-a visited-b]}
          (lowest-common-ancestors (:commit-graph state) branch-heads
                                   (:commit-graph remote-state) #{remote-tip})
          remote-graph (isolate-branch (:commit-graph remote-state) #{remote-tip} {})
          new-graph (clojure.core/merge remote-graph graph)
          new-state (-> state
                        (assoc-in [:commit-graph] new-graph)
                        (assoc-in [:branches branch] (remove-ancestors new-graph
                                                                       branch-heads
                                                                       #{remote-tip})))
          new-graph (:commit-graph new-state)]
     (when (and (not allow-induced-conflict?)
                (not (set/superset? lcas branch-heads)))
       (throw (ex-info "Remote meta is not pullable (a superset). "
                       {:type :not-superset
                        :state state
                        :branch branch
                        :remote-state remote-state
                        :remote-tip remote-tip
                        :lcas lcas})))
     (when (and (not allow-induced-conflict?)
                (multiple-branch-heads? new-state branch))
       (throw (ex-info "Cannot pull without inducing conflict, use merge instead."
                       {:type :multiple-branch-heads
                        :state new-state
                        :branch branch
                        :heads (get-in new-state [:branches branch])})))
     (debug "pulling: from cut " lcas " visited: " visited-b " new meta: " new-state)
     (assoc repo
       :state (clojure.core/merge state new-state)
       :downstream {:crdt :repo
                    :op {:method :pull
                         :version 1
                         :commit-graph (select-keys (:commit-graph new-state) visited-b)
                         :branches {branch #{remote-tip}}}}))))


(defn merge-heads
  "Constructs a vector of heads. You can reorder them."
  [meta-a branch-a meta-b branch-b]
  (let [heads-a (get-in meta-a [:branches branch-a])
        heads-b (get-in meta-b [:branches branch-b])]
    (distinct (concat heads-a heads-b))))


(defn merge
  "Merge a repository either with itself, or with remote metadata and
optionally supply the order in which parent commits should be
supplied. Otherwise see merge-heads how to get and manipulate them."
  ([{:keys [state] :as repo} author branch]
   (merge repo author branch meta))
  ([{:keys [state] :as repo} author branch remote-state]
   (merge repo author branch remote-state (merge-heads state branch remote-state branch) []))
  ([{:keys [state] :as repo} author branch remote-state heads correcting-transactions]
   (when-not (empty? (get-in repo [:prepared branch]))
     (throw (ex-info "There are pending transactions, which could conflict. Either commit or drop them."
                     {:type :transactions-pending-might-conflict
                      :transactions (get-in repo [:prepared branch])})))
   (let [source-heads (get-in state [:branches branch])
         remote-heads (get-in remote-state [:branches branch])
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
         new-graph (clojure.core/merge (:commit-graph state) (select-keys (:commit-graph remote-state)
                                                                          (:visited-b lcas)))]
     (debug "merging: into " author (:id state) lcas)
     (assoc-in (raw-commit (-> repo
                               (assoc-in [:state :commit-graph] new-graph)
                               (assoc-in [:prepared branch] correcting-transactions))
                           (vec heads) author branch
                           :allow-empty-txs? true)
               [:downstream :op :method] :merge))))
