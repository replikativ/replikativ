(ns doc.stress
  (:require [clojure.core.async :as async :refer [close! timeout]]
            [full.async :refer [<??]]
            [kabel.http-kit :refer [start stop]]
            [kabel.middleware.log :refer [logger]]
            [konserve.memory :refer [new-mem-store]]
            [midje.sweet :refer :all]
            [replikativ.crdt.materialize :refer [get-crdt]]
            [replikativ
             [peer :refer [server-peer]]
             [stage :refer [connect! create-stage! subscribe-crdts!]]]
            [replikativ.crdt.cdvcs.stage :as s]
            [replikativ.p2p
             [fetch :refer [fetch]]
             [hash :refer [ensure-hash]]
             [hooks :refer [hook]]]))


(require '[taoensso.timbre :as timbre])
(def prev-level (timbre/*config* :level))
(timbre/set-level! :warn)

(defn setup []
  ;; hooking map
  (def hooks (atom {[#".*"
                     #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                    [["mail:a@mail.com"
                      #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]]}))

  ;; setup two peers with stores and a single commit in mail:a@mail.com and mail:b@mail.com
  (def store-a (<?? (new-mem-store)))

  (def store-b (<?? (new-mem-store)))

  (def store-c (<?? (new-mem-store)))

  (def log-a (atom {}))

  (def peer-a (<?? (server-peer store-a "ws://127.0.0.1:9090"
                                ;; include hooking middleware in peer-a
                                :id "PEER A"
                                :middleware (comp (partial logger log-a :post-fetch)
                                                  fetch
                                                  (partial hook hooks)
                                                  (partial logger log-a :pre-fetch)
                                                  ensure-hash))))

  (def log-b (atom {}))

  (def peer-b (<?? (server-peer store-b "ws://127.0.0.1:9091"
                                :id "PEER B"
                                :middleware (comp fetch (partial logger log-b :log-b)))))


  (def peer-c (<?? (server-peer store-c "ws://127.0.0.1:9092"
                                :id "PEER C"
                                :middleware fetch)))

  (start peer-a)
  (start peer-b)
  (start peer-c)

  (def stage-a (<?? (create-stage! "mail:a@mail.com" peer-a)))

  (<?? (s/create-cdvcs! stage-a
                        :user "mail:a@mail.com"
                        :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))
  (<?? (s/create-cdvcs! stage-a
                        :user "mail:b@mail.com"
                        :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))

  (<?? (connect! stage-a "ws://127.0.0.1:9091" :retries 0))

  #_(<?? (connect! stage-a "ws://127.0.0.1:9092" :retries 0))

  (def stage-b (<?? (create-stage! "mail:b@mail.com" peer-b)))

  (<?? (s/create-cdvcs! stage-b
                        :user "mail:a@mail.com"
                        :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))
  (<?? (s/create-cdvcs! stage-b
                        :user "mail:b@mail.com"
                        :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"))

  (<?? (connect! stage-b "ws://127.0.0.1:9092" :retries 0))

  ;; wait until the subscription has propagated
  (<?? (timeout 5000)))


(setup)

;; always block around transact and exert backpressure
(let [st (.getTime (java.util.Date.))]
  (doseq [i (range 100)]
    (<?? (s/transact! stage-b
                      ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                      [['+ i]])))
  (println "Time taken: " (- (.getTime (java.util.Date.)) st) " ms"))

;; let the network settle
(<?? (timeout 10000))

;; check commits
(facts
 (->
  (<?? (get-crdt store-a (<?? (new-mem-store))
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
  (get-in [:state :commit-graph])
  count)
 => 101
 (->
  (<?? (get-crdt store-a (<?? (new-mem-store))
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
  (get-in [:state :heads])
  count)
 => 1
 (->
  (<?? (get-crdt store-b (<?? (new-mem-store))
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
  (get-in [:state :commit-graph])
  count)
 => 101
 (->
  (<?? (get-crdt store-c (<?? (new-mem-store))
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
  (get-in [:state :commit-graph])
  count)
 => 101

 ;(count @(:state store-a)) => 206
 ;(count @(:state store-b)) => 206
 ;(count @(:state store-c)) => 206
 )

(defn stop-all []
  (stop peer-a)
  (stop peer-b)
  (stop peer-c))

(stop-all)

(setup)

(doseq [i (range 100)]
  (s/transact! stage-b
               ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
               [['+ i]]))

;; let the network settle
(<?? (timeout 10000))

(facts
 (->
  (<?? (get-crdt store-a (<?? (new-mem-store))
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
  (get-in [:state :commit-graph])
  count)
 => 101
 (->
  (<?? (get-crdt store-a (<?? (new-mem-store))
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
  (get-in [:state :heads])
  count)
 => 1
 (->
  (<?? (get-crdt store-b (<?? (new-mem-store))
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
  (get-in [:state :commit-graph])
  count)
 => 101
 (->
  (<?? (get-crdt store-c (<?? (new-mem-store))
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))
  (get-in [:state :commit-graph])
  count)
 => 101

 ;(count @(:state store-a)) => 206
 ;(count @(:state store-b)) => 206
 ;(count @(:state store-c)) => 206
 )

(stop-all)



(timbre/set-level! prev-level)

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
                  (set (keys @(:state store-c))))


  (close! (full.async/-abort full.async/*super*)))

