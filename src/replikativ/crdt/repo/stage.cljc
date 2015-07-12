(ns replikativ.crdt.repo.stage
  "Upstream interaction for repository CRDT with stage."
    (:require [konserve.protocols :refer [-bassoc]]
              [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-repos!]]
              [replikativ.environ :refer [*id-fn* store-blob-trans-id store-blob-trans-value]]
              [replikativ.crdt.repo.repo :as repo]
              [replikativ.crdt.repo.impl :as impl]
              [replikativ.crdt.repo.meta :as meta]
              [replikativ.platform-log :refer [debug info warn]]
              [full.async :refer [go-try <?]]
              [hasch.core :refer [uuid]]
              [clojure.set :as set]
              #?(:clj [clojure.core.async :as async
                       :refer [>! timeout chan put! sub unsub pub close!]]
                 :cljs [cljs.core.async :as async
                        :refer [>! timeout chan put! sub unsub pub close!]])))


(defn create-repo! [stage & {:keys [user is-public? branch id description]
                             :or {is-public? false
                                  branch "master"
                                  description ""}}]
  "Create a repo given a description. Defaults to stage user and
  new-repository default arguments. Returns go block to synchronize."
  (go-try (let [user (or user (get-in @stage [:config :user]))
              nrepo (assoc (repo/new-repository user :branch branch)
                           :public is-public?
                           :description description)
              id (or id (*id-fn*))
              identities {user {id #{branch}}}
              ;; id is random uuid, safe swap!
              new-stage (swap! stage #(-> %
                                          (assoc-in [user id] nrepo)
                                          (assoc-in [user id :stage/op] :sub)
                                          (assoc-in [:config :subs user id] #{branch})))]
          (debug "creating new repo for " user "with id" id)
          (<? (sync! new-stage identities))
          (cleanup-ops-and-new-values! stage identities)
          (<? (subscribe-repos! stage (get-in new-stage [:config :subs])))
          id)))


(defn fork! [stage [user repo-id branch] & {:keys [into-user description is-public?]
                                            :or {is-public? false
                                                 description ""}}]
  "Forks from one staged user's repo a branch into a new repository for the
stage user into having repo-id. Returns go block to synchronize."
  (go-try
   (when-not (get-in @stage [user repo-id :branches branch])
     (throw (ex-info "Repository or branch does not exist."
                     {:type :repository-does-not-exist
                      :user user :repo repo-id :branch branch})))
   (let [suser (or into-user (get-in @stage [:config :user]))
         identities {suser {repo-id #{branch}}}
         ;; atomic swap! and sync, safe
         new-stage (swap! stage #(if (get-in % [suser repo-id])
                                   (throw (ex-info "Repository already exists, use pull."
                                                   {:type :forking-impossible
                                                    :user user :id repo-id}))
                                   (-> %
                                       (assoc-in [suser repo-id]
                                                 (assoc (repo/fork (get-in % [user repo-id :state])
                                                                   branch)
                                                        :public is-public?
                                                        :description description))
                                       (assoc-in [suser repo-id :stage/op] :sub)
                                       (assoc-in [:config :subs suser repo-id] #{branch}))))]
     (debug "forking " user repo-id "for" suser)
     (<? (sync! new-stage identities))
     (cleanup-ops-and-new-values! stage identities)
     (<? (subscribe-repos! stage (get-in new-stage [:config :subs]))))))


(defn remove-repos!
  "Remove repos map from stage, e.g. {user {repo-id #{branch1
branch2}}}. Returns go block to synchronize. TODO remove branches"
  [stage repos]
  (let [new-subs
        (->
         ;; can still get pubs in the mean time which undo in-memory removal, but should be safe
         (swap! stage (fn [old]
                        (reduce #(-> %1
                                     (update-in (butlast %2) dissoc (last %2))
                                     (update-in [:config :subs (first %2)] dissoc (last %)))
                                old
                                (for [[u rs] repos
                                      [id _] rs]
                                  [u id]))))
         (get-in [:config :subs]))]
    (subscribe-repos! stage new-subs)))


(defn branch!
  "Create a new branch with tip parent-commit."
  [stage [user repo] branch-name parent-commit]
  (go-try
   (let [new-stage (swap! stage (fn [old] (-> old
                                             (update-in [user repo]
                                                        #(repo/branch % branch-name parent-commit))
                                             (assoc-in [user repo :stage/op] :pub)
                                             (update-in [:config :subs user repo]
                                                        #(conj (or %1 #{}) %2) branch-name))))
         new-subs (get-in new-stage [:config :subs])]
     (<? (sync! new-stage {user {repo #{name}}}))
     (<? (subscribe-repos! stage new-subs)))))

(defn checkout!
  "Tries to check out one branch and waits until it is available.
  This possibly blocks forever if the branch cannot be fetched from some peer."
  [stage [user repo] branch]
  (subscribe-repos! stage (update-in (get-in @stage [:config :subs])
                                     [user repo] conj branch)))

;; TODO remove value?
(defrecord Abort [new-value aborted])

(defn abort-prepared [stage [user repo branch]]
  ;; racing ...
  (let [a (Abort. nil (get-in @stage [user repo :prepared branch]))]
    (swap! stage assoc-in [user repo :prepared branch] [])
    a))

(defn transact
  "Transact a transaction function trans-fn-code (given as quoted code: '(fn [old params] (merge old params))) on previous value of user's repository branch and params.
THIS DOES NOT COMMIT YET, you have to call commit! explicitly afterwards. It can still abort resulting in a staged replikativ.stage.Abort value for the repository. Returns go block to synchronize."
  ([stage [user repo branch] trans-fn-code params]
   (transact stage [user repo branch] [[trans-fn-code params]]))
  ([stage [user repo branch] transactions]
   (go-try
    (when-not (get-in @stage [user repo :state :branches branch])
      (throw (ex-info "Branch does not exist!"
                      {:type :branch-missing
                       :user user :repo repo :branch branch})))
    (when (some nil? (flatten transactions))
      (throw (ex-info "At least one transaction contains nil."
                      {:type :nil-transaction
                       :transactions transactions})))
    (let [{{:keys [val val-ch peer eval-fn]} :volatile
           {:keys [subs]} :config} @stage

           {{repo-meta repo} user}
           (locking stage
             (swap! stage update-in [user repo :prepared branch] concat transactions))

           branch-val :foo #_(<! (branch-value (get-in @peer [:volatile :store])
                                               eval-fn
                                               repo-meta
                                               branch))

           new-val
           ;; racing...
           (swap! (get-in @stage [:volatile :val-atom]) assoc-in [user repo branch] branch-val)]

      (info "transact: new stage value after trans " transactions ": \n" new-val)
      (put! val-ch new-val)))))

(defn transact-binary
  "Transact a binary blob to reference it later, this only prepares a transaction and does not commit.
  This can support transacting files if the underlying store supports
  this (FileSystemStore)."
  [stage [user repo branch] blob]
  (go-try
   #?(:clj ;; HACK efficiently short circuit addition to store
      (when (= (type blob) java.io.File)
        (let [store (-> stage deref :volatile :peer deref :volatile :store)
              id (uuid blob)]
          (<? (-bassoc store id blob)))))
   (<? (transact stage [user repo branch] [[store-blob-trans-value blob]]))))


(defn commit!
  "Commit all branches synchronously on stage given by the repository map,
e.g. {user1 {repo1 #{branch1}} user2 {repo1 #{branch1 branch2}}}.
Returns go block to synchronize."
  [stage repos]
  (go-try
   ;; atomic swap and sync, safe
   (<? (sync! (swap! stage (fn [old]
                             (reduce (fn [old [user id branch]]
                                       (-> old
                                           (update-in [user id] #(repo/commit % user branch))
                                           (assoc-in [user id :stage/op] :pub)))
                                     old
                                     (for [[user repo] repos
                                           [id branches] repo
                                           b branches]
                                       [user id b]))))
              repos))
   (cleanup-ops-and-new-values! stage repos)))

(defn pull!
  "Pull from remote-user (can be the same), repo branch in into-branch.
  Defaults to stage user as into-user. Returns go-block to synchronize."
  [stage [remote-user repo branch] into-branch & {:keys [into-user allow-induced-conflict?
                                                         rebase-transactions?]
                                                  :or {allow-induced-conflict? false
                                                       rebase-transactions? false}}]
  (go-try
   (let [{{u :user} :config} @stage
         user (or into-user u)]
     (when-not (and (not rebase-transactions?)
                    (empty? (get-in stage [user repo :prepared branch])))
       (throw (ex-info "There are prepared transactions, which could conflict. Either commit or drop them."
                       {:type :transactions-pending-might-conflict
                        :transactions (get-in stage [user repo :prepared branch])})))
     ;; atomic swap! and sync!, safe
     (<? (sync! (swap! stage (fn [{{{{{remote-heads branch} :branches :as remote-meta} :state} repo}
                                  remote-user :as stage-val}]
                               (when (not= (count remote-heads) 1)
                                 (throw (ex-info "Cannot pull from conflicting repo."
                                                 {:type :conflicting-remote-meta
                                                  :remote-user remote-user :repo repo :branch branch})))
                               (-> stage-val
                                   (update-in [user repo]
                                              #(repo/pull % into-branch remote-meta (first remote-heads)
                                                          allow-induced-conflict? rebase-transactions?))
                                   (assoc-in [user repo :stage/op] :pub))))
                {user {repo #{into-branch}}})))))


(defn merge-cost
  "Estimates cost for adding a further merge to the repository by taking
the ratio between merges and normal commits of the commit-graph into account."
  [graph]
  (let [merges (count (filter (fn [[k v]] (> (count v) 1)) graph))
        ratio (double (/ merges (count graph)))]
    (int (* (- (#?(:clj Math/log :cljs js/Math.log) (- 1 ratio)))
            100000))))


(defn merge!
  "Merge multiple heads in a branch of a repository. Use heads-order
  to decide in which order commits contribute to the value. By adding
  older commits before their parents, you can enforce to realize
  them (and their past) first for this merge (commit-reordering). Only
  reorder parts of the concurrent history, not of the sequential
  common past. Returns go channel to synchronize."
  [stage [user repo branch] heads-order
   & {:keys [wait? correcting-transactions]
      :or {wait? true
           correcting-transactions []}}]
  (let [heads (get-in @stage [user repo :state :branches branch])
        graph (get-in @stage [user repo :state :commit-graph])]
    (go-try
     (when-not graph
       (throw (ex-info "Repository or branch does not exist."
                       {:type :repo-does-not-exist
                        :user user :repo repo :branch branch})))
     (when-not (= (set heads-order) heads)
       (throw (ex-info "Supplied heads don't match branch heads."
                       {:type :heads-dont-match-branch
                        :heads heads
                        :supplied-heads heads-order})))
     (let [identities {user {repo #{branch}}}]
       (when wait?
         (<? (timeout (rand-int (merge-cost graph)))))
       (when-not (= heads (get-in @stage [user repo :state :branches branch]))
         (throw (ex-info "Heads changed, merge aborted."
                         {:type :heads-changed
                          :old-heads heads
                          :new-heads (get-in @stage [user repo :state :branches branch])})))
       ;; atomic swap! and sync!, safe
       (<? (sync! (swap! stage (fn [{{u :user} :config :as old}]
                                 (-> old
                                     (update-in [user repo]
                                                #(repo/merge % u branch (:state %)
                                                             heads-order
                                                             correcting-transactions))
                                     (assoc-in [user repo :stage/op] :pub))))
                  identities))
       (cleanup-ops-and-new-values! stage identities)))))
