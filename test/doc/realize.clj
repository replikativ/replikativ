(ns doc.realize
  (:require [clojure.core.async :refer [go]]
            [midje.sweet :refer :all]
            [konserve.store :refer [new-mem-store]]
            [geschichte.stage :refer :all]
            [geschichte.realize :refer :all]
            [geschichte.repo :as repo]
            [geschichte.platform :refer [<!?]]))


[[:section {:tag "realization" :title "Realization of repository values"}]]

"As in the [repository introduction](index.html), use a test-environment to fix runtime specific values:"

(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [repo/*id-fn* (let [counter (atom 0)]
                                      (fn ([] (swap! counter inc))
                                        ([val] (swap! counter inc))))
            repo/*date-fn* zero-date-fn]
    (f)))


(facts
 (let [store (<!? (new-mem-store (atom {1 {:transactions [[101 201]]
                                           :author "eve"}
                                        101 '(fn [old params] params)
                                        201 42
                                        2 {:transactions [[102 202]]
                                           :author "eve"}

                                        102 '(fn [old params] (inc old))
                                        202 nil
                                        3 {:transactions [[103 202]]
                                           :author "adam"}
                                        103 '(fn [old params] (dec old))
                                        4 {:transactions [[102 202]]
                                           :author "adam"}})))
       eval-fn {'(fn [old params] params) (fn [old params] params)
                '(fn [old params] (inc old)) (fn [old params] (inc old))
                '(fn [old params] (dec old)) (fn [old params] (dec old))
                '+ +}
       repo {:causal-order {1 []
                            2 [1]
                            3 [1]
                            4 [3]}
             :branches {"master" #{2 4}}}
       repo-non-conflicting {:causal-order {1 []
                                            2 [1]
                                            3 [2]
                                            4 [3]}
                             :branches {"master" #{4}}}
       causal (:causal-order repo)
       causal-non-conflicting (:causal-order repo-non-conflicting)]
   (<!? (commit-history-values store causal 4)) =>
   [{:author "eve", :id 1, :transactions [['(fn [old params] params) 42]]}
    {:author "adam", :id 3, :transactions [['(fn [old params] (dec old)) nil]]}
    {:author "adam", :id 4, :transactions [['(fn [old params] (inc old)) nil]]}]

   (<!? (commit-history-values store causal-non-conflicting 4)) =>
   [{:author "eve", :id 1, :transactions [['(fn [old params] params) 42]]}
    {:author "eve", :id 2, :transactions [['(fn [old params] (inc old)) nil]]}
    {:author "adam", :id 3, :transactions [['(fn [old params] (dec old)) nil]]}
    {:author "adam", :id 4, :transactions [['(fn [old params] (inc old)) nil]]}]

   (<!? (commit-value store eval-fn causal 3)) => 41
   (<!? (commit-value store eval-fn causal-non-conflicting 3)) => 42

   (try
     (<!? (branch-value store eval-fn {:state repo
                                       :transactions {"master" [['+ 2]]}} "master"))
     (catch clojure.lang.ExceptionInfo e
       (= (-> e ex-data :type) :multiple-branch-heads))) => true
   (<!? (branch-value store eval-fn {:state repo-non-conflicting
                                     :transactions {"master" [['+ 2]]}} "master")) => 43

   (<!? (summarize-conflict store eval-fn repo "master")) =>
   #geschichte.realize.Conflict{:lca-value 42,
                             :commits-a ({:id 3,
                                          :author "adam",
                                          :transactions [[(fn [old params] (dec old)) nil]]}
                                         {:id 4,
                                          :author "adam",
                                          :transactions [[(fn [old params] (inc old)) nil]]}),
                             :commits-b ({:id 2,
                                          :author "eve",
                                          :transactions [[(fn [old params] (inc old)) nil]]})}
   (try
     (<!? (summarize-conflict store eval-fn repo-non-conflicting "master"))
     (catch clojure.lang.ExceptionInfo e
       (= (-> e ex-data :type) :missing-conflict-for-summary))) => true))
