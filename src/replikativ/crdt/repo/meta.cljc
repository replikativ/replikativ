(ns replikativ.crdt.repo.meta
  "Operation on metadata and causal-order (directed acyclic graph) of a repository.

   Metadata repository-format for automatic server-side
   synching (p2p-web). Have a look at the midje-doc documentation for
   more information."
  (:require [clojure.set :as set]))

(defn consistent-causal? [causal]
  (let [parents (->> causal vals (map set) (apply set/union))
        commits (->> causal keys set)]
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
  "Naive online BFS implementation. Assumes no cycles exist."
  ([meta-a heads-a meta-b heads-b]
     (let [heads-a (set heads-a)
           heads-b (set heads-b)
           returnpaths-a (init-returnpath heads-a)
           returnpaths-b (init-returnpath heads-b)
           cut (set/intersection heads-a heads-b)]
       (if-not (empty? cut) {:cut cut
                             :returnpaths-a returnpaths-a
                             :returnpaths-b returnpaths-b}
               (lowest-common-ancestors meta-a heads-a returnpaths-a
                                        meta-b heads-b returnpaths-b))))
  ([meta-a heads-a returnpaths-a
    meta-b heads-b returnpaths-b]
     (let [new-returnpaths-a (track-returnpaths returnpaths-a heads-a meta-a)
           new-returnpaths-b (track-returnpaths returnpaths-b heads-b meta-b)
           cut (set/intersection (set (keys new-returnpaths-a)) (set (keys new-returnpaths-b)))]
       (if (or (not (empty? cut))
               (and (empty? heads-a) (empty? heads-b)))
         {:cut cut :returnpaths-a new-returnpaths-a :returnpaths-b new-returnpaths-b}
         (let [new-heads-a (set (mapcat meta-a heads-a))
               new-heads-b (set (mapcat meta-b heads-b))]
           (recur meta-a new-heads-a new-returnpaths-a
                  meta-b new-heads-b new-returnpaths-b))))))


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
  "Isolate a branch's metadata causal-order."
  ([meta branch]
   (isolate-branch (:causal-order meta) (-> meta :branches (get branch)) {}))
  ([causal-order cut branch-meta]
   (if (empty? cut) branch-meta
       (recur causal-order
              (set (mapcat causal-order cut))
              (merge branch-meta (select-keys causal-order cut))))))

(defn- old-heads [causal heads]
  (set (for [a heads b heads]
         (if (not= a b)                 ; => not a and b in cut
           (let [{:keys [returnpaths-a returnpaths-b]}
                 (lowest-common-ancestors causal #{a} causal #{b})
                 keys-a (set (keys returnpaths-a))
                 keys-b (set (keys returnpaths-b))]
             (cond (keys-b a) a
                   (keys-a b) b))))))


(defn remove-ancestors [causal heads-a heads-b]
  (if causal
    (let [to-remove (old-heads causal (set/union heads-a heads-b))]
      (set (filter #(not (to-remove %)) (set/union heads-a heads-b))))))

(defn downstream
  "Applies downstream updates from op to state. Idempotent and
  commutative."
  [{:keys [causal-order branches]} op]
  (let [new-causal (merge (:causal-order op) causal-order)
        new-branches  (merge-with (partial remove-ancestors new-causal)
                                  branches (:branches op))]
    ;; TODO move check to entry point/middleware
    (when-not (consistent-causal? new-causal)
      (throw (ex-info "Remote meta does not have a consistent causal oder."
                      {:type :inconsistent-causal-order
                       :op op
                       :causal new-causal})))
    {:branches new-branches
     :causal-order new-causal}))
