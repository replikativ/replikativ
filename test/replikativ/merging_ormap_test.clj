(ns replikativ.merging-ormap-test
  (:require  [clojure.test :refer :all]
             [replikativ.crdt.merging-ormap.core :refer :all]
             [replikativ.environ :refer [*date-fn*]]
             [superv.async :refer [<?? S]]
             [clojure.core.async :refer [timeout]]
             [kabel.peer :refer [start stop]]
             [konserve
              [filestore :refer [new-fs-store]]
              [memory :refer [new-mem-store]]]
             [replikativ
              [peer :refer [client-peer server-peer]]
              [stage :refer [connect! create-stage!]]]
             [replikativ.crdt.merging-ormap.stage :as mors]
             #_[replikativ.crdt.ormap.realize :as real]))



(deftest basic-merging-ormap-test
  (testing "Basic map and merging functionality."
    (let [omap (new-merging-ormap 'max max)]
      (is (nil? (or-get omap 1)))
      (is (= (-> (or-assoc omap 1 43 "jim")
                 (or-assoc 1 44 "jim")
                 (or-get 1))
             44))
      (is (nil? (-> (or-assoc omap 1 43 "jim")
                    (or-assoc 1 44 "jim")
                    (or-dissoc 1 "jim")
                    (or-get 1)))))))


(deftest merging-ormap-stage-test
  (testing "merging ormap operations"
    (let [user "mail:prototype@your-domain.com"
          mormap-id #uuid "12345678-be95-4700-8150-66e4651b8e46"
          store (<?? S (new-mem-store))
          peer (<?? S (client-peer S store))
          stage (<?? S (create-stage! user peer))
          _ (<?? S (mors/create-merging-ormap! stage 'max max
                                               :id mormap-id
                                               :description "some or merging map"
                                               :public false))]
      (is (= (get-in @stage [user mormap-id :downstream :crdt]) :merging-ormap))
      (<?? S (mors/assoc! stage [user mormap-id] :me 42))
      (<?? S (mors/assoc! stage [user mormap-id] :me 43))
      (is (= (<?? S (mors/get stage [user mormap-id] :me)) 43))
      (<?? S (timeout 1000))
      (is (= (<?? S (mors/get stage [user mormap-id] :me)) 43))
      (<?? S (mors/dissoc! stage [user mormap-id] :me))
      (is (= (<?? S (mors/get stage [user mormap-id] :me)) nil))
      (stop peer))))


(deftest merging-ormap-two-stage-test
  (testing "merging ormap two stage operations"
    (let [user "mail:prototype@your-domain.com"
          mormap-id #uuid "12345678-be95-4700-8150-66e4651b8e46"
          uri "ws://127.0.0.1:37912"

          store-a (<?? S (new-mem-store))
          peer-a (<?? S (server-peer S store-a uri))
          stage-a (<?? S (create-stage! user peer-a))

          store-b (<?? S (new-mem-store))
          peer-b (<?? S (client-peer S store-b))
          stage-b (<?? S (create-stage! user peer-b))

          _ (<?? S (mors/create-merging-ormap! stage-a 'max max
                                               :id mormap-id
                                               :description "some or merging map"
                                               :public false))
          _ (<?? S (mors/create-merging-ormap! stage-b 'max max
                                               :id mormap-id
                                               :description "some or merging map"
                                               :public false))]
      (<?? S (start peer-a))
      (<?? S (timeout 1000))
      (<?? S (connect! stage-b uri))
      (is (= (get-in @stage-a [user mormap-id :downstream :crdt]) :merging-ormap))
      (<?? S (mors/assoc! stage-a [user mormap-id] :me 42))
      (<?? S (mors/assoc! stage-b [user mormap-id] :me 43))
      (<?? S (timeout 1000))
      (is (= (<?? S (mors/get stage-a [user mormap-id] :me)) 43))
      (is (= (<?? S (mors/get stage-b [user mormap-id] :me)) 43))
      (<?? S (mors/dissoc! stage-a [user mormap-id] :me))
      (<?? S (timeout 1000))
      (is (= (<?? S (mors/get stage-a [user mormap-id] :me)) nil))
      (is (= (<?? S (mors/get stage-b [user mormap-id] :me)) nil))
      (stop peer-a)
      (stop peer-b))))
