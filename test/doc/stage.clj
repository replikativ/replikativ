(ns doc.stage
  (:require [clojure.core.async :refer [go <!!]]
            [midje.sweet :refer :all]
            [konserve.store :refer [new-mem-store]]
            [geschichte.stage :refer :all]))


(facts
 (let [store (<!! (new-mem-store (atom {1 {:transactions [[101 201]]
                                           :author "eve"}
                                        101 42
                                        201 '(fn [old params] params)
                                        2 {:transactions [[102 202]]
                                           :author "eve"}

                                        102 nil
                                        202 '(fn [old params] (inc old))
                                        3 {:transactions [[102 203]]
                                           :author "adam"}
                                        203 '(fn [old params] (dec old))
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
   (<!! (commit-history-values store causal 4)) =>
   [{:author "eve", :id 1, :transactions [[42 '(fn [old params] params)]]}
    {:author "adam", :id 3, :transactions [[nil '(fn [old params] (dec old))]]}
    {:author "adam", :id 4, :transactions [[nil '(fn [old params] (inc old))]]}]

   (<!! (commit-history-values store causal-non-conflicting 4)) =>
   [{:author "eve", :id 1, :transactions [[42 '(fn [old params] params)]]}
    {:author "eve", :id 2, :transactions [[nil '(fn [old params] (inc old))]]}
    {:author "adam", :id 3, :transactions [[nil '(fn [old params] (dec old))]]}
    {:author "adam", :id 4, :transactions [[nil '(fn [old params] (inc old))]]}]

   (<!! (commit-value store eval-fn causal 3)) => 41
   (<!! (commit-value store eval-fn causal-non-conflicting 3)) => 42

   (try
     (<!! (branch-value store eval-fn {:meta repo
                                       :transactions {"master" [[2 '+]]}} "master"))
     (catch IllegalArgumentException e
       (= (.getMessage e)  "Branch has multiple heads!"))) => true
   (<!! (branch-value store eval-fn {:meta repo-non-conflicting
                                     :transactions {"master" [[2 '+]]}} "master")) => 45

   (<!! (summarize-conflict store eval-fn repo "master")) =>
   #geschichte.stage.Conflict{:lca-value 42,
                              :commits-a ({:id 3,
                                           :author "adam",
                                           :transactions [[nil (fn [old params] (dec old))]]}
                                          {:id 4,
                                           :author "adam",
                                           :transactions [[nil (fn [old params] (inc old))]]}),
                              :commits-b ({:id 2,
                                           :author "eve",
                                           :transactions [[nil (fn [old params] (inc old))]]})}
   (try
     (<!! (summarize-conflict store eval-fn repo-non-conflicting "master"))
     (catch IllegalArgumentException e
       (= (.getMessage e)
          "No conflict to summarize for {:causal-order {1 [], 2 [1], 3 [2], 4 [3]}, :branches {\"master\" #{4}}} master"))) => true))
