(ns geschichte.meta
  "Operation on metadata and causal-order (directed acyclic graph) of a repository.

   Metadata repository-format for automatic server-side
   synching (p2p-web). Have a look at the midje-doc documentation for
   more information."
  (:require [clojure.set :as set]))


(defn- track-returnpaths [returnpaths heads meta]
  (reduce (fn [returnpaths head]
            (reduce (fn [returnpaths parent] (update-in returnpaths [parent] #(conj (or %1 #{}) %2) head))
                    returnpaths
                    (seq (meta head))))
          returnpaths
          heads))


(defn- init-returnpath [heads]
  (reduce #(assoc %1 %2 #{}) {} heads))


(defn lowest-common-ancestors
  "Naive online BFS implementation. Assumes no cycles exist."
  ([meta-a heads-a meta-b heads-b]
     (let [returnpaths-a (init-returnpath heads-a)
           returnpaths-b (init-returnpath heads-b)
           cut (set/intersection heads-a heads-b)]
       ; cover initial cut, TODO move case in actual function
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
         (let [new-heads-a (mapcat meta-a heads-a)
               new-heads-b (mapcat meta-b heads-b)]
           (recur meta-a new-heads-a new-returnpaths-a
                  meta-b new-heads-b new-returnpaths-b))))))


(defn- merge-parent [missing-returnpaths meta parent]
  (reduce (fn [meta child]
            (update-in meta [child] #(conj (or %1 #{}) %2) parent))
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


(defn isolate-branch
  "Isolate a branch's metadata causal-order."
  ([meta branch]
     (isolate-branch (:causal-order meta) ((:branches meta) branch) {}))
  ([causal-order cut branch-meta]
     (if (empty? cut) branch-meta
         (recur causal-order
                (set (mapcat causal-order cut))
                (merge branch-meta (select-keys causal-order cut))))))


(defn inline-meta
  "Generate inline metadata. Adding a (uu)id ensures unique hashes of
   commits. This metadata map is not needed by core repo functions like
   merging."
  [author schema branch id ts]
  {:id id
   :author author
   :branch branch
   :schema schema
   :ts ts})


(defn old-heads [causal heads]
  (set (for [a heads b heads]
         (if (not= a b)                 ; => not a and b in cut
           (let [{:keys [returnpaths-a returnpaths-b]}
                 (lowest-common-ancestors causal #{a} causal #{b})
                 keys-a (set (keys returnpaths-a))
                 keys-b (set (keys returnpaths-b))]
             (cond (keys-b a) a
                   (keys-a b) b))))))


(defn remove-ancestors [causal heads-a heads-b]
  (let [to-remove (old-heads causal (set/union heads-a heads-b))]
    (set (filter #(not (to-remove %)) (set/union heads-a heads-b)))))




(defn update [{:keys [id description schema public causal-order branches
                      head last-update pull-requests] :as meta} other-meta]
  (let [newer (> (.getTime (:last-update other-meta)) (.getTime last-update))
        new-causal (merge (:causal-order other-meta) causal-order)]
    {:causal-order new-causal
     :last-update (if newer (:last-update other-meta) last-update)
     :id id
     :description description
     :schema {:type (:type schema)
              :version (max (:version schema) (:version (:schema other-meta)))}
     :head (if newer (:head other-meta) head)
     :branches (merge-with (partial remove-ancestors new-causal)
                           branches (:branches other-meta))
     :public (or public (:public other-meta))
     :pull-requests (merge-with merge (:pull-requests other-meta) pull-requests)}))
