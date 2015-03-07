(ns geschichte.sync-test
  (:require [geschichte.sync :refer [filter-subs]]
            [midje.sweet :refer :all]))

(fact (filter-subs {"john" {42 #{"master"}}}
                   {"john" {42 {:type :state
                                :op {:branches {"master" #{3}}
                                     :causal-order {1 []
                                                    2 [1]
                                                    3 [2]
                                                    4 [3]}
                                     :public false}}
                            43 {:branches {"master" #{4 5}}}}})
      => {"john" {42 {:type :state
                      :op {:public false,
                           :causal-order {1 [],
                                          2 [1],
                                          3 [2]},
                           :branches {"master" #{3}}}}}})


;; TODO multi-repos, check branch causal-order extraction
(fact (filter-subs {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}},
                    "a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}}}
                   {"a@mail.com"
                    {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                     {:type :state
                      :op {:causal-order
                           {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                            #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                            [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                           :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                           :description "some repo.",
                           :schema
                           {:type "http://github.com/ghubber/geschichte", :version 1},
                           :branches
                           {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
                           :public false}}},
                    "b@mail.com"
                    {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                     {:type :state
                      :op {:causal-order
                           {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                            #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                            [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                           :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                           :description "some repo.",
                           :schema
                           {:type "http://github.com/ghubber/geschichte", :version 1},
                           :branches
                           {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
                           :public false}}}})
      => {"a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                        {:type :state
                         :op {:description "some repo.",
                              :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                              :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                             #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                             [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                              :public false,
                              :branches {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
                              :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}},
          "b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                        {:type :state
                         :op {:description "some repo.",
                              :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                              :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                             #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                             [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                              :public false,
                              :branches {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
                              :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}}})
