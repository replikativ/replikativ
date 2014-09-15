(ns geschichte.repo
  "Implementing core repository functions.
   Use this namespace to manage your repositories.

   Metadata is designed as a commutative replicative data type, so it
   can be synched between different servers without coordination. Don't
   add fields as this is part of the network specification."
  (:refer-clojure :exclude [merge])
  (:require [clojure.set :as set]
            [hasch.core :refer [uuid]]
            [geschichte.platform :refer [now]]
            [geschichte.platform-log :refer [debug info]]
            [geschichte.meta :refer [lowest-common-ancestors
                                     merge-ancestors isolate-branch]]))


(def ^:dynamic *id-fn*
  "DO NOT REBIND EXCEPT FOR TESTING OR YOU MIGHT CORRUPT DATA.
   Determines unique ids, possibly from a value.
   UUID is defined as public format."
  uuid)



(def ^:dynamic *date-fn*
  "DO NOT REBIND EXCEPT FOR TESTING OR YOU MIGHT CORRUPT DATA."
  now)


(defn new-repository
  "Create a (unique) repository for an initial value. Returns a map with
   new metadata and commit value and transaction values."
  [author description is-public init-value branch]
  (let [now (*date-fn*)
        ;; TODO fix initial commit
        init-id (*id-fn* init-value)
        init-fn-id (*id-fn* '(fn replace [old params] params))
        commit-val {:transactions [[init-id init-fn-id]]
                   :parents []
                   :ts now
                   :author author}
        commit-id (*id-fn* (dissoc commit-val :ts :author))
        repo-id (*id-fn*)
        new-meta  {:id repo-id
                   :description description
                   :schema {:type "http://github.com/ghubber/geschichte"
                            :version 1}
                   :public is-public
                   :causal-order {commit-id []}
                   :branches {branch #{commit-id}}
                   :head branch
                   :last-update now
                   :pull-requests {}}]
    {:meta new-meta

     :transactions {branch []}
     :op :meta-sub
     :new-values {branch {commit-id commit-val
                          init-id init-value
                          init-fn-id '(fn replace [old params] params)}}}))


(defn fork
  "Fork (clone) a remote branch as your working copy.
   Pull in more branches as needed separately."
  [remote-meta branch is-public]
  (let [branch-meta (-> remote-meta :branches (get branch))
        meta {:id (:id remote-meta)
              :description (:description remote-meta)
              :schema (:schema remote-meta)
              :causal-order (isolate-branch remote-meta branch)
              :branches {branch branch-meta}
              :head branch
              :last-update (*date-fn*)
              :pull-requests {}}]
    {:meta meta

     :transactions {branch []}
     :op :meta-sub}))


(defn- raw-commit
  "Commits to meta in branch with a value for an ordered set of parents.
   Returns a map with metadata and value+inlined metadata."
  [{:keys [meta transactions] :as repo} parents author branch]
  (let [branch-heads (get-in meta [:branches branch])
        ts (*date-fn*)
        ;; turn trans-pairs into new-values
        btrans (get transactions branch)
        trans-ids (mapv (fn [[params trans-fn]]
                          [(*id-fn* params) (*id-fn* trans-fn)]) btrans)
        commit-value {:transactions trans-ids
                      :ts ts
                      :parents parents
                      :author author}
        id (*id-fn* (dissoc commit-value :author :ts))
        new-meta (-> meta
                     (assoc-in [:causal-order id] parents)
                     (update-in [:branches branch] set/difference (set parents))
                     (update-in [:branches branch] conj id)
                     (assoc-in [:last-update] ts))
        new-values (clojure.core/merge
                    {id commit-value}
                    (zipmap (apply concat trans-ids)
                            (apply concat btrans)))]
    (debug "committing to " branch ": " id commit-value)
    (-> repo
        (assoc :meta new-meta :op :meta-pub)
        (assoc-in [:transactions branch] [])
        (assoc-in [:new-values branch] new-values))))


(defn commit
  "Commits to meta in branch with a value for a set of parents.
   Returns a map with metadata and value+inlined metadata."
  [repo author branch]
  (let [heads (get-in repo [:meta :branches branch])]
    (if (= (count heads) 1)
      (raw-commit repo (vec heads) author branch)
      (throw (ex-info "Branch has multiple heads."
                      {:type :multiple-branch-heads
                       :meta (:meta repo)
                       :branch branch
                       :heads heads})))))


(defn branch
  "Create a new branch with parent."
  [{:keys [meta] :as repo} name parent]
  (when (get-in meta [:branches name])
    (throw (ex-info "Branch already exists."
                    {:type :branch-exists
                     :branch name})))
  (let [new-meta (-> meta
                     (assoc-in [:branches name] #{parent})
                     (assoc-in [:last-update] (*date-fn*)))]
    (-> repo
        (assoc :meta new-meta :op :meta-pub)
        (assoc-in [:transactions name] []))))


(defn checkout
  "Checkout a branch."
  [{:keys [meta] :as repo} branch]
  (let [new-meta (assoc (:meta repo)
                   :head branch
                   :last-update (*date-fn*))]
    (assoc repo
      :meta new-meta
      :op :meta-pub)))


(defn multiple-branch-heads?
  "Checks whether branch has multiple heads."
  [meta branch]
  (> (count (get-in meta [:branches branch])) 1))


(defn pull
  "Pull all commits into branch from remote-tip (only its ancestors)."
  [{:keys [meta] :as repo} branch remote-meta remote-tip]
  (when (multiple-branch-heads? meta branch)
    (throw (ex-info "Cannot pull into conflicting repository, use merge instead."
                    {:type :conflicting-meta
                     :meta meta
                     :branch branch
                     :heads (get-in meta [:branches branch])})))
  (when (= (get-in meta [:branches branch]) #{remote-tip})
    (throw (ex-info "No pull necessary."
                    {:type :pull-unnecessary
                     :meta meta
                     :branch branch
                     :remote-meta remote-meta
                     :remote-tip remote-tip})))
  (when-not (set/superset? (-> remote-meta :causal-order keys set)
                           (-> meta :causal-order keys set))
    (throw (ex-info "Remote meta is not pullable (a superset). "
                    {:type :not-superset
                     :meta meta
                     :branch branch
                     :remote-meta remote-meta
                     :remote-tip remote-tip})))
  (let [branch-heads (get-in meta [:branches branch])
        {:keys [cut returnpaths-b]} (lowest-common-ancestors (:causal-order meta) branch-heads
                                                             (:causal-order remote-meta) #{remote-tip})
        new-meta (-> meta
                     (assoc-in [:last-update ] (if (< (compare (:last-update meta) (:last-update remote-meta)) 0)
                                                 (:last-update remote-meta)
                                                 (:last-update meta)))
                     (update-in [:causal-order] merge-ancestors cut returnpaths-b)
                     (update-in [:branches branch] set/difference branch-heads)
                     (update-in [:branches branch] conj remote-tip))]
    (when (multiple-branch-heads? new-meta branch)
      (throw (ex-info "Cannot pull without inducing conflict, use merge instead."
                      {:type :multiple-branch-heads
                       :meta new-meta
                       :branch branch
                       :heads (get-in new-meta [:branches branch])})))
    (assoc repo
      :meta new-meta
      :op :meta-pub)))




(defn merge-heads
  "Constructs a vector of heads. You can reorder them."
  [meta-a branch-a meta-b branch-b]
  (let [heads-a (get-in meta-a [:branches branch-a])
        heads-b (get-in meta-b [:branches branch-b])]
    (vec (distinct (concat heads-a heads-b)))))


(defn merge
  "Merge a repository either with itself, or with remote metadata and
optionally supply the order in which parent commits should be
supplied. Otherwise see merge-heads how to get and manipulate them."
  ([{:keys [meta] :as repo} author branch]
     (merge repo author branch meta))
  ([{:keys [meta] :as repo} author branch remote-meta]
     (merge repo author branch remote-meta (merge-heads meta branch remote-meta branch)))
  ([{:keys [meta] :as repo} author branch remote-meta heads]
     (let [source-heads (get-in meta [:branches branch])
           lcas (lowest-common-ancestors (:causal-order meta)
                                         source-heads
                                         (:causal-order remote-meta)
                                         heads)
           new-causal (merge-ancestors (:causal-order meta) (:cut lcas) (:returnpaths-b lcas))]
       (raw-commit (assoc-in repo [:meta :causal-order] new-causal) heads author branch))))
