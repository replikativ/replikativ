(ns replikativ.core-test
  (:require [clojure.test :refer :all]
            [clojure.set :as set]
            [replikativ.crdt.repo.repo :as repo]
            [replikativ.crdt.repo.meta :refer :all]
            [konserve.memory :as store]))

;; TODO navigation
;; move tip along branch/graph (like undo-tree), tagging?

;; Look at the bottom for a complete merging example.

; in kv-store
(def dummy-store {"mail:user@mail.com/1234567" {:commit-graph {1 #{}
                                                               2 #{1}}
                                                :branches {"master" #{2}}}
                  1 {:categories #{"economy" "politics"}
                     :links {"economy" #{"http://forbes.com" "http://handelsblatt.de"}
                             "politics" #{"http://washingtonpost.com"}}}
                  2 {:categories #{"economy" "politics" "environment"}
                     :links {"economy" #{"http://forbes.com" "http://handelsblatt.de"}
                             "politics" #{"http://washingtonpost.com"}
                             "environment" #{"http://greenpeace.org"}}}})

;; Metadata operations

(deftest lca-test
  (testing "Lowest common ancestor"
    (reset! lca-cache {})
    (is (= {:lcas #{1}, :visited-a #{1}, :visited-b #{1}}
           (lowest-common-ancestors {1 #{}}
                                    #{1}
                                    {1 #{}}
                                    #{1})))

    (reset! lca-cache {})
    (is (= {:lcas #{2}, :visited-a #{2}, :visited-b #{2}}
           (lowest-common-ancestors {1 #{}
                                     2 #{1}}
                                    #{2}
                                    {1 #{}
                                     2 #{1}}
                                    #{2})))

    (reset! lca-cache {})
    (is (= {:lcas #{1}, :visited-a #{1}, :visited-b #{1 2}}
           (lowest-common-ancestors {1 #{}}
                                    #{1}
                                    {1 #{}
                                     2 #{1}}
                                    #{2})))

    (reset! lca-cache {})
    (is (= {:lcas #{1},
            :visited-a #{1 4 3 2},
            :visited-b #{7 1 5}}
           (lowest-common-ancestors {1 #{}
                                     2 #{1}
                                     3 #{1}
                                     4 #{2 3}}
                                    #{4}
                                    {1 #{}
                                     5 #{1}
                                     7 #{5}}
                                    #{7})))

    (reset! lca-cache {})
    (is (= {:lcas #{2},
            :visited-a #{1 4 3 2},
            :visited-b #{7 2 5}}
           (lowest-common-ancestors {1 #{}
                                     2 #{1}
                                     3 #{1}
                                     4 #{2 3}}
                                    #{4}
                                    {1 #{}
                                     2 #{1}
                                     5 #{2}
                                     7 #{5}}
                                    #{7})))

    (reset! lca-cache {})
    (is (= {:lcas #{3 2},
            :visited-a #{1 4 3 2},
            :visited-b #{7 3 2 5}}
           (lowest-common-ancestors {1 #{}
                                     2 #{1}
                                     3 #{1}
                                     4 #{2}}
                                    #{3 4}
                                    {1 #{}
                                     2 #{1}
                                     3 #{1}
                                     5 #{3}
                                     7 #{2}}
                                    #{5 7})))))

(deftest missing-extra-path-test
  (testing
      (let [problematic-graph {1 []
                               2 [1]
                               3 [1]
                               4 [3]
                               5 [4]
                               6 [5 2]}]
        (reset! lca-cache {})
        (is (= (lowest-common-ancestors {1 [] 2 [1]} #{2} problematic-graph #{6})
               {:lcas #{1 2}, :visited-a #{1 2}, :visited-b #{1 4 6 3 2 5}})))))



(deftest remove-ancestors-test
  (testing "Testing removal of ancestors."
    (reset! lca-cache {})
    (is (= (remove-ancestors {1 #{}
                              2 #{1}
                              3 #{2}
                              4 #{2}
                              5 #{4}
                              6 #{2}} #{6 4} #{3 5})
           #{3 5 6}))))

(deftest isolate-branch-test
  (testing "Testing isolation of branch metadata."
    (is (= (isolate-branch {1 #{}
                            2 #{1}
                            3 #{1}
                            4 #{2}} #{4} {})
           {1 #{}, 2 #{1}, 4 #{2}}))))

(deftest consistent-graph-test
  (testing "Consistency check of graph order.")
  (is (consistent-graph? {1 []
                          2 [1]
                          3 [1]
                          4 [3 2]}))
  (is (not (consistent-graph? {1 []
                               3 [1]
                               4 [3 2]}))))





#_(run-tests)



(comment
  ; TODO implement commit sequence
  (-> {:a 1 :b 2}

      ((fn [old {:keys [one two]}]
         (update-in old [:a] + one two)) {:one 1 :two 2})

      (merge {:x "h" :y :b})

      ((fn [old {:keys [some]}]
         (update-in old [:b] #(reduce + % some))) {:some [1 2] :none []})
      ;; commit-history rewrite on merge -> new history + old branches ?
      )

                                        ; alternative sequence
  (-> {:a 1 :b 2}

      ((fn [old {:keys [one two]}]
         (assoc-in old [:a] one)) {:one -1001}) ; conflict in :a

      (merge {:x "g" :y :b})            ; conflict in :x
      )


                                        ; merge attempt
  (-> {:a 1 :b 2}

      ((fn [old {:keys [one two]}]
         (update-in old [:a] + one two)) {:one 1 :two 2})

      (merge {:x "h" :y :b})

      ((fn [old {:keys [some]}]
         (update-in old [:b] #(reduce + % some))) {:some [1 2] :none []})

      ((fn [old {:keys [one two]}]
         (assoc-in old [:a] one)) {:one -1001}) ; conflict in :a

      (merge {:x "g" :y :b})            ; conflict in :x
      ))
