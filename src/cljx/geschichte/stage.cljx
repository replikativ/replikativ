(ns ^:shared geschichte.stage
    (:require [konserve.protocols :refer [-get-in]]
              #+clj [clojure.core.async :as async
                     :refer [<! >! timeout chan alt! go put! filter< map< go-loop]]
              #+cljs [cljs.core.async :as async
                      :refer [<! >! timeout chan put! filter< map<]])
    #+cljs (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]))


(defn transact [stage params trans-code]
  (update-in stage [:transactions] conj [params trans-code]))


(defn realize-value [stage store eval-fn]
  (go (let [{:keys [head branches causal-order]} (:meta stage)
            tip (get-in branches [head :heads])
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
