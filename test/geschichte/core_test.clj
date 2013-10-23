(ns geschichte.core-test
  (:require [clojure.test :refer :all]
            [clojure.set :as set]
            [geschichte.repo :refer :all]
            [geschichte.meta :refer :all]
            [clojure.core.incubator :refer [dissoc-in]]))


; in kv-store
(def repo {"http://cloneit.polyc0l0r.net/geschichte" {:master 1069947109
                                                      -1708856515 #{}
                                                      1069947109 #{-1708856515}}
           -1708856515 {:categories #{"economy" "politics"}
                        :links {"economy" #{"http://forbes.com" "http://handelsblatt.de"}
                                "politics" #{"http://washingtonpost.com"}}}
           1069947109 {:categories #{"economy" "politics" "environment"}
                       :links {"economy" #{"http://forbes.com" "http://handelsblatt.de"}
                               "politics" #{"http://washingtonpost.com"}
                               "environment" #{"http://greenpeace.org"}}}})


(deftest commit-test
  (testing "Commit test against non-head."
    (is (= nil (commit "http://cloneit.polyc0l0r.net/geschichte" (repo "http://cloneit.polyc0l0r.net/geschichte")
                       #{-1708856515}
                       (update-in (repo -1708856515) [:links "environment"] conj "http://opensourceecology.org")))))
  (testing "Commit test against head."
    (let [head-commit (commit "http://cloneit.polyc0l0r.net/geschichte" (repo "http://cloneit.polyc0l0r.net/geschichte")
                              #{1069947109}
                              (update-in (repo 1069947109) [:links "environment"] conj "http://opensourceecology.org"))]
      (is (= (dissoc (second (first (:puts head-commit))) :uuid)
             {     ; :uuid #uuid "397434f8-75fe-44a6-912e-b0014421f63b",
              :categories #{"politics" "environment" "economy"},
              :links {"politics" #{"http://washingtonpost.com"},
                      "environment" #{"http://opensourceecology.org" "http://greenpeace.org"},
                      "economy" #{"http://handelsblatt.de" "http://forbes.com"}}}))
      (is (not= (:master (head-commit  "http://cloneit.polyc0l0r.net/geschichte"))
                1069947109)))))


(deftest lca-test
  (testing "Lowest common ancestor test."
    (is (= {:cut #{1},
            :backways-a {1 #{}},
            :backways-b {1 #{}}}
           (lowest-common-ancestors {:master 1
                                     1 #{}}
                                    {:master 1
                                     1 #{}})))
    (is (= {:cut #{2},
            :backways-a {2 #{}},
            :backways-b {2 #{}}}
           (lowest-common-ancestors {:master 2
                                     1 #{}
                                     2 #{1}}
                                    {:master 2
                                     1 #{}
                                     2 #{1}})))
    (is (= {:cut #{1},
            :backways-a {1 #{}},
            :backways-b {2 #{},
                         1 #{2}}}
           (lowest-common-ancestors {:master 1
                                     1 #{}}
                                    {:master 2
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
           (lowest-common-ancestors {:master 4
                                     1 #{}
                                     2 #{1}
                                     3 #{1}
                                     4 #{2 3}}
                                    {:master 7
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
           (lowest-common-ancestors {:master 4
                                     1 #{}
                                     2 #{1}
                                     3 #{1}
                                     4 #{2 3}}
                                    {:master 7
                                     1 #{}
                                     2 #{1}
                                     5 #{2}
                                     7 #{5}})))
    (is (= {:cut #{1069947108},
            :backways-a {1069947108 #{-1843021530},
                         -1843021530 #{}},
            :backways-b {1069947108 #{-1843021531},
                         -1843021531 #{}}}
           (lowest-common-ancestors {:master -1843021530
                                     -1708856515 #{}
                                     1069947109 #{-1708856515}
                                     1069947108 #{1069947109}
                                     -1843021530 #{1069947108}}
                                    {:master -1843021531
                                     -1708856515 #{}
                                     1069947109 #{-1708856515}
                                     1069947108 #{1069947109}
                                     -1843021531 #{1069947108}})))))


(deftest merge-ancestors-test
  (let [new-a (commit "http://cloneit.polyc0l0r.net/geschichte" (repo "http://cloneit.polyc0l0r.net/geschichte")
                      #{1069947109}
                      (update-in (repo 1069947109) [:links "economy"] conj "http://opensourceecology.org"))
        meta-a ((:puts new-a) "http://cloneit.polyc0l0r.net/geschichte")
        new-b (commit "http://cloneit.polyc0l0r.net/geschichte" (repo "http://cloneit.polyc0l0r.net/geschichte")
                      #{1069947109}
                      (update-in (repo 1069947109) [:links "environment"] conj "http://bund.de"))
        meta-b ((:puts new-b) "http://cloneit.polyc0l0r.net/geschichte")
        lcas (lowest-common-ancestors meta-a meta-b)
        new-meta (merge-ancestors meta-a (:cut lcas) (:backways-b lcas))]
    (testing "Merge test."
      (is (= new-meta
             {-891945387 #{1069947109},
              -1075800112 #{1069947109},
              :master -1075800112,
              -1708856515 #{},
              1069947109 #{-1708856515}})))))

#_(run-tests)
