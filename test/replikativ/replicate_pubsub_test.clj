(ns replikativ.replicate-pubsub-test
  "Tests for replikativ replication using the pubsub API.

   This mirrors replicate_test.clj but uses the new pubsub-based sync
   instead of the wire protocol."
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [>!! chan timeout]]
            [superv.async :refer [<?? S]]
            [kabel.peer :refer [start stop connect]]
            [konserve.core :as k]
            [konserve.memory :refer [new-mem-store]]
            [replikativ.peer :refer [server-peer client-peer]]
            [replikativ.pubsub :as rpubsub]
            [replikativ.crdt :refer [map->CDVCS map->LWWR]]
            [replikativ.crdt.cdvcs.impl]  ;; Load CDVCS protocol implementations
            [replikativ.crdt.lwwr.impl]   ;; Load LWWR protocol implementations
            [replikativ.protocols :refer [-downstream -handshake]]))


;; =============================================================================
;; Test Infrastructure
;; =============================================================================

(defn unique-port []
  (+ 48000 (rand-int 100)))

(defmacro with-pubsub-peers
  "Execute body with server and client peers using pubsub sync."
  [& body]
  `(let [port# (unique-port)
         url# (str "ws://localhost:" port#)]
     (let [server-store# (<?? S (new-mem-store))
           server-peer# (<?? S (server-peer S server-store# url#
                                                    :id "SERVER-PUBSUB"))]
       (<?? S (start server-peer#))
       (try
         (let [client-store# (<?? S (new-mem-store))
               client-peer# (<?? S (client-peer S client-store#
                                                        :id "CLIENT-PUBSUB"))]
           ;; Connect client to server
           (<?? S (connect S client-peer# url#))
           (<?? S (timeout 200))  ;; Let connection establish
           (try
             (let [~'server-peer server-peer#
                   ~'client-peer client-peer#
                   ~'server-store server-store#
                   ~'client-store client-store#]
               ~@body)
             (finally
               (stop client-peer#))))
         (finally
           (stop server-peer#))))))


;; =============================================================================
;; Pubsub Replication Tests
;; =============================================================================

(deftest test-cdvcs-replication-pubsub
  (testing "CDVCS replication using pubsub API"
    (with-pubsub-peers
      (let [user "john"
            crdt-id #uuid "12345678-1234-1234-1234-123456789abc"
            ;; Create initial CDVCS state
            initial-cdvcs (map->CDVCS {:commit-graph {1 []
                                                       2 [1]}
                                        :heads #{2}
                                        :version 1})]

        ;; Store CDVCS on server
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id]]
                           {:crdt :cdvcs
                            :state initial-cdvcs
                            :description "Bookmark collection."
                            :public false}))

        ;; Register CRDT on server
        (rpubsub/register-crdt! server-peer S
                                (-> @server-peer :volatile :cold-store)
                                (-> @server-peer :volatile :mem-store)
                                user crdt-id {})

        ;; Subscribe from client
        (<?? S (rpubsub/subscribe-crdt! client-peer S
                                        (-> @client-peer :volatile :cold-store)
                                        (-> @client-peer :volatile :mem-store)
                                        user crdt-id {}))

        ;; Wait for sync
        (<?? S (timeout 1000))

        ;; Verify client received the CDVCS
        (let [client-crdt (<?? S (k/get (-> @client-peer :volatile :mem-store)
                                        [user crdt-id]))]
          (is (some? (:state client-crdt)) "Client should have CDVCS state")
          (is (= :cdvcs (:crdt client-crdt)) "CRDT type should be :cdvcs")
          (is (= {1 [] 2 [1]} (-> client-crdt :state :commit-graph))
              "Commit graph should match")
          (is (= #{2} (-> client-crdt :state :heads))
              "Heads should match"))

        ;; Now publish a downstream update (new commit)
        (let [new-cdvcs (map->CDVCS {:commit-graph {1 []
                                                     2 [1]
                                                     3 [2]}
                                      :heads #{3}
                                      :version 1})
              commit-op {:method :commit
                         :commit-graph {3 [2]}
                         :heads #{3}
                         :version 1}
              downstream {:crdt :cdvcs
                          :method :commit
                          :op commit-op}]

          ;; Update server state
          (<?? S (k/update-in (-> @server-peer :volatile :mem-store)
                              [[user crdt-id]]
                              (fn [{:keys [state] :as current}]
                                (assoc current :state (-downstream state commit-op)))))

          ;; Publish to subscribers
          (<?? S (rpubsub/publish-downstream! server-peer user crdt-id downstream)))

        ;; Wait for sync
        (<?? S (timeout 500))

        ;; Verify client received the update
        (let [client-crdt (<?? S (k/get (-> @client-peer :volatile :mem-store)
                                        [user crdt-id]))]
          (is (= {1 [] 2 [1] 3 [2]} (-> client-crdt :state :commit-graph))
              "Commit graph should include new commit")
          (is (= #{3} (-> client-crdt :state :heads))
              "Heads should be updated"))))))


(deftest test-multi-crdt-subscription-pubsub
  (testing "Multiple CRDT subscription using subscribe-crdts!"
    (with-pubsub-peers
      (let [user "multi-user"
            crdt-id-1 #uuid "11111111-1111-1111-1111-111111111111"
            crdt-id-2 #uuid "22222222-2222-2222-2222-222222222222"
            lwwr-1 (map->LWWR {:register "value-1" :timestamp (java.util.Date.)})
            lwwr-2 (map->LWWR {:register "value-2" :timestamp (java.util.Date.)})]

        ;; Store both CRDTs on server
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id-1]]
                           {:crdt :lwwr :state lwwr-1}))
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id-2]]
                           {:crdt :lwwr :state lwwr-2}))

        ;; Register both using register-crdts!
        (rpubsub/register-crdts! server-peer S
                                 (-> @server-peer :volatile :cold-store)
                                 (-> @server-peer :volatile :mem-store)
                                 {user #{crdt-id-1 crdt-id-2}}
                                 {})

        ;; Subscribe to both using subscribe-crdts!
        (<?? S (rpubsub/subscribe-crdts! client-peer S
                                         (-> @client-peer :volatile :cold-store)
                                         (-> @client-peer :volatile :mem-store)
                                         {user #{crdt-id-1 crdt-id-2}}
                                         {}))

        ;; Wait for sync
        (<?? S (timeout 1500))

        ;; Verify both synced
        (let [client-1 (<?? S (k/get (-> @client-peer :volatile :mem-store)
                                     [user crdt-id-1]))
              client-2 (<?? S (k/get (-> @client-peer :volatile :mem-store)
                                     [user crdt-id-2]))]
          (is (some? (:state client-1)) "First CRDT should sync")
          (is (some? (:state client-2)) "Second CRDT should sync")
          (is (= "value-1" (-> client-1 :state :register)) "First value should match")
          (is (= "value-2" (-> client-2 :state :register)) "Second value should match"))))))


(deftest test-downstream-callback-pubsub
  (testing "on-downstream callback is invoked for updates"
    (with-pubsub-peers
      (let [user "callback-user"
            crdt-id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            received-updates (atom [])
            lwwr-state (map->LWWR {:register "initial" :timestamp (java.util.Date. 0)})]

        ;; Store and register on server
        (<?? S (k/assoc-in (-> @server-peer :volatile :mem-store)
                           [[user crdt-id]]
                           {:crdt :lwwr :state lwwr-state}))
        (rpubsub/register-crdt! server-peer S
                                (-> @server-peer :volatile :cold-store)
                                (-> @server-peer :volatile :mem-store)
                                user crdt-id {})

        ;; Subscribe with callback
        (<?? S (rpubsub/subscribe-crdt! client-peer S
                                        (-> @client-peer :volatile :cold-store)
                                        (-> @client-peer :volatile :mem-store)
                                        user crdt-id
                                        {:on-downstream
                                         (fn [[u cid] downstream]
                                           (swap! received-updates conj
                                                  {:user u :crdt-id cid :downstream downstream}))}))

        ;; Wait for handshake
        (<?? S (timeout 800))

        ;; Should have received handshake callback
        (is (= 1 (count @received-updates)) "Should receive handshake callback")
        (is (= :handshake (-> @received-updates first :downstream :method))
            "First callback should be handshake")

        ;; Publish an update
        (let [new-op {:register "updated" :timestamp (java.util.Date.)}
              downstream {:crdt :lwwr :method :update :op new-op}]
          (<?? S (k/update-in (-> @server-peer :volatile :mem-store)
                              [[user crdt-id]]
                              (fn [{:keys [state] :as current}]
                                (assoc current :state (-downstream state new-op)))))
          (<?? S (rpubsub/publish-downstream! server-peer user crdt-id downstream)))

        ;; Wait for update
        (<?? S (timeout 500))

        ;; Should have received update callback
        (is (= 2 (count @received-updates)) "Should receive update callback")
        (is (= :update (-> @received-updates second :downstream :method))
            "Second callback should be update")))))


(deftest test-unsubscribe-pubsub
  (testing "Unsubscribe stops receiving updates"
    (with-pubsub-peers
      (let [user "unsub-user"
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
        (<?? S (rpubsub/subscribe-crdt! client-peer S
                                        (-> @client-peer :volatile :cold-store)
                                        (-> @client-peer :volatile :mem-store)
                                        user crdt-id {}))
        (<?? S (timeout 800))

        ;; Verify initial sync
        (let [client-state (<?? S (k/get (-> @client-peer :volatile :mem-store)
                                         [user crdt-id]))]
          (is (= "initial" (-> client-state :state :register))))

        ;; Unsubscribe
        (rpubsub/unsubscribe-crdt! client-peer user crdt-id)
        (<?? S (timeout 200))

        ;; Publish an update after unsubscribe
        (let [new-op {:register "after-unsub" :timestamp (java.util.Date.)}
              downstream {:crdt :lwwr :method :update :op new-op}]
          (<?? S (k/update-in (-> @server-peer :volatile :mem-store)
                              [[user crdt-id]]
                              (fn [{:keys [state] :as current}]
                                (assoc current :state (-downstream state new-op)))))
          (<?? S (rpubsub/publish-downstream! server-peer user crdt-id downstream)))

        (<?? S (timeout 500))

        ;; Client should still have old value (didn't receive update)
        (let [client-state (<?? S (k/get (-> @client-peer :volatile :mem-store)
                                         [user crdt-id]))]
          (is (= "initial" (-> client-state :state :register))
              "Client should not receive updates after unsubscribe"))))))
