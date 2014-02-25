(ns geschichte.platform-test
  (:refer-clojure :exclude [read-string])
  (:require [clojure.test :refer :all]
            [geschichte.protocols :refer :all]
            [geschichte.platform :refer :all]))

(deftest coercion-test
  (testing "Basic coercions"
    (is (= (edn-hash "hello")
           '(-99 104 101 108 108 111)))

    (is (= (edn-hash 123456)
           '(-102 49 50 51 52 53 54)))

    (is (= (edn-hash (double 123.1))
           (edn-hash (float 123.1))))

    (is (= (edn-hash ::test)
           '(-101 103 101 115 99 104 105 99 104 116 101 46 112 108 97 116
                 102 111 114 109 45 116 101 115 116 116 101 115 116)))

    (is (= (edn-hash `+)
           (edn-hash 'clojure.core/+)))

    (is (= (edn-hash '+)
           '(-100 43)))

    (is (= (edn-hash '(1 2 3))
           '(-104 -102 49 -102 50 -102 51)))

    (is (= (edn-hash [1 2 3 4])
           '(-103 -102 49 -102 50 -102 51 -102 52)))

    (is (= (edn-hash {:a "hello"
                     :balloon "world"})
           '(-105 0 0 3 -4 4 9 3 3 1 -99 119 111 114 108 100)))

    (is (= (edn-hash #{1 2 3 4})
           '(-106 0 4)))

    (is (= (edn-hash (sorted-set 1 2 3 4))
           '(-107 -102 49 -102 50 -102 51 -102 52)))

    (is (= (edn-hash (sorted-map :a 1 :c 2 :b 3))
           '(-108 -103 -101 97 -102 49 -103 -101 98 -102 51 -103 -101 99 -102 50)))))


(deftest padded-coercion
  (testing "Padded xor coercion for commutative collections."
    (is (= (padded-coerce [[0xa0 0x01] [0x0c 0xf0 0x5f] [0x0a 0x30 0x07]])
           (padded-coerce [[0xa0 0x01] [0x0a 0x30 0x07] [0x0c 0xf0 0x5f]])))))


(deftest code-hashing
  (testing "Code hashing."
    (is (= (-> '(fn fib [n]
                  (if (or (= n 1) (= n 2)) 1
                      (+ (fib (- n 1)) (fib (- n 2)))))
               edn-hash
               sha-1
               uuid5)
           #uuid "356730af-456b-5f35-a7de-7485ebaddb57"))))


(defprotocol Test
      (-test [this]))


(defrecord TestRec [state]
      Test
      (-test [this] state))


(deftest record-test
  (testing "Record hashing."
    (is (= (->> (TestRec. [1 2 3])
                edn-hash
                sha-1
                uuid5)
           #uuid "08e981cc-f5c2-5ca4-bb0a-7d4a291abae9"))))

#_(run-tests)
