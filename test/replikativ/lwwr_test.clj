(ns replikativ.lwwr-test
  (:require [clojure.test :refer :all]
            [superv.async :refer [<?? S]]
            [kabel.peer :refer [start stop]]
            [konserve
             [filestore :refer [new-fs-store]]
             [memory :refer [new-mem-store]]]
            [replikativ
             [peer :refer [client-peer]]
             [stage :refer [connect! create-stage!]]]
            [replikativ.crdt.lwwr.stage :as ls]
            [replikativ.crdt.lwwr.realize :refer [stream-into-atom!]]))

(deftest lwwr-stage-test
  (testing "lwwr operations"
    (let [user "mail:prototype@your-domain.com"
          lwwr-id #uuid "1d8f1e25-be95-4700-8150-66e4651b8e46"
          store (<?? S (new-mem-store))
          peer (<?? S (client-peer S store))
          stage (<?? S (create-stage! user peer))
          val-atom (atom nil)
          _ (<?? S (ls/create-lwwr! stage
                                  :id lwwr-id
                                  :description "some lww register"
                                  :public false))
          stream (stream-into-atom! stage [user lwwr-id] val-atom)]
      (is (= (get-in @stage [user lwwr-id :downstream :crdt]) :lwwr))
      (is (= (get-in @stage [user lwwr-id :state :register]) nil))
      (<?? S (ls/set-register! stage [user lwwr-id] {:a 1}))
      (is (= (get-in @stage [user lwwr-id :state :register]) {:a 1}))
      (<?? S (ls/set-register! stage [user lwwr-id] {:b "2"}))
      (is (= (get-in @stage [user lwwr-id :state :register]) {:b "2"}))
      (Thread/sleep 100) ;; wait for convergence
      (is (= @val-atom {:b "2"}))
      (stop peer))))


(comment
  
  (def user "mail:prototype@your-domain.com")
  
  (def lwwr-id #uuid "1d8f1e25-be95-4700-8150-66e4651b8e46")

  (def store-a (<?? (new-mem-store)))
  (def store-b (<?? (new-mem-store)))
  
  (def peer-a (<?? (server-peer store-a "ws://127.0.0.1:9090")))
  (start peer-a)
  
  (def peer-b (<?? (server-peer store-b "ws://127.0.0.1:9091")))
  (start peer-b)
  
  (def stage-a (<?? (create-stage! user peer-a)))
  
  (<?? (ls/create-lwwr! stage-a :id lwwr-id :init-val {:a 1}))
  
  (get-in @stage-a [user lwwr-id :state :register])
  
  (def stage-b (<?? (create-stage! user peer-b)))

  (<?? (ls/create-lwwr! stage-b :id lwwr-id :init-val {:b 2}))

  (get-in @stage-b [user lwwr-id :state :register])

  (<?? (connect! stage-b "ws://127.0.0.1:9090"))

  (get-in @stage-b [user lwwr-id :state :register])
  
  (<?? (ls/set-register! stage-b [user lwwr-id] {:b "2"}))
  
  (get-in @stage-b [user lwwr-id :state :register])
  (get-in @stage-a [user lwwr-id :state :register])
  
  (stop peer-b)
  (stop peer-a)
  
  )
