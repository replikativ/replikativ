(ns replikativ.crdt.repo.repo
  "Implementing core repository functions purely and value based. All
  operations return a new state of the CRDT record and the
  corresponding downstream operation for synchronisation."
  (:refer-clojure :exclude [merge])
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id store-blob-trans]]
            [replikativ.platform-log :refer [debug info]]
            [replikativ.crdt.repo.meta :refer [consistent-causal? lowest-common-ancestors
                                               merge-ancestors isolate-branch remove-ancestors]]))



(defn new-repository
  "Create a (unique) repository for an initial value. Returns a map with
   new metadata and initial commit value in branch \"master."
  [author description & {:keys [is-public? branch id] :or {is-public? false
                                                           branch "master"}}]
  (let [now (*date-fn*)
        commit-val {:transactions [] ;; common base commit (not allowed elsewhere)
                    :parents []
                    :branch branch
                    :ts now
                    :author author}
        commit-id (*id-fn* (select-keys commit-val #{:transactions :parents}))
        repo-id (or id (*id-fn*))
        new-state {:causal-order {commit-id []}
                   :branches {branch #{commit-id}}}]
    {:state new-state
     :transactions {branch []}
     :downstream {:crdt :replikativ.repo
                  :public is-public?
                  :description description
                  :op (assoc new-state
                        :method :new-state
                        :version 1)}
     :new-values {branch {commit-id commit-val}}}))


(defn fork
  "Fork (clone) a remote branch as your working copy.
   Pull in more branches as needed separately."
  [remote-state branch is-public description]
  (let [branch-meta (-> remote-state :branches (get branch))
        state {:causal-order (isolate-branch remote-state branch)
               :branches {branch branch-meta}}]
    {:state state
     :transactions {branch []}
     :downstream {:crdt :replikativ.repo
                  :description description
                  :public is-public
                  :op (assoc state
                        :method :new-state
                        :version 1)}}))

(defn- raw-commit
  "Commits to meta in branch with a value for an ordered set of parents.
   Returns a map with metadata and value+inlined metadata."
  [{:keys [state transactions] :as repo} parents author branch
   & {:keys [allow-empty-txs?]
      :or {allow-empty-txs? false}}]
  (when-not (consistent-causal? (:causal-order state))
    (throw (ex-info "Causal order does not contain commits of all referenced parents."
                    {:type :inconsistent-causal-order
                     :state state})))
  (when (and (not allow-empty-txs?) (empty? transactions))
    (throw (ex-info "No transactions to commit."
                    {:type :no-transactions
                     :repo repo
                     :branch branch})))
  (let [branch-heads (get-in state [:branches branch])
        ts (*date-fn*)
        ;; turn trans-pairs into new-values
        btrans (get transactions branch)
        trans-ids (mapv (fn [[trans-fn params]]
                          [(*id-fn* trans-fn) (*id-fn* params)]) btrans)
        commit-value {:transactions trans-ids
                      :ts ts
                      :branch branch
                      :parents (vec parents)
                      :author author}
        id (*id-fn* (select-keys commit-value #{:transactions :parents}))
        parents (vec parents)
        new-state (-> state
                      (assoc-in [:causal-order id] parents)
                      (update-in [:branches branch] set/difference (set parents))
                      (update-in [:branches branch] conj id))
        new-values (clojure.core/merge
                    {id commit-value}
                    (zipmap (apply concat trans-ids)
                            (apply concat btrans)))]
    (debug "committing to " branch ": " id commit-value)
    (-> repo
        (assoc
            :state new-state
            :downstream {:crdt :replikativ.repo
                         :op {:method :commit
                              :version 1
                              :causal-order {id parents}
                              :branches {branch (get-in new-state [:branches branch])}}})
        (assoc-in [:transactions branch] [])
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
        (assoc :state new-state :downstream {:crdt :replikativ.repo
                                             :op {:method :branch
                                                  :version 1
                                                  :branches {name #{parent}}}})
        (assoc-in [:transactions name] []))))


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
   (when (get-in state [:causal-order remote-tip])
     (throw (ex-info "No pull necessary."
                     {:type :pull-unnecessary
                      :state state
                      :branch branch
                      :remote-state remote-state
                      :remote-tip remote-tip})))
   (let [{{branch-heads branch} :branches
          causal :causal-order} state
          {:keys [cut returnpaths-a returnpaths-b]}
          (lowest-common-ancestors (:causal-order state) branch-heads
                                   (:causal-order remote-state) #{remote-tip})
          remote-causal (isolate-branch (:causal-order remote-state) #{remote-tip} {})
          new-causal (clojure.core/merge remote-causal causal)
          new-state (-> state
                        (assoc-in [:causal-order] new-causal)
                        (assoc-in [:branches branch] (remove-ancestors new-causal
                                                                       branch-heads
                                                                       #{remote-tip})))
          new-causal (:causal-order new-state)]
     (when (and (not allow-induced-conflict?)
                (not (set/superset? cut branch-heads)))
       (throw (ex-info "Remote meta is not pullable (a superset). "
                       {:type :not-superset
                        :state state
                        :branch branch
                        :remote-state remote-state
                        :remote-tip remote-tip
                        :cut cut})))
     (when (and (not allow-induced-conflict?)
                (multiple-branch-heads? new-state branch))
       (throw (ex-info "Cannot pull without inducing conflict, use merge instead."
                       {:type :multiple-branch-heads
                        :state new-state
                        :branch branch
                        :heads (get-in new-state [:branches branch])})))
     (debug "pulling: from cut " cut " returnpaths: " returnpaths-b " new meta: " new-state)
     (assoc repo
       :state (clojure.core/merge state new-state)
       :downstream {:crdt :replikativ.repo
                    :op {:method :pull
                         :version 1
                         :causal-order (select-keys (:causal-order new-state) (keys returnpaths-b))
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
   (when-not (empty? (get-in repo [:transactions branch]))
     (throw (ex-info "There are pending transactions, which could conflict. Either commit or drop them."
                     {:type :transactions-pending-might-conflict
                      :transactions (get-in repo [:transactions branch])})))
   (let [source-heads (get-in state [:branches branch])
         remote-heads (get-in remote-state [:branches branch])
         heads-needed (set/union source-heads remote-heads)
         _ (when-not (= heads-needed (set heads))
             (throw (ex-info "Heads provided don't match."
                             {:type :heads-dont-match
                              :heads heads
                              :heads-needed heads-needed})))
         lcas (lowest-common-ancestors (:causal-order state)
                                       source-heads
                                       (:causal-order remote-state)
                                       remote-heads)
         new-causal (merge-ancestors (:causal-order state) (:cut lcas) (:returnpaths-b lcas))]
     (debug "merging: into " author (:id state) lcas)
     (assoc-in (raw-commit (-> repo
                               (assoc-in [:state :causal-order] new-causal)
                               (assoc-in [:transactions branch] correcting-transactions))
                           (vec heads) author branch
                           :allow-empty-txs? true)
               [:downstream :op :method] :merge))))
