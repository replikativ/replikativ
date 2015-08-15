(ns replikativ.crdt.repo.meta
  "Operation on metadata and commit-graph (directed acyclic graph) of a repository.

   Metadata repository-format for automatic server-side
   synching (p2p-web). Have a look at the midje-doc documentation for
   more information."
  (:require [clojure.set :as set])
  (:require [replikativ.environ :refer [*date-fn*]]))


(defn consistent-graph? [graph]
  (let [parents (->> graph vals (map set) (apply set/union))
        commits (->> graph keys set)]
    (set/superset? commits parents)))

;; simple LRU cache
(def lca-cache (atom {}))


(defn- query-lca-cache [head]
  (when-let [hits (@lca-cache head)]
    (swap! lca-cache assoc-in [head :ts] (.getTime (*date-fn*)))
    (:hits hits)))


(defn- swap-lca-cache! [lca start-heads-a start-heads-b]
  (when (> (count @lca-cache) 1000)
    (swap! lca-cache (fn [old]
                       (->> old
                            (sort-by (comp :ts second) >)
                            (take 500)
                            (map (fn [[k v]] [k (assoc v :ts 0)]))
                            (into {})))))
  (swap! lca-cache (fn [old] (merge-with (fn [{ha :hits ta :ts}
                                            {hb :hits tb :ts}]
                                          {:hits (set/union ha hb)
                                           :ts (max ta tb)})
                                        old (->> (repeat {:hits #{lca}
                                                          :ts 0})
                                                 (interleave (concat start-heads-a
                                                                     start-heads-b))
                                                 (partition 2)
                                                 (mapv vec)
                                                 (into {}))))))


(defn- match [heads-a heads-b {va :visited-a vb :visited-b c :lcas}]
  (cond (and (set/subset? heads-a va)
             (set/subset? heads-b vb))
        [{:lcas c :visited-a va :visited-b vb}]

        (and (set/subset? heads-a vb)
             (set/subset? heads-b va))
        [{:lcas c :visited-a vb :visited-b va}]

        :else []))


(defn lowest-common-ancestors
  "Online BFS implementation with O(n) complexity. Assumes no cycles
  exist."
  ([graph-a heads-a graph-b heads-b]
   ;; fast forward path
   (cond
     ;; state sync: b subgraph of a
     #_(and (not= (count graph-b) 1) ;; operation
            (= (count (select-keys graph-a heads-b))
               (count heads-b))
            (zero? (count (select-keys graph-b heads-a))))
     #_{:lcas (set heads-b) :visited-a (set (keys graph-a)) :visited-b (set heads-b)}

     ;; state sync: a subgraph of b
     #_(and (not= (count graph-a) 1) ;; operation
            (= (count (select-keys graph-b heads-a))
               (count heads-a))
            (zero? (count (select-keys graph-a heads-b))))
     #_{:lcas (set heads-a) :visited-a (set heads-a) :visited-b (set (keys graph-b))}

     ;; already done
     (= (set heads-a) (set heads-b))
     {:lcas (set heads-a) :visited-a (set heads-a) :visited-b (set heads-b)}

     :else
     (lowest-common-ancestors graph-a heads-a heads-a heads-a
                              graph-b heads-b heads-b heads-b)))
  ([graph-a heads-a visited-a start-heads-a
    graph-b heads-b visited-b start-heads-b]
   (when (and (empty? heads-a) (empty? heads-b))
     (throw (ex-info "Graph is not connected, LCA failed."
                     {:graph-a graph-a
                      :graph-b graph-b
                      :visited-a visited-a
                      :visited-b visited-b})))
   (if-let [cache-hit (some->> (or (query-lca-cache (first heads-a))
                                   (query-lca-cache (first heads-b)))
                               (mapcat (partial match heads-a heads-b))
                               first)]
     (let [lca (merge-with set/union cache-hit {:visited-a visited-a
                                                :visited-b visited-b})]
       (swap-lca-cache! lca start-heads-a start-heads-b)
       lca)
     (let [new-heads-a (set (mapcat graph-a heads-a))
           new-heads-b (set (mapcat graph-b heads-b))
           visited-a (set/union visited-a new-heads-a)
           visited-b (set/union visited-b new-heads-b)
           lcas (set/intersection visited-a visited-b)]
       (if (and (not (empty? lcas))
                ;; keep going until all paths of b are in graph-a to
                ;; complete visited-b.
                (= (count (select-keys graph-a new-heads-b))
                   (count new-heads-b)))
         (let [lca {:lcas lcas :visited-a visited-a :visited-b visited-b}]
           (swap-lca-cache! lca start-heads-a start-heads-b)
           lca)
         (recur graph-a new-heads-a visited-a start-heads-a
                graph-b new-heads-b visited-b start-heads-b))))))


;; TODO refactor to isolate-tipps
(defn isolate-branch
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
           (let [{:keys [lcas]} (lowest-common-ancestors graph #{a} graph #{b})]
             (lcas b))))))


(defn remove-ancestors [graph heads-a heads-b]
  (let [to-remove (old-heads graph (set/union heads-a heads-b))]
    (set (filter #(not (to-remove %)) (set/union heads-a heads-b)))))


(defn downstream
  "Applies downstream updates from op to state. Idempotent and
  commutative."
  [{bs :branches cg :commit-graph :as repo}
   {obs :branches ocg :commit-graph :as op}]
  ;; TODO protect commit-graph from overwrites
  (try
    (let [new-graph (merge cg ocg)
          new-branches (merge-with (partial remove-ancestors new-graph) bs obs)]
      (assoc repo
             :branches new-branches
             :commit-graph new-graph))
    (catch Throwable e
      (throw (ex-info "Cannot apply downstream operation."
                      {:error e
                       :op op
                       :repo repo})))))


(comment
  ;; lca benchmarking...
  (def giant-graph (->> (interleave (range 1 (inc 1e6)) (range 0 1e6))
                        (partition 2)
                        (mapv (fn [[k v]] [k (if (= v 0) [] [v])]))
                        (into {})))


  (time (do (lowest-common-ancestors giant-graph #{100}  giant-graph #{1})
            nil))

  (time (let [{:keys [lcas visited-a visited-b] :as lca}
              (lowest-common-ancestors  giant-graph #{1000000} {1 [] 2 [1]} #{2})]
          [lcas (count visited-a) (count visited-b)]
          #_(select-keys giant-graph visited-b)))

  (count @lca-cache)

  (map :ts (vals @lca-cache))
  (map (comp keys second) @lca-cache)


  (-> @lca-cache vals first :ts)
  )
