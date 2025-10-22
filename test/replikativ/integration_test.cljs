(ns replikativ.integration-test
  "Cross-platform integration test - Node.js client connecting to JVM server.

  Tests that a Node.js ClojureScript client can successfully synchronize
  CRDTs with a JVM Clojure server."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [clojure.core.async :refer [timeout go] :include-macros true]
            [superv.async :refer [S <? go-try] :include-macros true]
            [konserve.memory :refer [new-mem-store]]
            [replikativ.peer :refer [client-peer]]
            [replikativ.stage :refer [create-stage! connect!]]
            [replikativ.crdt.lwwr.stage :as lwwr]
            [replikativ.crdt.lwwr.realize :refer [stream-into-atom!]]
            [kabel.peer :refer [stop]]
            [taoensso.telemere :include-macros true]))

(def url "ws://localhost:47297")
(def user "integration-test-user")
(def lwwr-id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc7777")

(deftest ^:integration lwwr-cross-platform-sync
  (testing "Node.js client syncing LWWR with JVM server"
    (async done
      (println "==> Starting integration test")
      (go-try S
        (try
          (println "==> Creating store...")
          (let [store (<? S (new-mem-store))]
            (println "==> Store created")
            (println "==> Creating peer...")
            (let [peer (<? S (client-peer S store))]
              (println "==> Peer created")
              (println "==> Creating stage...")
              (let [stage (<? S (create-stage! user peer))]
                (println "==> Stage created")
                (println "==> Creating LWWR on client...")
                (<? S (lwwr/create-lwwr! stage :id lwwr-id))
                (println "==> LWWR created on client")
                (let [val-atom (atom nil)]
                  (println "==> Subscribing to LWWR...")
                  (stream-into-atom! stage [user lwwr-id] val-atom)

                  (println "==> Connecting to server at" url)
                  (<? S (connect! stage url))
                  (println "==> Connected, waiting for sync...")

                  ;; Wait for initial sync (increased to allow for slower sync)
                  (<? S (timeout 5000))
                  (println "==> Sync timeout elapsed, checking value...")

                  ;; Verify we received server's initial value
                  (println "==> Val-atom contents:" @val-atom)
                  (is (map? @val-atom) "Should receive a map from server")
                  (is (= (:server @val-atom) "ready") "Should receive server's initial value")
                  (is (number? (:timestamp @val-atom)) "Should have timestamp")

                  (println "✓ Received initial value from server:" @val-atom)

                  ;; Update from client side
                  (println "==> Updating from client...")
                  (<? S (lwwr/set-register! stage [user lwwr-id]
                                            {:client "updated"
                                             :timestamp (.now js/Date)}))

                  ;; Wait for propagation
                  (<? S (timeout 1000))

                  ;; Verify our update is in the stage
                  (is (= (:client @val-atom) "updated") "Should see client update")

                  (println "✓ Client update successful:" @val-atom)

                  (stop peer)
                  (println "==> Test completed successfully")
                  (done)))))
          (catch js/Error e
            (println "==> ERROR:" (.-message e))
            (println "==> Stack:" (.-stack e))
            (done)))))))

(defn ^:export run []
  "Entry point for running integration tests from command line."
  (cljs.test/run-tests))
