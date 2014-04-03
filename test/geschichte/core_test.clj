(ns geschichte.core-test
  (:require [clojure.test :refer :all]
            [clojure.set :as set]
            [geschichte.repo :as repo]
            [geschichte.meta :refer :all]
            [geschichte.data :refer :all]
            [konserve.store :as store]
            [clojure.core.incubator :refer [dissoc-in]]))

;; TODO navigation
;; move tip along branch/graph (like undo-tree), tagging?

;; TODO synching
;; mutual peer/repo synching (global), merge-request, auto-pulling locally(, auto-merging?)

;; Look at the bottom for a complete merging example.

; in kv-store
(def dummy-store {"user@mail.com/1234567" {:causal-order {1 #{}
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
    (is (= {:cut #{1},
            :returnpaths-a {1 #{}},
            :returnpaths-b {1 #{}}}
           (lowest-common-ancestors {1 #{}}
                                    #{1}
                                    {1 #{}}
                                    #{1})))
    (is (= {:cut #{2},
            :returnpaths-a {2 #{}},
            :returnpaths-b {2 #{}}}
           (lowest-common-ancestors {1 #{}
                                     2 #{1}}
                                    #{2}
                                    {1 #{}
                                     2 #{1}}
                                    #{2})))
    (is (= {:cut #{1},
            :returnpaths-a {1 #{}},
            :returnpaths-b {2 #{},
                            1 #{2}}}
           (lowest-common-ancestors {1 #{}}
                                    #{1}
                                    {1 #{}
                                     2 #{1}}
                                    #{2})))
    (is (= {:cut #{1},
            :returnpaths-a {1 #{2 3},
                            2 #{4},
                            3 #{4},
                            4 #{}},
            :returnpaths-b {1 #{5},
                            5 #{7},
                            7 #{}}}
           (lowest-common-ancestors {1 #{}
                                     2 #{1}
                                     3 #{1}
                                     4 #{2 3}}
                                    #{4}
                                    {1 #{}
                                     5 #{1}
                                     7 #{5}}
                                    #{7})))
    (is (= {:cut #{2},
            :returnpaths-a {1 #{2 3},
                            2 #{4},
                            3 #{4},
                            4 #{}},
            :returnpaths-b {2 #{5},
                            5 #{7},
                            7 #{}}}
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
    (is (= {:cut #{2 3},
            :returnpaths-a {2 #{4},
                            1 #{3},
                            4 #{},
                            3 #{}},
            :returnpaths-b {2 #{7},
                            3 #{5},
                            7 #{},
                            5 #{}}}
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

(deftest remove-ancestors-test
  (testing "Testing removal of ancestors."
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



;; Data functions and complete merging

(deftest changes-to-base-test
  (testing "Three way merge changeset"
    (is (= (changes-to-base {:a [1 2 3]} {:a [1 2 2]} {:a [1]})
           {:removals-a {:a [nil nil 3]},
            :additions-a {:a [nil nil 2]},
            :removals-b {:a [nil 2 3]},
            :additions-b nil})
        (= (changes-to-base {:a [1 2 3] :b "helo"} {:a [1 2 2] :b "hello"} {:a [1] :b "halo"})
           {:removals-a {:a [nil nil 3], :b "helo"},
            :additions-a {:a [nil nil 2], :b "hello"},
            :removals-b {:a [nil 2 3], :b "helo"},
            :additions-b {:b "halo"}}))))

(deftest conflicts-test
  (testing "Conflict detection."
    (is (= (conflicts {:removals-a {:b "helo"},
                       :additions-a {:b "hello"},
                       :removals-b {:b "helo"},
                       :additions-b {:b "halo"}})
           [{:b :conflict} {:b :conflict} {:b :conflict}]))
    (is (= (conflicts {:removals-a {:a [nil nil 3], :b "helo"},
                       :additions-a {:a [nil nil 2], :b "hello"},
                       :removals-b {:a [nil 2 3], :b "helo"},
                       :additions-b {:b "halo"}})
           [{:a [nil nil :conflict], :b :conflict} {:b :conflict} {:b :conflict}]))
    (is (= (conflicts {:removals-a {:a [nil nil 3], :b "helo"},
                       :additions-a {:a [nil nil 2], :b "hello"},
                       :removals-b {:a [nil 2 nil], :b "helo"},
                       :additions-b {:a [nil 3 nil]}})
           [{:b :conflict} nil nil]))
    (is (= (conflicts {:removals-a {}
                       :additions-a {:a 1}
                       :removals-b {}
                       :additions-b {:b 1}})
           [nil nil nil]))
    (is (= (conflicts? {:removals-a {:a [nil nil 3], :b "helo"},
                        :additions-a {:a [nil nil 2], :b "hello"},
                        :removals-b {:a [nil 2 nil], :b "helo"},
                        :additions-b {:a [nil 3 nil]}})
           true))
    (is (= (conflicts? {:removals-a {}
                        :additions-a {:a 1}
                        :removals-b {}
                        :additions-b {:a 2}})
           true))))

(defn test-patch [old new]
  (let [[r a] (clojure.data/diff old new)]
    (= new (apply-patch r a old))))

(deftest patch-test
  (testing "Testing patch resolution (inverse to diff)."
    (is (= true
           (let [olds [{:a [1 2 3 [1 2 3]]}
                       {:a 1 :b 2}
                       [1 {:a 1} :b]
                       5
                       {1 {[1 "hello"] :a}}
                       [#{1 2 3 4}]
                       '(\h \e \l \l 0)
                       [1 2 3 4]]
                 news [{:a [1 2 [1 2]]}
                       {:a 1}
                       []
                       3
                       {1 "hello"}
                       [#{1 2 3}]
                       '(\h \e \l \o)
                       [1 3]]]
             (->> (map test-patch olds news)
                  (every? true?)))))))

; by Chouser:
(defn deep-merge-with
  "Like merge-with, but merges maps recursively, applying the given fn
   only when there's a non-map at a particular level.

   (deepmerge + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
                {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
   -> {:a {:b {:z 3, :c 3, :d {:z 9, :x 1, :y 2}}, :e 103}, :f 4}"
  [f & maps]
  (apply
    (fn m [& maps]
      (if (every? map? maps)
        (apply merge-with m maps)
        (apply f maps)))
    maps))


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
