(ns replikativ.crdt.repo.meta
  "Operation on metadata and commit-graph (directed acyclic graph) of a repository.

   Metadata repository-format for automatic server-side
   synching (p2p-web). Have a look at the midje-doc documentation for
   more information."
  (:require [clojure.set :as set]))

(defn consistent-graph? [graph]
  (let [parents (->> graph vals (map set) (apply set/union))
        commits (->> graph keys set)]
    (set/superset? commits parents)))

(defn- track-returnpaths [returnpaths heads meta]
  (reduce (fn [returnpaths head]
            (reduce (fn [returnpaths parent]
                      (update-in returnpaths [parent] #(conj (or %1 #{}) %2) head))
                    returnpaths
                    (meta head)))
          returnpaths
          heads))


(defn- init-returnpath [heads]
  (reduce #(assoc %1 %2 #{}) {} heads))


(defn lowest-common-ancestors
  "Online BFS implementation with O(n) complexity. Assumes no cycles exist."
  ([meta-a heads-a meta-b heads-b]
   (let [heads-a (set heads-a)
         heads-b (set heads-b)
         returnpaths-a (init-returnpath heads-a)
         returnpaths-b (init-returnpath heads-b)
         cut (set/intersection heads-a heads-b)]
     (if-not (empty? cut) {:cut cut
                           :returnpaths-a returnpaths-a
                           :returnpaths-b returnpaths-b}
             (lowest-common-ancestors meta-a heads-a returnpaths-a heads-a
                                      meta-b heads-b returnpaths-b heads-b))))
  ([meta-a heads-a returnpaths-a visited-a
    meta-b heads-b returnpaths-b visited-b]
   (when (and (empty? heads-a) (empty? heads-b))
     (throw (ex-info "Graph is not connected, LCA failed."
                     {:meta-a meta-a
                      :meta-b meta-b
                      :returnpaths-a returnpaths-a
                      :returnpaths-b returnpaths-b})))
   (let [new-returnpaths-a (track-returnpaths returnpaths-a heads-a meta-a)
         new-returnpaths-b (track-returnpaths returnpaths-b heads-b meta-b)
         new-heads-a (set (mapcat meta-a heads-a))
         new-heads-b (set (mapcat meta-b heads-b))
         visited-a (set/union visited-a new-heads-a)
         visited-b (set/union visited-b new-heads-b)
         cut (set/intersection visited-a visited-b)]
     (if (not (empty? cut))
       {:cut cut :returnpaths-a new-returnpaths-a :returnpaths-b new-returnpaths-b}
       (recur meta-a new-heads-a new-returnpaths-a visited-a
              meta-b new-heads-b new-returnpaths-b visited-b)))))


(defn- merge-parent [missing-returnpaths meta parent]
  (reduce (fn [meta child]
            (update-in meta [child] #(conj (or %1 []) %2) parent))
          meta
          (missing-returnpaths parent)))


(defn merge-ancestors
  "Use returnpaths and cut from lowest-common-ancestors to merge alien
   ancestor paths into meta data."
  ([meta cut missing-returnpaths]
     (let [new-meta (reduce (partial merge-parent missing-returnpaths) meta cut)
           new-cut (mapcat missing-returnpaths cut)]
       (if (empty? new-cut) new-meta
         (recur new-meta new-cut missing-returnpaths)))))


;; TODO refactor to isolate-tipps
(declare isolate-branch)
(defn isolate-branch                    ; -nomemo
  "Isolate a branch's metadata commit-graph."
  ([meta branch]
   (isolate-branch (:commit-graph meta) (-> meta :branches (get branch)) {}))
  ([commit-graph cut branch-meta]
   (if (empty? cut) branch-meta
       (recur commit-graph
              (set (mapcat commit-graph cut))
              (merge branch-meta (select-keys commit-graph cut))))))

(defn- old-heads [graph heads]
  (set (for [a heads b heads]
         (if (not= a b)                 ; => not a and b in cut
           (let [{:keys [returnpaths-a returnpaths-b]}
                 (lowest-common-ancestors graph #{a} graph #{b})
                 keys-a (set (keys returnpaths-a))
                 keys-b (set (keys returnpaths-b))]
             (cond (keys-b a) a
                   (keys-a b) b))))))


(defn remove-ancestors [graph heads-a heads-b]
  (if graph
    (let [to-remove (old-heads graph (set/union heads-a heads-b))]
      (set (filter #(not (to-remove %)) (set/union heads-a heads-b))))))

(defn downstream
  "Applies downstream updates from op to state. Idempotent and
  commutative."
  [{:keys [commit-graph branches] :as repo} op]
  ;; TODO protect commit-graph from overwrites
  (let [new-graph (merge commit-graph (:commit-graph op))
        ;; TODO supply whole graph including history
        new-branches (merge-with (partial remove-ancestors new-graph)
                                 branches (:branches op))]
    ;; TODO move check to entry point/middleware
    #_(when-not (consistent-graph? new-graph)
        (throw (ex-info "Remote meta does not have a consistent graph oder."
                        {:type :inconsistent-commit-graph
                         :op op
                         :graph new-graph})))
    (assoc repo
           :branches new-branches
           :commit-graph new-graph)))


(comment
  ;; lca benchmarking...
  (def giant-graph (->> (interleave (range 1 (inc 1e5)) (range 0 1e5))
                        (partition 2)
                        (mapv (fn [[k v]] [k (if (= v 0) [] [v])]))
                        (into {})))
  (giant-graph 100000)


  (time (lowest-common-ancestors giant-graph #{100000}  giant-graph #{1}))
  ;; 1e3 220 ms
  ;; 1e4 22 secs ...
  )
