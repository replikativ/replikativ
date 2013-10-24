(ns geschichte.data
  (:require [clojure.data :refer [diff]]
            [geschichte.zip :as z]
            [clojure.zip :as zip]))


(defn three-way-merge [base a b]
  "Calculate a map with additions and removals to a and b compared to base."
  (let [base-a (diff base a)
        base-b (diff base b)]
    {:removals-a (first base-a)
     :additions-a (second base-a)
     :removals-b (first base-b)
     :additions-b (second base-b)}))

(defn atom? [x]
  (or (number? x) (string? x)))

(defn- isolate-atomic-conflict-places [x]
; Use zipper to equalize atoms in removal and addition maps (tree)
  (-> (z/universal-zip x)
      (z/tree-edit atom? #(if %1 :conflict %2))
      (zip/root)
      ;; hack, inverse nils -> nil on rediff changesets
      (z/universal-zip)
      (z/tree-edit nil? #(if %1 (gensym) %2))
      (zip/root)))

(defn conflicts
  "Show conflict places between additions and removals (covers updates)
   of a three-way-merge as :atom."
  [{:keys [removals-a additions-a
           removals-b additions-b]}]
  [(nth (diff (isolate-atomic-conflict-places additions-a)
              (isolate-atomic-conflict-places removals-b)) 2)
   (nth (diff (isolate-atomic-conflict-places additions-b)
              (isolate-atomic-conflict-places removals-a)) 2)
   (nth (diff (isolate-atomic-conflict-places additions-b)
              (isolate-atomic-conflict-places additions-a)) 2)])

(defn conflicts?
  "Are there places of conflicts in the three-way-merge results map?"
  [x]
  (not (every? nil? (conflicts x))))
