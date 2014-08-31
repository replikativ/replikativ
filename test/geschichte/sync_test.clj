(ns geschichte.sync-test
  (:require [geschichte.sync :refer [filter-subs]]
            [midje.sweet :refer :all]))

(fact (filter-subs {"john" {42 #{"master"}}}
                   {"john" {42 {:branches {"master" #{3}}
                                :causal-order {1 []
                                               2 [1]
                                               3 [2]
                                               4 [3]}
                                :public false}
                            43 {:branches {"master" #{4 5}}}}})
      => {"john" {42 {:public false,
                      :causal-order {1 [],
                                     2 [1],
                                     3 [2]},
                      :branches {"master" #{3}}}}})


;; TODO multi-repos, check branch causal-order extraction
(fact (filter-subs {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}},
                    "a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}}}
                   {"a@mail.com"
                    {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                     {:causal-order

                      {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                       #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                       [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                      :last-update #inst "2014-08-26T21:14:27.179-00:00",
                      :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                      :description "some repo.",
                      :schema
                      {:type "http://github.com/ghubber/geschichte", :version 1},
                      :head "master",
                      :branches
                      {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
                      :public false,
                      :pull-requests {}}},
                    "b@mail.com"
                    {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                     {:causal-order
                      {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                       #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                       [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                      :last-update #inst "2014-08-31T10:56:34.530-00:00",
                      :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                      :description "some repo.",
                      :schema
                      {:type "http://github.com/ghubber/geschichte", :version 1},
                      :head "master",
                      :branches
                      {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
                      :public false,
                      :pull-requests {}}}})
      => {"a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" {:description "some repo.", :schema {:type "http://github.com/ghubber/geschichte", :version 1}, :pull-requests {}, :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [], #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb" [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]}, :public false, :branches {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}}, :head "master", :last-update #inst "2014-08-26T21:14:27.179-00:00", :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}, "b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" {:description "some repo.", :schema {:type "http://github.com/ghubber/geschichte", :version 1}, :pull-requests {}, :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [], #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb" [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]}, :public false, :branches {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}}, :head "master", :last-update #inst "2014-08-31T10:56:34.530-00:00", :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}})
