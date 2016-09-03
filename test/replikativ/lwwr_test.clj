(ns replikativ.lwwr-test
  (:require [clojure.test :refer :all]
            [full.async :refer [<??]]
            [kabel.http-kit :refer [start stop]]
            [konserve
             [filestore :refer [new-fs-store]]
             [memory :refer [new-mem-store]]]
            [replikativ
             [peer :refer [server-peer]]
             [stage :refer [connect! create-stage!]]]
            [replikativ.crdt.lwwr.stage :as ls]))

(deftest lwwr-stage-test
  (testing "lwwr operations"
    (let [user "mail:prototype@your-domain.com"
          lwwr-id #uuid "1d8f1e25-be95-4700-8150-66e4651b8e46"
          store (<?? (new-mem-store))
          peer (<?? (server-peer store "ws://127.0.0.1:9090"))
          stage (<?? (create-stage! user peer))
          _ (<?? (ls/create-lwwr! stage
                                  :id lwwr-id
                                  :description "some lww register"
                                  :public false))]
      (is (= (get-in @stage [user lwwr-id :downstream :crdt]) :lwwr))
      (is (= (get-in @stage [user lwwr-id :state :register]) nil))
      (<?? (ls/set-register! stage [user lwwr-id] {:a 1}))
      (is (= (get-in @stage [user lwwr-id :state :register]) {:a 1}))
      (<?? (ls/set-register! stage [user lwwr-id] {:b "2"}))
      (is (= (get-in @stage [user lwwr-id :state :register]) {:b "2"})))))



(comment
  
  (def user "mail:prototype@your-domain.com")
  
  (def lwwr-id #uuid "1d8f1e25-be95-4700-8150-66e4651b8e46")

  (def store-a (<?? (new-mem-store)))
  (def store-b (<?? (new-mem-store)))
  
  (def peer-a (<?? (server-peer store-a "ws://127.0.0.1:9090")))
  (def peer-b (<?? (server-peer store-b "ws://127.0.0.1:9090")))
  
  (start peer-a)
  
  (def stage-a (<?? (create-stage! user peer-a))) ;; API for peer
  
  (<?? (ls/create-lwwr! stage-a :id lwwr-id :user user :description "some lww register" :public true))

  (<?? (ls/set-register! stage-a [user lwwr-id] {:a 1 :b "2"}))
  
  (<?? (ls/set-register! stage-a [user lwwr-id] {:a 1 :b "2" :c :3}))

  (get-in @stage-a [user lwwr-id :state :register])

  (stop peer-a)

  )
