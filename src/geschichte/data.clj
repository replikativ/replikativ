(ns ^:shared geschichte.data
  (:require [clojure.data :refer [diff]]
            [geschichte.zip :as z]
            [clojure.zip :as zip]))


(defn changes-to-base
  "Calculate a map with additions and removals to a and b compared to base."
  [base a b]
  (let [base-a (diff base a)
        base-b (diff base b)]
    {:removals-a (first base-a)
     :additions-a (second base-a)
     :removals-b (first base-b)
     :additions-b (second base-b)}))

(defn atom?
  "Determines whether atomic places conflict."
  [x]
  (not (or (seq? x) (vector? x) (map? x) (set? x) (nil? x))))

(defn- gensym-nils [diff]
  "Inverse nils (not changed in diff):
   gensymed nils -> nil in (re)diff common set."
  (-> (z/universal-zip diff)
      (z/tree-edit nil? (fn [loc res val] (if res (gensym) val)))))

(defn- isolate-atomic-conflict-places
  "Provided to later rediff the result for a common set by unifying atomic
   changes to :conflict and inverting nils."
  [diff]
  (-> (z/universal-zip diff)
      (z/tree-edit atom? (fn [loc res val]
                           (if (and res ; hack around map keys:
                                    (not= (type (zip/node (zip/prev loc)))
                                          clojure.lang.MapEntry))
                             :conflict
                             val)))
      gensym-nils))

(defn conflicts
  "Show conflict places between additions and removals (covers updates)
   of a three-way-merge as :conflict.
   [add-a-rem-b-conflicts add-b-rem-a-conflicts add-a-add-b-conflicts]"
  [{:keys [removals-a additions-a
           removals-b additions-b]}]
  [(nth (diff (isolate-atomic-conflict-places additions-a)
              (isolate-atomic-conflict-places removals-b)) 2)
   (nth (diff (isolate-atomic-conflict-places additions-b)
              (isolate-atomic-conflict-places removals-a)) 2)
   (nth (diff (isolate-atomic-conflict-places additions-a)
              (isolate-atomic-conflict-places additions-b)) 2)])

(defn conflicts?
  "Are there places of conflicts in the three-way-merge results map?"
  [x]
  (not (every? nil? (conflicts x))))
