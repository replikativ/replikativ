(ns replikativ.replicate-test
  (:require [replikativ.core :refer [filter-subs]]
            [midje.sweet :refer :all]
            [kabel.platform :refer [client-connect!]
               :include-macros true]
            [full.async :refer [<? <?? go-try go-for go-loop-try]]
            [konserve.memory :refer [new-mem-store]]))

(fact (<?? (filter-subs (<?? (new-mem-store))
                        {"john" {42 #{"master"}}}
                        {"john" {42 {:crdt :repo,
                                     :op {:method :new-state,
                                          :branches {"master" #{3}},
                                          :commit-graph {1 []
                                                         2 [1]
                                                         3 [2]
                                                         4 [3]}}
                                     :public false,
                                     :description "foo"}
                                 43 {:branches {"master" #{4 5}}}}}))
      => {"john" {42 {:crdt :repo
                      :op {:method :new-state
                           :commit-graph {1 [],
                                          2 [1],
                                          3 [2]},
                           :branches {"master" #{3}}}
                      :description "foo",
                      :public false}}})


;; TODO multi-repos, check branch commit-graph extraction
(fact (<?? (filter-subs (<?? (new-mem-store))
                        {"mail:b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}},
                         "mail:a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}}}
                        {"mail:a@mail.com"
                         {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                          {:crdt :repo,
                           :op {:method :new-state,
                                :commit-graph
                                {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                 #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                 [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                                :branches
                                {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}}}
                           :description "some repo."
                           :public false}},
                         "mail:b@mail.com"
                         {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                          {:crdt :repo,
                           :op {:method :new-state
                                :commit-graph
                                {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                 #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                 [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                                :branches
                                {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}}}
                           :public false,
                           :description "some repo."}}}))
      => {"mail:a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                        {:crdt :repo,
                         :op {:method :new-state,
                              :commit-graph {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                             #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                             [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                              :branches {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}}}
                         :description "some repo.",
                         :public false}},
          "mail:b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                        {:crdt :repo
                         :op {:method :new-state
                              :commit-graph {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                             #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                             [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                              :branches {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}}}
                         :description "some repo.",
                         :public false}}})
