(ns geschichte.replicate-test
  (:require [geschichte.replicate :refer [filter-subs]]
            [midje.sweet :refer :all]
            [geschichte.platform :refer [client-connect! <? <!? go-loop<? go-loop>? go<?]
               :include-macros true]
            [konserve.store :refer [new-mem-store]]))

(fact (<!? (filter-subs (<!? (new-mem-store))
                        {"john" {42 #{"master"}}}
                        {"john" {42 {:crdt :geschichte.repo,
                                     :op {:method :new-state,
                                          :branches {"master" #{3}},
                                          :causal-order {1 []
                                                         2 [1]
                                                         3 [2]
                                                         4 [3]}}
                                     :public false,
                                     :description "foo"}
                                 43 {:branches {"master" #{4 5}}}}}))
      => {"john" {42 {:crdt :geschichte.repo
                      :op {:method :new-state
                           :causal-order {1 [],
                                          2 [1],
                                          3 [2]},
                           :branches {"master" #{3}}}
                      :description "foo",
                      :public false}}})


;; TODO multi-repos, check branch causal-order extraction
(fact (<!? (filter-subs (<!? (new-mem-store))
                        {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}},
                         "a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}}}
                        {"a@mail.com"
                         {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                          {:crdt :geschichte.repo,
                           :op {:method :new-state,
                                :causal-order
                                {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                 #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                 [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                                :branches
                                {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}}}
                           :description "some repo."
                           :public false}},
                         "b@mail.com"
                         {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                          {:crdt :geschichte.repo,
                           :op {:method :new-state
                                :causal-order
                                {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                 #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                 [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                                :branches
                                {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}}}
                           :public false,
                           :description "some repo."}}}))
      => {"a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                        {:crdt :geschichte.repo,
                         :op {:method :new-state,
                              :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                             #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                             [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                              :branches {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}}}
                         :description "some repo.",
                         :public false}},
          "b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                        {:crdt :geschichte.repo
                         :op {:method :new-state
                              :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                             #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                             [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                              :branches {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}}}
                         :description "some repo.",
                         :public false}}})
