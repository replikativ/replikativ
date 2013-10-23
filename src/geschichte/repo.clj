(ns geschichte.repo
  (:require [clojure.data :refer [diff]]
            [clojure.set :as set]
            [geschichte.meta :refer [lowest-common-ancestors merge-ancestors]]))

;; Implementing core repository functions.

(defn add-meta-to-value
  "Add metadata to value. Adding a uuid ensures unique hashes of
   commits. This metadata is not needed by core repo functions like
   merging."
  [author value]
  (-> value
      (assoc-in [:meta :uuid] (java.util.UUID/randomUUID))
      (assoc-in [:meta :ts] (System/currentTimeMillis))
      (assoc-in [:meta :author] author)))

(defn commit
  "Commits to repo with repo-id and metadata meta
   a commit with parents and value new.
   Returns a map describing the transaction with :puts."
  [repo-id meta parents new]
    (if (contains? parents (:master meta))
      (let [h (hash new)
            new-meta (assoc meta h (into #{} parents) :master h)]
        {:puts {h new
                repo-id new-meta}})))

(defn merge-branches [repo-id meta-source meta-target val]
  "Merge target branch into source branch with value val."
  (let [lcas (lowest-common-ancestors meta-source meta-target)
        new-meta (merge-ancestors meta-source (:cut lcas) (:backways-b lcas))]
    (commit repo-id new-meta #{(:master meta-source) (:master meta-target)} val)))
