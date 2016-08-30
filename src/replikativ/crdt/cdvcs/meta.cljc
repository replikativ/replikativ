(ns replikativ.crdt.cdvcs.meta
  "Operation on metadata and commit-graph (directed acyclic graph) of a CDVCS.

   Metadata CDVCS-format for automatic server-side
  synching (p2p-web). Have a look at the documentation for more
  information."
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*date-fn*]]))


;; adapted from clojure.set, but is faster if the intersection is small
;; where intersection in clojure.set is faster when the intersection is large
(defn intersection [s1 s2]
  (if (< (count s2) (count s1))
    (recur s2 s1)
    (reduce (fn [result item]
              (if (contains? s2 item)
                (conj result item)
                result))
            #{} s1)))

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
   (let [new-heads-a (set (mapcat graph-a heads-a))
         new-heads-b (set (mapcat graph-b heads-b))
         visited-a (set/union visited-a new-heads-a)
         visited-b (set/union visited-b new-heads-b)]
     ;; short circuit intersection
     ;; as long as we don't match any new heads-b in visited-a
     (if (and (not-empty new-heads-b) 
              (empty? (intersection visited-a new-heads-b)))
       (recur graph-a new-heads-a visited-a start-heads-a
              graph-b new-heads-b visited-b start-heads-b)
       (let [lcas (intersection visited-a visited-b)]
         (if (and (not (empty? lcas))
                  ;; keep going until all paths of b are in graph-a to
                  ;; complete visited-b.
                  (= (count (select-keys graph-a new-heads-b))
                     (count new-heads-b)))
           {:lcas lcas :visited-a visited-a :visited-b visited-b}
           (do
             (when (and (empty? new-heads-a) (empty? new-heads-b))
               (throw (ex-info "Graph is not connected, LCA failed."
                               {:graph-a graph-a
                                :graph-b graph-b
                                :dangling-heads-a heads-a
                                :dangling-heads-b heads-b
                                :start-heads-a start-heads-a
                                :start-heads-b start-heads-b
                                :visited-a visited-a
                                :visited-b visited-b})))
             (recur graph-a new-heads-a visited-a start-heads-a
                    graph-b new-heads-b visited-b start-heads-b))))))))


(defn- pairwise-lcas [new-graph graph-a heads-a heads-b]
  (cond
    ;; if heads-b is already in graph-a, then they are the lcas
    (= (count heads-b) (count (select-keys graph-a (seq heads-b))))
    (set/difference heads-b heads-a)

    :else
    (apply set/union
           (for [a heads-a b heads-b
                 :when (not= a b)]
             (let [{:keys [lcas]} (lowest-common-ancestors new-graph #{a} new-graph #{b})]
               lcas)))))


(defn remove-ancestors [new-graph graph-a heads-a heads-b]
  (let [to-remove (pairwise-lcas new-graph graph-a heads-a heads-b)]
    (set/difference (set/union heads-a heads-b) to-remove)))


(defn downstream
  "Applies downstream updates from op to state. Idempotent and
  commutative."
  [{bs :heads cg :commit-graph :as cdvcs}
   {obs :heads ocg :commit-graph :as op}]
  (try

    (let [;; faster, but safer was not to overwrite parts of the graph
          new-graph (if (> (count cg) (count ocg))
                      (merge cg ocg)
                      (merge ocg cg))
          new-heads (remove-ancestors new-graph cg bs obs)]
      ;; TODO disable debug checks in releases automatically
      #_(when-not (consistent-graph? new-graph)
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
