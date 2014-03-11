(ns geschichte.stage
  (:require [geschichte.protocols :refer [-get-in]])
  (:require [clojure.core.async :as async
             :refer [<! >! timeout chan alt! go put! filter< map< go-loop]]))


(defn transact [stage params trans-code]
  (update-in stage [:transactions] conj [params trans-code]))

(defn realize-value [stage store eval-fn]
  (go (let [{:keys [head branches causal-order]} (:meta stage)
            tip (branches head)
            hist (loop [c (first tip)
                        hist '()]
                   (if c
                     (recur (first (causal-order c))
                            (conj hist (:transactions
                                        (<! (-get-in store [c])))))
                     hist))]
        (reduce (fn [acc [params trans-fn]]
                  ((eval-fn trans-fn) acc params))
                nil
                (concat (apply concat hist) (:transactions stage))))))
