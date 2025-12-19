(ns replikativ.stage-pubsub-test
  "Tests for the pubsub-based stage API."
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [chan timeout close! <!! >!! go]]
            [superv.async :refer [<?? S go-try]]
            [kabel.peer :refer [start stop]]
            [konserve.core :as k]
            [konserve.memory :refer [new-mem-store]]
            [replikativ.peer :refer [server-peer client-peer]]
            [replikativ.stage :as stage]
            [replikativ.pubsub :as rpubsub]
            [replikativ.crdt :refer [map->CDVCS map->LWWR]]
            [replikativ.crdt.cdvcs.impl]  ;; Load CDVCS protocol implementations
            [replikativ.crdt.lwwr.impl]   ;; Load LWWR protocol implementations
            [replikativ.protocols :refer [-downstream -handshake]]))


;; =============================================================================
;; Test Infrastructure
;; =============================================================================

(defn unique-port []
  (+ 49100 (rand-int 100)))

(defmacro with-pubsub-stage-peers
  "Execute body with server stage and client stage using pubsub sync."
  [& body]
  `(let [port# (unique-port)
         url# (str "ws://localhost:" port#)]
     (let [server-store# (<?? S (new-mem-store))
           server-peer# (<?? S (server-peer S server-store# url#
                                                    :id "SERVER-STAGE-PUBSUB"))]
       (<?? S (start server-peer#))
       (try
         (let [client-store# (<?? S (new-mem-store))
               client-peer# (<?? S (client-peer S client-store#
                                                        :id "CLIENT-STAGE-PUBSUB"))
               ;; Create stages
               server-stage# (<?? S (stage/create-stage! "server-user" server-peer#))
               client-stage# (<?? S (stage/create-stage! "client-user" client-peer#))]
           ;; Connect client to server
           (<?? S (stage/connect! client-stage# url#))
           (<?? S (timeout 300))  ;; Let connection establish
           (try
             (let [~'server-peer server-peer#
                   ~'client-peer client-peer#
                   ~'server-store server-store#
                   ~'client-store client-store#
                   ~'server-stage server-stage#
                   ~'client-stage client-stage#]
               ~@body)
             (finally
               (stop client-peer#))))
         (finally
           (stop server-peer#))))))


;; =============================================================================
;; Stage Creation Tests
;; =============================================================================

(deftest test-create-stage-pubsub
  (testing "Create a pubsub stage"
    (let [store (<?? S (new-mem-store))
          peer (<?? S (client-peer S store :id "TEST-PEER"))
          stage (<?? S (stage/create-stage! "test-user" peer))]
      (is (some? stage) "Stage should be created")
      (is (string? (get-in @stage [:config :id])) "Stage should have ID")
      (is (= "test-user" (get-in @stage [:config :user])) "Stage should have user")
      (is (some? (get-in @stage [:volatile :peer])) "Stage should have peer reference")
      (is (some? (get-in @stage [:volatile :downstream-mult])) "Stage should have downstream mult")
      (stop peer))))


;; =============================================================================
;; LWWR Sync Tests
;; =============================================================================

(deftest test-lwwr-sync-via-stage
  (testing "LWWR sync through pubsub stage"
    (with-pubsub-stage-peers
      (let [user "test-user"
            crdt-id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            lwwr-state (map->LWWR {:register "hello" :timestamp (java.util.Date. 0)})]

        ;; Store LWWR on server
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id]]
                           {:crdt :lwwr :state lwwr-state}))

        ;; Register CRDT on server
        (rpubsub/register-crdt! server-peer S
                                (-> @server-peer :volatile :cold-store)
                                (-> @server-peer :volatile :mem-store)
                                user crdt-id {})

        ;; Subscribe from client stage
        (<?? S (stage/subscribe-crdts! client-stage {user #{crdt-id}}))

        ;; Wait for sync
        (<?? S (timeout 1000))

        ;; Verify client stage received the CRDT
        (let [client-state (get-in @client-stage [user crdt-id :state])]
          (is (some? client-state) "Client stage should have CRDT state")
          (is (= "hello" (:register client-state)) "Register value should match"))))))


(deftest test-lwwr-downstream-via-stage
  (testing "LWWR downstream publication through pubsub stage"
    (with-pubsub-stage-peers
      (let [user "test-user"
            crdt-id #uuid "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"
            lwwr-state (map->LWWR {:register "initial" :timestamp (java.util.Date. 0)})]

        ;; Store and register on server
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id]]
                           {:crdt :lwwr :state lwwr-state}))
        (rpubsub/register-crdt! server-peer S
                                (-> @server-peer :volatile :cold-store)
                                (-> @server-peer :volatile :mem-store)
                                user crdt-id {})

        ;; Subscribe from client
        (<?? S (stage/subscribe-crdts! client-stage {user #{crdt-id}}))
        (<?? S (timeout 800))

        ;; Verify initial sync
        (is (= "initial" (get-in @client-stage [user crdt-id :state :register])))

        ;; Publish downstream update from server
        (let [new-op {:register "updated" :timestamp (java.util.Date.)}
              downstream {:crdt :lwwr :method :update :op new-op}]
          ;; Update server state
          (<?? S (k/update-in (-> @server-peer :volatile :mem-store)
                              [[user crdt-id]]
                              (fn [{:keys [state] :as current}]
                                (assoc current :state (-downstream state new-op)))))
          ;; Publish
          (<?? S (rpubsub/publish-downstream! server-peer user crdt-id downstream)))

        ;; Wait for update
        (<?? S (timeout 500))

        ;; Verify client received update
        (is (= "updated" (get-in @client-stage [user crdt-id :state :register])))))))


;; =============================================================================
;; CDVCS Sync Tests
;; =============================================================================

(deftest test-cdvcs-sync-via-stage
  (testing "CDVCS sync through pubsub stage"
    (with-pubsub-stage-peers
      (let [user "cdvcs-user"
            crdt-id #uuid "cccccccc-dddd-eeee-ffff-000000000000"
            cdvcs-state (map->CDVCS {:commit-graph {1 [] 2 [1]}
                                      :heads #{2}
                                      :version 1})]

        ;; Store on server
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id]]
                           {:crdt :cdvcs
                            :state cdvcs-state
                            :description "Test CDVCS"}))

        ;; Register
        (rpubsub/register-crdt! server-peer S
                                (-> @server-peer :volatile :cold-store)
                                (-> @server-peer :volatile :mem-store)
                                user crdt-id {})

        ;; Subscribe
        (<?? S (stage/subscribe-crdts! client-stage {user #{crdt-id}}))
        (<?? S (timeout 1000))

        ;; Verify
        (let [client-state (get-in @client-stage [user crdt-id :state])]
          (is (some? client-state) "Client should have CDVCS state")
          (is (= {1 [] 2 [1]} (:commit-graph client-state)) "Commit graph should match")
          (is (= #{2} (:heads client-state)) "Heads should match"))))))


;; =============================================================================
;; Stage Sync Tests
;; =============================================================================

(deftest test-stage-sync-upstream
  (testing "Stage sync! pushes upstream changes via pubsub"
    (with-pubsub-stage-peers
      (let [user "sync-user"
            crdt-id #uuid "dddddddd-eeee-ffff-0000-111111111111"
            lwwr-state (map->LWWR {:register "initial" :timestamp (java.util.Date. 0)})]

        ;; Store on server
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id]]
                           {:crdt :lwwr :state lwwr-state}))
        (rpubsub/register-crdt! server-peer S
                                (-> @server-peer :volatile :cold-store)
                                (-> @server-peer :volatile :mem-store)
                                user crdt-id {})

        ;; Also register on server stage for sync!
        (stage/register-crdts! server-stage {user #{crdt-id}})

        ;; Set up stage state for sync
        (let [new-op {:register "from-stage" :timestamp (java.util.Date.)}
              downstream {:crdt :lwwr :method :update :op new-op}]
          ;; Update local stage state
          (swap! server-stage assoc-in [user crdt-id]
                 {:state (-downstream lwwr-state new-op)
                  :new-values {}
                  :downstream downstream})

          ;; Sync upstream
          (<?? S (stage/sync! @server-stage [user crdt-id])))

        ;; Wait
        (<?? S (timeout 500))

        ;; Verify server state updated
        (let [server-state (<?? S (k/get (-> @server-peer :volatile :mem-store) [user crdt-id]))]
          (is (= "from-stage" (-> server-state :state :register))))))))


;; =============================================================================
;; Downstream Channel Tests
;; =============================================================================

(deftest test-downstream-channel
  (testing "Downstream channel receives notifications"
    (with-pubsub-stage-peers
      (let [user "downstream-user"
            crdt-id #uuid "eeeeeeee-ffff-0000-1111-222222222222"
            lwwr-state (map->LWWR {:register "initial" :timestamp (java.util.Date. 0)})
            received (atom [])]

        ;; Store and register
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id]]
                           {:crdt :lwwr :state lwwr-state}))
        (rpubsub/register-crdt! server-peer S
                                (-> @server-peer :volatile :cold-store)
                                (-> @server-peer :volatile :mem-store)
                                user crdt-id {})

        ;; Get downstream channel before subscribing
        (let [downstream-ch (stage/downstream-channel client-stage)]
          ;; Start collecting
          (go
            (loop []
              (when-let [msg (<!! downstream-ch)]
                (swap! received conj msg)
                (recur))))

          ;; Subscribe
          (<?? S (stage/subscribe-crdts! client-stage {user #{crdt-id}}))
          (<?? S (timeout 800))

          ;; Should have received handshake
          (is (>= (count @received) 1) "Should receive at least one downstream notification")
          (is (= user (:user (first @received))) "Notification should have correct user")
          (is (= crdt-id (:crdt-id (first @received))) "Notification should have correct crdt-id")

          ;; Clean up
          (stage/close-downstream-channel! client-stage downstream-ch))))))


;; =============================================================================
;; Multiple CRDT Subscription Tests
;; =============================================================================

(deftest test-multiple-crdt-subscription
  (testing "Subscribe to multiple CRDTs at once"
    (with-pubsub-stage-peers
      (let [user "multi-user"
            crdt-id-1 #uuid "11111111-1111-1111-1111-111111111111"
            crdt-id-2 #uuid "22222222-2222-2222-2222-222222222222"
            lwwr-1 (map->LWWR {:register "value-1" :timestamp (java.util.Date.)})
            lwwr-2 (map->LWWR {:register "value-2" :timestamp (java.util.Date.)})]

        ;; Store both
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id-1]]
                           {:crdt :lwwr :state lwwr-1}))
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id-2]]
                           {:crdt :lwwr :state lwwr-2}))

        ;; Register both
        (rpubsub/register-crdts! server-peer S
                                  (-> @server-peer :volatile :cold-store)
                                  (-> @server-peer :volatile :mem-store)
                                  {user #{crdt-id-1 crdt-id-2}}
                                  {})

        ;; Subscribe to both
        (<?? S (stage/subscribe-crdts! client-stage {user #{crdt-id-1 crdt-id-2}}))
        (<?? S (timeout 1500))

        ;; Verify both synced
        (is (= "value-1" (get-in @client-stage [user crdt-id-1 :state :register])))
        (is (= "value-2" (get-in @client-stage [user crdt-id-2 :state :register])))))))


;; =============================================================================
;; Remove/Unsubscribe Tests
;; =============================================================================

(deftest test-remove-crdts
  (testing "Remove CRDTs stops updates"
    (with-pubsub-stage-peers
      (let [user "remove-user"
            crdt-id #uuid "ffffffff-0000-1111-2222-333333333333"
            lwwr-state (map->LWWR {:register "initial" :timestamp (java.util.Date. 0)})]

        ;; Store and register
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id]]
                           {:crdt :lwwr :state lwwr-state}))
        (rpubsub/register-crdt! server-peer S
                                (-> @server-peer :volatile :cold-store)
                                (-> @server-peer :volatile :mem-store)
                                user crdt-id {})

        ;; Subscribe
        (<?? S (stage/subscribe-crdts! client-stage {user #{crdt-id}}))
        (<?? S (timeout 800))

        ;; Verify initial sync
        (is (= "initial" (get-in @client-stage [user crdt-id :state :register])))

        ;; Remove subscription
        (<?? S (stage/remove-crdts! client-stage {user #{crdt-id}}))
        (<?? S (timeout 500))  ;; Wait for unsubscribe to propagate to server

        ;; Publish update after unsubscribe
        (let [new-op {:register "after-remove" :timestamp (java.util.Date.)}
              downstream {:crdt :lwwr :method :update :op new-op}]
          (<?? S (k/update-in (-> @server-peer :volatile :mem-store)
                              [[user crdt-id]]
                              (fn [{:keys [state] :as current}]
                                (assoc current :state (-downstream state new-op)))))
          (<?? S (rpubsub/publish-downstream! server-peer user crdt-id downstream)))

        (<?? S (timeout 500))

        ;; Client should still have old value (update not received)
        ;; Note: The in-memory state may have been removed by remove-crdts!
        ;; so we check that it's either nil or still "initial"
        (let [client-val (get-in @client-stage [user crdt-id :state :register])]
          (is (or (nil? client-val) (= "initial" client-val))
              "Client should not have received update after unsubscribe"))))))
