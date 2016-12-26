(ns replikativ.stress-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [close! timeout]]
            [superv.async :refer [<?? S]]
            [kabel.peer :refer [start stop]]
            [kabel.middleware.log :refer [logger]]
            [konserve.memory :refer [new-mem-store]]
            [konserve.filestore :refer [new-fs-store]]
            [replikativ.crdt.materialize :refer [get-crdt]]
            [replikativ
             [peer :refer [server-peer]]
             [stage :refer [connect! create-stage! subscribe-crdts!]]]
            [replikativ.crdt.cdvcs.stage :as s]
            [replikativ.p2p
             [fetch :refer [fetch]]
             [hash :refer [ensure-hash]]
             [hooks :refer [hook]]]))


#_(require '[taoensso.timbre :as timbre])
#_(def prev-level (timbre/*config* :level))
#_(timbre/set-level! :warn)

(defn setup []
  ;; hooking map
  (def hooks (atom {[#".*"
                     #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                    [["mail:a@mail.com"
                      #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]]}))

  ;; setup two peers with stores and a single commit in mail:a@mail.com and mail:b@mail.com
  (def store-a (<?? S (new-mem-store)))

  (def store-b (<?? S (new-mem-store)))

  (def store-c (<?? S (new-mem-store)))

  (def log-a (atom {}))

  (def peer-a (<?? S (server-peer S store-a "ws://127.0.0.1:9090"
                                ;; include hooking middleware in peer-a
                                :id "PEER A"
                                :middleware (comp (partial logger log-a :post-fetch)
                                                  fetch
                                                  (partial hook hooks)
                                                  (partial logger log-a :pre-fetch)
                                                  ensure-hash))))

  (def log-b (atom {}))

  (def peer-b (<?? S (server-peer S store-b "ws://127.0.0.1:9091"
                                :id "PEER B"
                                :middleware (comp fetch (partial logger log-b :log-b)))))


  (def peer-c (<?? S (server-peer S store-c "ws://127.0.0.1:9092"
                                :id "PEER C"
                                :middleware fetch)))

  (start peer-a)
  (start peer-b)
  (start peer-c)

  (def stage-a (<?? S (create-stage! "mail:a@mail.com" peer-a)))

  (<?? S (s/create-cdvcs! stage-a
                        :user "mail:a@mail.com"
                        :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))
  (<?? S (s/create-cdvcs! stage-a
                        :user "mail:b@mail.com"
                        :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))

  (<?? S (connect! stage-a "ws://127.0.0.1:9091" :retries 0))

  (<?? S (connect! stage-a "ws://127.0.0.1:9092" :retries 0))

  (def stage-b (<?? S (create-stage! "mail:b@mail.com" peer-b)))

  (<?? S (s/create-cdvcs! stage-b
                        :user "mail:a@mail.com"
                        :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))
  (<?? S (s/create-cdvcs! stage-b
                        :user "mail:b@mail.com"
                        :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))

  (<?? S (connect! stage-b "ws://127.0.0.1:9092" :retries 0)))

(defn stop-all []
  (stop peer-a)
  (stop peer-b)
  (stop peer-c))


(deftest test-stress-two-peers
  (testing "Always block around transact and exert backpressure."
    (let [st (.getTime (java.util.Date.))]
      (doseq [i (range 100)]
        (<?? S (s/transact! stage-b
                            ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                            [['+ i]])))
      (println "Time taken: " (- (.getTime (java.util.Date.)) st) " ms"))
    (<?? S (timeout 8000))
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
          count))
        101)))



(deftest test-stress-thread-safety-stage
  (doseq [i (range 100)]
    (s/transact! stage-b
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                 [['+ i]]))
  (<?? S (timeout 8000))
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

 ;(count @(:state store-a)) => 206
 ;(count @(:state store-b)) => 206
 ;(count @(:state store-c)) => 206
 )


(defn each-fixture [f]
  (try
    (setup)
    (f)
    (finally
      (stop-all))))

(use-fixtures :each each-fixture)

#_(timbre/set-level! prev-level)

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

