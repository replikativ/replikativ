(ns ^:shared geschichte.repo
  "Implementing core repository functions.
   Use this namespace to manage your repositories.

   Metadata is designed as a commutative replicative data type, so it
   can be synched between different servers without coordination. Don't
   add fields as this is part of the network specification."
  (:require [clojure.set :as set]
            [geschichte.platform :refer [uuid now]]
            [geschichte.meta :refer [lowest-common-ancestors
                                     merge-ancestors inline-meta
                                     isolate-branch]]))


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
   new metadata and value + inline metadata. You can add fields to
   *inline* metadata as long as you keep them namespaced with globally
   unique names."
  [description author schema is-public value]
  (let [val-id (*id-fn* value)
        ts (*date-fn*)]
    {:meta {:id (*id-fn*)
            :description description
            :schema {:type "http://github.com/ghubber/geschichte"
                     :version 1}
            :public is-public
            :causal-order {val-id #{}
                           :root val-id}
            :branches {"master" #{val-id}}
            :head "master"
            :last-update ts
            :pull-requests {}}
     :value (assoc value :geschichte.meta/meta
                   (inline-meta author schema "master" val-id ts))}))


(defn clone
  "Clone a remote branch as your working copy.
   Pull in more branches as needed separately."
  [remote-meta branch is-public]
  (if-let [heads ((:branches remote-meta) branch)]
    {:id (:id remote-meta)
     :description (:description remote-meta)
     :schema (:schema remote-meta)
     :causal-order (isolate-branch remote-meta branch)
     :branches {branch heads}
     :head branch
     :ts (*date-fn*)
     :pull-requests {}}
    {:error "Branch does not exist."
     :remote-meta remote-meta :branch branch}))


(defn- raw-commit
  "Commits to meta in branch with a value for a set of parents.
   Returns a map with metadata and value+inlined metadata."
  [meta author schema branch parents value]
  (let [branch-heads ((:branches meta) branch)]
    (if-not (some parents branch-heads)
      {:error "No parent is in branch heads."
       :parents parents :branch branch :meta meta :branch-heads branch-heads}
      (let [id (*id-fn* value)
            ts (*date-fn*)
            new-meta (-> meta
                         (assoc-in [:causal-order id] parents)
                         (update-in [:branches branch] set/difference parents)
                         (update-in [:branches branch] conj id)
                         (assoc :last-update ts))
            inline-meta (inline-meta author schema branch id ts)]
        {:meta new-meta
         :value (assoc value :geschichte.meta/meta inline-meta)}))))

(defn commit
  "Commits to meta in branch with a value for a set of parents.
   Returns a map with metadata and value+inlined metadata."
  [meta author schema branch parent value]
  (raw-commit meta author schema branch #{parent} value))


(defn branch
  "Create a new branch with parent."
  [meta name parent]
  (if ((:branches meta) name)
    {:error "Branch already exists!" :branch name :meta meta}
    {:meta (assoc-in meta [:branches name] #{parent})}))


(defn multiple-branch-heads?
  "Checks whether branch has multiple heads."
  [meta branch]
  (> (count ((:branches meta) branch)) 1))

(defn merge-necessary?
  "Determines whether branch-head is ancestor."
  [cut branch-head]
  (not (cut branch-head)))


(defn pull-lcas
  "Pull a lowest-common-ancestor map containing the cut and returnpaths
   to remote-head."
  [meta branch remote-tip lcas]
  (let [{:keys [cut returnpaths-b]} lcas
        branch-heads ((:branches meta) branch)
        branch-head (first branch-heads)]
    (cond (multiple-branch-heads? meta branch)
          {:error "Cannot pull into branch with multiple heads (conflicts). Merge branch heads first."
           :meta meta :branch branch :lcas lcas}

          (merge-necessary? cut branch-head)
          {:error "Remote-tip is not descendant of local branch head. Merge is necessary."
           :meta meta :branch branch :lcas lcas}

          :else
          {:meta (-> meta
                     (update-in [:causal-order]
                                merge-ancestors cut returnpaths-b)
                     (update-in [:branches branch] set/difference branch-heads)
                     (update-in [:branches branch] conj remote-tip))
           :branch-update branch
           :new-revisions (set/difference (set (keys returnpaths-b)) cut)})))


(defn pull
  "Pull all commits into branch from remote-tip (only its ancestors)."
  ([meta branch remote-meta remote-tip]
     (let [branch-heads ((:branches meta) branch)
           lcas (lowest-common-ancestors (:causal-order meta) branch-heads
                                         (:causal-order remote-meta) #{remote-tip})]
       (pull-lcas meta branch remote-tip lcas))))


(defn merge-lcas
  "Merge target-heads with help of lowest-common-ancestors."
  [author schema branch source-meta source-heads target-heads value lcas]
  (let [new-causal (merge-ancestors (:causal-order source-meta) (:cut lcas) (:returnpaths-b lcas))]
    (raw-commit (assoc source-meta :causal-order new-causal)
                author schema
                branch
                (set/union source-heads target-heads)
                value)))

(defn merge-heads
  "Merge source and target heads into source branch with value as commit."
  ([author schema branch source-meta source-heads target-meta target-heads value]
     (merge-lcas author schema branch source-meta source-heads target-heads value
                 (lowest-common-ancestors (:causal-order source-meta)
                                          source-heads
                                          (:causal-order target-meta)
                                          target-heads))))
