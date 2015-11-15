(ns replikativ.stage-test
  (:require [clojure.test :refer :all]
            [clojure.set :as set]
            [replikativ.crdt.cdvcs.realize :refer :all]))


(deftest commit-history-test
  (testing "Test commit history construction."
    (is (= (commit-history {1 []
                            2 [1]
                            3 [2]
                            4 [3]
                            5 [1]
                            6 [5 4]} 6)
           [1 5 2 3 4 6]))
    (is (= (commit-history {5 [3 1]
                            4 [3 1]
                            3 [2]
                            2 [1]
                            1 []} 5)
           [1 2 3 5]))))
