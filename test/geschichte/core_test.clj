(ns geschichte.core-test
  (:require [clojure.test :refer :all]
            [clojure.set :as set]
            [geschichte.repo :refer :all]
            [geschichte.meta :refer :all]
            [geschichte.data :refer :all]
            [geschichte.store :as store]
            [clojure.core.incubator :refer [dissoc-in]]))

;; Look at the bottom for a complete merging example.

; in kv-store
(def repo {"http://cloneit.polyc0l0r.net/geschichte" {:head 1069947109
                                                      -1708856515 #{}
                                                      1069947109 #{-1708856515}}
           -1708856515 {:categories #{"economy" "politics"}
                        :links {"economy" #{"http://forbes.com" "http://handelsblatt.de"}
                                "politics" #{"http://washingtonpost.com"}}}
           1069947109 {:categories #{"economy" "politics" "environment"}
                       :links {"economy" #{"http://forbes.com" "http://handelsblatt.de"}
                               "politics" #{"http://washingtonpost.com"}
                               "environment" #{"http://greenpeace.org"}}}})

;; Metadata operations

(deftest commit-test
  (testing "Commit against non-head."
    (is (= nil (commit "http://cloneit.polyc0l0r.net/geschichte" (repo "http://cloneit.polyc0l0r.net/geschichte")
                       #{-1708856515}
                       (update-in (repo -1708856515) [:links "environment"] conj "http://opensourceecology.org")))))
  (testing "Commit against head."
    (let [head-commit (commit "http://cloneit.polyc0l0r.net/geschichte" (repo "http://cloneit.polyc0l0r.net/geschichte")
                              #{1069947109}
                              (update-in (repo 1069947109) [:links "environment"] conj "http://opensourceecology.org"))]
      (is (= (dissoc (second (first (:puts head-commit))) :uuid)
             {     ; :uuid #uuid "397434f8-75fe-44a6-912e-b0014421f63b",
              :categories #{"politics" "environment" "economy"},
              :links {"politics" #{"http://washingtonpost.com"},
                      "environment" #{"http://opensourceecology.org" "http://greenpeace.org"},
                      "economy" #{"http://handelsblatt.de" "http://forbes.com"}}}))
      (is (not= (:head (head-commit  "http://cloneit.polyc0l0r.net/geschichte"))
                1069947109)))))


(deftest lca-test
  (testing "Lowest common ancestor"
    (is (= {:cut #{1},
            :backways-a {1 #{}},
            :backways-b {1 #{}}}
           (lowest-common-ancestors {:head 1
                                     1 #{}}
                                    {:head 1
                                     1 #{}})))
    (is (= {:cut #{2},
            :backways-a {2 #{}},
            :backways-b {2 #{}}}
           (lowest-common-ancestors {:head 2
                                     1 #{}
                                     2 #{1}}
                                    {:head 2
                                     1 #{}
                                     2 #{1}})))
    (is (= {:cut #{1},
            :backways-a {1 #{}},
            :backways-b {2 #{},
                         1 #{2}}}
           (lowest-common-ancestors {:head 1
                                     1 #{}}
                                    {:head 2
                                     1 #{}
                                     2 #{1}})))
    (is (= {:cut #{1},
            :backways-a {1 #{2 3},
                         2 #{4},
                         3 #{4},
                         4 #{}},
            :backways-b {1 #{5},
                         5 #{7},
                         7 #{}}}
           (lowest-common-ancestors {:head 4
                                     1 #{}
                                     2 #{1}
                                     3 #{1}
                                     4 #{2 3}}
                                    {:head 7
                                     1 #{}
                                     5 #{1}
                                     7 #{5}})))
    (is (= {:cut #{2},
            :backways-a {1 #{2 3},
                         2 #{4},
                         3 #{4},
                         4 #{}},
            :backways-b {2 #{5},
                         5 #{7},
                         7 #{}}}
           (lowest-common-ancestors {:head 4
                                     1 #{}
                                     2 #{1}
                                     3 #{1}
                                     4 #{2 3}}
                                    {:head 7
                                     1 #{}
                                     2 #{1}
                                     5 #{2}
                                     7 #{5}})))
    (is (= {:cut #{1069947108},
            :backways-a {1069947108 #{-1843021530},
                         -1843021530 #{}},
            :backways-b {1069947108 #{-1843021531},
                         -1843021531 #{}}}
           (lowest-common-ancestors {:head -1843021530
                                     -1708856515 #{}
                                     1069947109 #{-1708856515}
                                     1069947108 #{1069947109}
                                     -1843021530 #{1069947108}}
                                    {:head -1843021531
                                     -1708856515 #{}
                                     1069947109 #{-1708856515}
                                     1069947108 #{1069947109}
                                     -1843021531 #{1069947108}})))))


(deftest merge-ancestors-test
  (let [repo-id "http://cloneit.polyc0l0r.net/geschichte"
        new-a (commit repo-id (repo repo-id)
                      #{1069947109}
                      (update-in (repo 1069947109) [:links "economy"] conj "http://opensourceecology.org"))
        meta-a ((:puts new-a) repo-id)
        new-b (commit repo-id (repo repo-id)
                      #{1069947109}
                      (update-in (repo 1069947109) [:links "environment"] conj "http://bund.de"))
        meta-b ((:puts new-b) repo-id)
        lcas (lowest-common-ancestors meta-a meta-b)]
    (testing "Merge ancestors metadata test."
      (is (= (merge-ancestors meta-a (:cut lcas) (:backways-b lcas))
             {-891945387 #{1069947109},
              -1075800112 #{1069947109},
              :head -1075800112,
              -1708856515 #{},
              1069947109 #{-1708856515}})))))

;; Data functions around merging

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
  [repo-id
   meta-source head-val-source
   meta-target head-val-target]
  (let [merged (deep-merge-with set/union head-val-source head-val-target)]
    (merge-branches repo-id meta-source meta-target merged)))

;; Complete example for a dumb (commutative) merge function. Use
;; application specific merge logic and/or a user controlled 3-way merge
;; in your application.

(deftest merge-test
  (let [repo-id "http://cloneit.polyc0l0r.net/geschichte"
        puts-a (:puts (commit repo-id (repo repo-id)
                              #{1069947109}
                              (update-in (repo 1069947109) [:links "environment"] conj "http://opensourceecology.org")))
        meta-a (puts-a repo-id)
        val-a (puts-a 681621550)
        puts-b (:puts (commit repo-id (repo repo-id)
                              #{1069947109}
                              (update-in (repo 1069947109) [:links "environment"] conj "http://bund.de")))
        meta-b (puts-b repo-id)
        val-b (puts-b -891945387)]
    (testing "Dumb total merge test."
      (is (= (dumb-merge repo-id meta-a val-a meta-b val-b)
             {:puts {569084250 {:categories #{"politics" "environment" "economy"},
                                :links {"politics" #{"http://washingtonpost.com"},
                                        "environment" #{"http://opensourceecology.org" "http://greenpeace.org" "http://bund.de"},
                                        "economy" #{"http://handelsblatt.de" "http://forbes.com"}}},
                     "http://cloneit.polyc0l0r.net/geschichte" {569084250 #{-891945387 681621550},
                                                                -891945387 #{1069947109},
                                                                681621550 #{1069947109},
                                                                :head 569084250,
                                                                -1708856515 #{},
                                                                1069947109 #{-1708856515}}}})))))


;; Store

(defrecord MemoryStore [s]
  store/IKeyValueStore
  (-get [this key cb] (cb {:result (get @s key)}))
  (-del [this key cb] (cb (swap! s dissoc key)))
  (-put [this key val cb] (cb (swap! s assoc key val)))
  (-transact [this {:keys [puts dels gets] :as trans} cb]
    (cb
     (-> (swap! s (fn [old] (-> old
                               (#(when dels (apply dissoc % dels)))
                               (#(when puts (merge % puts))))))
         (#(when gets (reduce (fn [trans k] (assoc-in trans [:gets k] (get % k)))
                              (assoc trans :gets {})
                              gets)))))))


(defn mem-store [] (MemoryStore. (atom {:a 1 :b 2 :c "ehlo"})))

; TODO fix callback deftest macro collision (?)
#_(deftest memory-store-test
  (testing "Memory store implementation.")
  (let [s (mem-store)]
    (store/-get s :a #(is (= % 1)))
    (store/-put s :c "helo" #(is (= % {:a 1, :c "helo," :b 2})))
    (store/-transact s {:dels #{:c}
                        :puts {:c "servus"}
                        :gets #{:a :c}} #(is (= %
                                                {:gets {:c "servus," :a 1},
                                                 :puts {:c "servus"},
                                                 :dels #{:c}})))))

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
