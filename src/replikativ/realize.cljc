(ns replikativ.realize
  "Functions to realize commited transactions."
  (:require [clojure.set :as set]
            [konserve.core :as k]
            [replikativ.environ :refer [store-blob-trans-id store-blob-trans-value store-blob-trans]]
            [kabel.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [<? go-try <?*]])
            #?(:clj [full.lab :refer [go-loop-super]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.async :refer [go-try <? <?*]])))


(defn commit-transactions
  "Fetch commit transactions."
  [store commit-value]
  (go-try
   (->> commit-value
        :transactions
        (map (fn [[trans-id param-id]]
               (go-try [(<? (k/get-in store [trans-id]))
                        (<? (if (= trans-id store-blob-trans-id)
                              (k/bget store param-id identity)
                              (k/get-in store [param-id])))])))
        <?*)))


(defn trans-apply
  "Apply a transaction to the value due to the eval-fn interpreter."
  [eval-fn val [trans-fn params]]
  (try
    (if (= trans-fn store-blob-trans-value)
      (store-blob-trans val params)
      ((eval-fn trans-fn) val params))
    (catch #?(:clj Exception :cljs js/Error) e
        (throw (ex-info "Cannot transact."
                        {:trans-fn trans-fn
                         :params params
                         :old val
                         :exception e})))))


(defn reduce-commits
  "Reduce over the commits in order, applying the transactions with the help of
  the eval-fn on the way."
  [store eval-fn init commits]
  (let [[f & r] commits]
    (go-try (loop [[f & r] commits
                   val init]
              (if f
                (let [cval (<? (k/get-in store [f]))
                      transactions  (<? (commit-transactions store cval))]
                  (recur r (reduce (partial trans-apply eval-fn) val transactions)))
                val)))))
