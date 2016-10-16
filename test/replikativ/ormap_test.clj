(ns replikativ.ormap-test
  (:require [clojure.test :refer :all]
            [replikativ.environ :refer [*date-fn*]]
            [superv.async :refer [<?? S]]
            [kabel.http-kit :refer [start stop]]
            [konserve
             [filestore :refer [new-fs-store]]
             [memory :refer [new-mem-store]]]
            [replikativ
             [peer :refer [client-peer]]
             [stage :refer [connect! create-stage!]]]
            [replikativ.crdt.ormap.stage :as ors]))


(deftest ormap-stage-test
  (testing "ormap operations"
    (let [user "mail:prototype@your-domain.com"
          ormap-id #uuid "12345678-be95-4700-8150-66e4651b8e46"
          store (<?? S (new-mem-store))
          peer (<?? S (client-peer S store))
          stage (<?? S (create-stage! user peer))
          _ (<?? S (ors/create-ormap! stage
                                    :id ormap-id
                                    :description "some or map"
                                    :public false))]
      (is (= (get-in @stage [user ormap-id :downstream :crdt]) :ormap))
      (binding [*date-fn* (constantly 0)]
        (<?? S (ors/assoc! stage [user ormap-id] :me [['set-person {:name "Hal"}]])))
      (is (= (map #(dissoc % :uid) (<?? S (ors/get stage [user ormap-id] :me)))
             [{:transactions [['set-person {:name "Hal"}]],
               :ts 0,
               :author "mail:prototype@your-domain.com",
               :version 1,
               :crdt :ormap}]))
      (<?? S (ors/dissoc! stage [user ormap-id] :me [['remove-person {:name "Hal"}]]))
      (is (= (<?? S (ors/get stage [user ormap-id] :me)) nil))
      (stop peer))))


(comment
(def user-a "mail:a@mail.com")
(def ormap-id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc7499")

(<?? (ors/create-simple-ormap! stage-b :user user-a
                               :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc7499"))

(<?? (ors/or-assoc! stage-a [user-a ormap-id] 12 [['+ 42]]))

(map
 #(<?? (commit-transactions store-a %))
 (<?? (ors/or-get stage-b [user-a ormap-id] 12)))


(require '[replikativ.realize :refer [commit-transactions]])

(<?? (ors/or-dissoc! stage-a [user-a ormap-id] 12 [['- 42]]))

)
