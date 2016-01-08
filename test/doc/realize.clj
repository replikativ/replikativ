(ns doc.realize
  (:require [midje.sweet :refer :all]
            [konserve.memory :refer [new-mem-store]]
            [replikativ.environ :refer [*id-fn* *date-fn*]]
            [replikativ.crdt.cdvcs.stage :refer :all]
            [replikativ.crdt.cdvcs.realize :refer :all]
            [replikativ.crdt.cdvcs.repo :as repo]
            [replikativ.crdt.cdvcs.meta :as meta]
            [full.async :refer [<??]]))


[[:section {:tag "realization" :title "Realization of repository values"}]]

"As in the [repository introduction](index.html), use a test-environment to fix runtime specific values:"

(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [*id-fn* (let [counter (atom 0)]
                      (fn ([] (swap! counter inc))
                        ([val] (swap! counter inc))))
            *date-fn* zero-date-fn]
    (f)))

(reset! meta/lca-cache {})

(facts
 (let [store (<?? (new-mem-store (atom {1 {:transactions [[101 201]]
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
       repo {:commit-graph {1 []
                            2 [1]
                            3 [1]
                            4 [3]}
             :heads #{2 4}}
       repo-non-conflicting {:commit-graph {1 []
                                            2 [1]
                                            3 [2]
                                            4 [3]}
                             :heads #{4}}
       graph (:commit-graph repo)
       graph-non-conflicting (:commit-graph repo-non-conflicting)]
   (<?? (commit-history-values store graph 4)) =>
   [{:author "eve", :id 1, :transactions [['(fn [old params] params) 42]]}
    {:author "adam", :id 3, :transactions [['(fn [old params] (dec old)) nil]]}
    {:author "adam", :id 4, :transactions [['(fn [old params] (inc old)) nil]]}]

   (<?? (commit-history-values store graph-non-conflicting 4)) =>
   [{:author "eve", :id 1, :transactions [['(fn [old params] params) 42]]}
    {:author "eve", :id 2, :transactions [['(fn [old params] (inc old)) nil]]}
    {:author "adam", :id 3, :transactions [['(fn [old params] (dec old)) nil]]}
    {:author "adam", :id 4, :transactions [['(fn [old params] (inc old)) nil]]}]

   (<?? (commit-value store eval-fn graph 3)) => 41
   (<?? (commit-value store eval-fn graph-non-conflicting 3)) => 42

   (try
     (<?? (head-value store eval-fn {:state repo
                                     :transactions [['+ 2]]}))

     (catch clojure.lang.ExceptionInfo e
       (= (-> e ex-data :type) :multiple-heads))) => true
   (<?? (head-value store eval-fn {:state repo-non-conflicting
                                   :transactions [['+ 2]]})) => 43

   (<?? (summarize-conflict store eval-fn repo)) =>
   #replikativ.crdt.cdvcs.realize.Conflict{:lca-value 42,
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
     (<?? (summarize-conflict store eval-fn repo-non-conflicting))
     (catch clojure.lang.ExceptionInfo e
       (= (-> e ex-data :type) :missing-conflict-for-summary))) => true))
