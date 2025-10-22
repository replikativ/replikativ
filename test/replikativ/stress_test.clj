(ns replikativ.stress-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [close! timeout]]
            [superv.async :refer [<?? S]]
            [kabel.peer :refer [start stop]]
            [kabel.middleware.transit :refer [transit]]
            [konserve.memory :refer [new-mem-store]]
            [replikativ.crdt.materialize :refer [get-crdt]]
            [replikativ
             [peer :refer [server-peer]]
             [stage :refer [connect! create-stage! subscribe-crdts!]]]
            [replikativ.crdt.cdvcs.stage :as s]
            [replikativ.p2p
             [fetch :refer [fetch]]
             [hooks :refer [hook]]]
            [taoensso.timbre :as timbre]))


(defn create-test-system
  "Create a test system with 3 peers and 2 stages. Returns a map with all the state."
  []
  (let [prev-level (timbre/*config* :level)
        _ (timbre/set-level! :warn)

        ;; hooking map
        hooks (atom {[#".*"
                      #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                     [["mail:a@mail.com"
                       #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]]})

        ;; setup three peers with stores
        store-a (<?? S (new-mem-store))
        store-b (<?? S (new-mem-store))
        store-c (<?? S (new-mem-store))

        peer-a (<?? S (server-peer S store-a "ws://127.0.0.1:9090"
                                   ;; include hooking middleware in peer-a
                                   :middleware (comp fetch
                                                     (partial hook hooks))
                                   :id "PEER A"))

        log-b (atom {})

        peer-b (<?? S (server-peer S store-b "ws://127.0.0.1:9091"
                                   :middleware fetch
                                   :id "PEER B"))

        peer-c (<?? S (server-peer S store-c "ws://127.0.0.1:9092"
                                   :middleware fetch
                                   :id "PEER C"))

        _ (start peer-a)
        _ (start peer-b)
        _ (start peer-c)

        stage-a (<?? S (create-stage! "mail:a@mail.com" peer-a))

        _ (<?? S (s/create-cdvcs! stage-a
                                  :user "mail:a@mail.com"
                                  :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))
        _ (<?? S (s/create-cdvcs! stage-a
                                  :user "mail:b@mail.com"
                                  :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))

        _ (<?? S (connect! stage-a "ws://127.0.0.1:9091" :retries 0))
        _ (<?? S (connect! stage-a "ws://127.0.0.1:9092" :retries 0))

        stage-b (<?? S (create-stage! "mail:b@mail.com" peer-b))

        _ (<?? S (s/create-cdvcs! stage-b
                                  :user "mail:a@mail.com"
                                  :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))
        _ (<?? S (s/create-cdvcs! stage-b
                                  :user "mail:b@mail.com"
                                  :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))

        _ (<?? S (connect! stage-b "ws://127.0.0.1:9092" :retries 0))]

    {:hooks hooks
     :store-a store-a
     :store-b store-b
     :store-c store-c
     :peer-a peer-a
     :peer-b peer-b
     :peer-c peer-c
     :log-b log-b
     :stage-a stage-a
     :stage-b stage-b
     :prev-level prev-level}))

(defn teardown-test-system
  "Stop all peers and restore logging level."
  [{:keys [peer-a peer-b peer-c prev-level]}]
  (stop peer-a)
  (stop peer-b)
  (stop peer-c)
  (timbre/set-level! prev-level))


(deftest test-stress-two-peers
  (let [system (create-test-system)
        {:keys [store-a store-b store-c stage-b]} system]
    (try
      (testing "Always block around transact and exert backpressure."
        (let [st (.getTime (java.util.Date.))]
          (doseq [i (range 100)]
            (<?? S (s/transact! stage-b
                                ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                                [['+ i]])))
          (println "Time taken: " (- (.getTime (java.util.Date.)) st) " ms"))
        (<?? S (timeout 10000))
        (is (= (->
                (<?? S (get-crdt S store-a (<?? S (new-mem-store))
                                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
                (get-in [:state :commit-graph])
                count)
               101))
        (is (= (->
                (<?? S (get-crdt S store-a (<?? S (new-mem-store))
                                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
                (get-in [:state :heads])
                count)
               1))
        (is (= (->
                (<?? S (get-crdt S store-b (<?? S (new-mem-store))
                                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
                (get-in [:state :commit-graph])
                count)
               101))
        (is (=
             (->
              (<?? S (get-crdt S store-c (<?? S (new-mem-store))
                               ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
              (get-in [:state :commit-graph])
              count)
            101)))
      (finally
        (teardown-test-system system)))))



(deftest test-stress-thread-safety-stage
  (let [system (create-test-system)
        {:keys [store-a store-b store-c stage-b]} system]
    (try
      (doseq [i (range 100)]
        (s/transact! stage-b
                     ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                     [['+ i]]))
      (<?? S (timeout 10000))
      (is (=
           (->
            (<?? S (get-crdt S store-a (<?? S (new-mem-store))
                             ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
            (get-in [:state :commit-graph])
            count)
           101))
      (is (=
           (->
            (<?? S (get-crdt S store-a (<?? S (new-mem-store))
                             ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
            (get-in [:state :heads])
            count)
           1))
      (is (=
           (->
            (<?? S (get-crdt S store-b (<?? S (new-mem-store))
                             ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
            (get-in [:state :commit-graph])
            count)
           101))
      (is (= (->
              (<?? S (get-crdt S store-c (<?? S (new-mem-store))
                               ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
              (get-in [:state :commit-graph])
              count)
             101))
      (finally
        (teardown-test-system system)))))


(comment
  (->> (get-in @log-a [:pre-fetch :in])
       (filter #(= (:type %) :pub/downstream))
       #_(filter #(not= (:sender %) "STAGE-4f64"))
       (map (fn [{{{:keys [heads]} :op} :downstream
                  id :id :as op}]
              id #_[id op]))
       #_(filter (fn [[id h]] (= id 1))))



  ;; but even if you don't we must have robust behaviour
  (doseq [i (range 100)]
    (s/transact! stage-b
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                 [['+ i]]))

  (<?? (timeout 500))

  (require '[clojure.set :as set])

  (set/difference (set (keys @(:state store-b)))
                  (set (keys @(:state store-c)))))
