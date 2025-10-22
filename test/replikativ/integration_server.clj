(ns replikativ.integration-server
  "Test server for cross-platform integration tests.

  Starts a JVM server that hosts CRDTs for Node.js clients to sync with."
  (:require [kabel.peer :refer [start]]
            [replikativ.peer :refer [server-peer]]
            [replikativ.stage :refer [create-stage!]]
            [replikativ.crdt.lwwr.stage :as lwwr]
            [superv.async :refer [<?? S]]
            [konserve.memory :refer [new-mem-store]]))

(def url "ws://localhost:47297")
(def user "integration-test-user")
(def lwwr-id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc7777")

(defn -main [& _args]
  (println "==> Starting replikativ integration test server")
  (println "    URL:" url)
  (println "    User:" user)
  (println "    LWWR ID:" lwwr-id)

  (let [store (<?? S (new-mem-store))
        peer (<?? S (server-peer S store url))
        stage (<?? S (create-stage! user peer))]

    ;; Start the peer
    (start peer)

    ;; Create a test LWWR that client will sync
    (<?? S (lwwr/create-lwwr! stage
                              :id lwwr-id
                              :init-val {:server "ready"
                                        :timestamp (System/currentTimeMillis)}))

    (println "==> Server started successfully")
    (println "    LWWR value:" (get-in @stage [user lwwr-id :state :register]))

    ;; Keep server running
    @(promise)))
