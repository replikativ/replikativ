(ns geschichte.meta
  (:require [clojure.set :as set]))

;; Operation on the metadata dependency tree of the repository.

(defn- track-backways [backways heads meta]
  (reduce (fn [backways head]
            (reduce (fn [backways parent] (update-in backways [parent] #(conj (or %1 #{}) %2) head))
                    backways
                    (seq (meta head))))
          backways
          heads))

(defn lowest-common-ancestors
  "Naive online BFS implementation. Assumes common ancestor exists! (same repo)"
  ([meta-a meta-b] (lowest-common-ancestors meta-a (:head meta-a)
                                            meta-b (:head meta-b)))
  ([meta-a head-a meta-b head-b]
     ; cover initial identity, TODO move case in actual function
     (if (= head-a head-b) {:cut #{head-a}
                            :backways-a {head-a #{}}
                            :backways-b {head-b #{}}}
         (lowest-common-ancestors meta-a [head-a] {head-a #{}}
                                  meta-b [head-b] {head-b #{}})))
  ([meta-a heads-a backways-a
    meta-b heads-b backways-b]
     (let [new-backways-a (track-backways backways-a heads-a meta-a)
           new-backways-b (track-backways backways-b heads-b meta-b)
           cut (set/intersection (set (keys new-backways-a)) (set (keys new-backways-b)))]
       (if (not (empty? cut)) {:cut cut :backways-a new-backways-a :backways-b new-backways-b}
           (let [new-heads-a (mapcat meta-a heads-a)
                 new-heads-b (mapcat meta-b heads-b)]
             (recur meta-a new-heads-a new-backways-a
                    meta-b new-heads-b new-backways-b))))))

(defn- merge-parent [missing-backways meta parent]
  (reduce (fn [meta child]
            (update-in meta [child] #(conj (or %1 #{}) %2) parent))
          meta
          (missing-backways parent)))

(defn merge-ancestors
  "Use backways and cut from lowest-common-ancestors to merge alien
   ancestor paths into meta data."
  ([meta cut missing-backways]
     (let [new-meta (reduce (partial merge-parent missing-backways) meta cut)
           new-cut (mapcat missing-backways cut)]
       (if (empty? new-cut) new-meta
         (recur new-meta new-cut missing-backways)))))


(defn inline-meta
  "Generate inline metadata. Adding a uuid ensures unique hashes of
   commits. This metadata map is not needed by core repo functions like
   merging."
  []
  {:uuid (java.util.UUID/randomUUID)
   :ts (System/currentTimeMillis)})
