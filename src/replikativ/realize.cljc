(ns replikativ.realize
  "Functions to realize commited transactions."
  (:require [clojure.set :as set]
            [konserve.core :as k]
            [replikativ.environ :refer [store-blob-trans-id store-blob-trans-value
                                        store-blob-trans]]
            #?(:clj [kabel.platform-log :refer [debug info warn]])
            #?(:clj [superv.async :refer [<? go-try <?* go-loop-try]])
            [superv.async :refer [reduce<]]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [go-try <? <?* go-loop-try]]
                            [kabel.platform-log :refer [debug info warn]])))


(defn commit-transactions
  "Fetch commit transactions."
  [S store commit-value]
  (go-try S
   (->> commit-value
        :transactions
        (map (fn [[trans-id param-id]]
               (go-try S [(<? S (k/get-in store [trans-id]))
                          (<? S (if (= trans-id store-blob-trans-id)
                                  (k/bget store param-id identity)
                                  (k/get-in store [param-id])))])))
        (<?* S))))


(defn trans-apply
  "Apply a transaction to the value due to the eval-fn interpreter."
  [eval-fns S val [trans-fn params]]
  (try
    (if (= trans-fn store-blob-trans-value)
      (store-blob-trans val params)
      ((eval-fns trans-fn) S val params))
    (catch #?(:clj Exception :cljs js/Error) e
        (throw (ex-info "Cannot transact."
                        {:trans-fn trans-fn
                         :params params
                         :old val
                         :exception e})))))


(defn reduce-commits
  "Reduce over the commits in order, applying the transactions with the help of
  the eval-fns on the way."
  [S store eval-fns init commits]
  (let [[f & r] commits]
    (reduce< S (fn [S val elem]
                 (go-try S
                   (let [cval (<? S (k/get-in store [elem]))
                         transactions  (<? S (commit-transactions S store cval))]
                     (<? S (reduce< S (partial trans-apply eval-fns)
                                    val transactions)))))
             init commits)))
