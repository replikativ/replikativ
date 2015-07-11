(ns replikativ.crdt.utils
  (:require [replikativ.protocols :refer [PExternalValues]]))

(defn flatten-all-colls [colls]
  "Fast non-lazy version of flatten which flattens all collections,
  but not records. Inspired by slovic on clojuredocs.org."
  (loop [c colls, fl `()]
    (cond
      (and (coll? (first c))
           (not (record? (first c)))) (recur (concat (first (seq c)) (rest c)) fl)
      (empty? c) (reverse fl)
      :else (recur (rest c) (cons (first c) fl)))))


; (flatten-all-colls [1 2 3 #{5 6 7 {8 #replikativ.crdt.repo.impl.Repository{:causal-order 0 :branches 1 :store 2 :cursor 3} 10 11}}])

(defn extract-crdts [transactions]
  (->> transactions
       flatten-all-colls
       (filter #(satisfies? PExternalValues %))
       (map #(dissoc % :store))
       (into #{})))
