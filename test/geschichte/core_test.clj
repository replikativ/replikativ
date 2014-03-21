(ns geschichte.core-test
  (:require [clojure.test :refer :all]
            [clojure.set :as set]
            [geschichte.repo :as repo]
            [geschichte.meta :refer :all]
            [geschichte.data :refer :all]
            [geschichte.store :as store]
            [geschichte.sync :as sync]
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


(deftest merge-ancestors-test
  (let [counter (atom 2)]
    (binding [geschichte.repo/*id-fn* (fn ([] (swap! counter inc))
                                        ([val] (swap! counter inc)))]
      (let [repo-id "user@mail.com/1234567"
            new-a (repo/commit (dummy-store "user@mail.com/1234567")
                          "user@mail.com"
                          {:type "schema"
                           :version 1}
                          "master"
                          2
                          (update-in (dummy-store 2) [:links "economy"] conj "http://opensourceecology.org"))
            meta-a (:meta new-a)
            new-b (repo/commit (dummy-store "user@mail.com/1234567")
                          "user@mail.com"
                          {:type "schema"
                           :version 1}
                          "master"
                          2
                          (update-in (dummy-store 2) [:links "environment"] conj  "http://bund.de"))
            meta-b (:meta new-b)
            lcas (lowest-common-ancestors (:causal-order meta-a) #{3}
                                          (:causal-order meta-b) #{4})]
        (testing "Merge ancestors metadata test."
            (is (= (merge-ancestors (:causal-order meta-a) (:cut lcas) (:returnpaths-b lcas))
                   {4 #{2}, 3 #{2}, 1 #{}, 2 #{1}})))))))

;; Repo functions

(deftest commit-test
  (testing "Commit against non-head."
    (is (= {:error "No parent is in branch heads.",
            :parents #{1}, :branch "master",
            :meta {:causal-order {1 #{}, 2 #{1}},
                   :branches {"master" #{2}}}
            :branch-heads #{2}}
           (repo/commit (dummy-store "user@mail.com/1234567")
                        "user@mail.com"
                        {:type "schema"
                         :version 1}
                        "master"
                        1
                        (update-in (dummy-store 2) [:links "economy"] conj "http://opensourceecology.org")))))
  (testing "Commit against head."
    (let [counter (atom 2)]
      (binding [geschichte.repo/*id-fn* (fn ([] (swap! counter inc))
                                          ([val] (swap! counter inc)))]
        (let [head-commit (repo/commit (dummy-store "user@mail.com/1234567")
                                       "user@mail.com"
                                       {:type "schema"
                                        :version 1}
                                       "master"
                                       2
                                       (update-in (dummy-store 2) [:links "economy"] conj "http://opensourceecology.org"))]
          (is (= (-> head-commit
                     (dissoc-in [:meta :last-update])
                     (dissoc-in [:value :geschichte.meta/meta :ts]))
                 {:meta {:causal-order {3 #{2}, 1 #{}, 2 #{1}},
                         :branches {"master" #{3}}},
                  :value {:geschichte.meta/meta {:id 3, :author "user@mail.com", :branch "master",
                                                 :schema {:version 1, :type "schema"}},
                          :categories #{"politics" "environment" "economy"},
                          :links {"politics" #{"http://washingtonpost.com"},
                                  "environment" #{"http://greenpeace.org"},
                                  "economy" #{"http://handelsblatt.de" "http://opensourceecology.org" "http://forbes.com"}}}})))))))


(deftest pull-test
  (testing "Pull in (fast-forward) remote changes."
    (let [counter (atom 2)]
      (binding [geschichte.repo/*id-fn* (fn ([] (swap! counter inc))
                                          ([val] (swap! counter inc)))]
        (let [commits (-> (dummy-store "user@mail.com/1234567")
                          (repo/commit "user@mail.com" {:type "schema" :version 1} "master" #{2}
                                       (update-in (dummy-store 2) [:links "environment"] conj "http://opensourceecology.org"))
                          :meta
                          (repo/commit "user@mail.com" {:type "schema" :version 1} "master" #{3}
                                        ; dismissing value, since store is not updated:
                                       (update-in (dummy-store 2) [:links "environment"] conj "http://bund.de"))
                          :meta)]
          (is (= (repo/pull (dummy-store "user@mail.com/1234567") "master" commits 4))
              {:meta {:causal-order {4 #{3}, 3 #{2}, 1 #{}, 2 #{1}},
                      :branches {"master" #{4}}},
               :branch-update "master"}))))))


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

(defn dumb-merge
  "Dumb unification assuming set logic (deletion does not work that way).
   inline-meta-data avoided here to have reproducible results
   for testing."
  [meta-source head-val-source
   meta-target head-val-target]
  (let [merged (deep-merge-with set/union
                                (dissoc head-val-source :geschichte.meta/meta)
                                (dissoc head-val-target :geschichte.meta/meta))]
    (repo/merge "user@mail.com"
                {:type "schema"
                 :version 1}
                "master"
                meta-source ((:branches meta-source) "master")
                meta-target ((:branches meta-target) "master")
                merged)))

;; Complete example for a dumb (commutative) merge function. Use
;; application specific merge logic and/or a user controlled 3-way merge
;; in your application.

(deftest merge-test
  (testing "Dumb total merge test."
    (let [counter (atom 2)]
      (binding [geschichte.repo/*id-fn* (fn ([] (swap! counter inc))
                                          ([val] (swap! counter inc)))]
        (let [head-commit-a (repo/commit (dummy-store "user@mail.com/1234567")
                                         "user@mail.com"
                                         {:type "schema"
                                          :version 1}
                                         "master"
                                         2
                                         (update-in (dummy-store 2) [:links "environment"] conj "http://opensourceecology.org"))
              head-commit-b (repo/commit (dummy-store "user@mail.com/1234567")
                                         "user@mail.com"
                                         {:type "schema"
                                          :version 1}
                                         "master"
                                         2
                                         (update-in (dummy-store 2) [:links "environment"] conj "http://bund.de"))]
          (is (= (-> (dumb-merge (:meta head-commit-a) (:value head-commit-a) (:meta head-commit-b) (:value head-commit-b))
                     (dissoc-in [:meta :last-update])
                     (dissoc-in [:value :geschichte.meta/meta :ts]))
                 {:meta {:causal-order {5 #{3 4}, 4 #{2}, 3 #{2}, 1 #{}, 2 #{1}},
                         :branches {"master" #{5}}},
                  :value {:geschichte.meta/meta {:id 5, :author "user@mail.com", :branch "master", :schema {:version 1, :type "schema"}},
                          :categories #{"politics" "environment" "economy"},
                          :links {"politics" #{"http://washingtonpost.com"},
                                  "environment" #{"http://opensourceecology.org" "http://greenpeace.org" "http://bund.de"},
                                  "economy" #{"http://handelsblatt.de" "http://forbes.com"}}}})))))))


(deftest get-globally-test
  (testing "Global resolution test."
    (let [s (mem-store)]
      (store/get-globally {:local s} [:a] #(is (= % 1))))))

(deftest get-with-local-updates
  (testing "Global resolution with local change cache/stage."
    (let [staged {:base :a
                  :b 1}]
      (store/get-with-local-updates staged {:local (mem-store)} [:b] #(is (= % 1))))))



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
