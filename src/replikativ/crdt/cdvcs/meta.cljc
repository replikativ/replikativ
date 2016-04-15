(ns replikativ.crdt.cdvcs.meta
  "Operation on metadata and commit-graph (directed acyclic graph) of a CDVCS.

   Metadata CDVCS-format for automatic server-side
  synching (p2p-web). Have a look at the documentation for more
  information."
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*date-fn*]]))


(defn consistent-graph? [graph]
  (let [parents (->> graph vals (map set) (apply set/union))
        commits (->> graph keys set)]
    (set/superset? commits parents)))


(defn lowest-common-ancestors
  "Online BFS implementation with O(n) complexity. Assumes no cycles
  exist."
  ([graph-a heads-a graph-b heads-b]
   (cond
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
       {:lcas lcas :visited-a visited-a :visited-b visited-b}
       (recur graph-a new-heads-a visited-a start-heads-a
              graph-b new-heads-b visited-b start-heads-b)))))

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
  [{bs :heads cg :commit-graph :as cdvcs}
   {obs :heads ocg :commit-graph :as op}]
  (try
    (let [new-graph (merge ocg cg)
          new-heads (remove-ancestors new-graph bs obs)]
      ;; TODO remove debug checks in releases...
      (when-not (consistent-graph? new-graph)
        (throw (ex-info "Inconsistent graph created."
                        {:cdvcs cdvcs
                         :op op})))
      (assoc cdvcs
             :heads new-heads
             :commit-graph new-graph))
    (catch #?(:clj Exception :cljs js/Error) e
      (throw (ex-info "Cannot apply downstream operation."
                      {:error e
                       :op op
                       :cdvcs cdvcs})))))


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

  )
