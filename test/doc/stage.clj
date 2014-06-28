(ns doc.stage
  (:require [clojure.core.async :refer [go <!!]]
            [midje.sweet :refer :all]
            [konserve.store :refer [new-mem-store]]
            [geschichte.stage :refer :all]
            [geschichte.repo :as repo]))


[[:section {:tag "stage-sync" :title "Stage-based syncing"}]]

"To execute the syncing (storage) related side-effects, you create a runtime *stage* primitive, wire it to a peer and synchronize its value (unless it is loaded). To update, you transact the stage, like swapping an atom, except that you should parametrize the function to make data used in the transaction explicit for later inspection (like a serialized scope). Once you are finished you commit and sync!."

"As in the [repository introduction](index.html), use a test-environment to fix runtime specific values:"

(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [repo/*id-fn* (let [counter (atom 0)]
                                      (fn ([] (swap! counter inc))
                                        ([val] (swap! counter inc))))
            repo/*date-fn* zero-date-fn]
    (f)))

#_(test-env
 (fn [] (let [store (<!! (new-mem-store))
             peer (client-peer "CLIENT" store)
             stage (atom (-> (repo/new-repository "me@mail.com"
                                                  "Testing."
                                                  false
                                                  {:some 43})
                             (s/wire-stage peer)
                             <!!
                             s/sync!
                             <!!))]
         (<!! (s/sync!
               (swap! stage #(-> (s/transact % {:other 44} 'merge)
                                 repo/commit))))
         (facts
          (-> store :state deref)
          =>
          {1 {:some 43},
           2 '(fn replace [old params] params),
           3 {:author "me@mail.com",
              :parents [],
              :transactions [[1 2]],
              :ts #inst "1970-01-01T00:00:00.000-00:00"},
           5 {:other 44},
           6 'merge,
           7 {:author "me@mail.com",
              :parents [3],
              :transactions [[5 6]],
              :ts #inst "1970-01-01T00:00:00.000-00:00"},
           "me@mail.com" {4 {:causal-order {3 [], 7 [3]},
                             :last-update #inst "1970-01-01T00:00:00.000-00:00",
                             :head "master",
                             :public false,
                             :branches {"master" #{7}},
                             :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                             :pull-requests {},
                             :id 4,
                             :description "Testing."}}}
          ;; a simple (but inefficient) way to access the value of the repo is to realize all transactions
          ;; in memory:
          (<!! (s/realize-value (s/transact @stage {:some 42} 'merge) store eval))
          => {:other 44, :some 42})
         (stop peer))))



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
