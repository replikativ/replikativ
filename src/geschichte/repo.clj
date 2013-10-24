(ns geschichte.repo
  (:require [geschichte.meta :refer [lowest-common-ancestors merge-ancestors]]))

;; Implementing core repository functions.

(defn commit
  "Commits to repo with repo-id and metadata meta
   a commit with parents and value new.
   Returns a map describing the transaction with :puts."
  [repo-id meta parents new]
    (if (contains? parents (:head meta))
      (let [h (hash new)
            new-meta (assoc meta h (into #{} parents) :head h)]
        {:puts {h new
                repo-id new-meta}})))

(defn merge-branches [repo-id meta-source meta-target val]
  "Merge target branch into source branch with value val."
  (let [lcas (lowest-common-ancestors meta-source meta-target)
        new-meta (merge-ancestors meta-source (:cut lcas) (:backways-b lcas))]
    (commit repo-id new-meta #{(:head meta-source) (:head meta-target)} val)))
