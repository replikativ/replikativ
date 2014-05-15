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
                            43 {:branches {"master" #{4 5}}}}}
                   {"john" {42 {:causal-order {1 []
                                               2 [1]}
                                :branches {"master" #{2}}}}})
      => {"john" {42 {:public false,
                      :causal-order {3 [2]},
                      :branches {"master" #{3}}}}})
