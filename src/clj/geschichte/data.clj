(ns ^:shared geschichte.data
  (:require [clojure.data :refer [diff]]
            [geschichte.zip :as z]
            [clojure.zip :as zip])
  (:import java.util.Arrays
           (java.security MessageDigest Security Provider)
           (java.io FileInputStream File InputStream))  )

(let [md (MessageDigest/getInstance "sha-1")
      data (into-array Byte/TYPE "WORLD!")]
  (.reset md)
  (.update md data 0 (count data))
  (count (mapv byte(.digest md))))



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


;; patch related fns TODO simplify

(defn- fill-nil [base p]
  (concat base (repeat (- (count p)
                          (count (take-while nil? (reverse p)))
                          (count base)) nil)))

(declare patch)
(defn- add-seq [base add]
  (let [nb (fill-nil base add)]
    (if base
      (map #(patch %1 %2 true) nb add)
      add)))

(defn- rm-seq [base rm]
  (loop [[b & br] base
         [r & rr] rm
         res []]
    (if (or b r)
      (recur br
             rr
             (if (= r b) ;; element is removed
               res
               (conj res
                     (if (= r nil) b
                         (patch b r false)))))
      res)))

(defn- patch
  [base p add]
  (let [pfn #(patch %1 %2 add)]
    (cond (map? p)
          (let [ks (keys p)]
            (reduce (fn [b [k v]]
                      (if add
                        (assoc b k (if (atom? v) v
                                       (pfn (b k) v)))
                        (if (atom? v) (dissoc b k)
                            (assoc b k (pfn (b k) v)))))
                    base
                    (->> (interleave ks (map p ks))
                         (partition 2))))

          (vector? p) (vec (if add (add-seq base p)
                               (rm-seq base p)))
          (seq? p) (if add (add-seq base p)
                       (rm-seq base p))
          (set? p) (if add (clojure.set/union base p)
                       (clojure.set/difference base p))
          (atom? p) p
          (nil? p) base)))

(defn apply-patch
  "Resolve removals and additions against base.
   Inverse to clojure.data/diff.
   nil is a special value and should be avoided."
  [removals additions base]
  (-> base
      (patch removals false)
      (patch additions true)))
