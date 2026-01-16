(ns replikativ.pubsub-test
  "Tests for replikativ pubsub integration."
  (:require [clojure.test :refer [deftest testing is]]
            [replikativ.pubsub :as rpubsub]
            [replikativ.crdt :refer [map->LWWR map->CDVCS]]
            [replikativ.crdt.lwwr.core :as lwwr]
            [replikativ.crdt.lwwr.impl] ;; Load LWWR protocol implementations
            [replikativ.crdt.cdvcs.impl] ;; Load CDVCS protocol implementations
            [replikativ.protocols :refer [-downstream -handshake]]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            [kabel.peer :as peer]
            [kabel.http-kit :as http-kit]
            [konserve.core :as k]
            [konserve.memory :refer [new-mem-store]]
            [superv.async :refer [<?? S]]
            [clojure.core.async :refer [<!! >! go chan put! close! timeout]]))

;; =============================================================================
;; Test Infrastructure
;; =============================================================================

(def ^:dynamic *server-peer* nil)
(def ^:dynamic *client-peer* nil)
(def ^:dynamic *server-cold-store* nil)
(def ^:dynamic *server-mem-store* nil)
(def ^:dynamic *client-cold-store* nil)
(def ^:dynamic *client-mem-store* nil)

(defn unique-port []
  (+ 47600 (rand-int 100)))

(defmacro with-crdt-peers
  "Execute body with server and client peers set up for CRDT sync."
  [& body]
  `(let [port# (unique-port)
         url# (str "ws://localhost:" port#)
         sid# (java.util.UUID/randomUUID)
         cid# (java.util.UUID/randomUUID)]
     (binding [*server-cold-store* (<?? S (new-mem-store))
               *server-mem-store* (<?? S (new-mem-store))
               *client-cold-store* (<?? S (new-mem-store))
               *client-mem-store* (<?? S (new-mem-store))]
       (let [handler# (http-kit/create-http-kit-handler! S url# sid#)]
         ;; Server peer with pubsub middleware
         (binding [*server-peer* (peer/server-peer S handler# sid#
                                                   (rpubsub/pubsub-middleware)
                                                   identity)]
           ;; Start server
           (<?? S (peer/start *server-peer*))
           (try
             ;; Client peer with pubsub middleware
             (binding [*client-peer* (peer/client-peer S cid#
                                                       (rpubsub/pubsub-middleware)
                                                       identity)]
               ;; Connect client to server
               (<?? S (peer/connect S *client-peer* url#))
               ;; Wait for connection
               (<?? S (timeout 200))
               (try
                 ~@body
                 (finally
                   nil)))
             (finally
               (<?? S (peer/stop *server-peer*)))))))))

;; =============================================================================
;; Unit Tests for CRDTSyncStrategy
;; =============================================================================

(deftest crdt-sync-strategy-protocol-test
  (testing "Server strategy produces handshake items"
    (let [cold-store (<?? S (new-mem-store))
          mem-store (<?? S (new-mem-store))
          user "test-user"
          crdt-id #uuid "12345678-1234-1234-1234-123456789012"
          ;; Create LWWR CRDT with initial value
          lwwr-state (map->LWWR {:register "initial"
                                 :timestamp (java.util.Date.)})]

      ;; Store the CRDT state
      (<?? S (k/assoc-in mem-store [[user crdt-id]]
                         {:crdt :lwwr
                          :state lwwr-state}))

      (let [strategy (rpubsub/crdt-sync-strategy S cold-store mem-store user crdt-id
                                                  {:role :server})
            items-ch (proto/-handshake-items strategy nil)
            items (atom [])]
        ;; Collect items
        (loop []
          (when-let [item (<!! items-ch)]
            (swap! items conj item)
            (recur)))

        ;; Should have one handshake item
        (is (= 1 (count @items)))
        (is (= :lwwr (:crdt (first @items))))
        (is (= :handshake (:method (first @items))))))))

(deftest crdt-apply-downstream-test
  (testing "Client strategy applies downstream operations"
    (let [cold-store (<?? S (new-mem-store))
          mem-store (<?? S (new-mem-store))
          user "test-user"
          crdt-id #uuid "12345678-1234-1234-1234-123456789012"
          strategy (rpubsub/crdt-sync-strategy S cold-store mem-store user crdt-id
                                                {:role :client})
          ;; Create a handshake item
          lwwr-state (map->LWWR {:register "from-server"
                                 :timestamp (java.util.Date.)})
          handshake-item {:crdt :lwwr
                          :method :handshake
                          :op (-handshake lwwr-state S)}]

      ;; Apply handshake
      (let [result (<!! (proto/-apply-handshake-item strategy handshake-item))]
        (is (:ok result)))

      ;; Verify state was stored
      (let [stored (<?? S (k/get mem-store [user crdt-id]))]
        (is (some? (:state stored)))
        (is (= :lwwr (:crdt stored)))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest basic-crdt-sync-test
  (testing "Basic CRDT sync via pubsub"
    (with-crdt-peers
      (let [user "test-user"
            crdt-id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            ;; Create LWWR on server
            lwwr-state (map->LWWR {:register "server-value"
                                   :timestamp (java.util.Date.)})]

        ;; Store CRDT state on server
        (<?? S (k/assoc-in *server-mem-store* [[user crdt-id]]
                           {:crdt :lwwr
                            :state lwwr-state}))

        ;; Register CRDT on server
        (rpubsub/register-crdt! *server-peer* S *server-cold-store* *server-mem-store*
                                user crdt-id {})

        ;; Subscribe from client
        (<?? S (rpubsub/subscribe-crdt! *client-peer* S *client-cold-store* *client-mem-store*
                                        user crdt-id {}))

        ;; Wait for sync
        (<?? S (timeout 1000))

        ;; Verify client received the CRDT
        (let [client-state (<?? S (k/get *client-mem-store* [user crdt-id]))]
          (is (some? client-state))
          (is (= :lwwr (:crdt client-state))))))))

(deftest publish-downstream-test
  (testing "Downstream publications are synced"
    (with-crdt-peers
      (let [user "test-user"
            crdt-id #uuid "11111111-2222-3333-4444-555555555555"
            lwwr-state (map->LWWR {:register "initial"
                                   :timestamp (java.util.Date. 0)})]

        ;; Store and register on server
        (<?? S (k/assoc-in *server-mem-store* [[user crdt-id]]
                           {:crdt :lwwr
                            :state lwwr-state}))
        (rpubsub/register-crdt! *server-peer* S *server-cold-store* *server-mem-store*
                                user crdt-id {})

        ;; Subscribe from client
        (<?? S (rpubsub/subscribe-crdt! *client-peer* S *client-cold-store* *client-mem-store*
                                        user crdt-id {}))
        (<?? S (timeout 800))

        ;; Verify initial sync
        (let [client-state (<?? S (k/get *client-mem-store* [user crdt-id]))]
          (is (some? client-state)))

        ;; Publish a downstream update from server
        (let [new-timestamp (java.util.Date.)
              new-op {:register "updated" :timestamp new-timestamp}
              downstream {:crdt :lwwr
                          :method :update
                          :op new-op}]
          ;; Apply to server first
          (<?? S (k/update-in *server-mem-store* [[user crdt-id]]
                              (fn [{:keys [state] :as current}]
                                (assoc current :state (-downstream state new-op)))))
          ;; Publish to subscribers
          (<?? S (rpubsub/publish-downstream! *server-peer* user crdt-id downstream)))

        ;; Wait for sync
        (<?? S (timeout 500))

        ;; Verify client received the update
        (let [client-crdt (<?? S (k/get *client-mem-store* [user crdt-id]))
              client-state (:state client-crdt)]
          (is (some? client-state))
          ;; The LWWR value should be updated
          (is (= "updated" (:register client-state))))))))

(deftest multiple-crdts-test
  (testing "Multiple CRDTs can be synced"
    (with-crdt-peers
      (let [user "multi-user"
            crdt-id-1 #uuid "11111111-1111-1111-1111-111111111111"
            crdt-id-2 #uuid "22222222-2222-2222-2222-222222222222"
            lwwr-1 (map->LWWR {:register "crdt-1" :timestamp (java.util.Date.)})
            lwwr-2 (map->LWWR {:register "crdt-2" :timestamp (java.util.Date.)})]

        ;; Store both on server
        (<?? S (k/assoc-in *server-mem-store* [[user crdt-id-1]]
                           {:crdt :lwwr :state lwwr-1}))
        (<?? S (k/assoc-in *server-mem-store* [[user crdt-id-2]]
                           {:crdt :lwwr :state lwwr-2}))

        ;; Register both
        (rpubsub/register-crdt! *server-peer* S *server-cold-store* *server-mem-store*
                                user crdt-id-1 {})
        (rpubsub/register-crdt! *server-peer* S *server-cold-store* *server-mem-store*
                                user crdt-id-2 {})

        ;; Subscribe to both
        (<?? S (rpubsub/subscribe-crdt! *client-peer* S *client-cold-store* *client-mem-store*
                                        user crdt-id-1 {}))
        (<?? S (rpubsub/subscribe-crdt! *client-peer* S *client-cold-store* *client-mem-store*
                                        user crdt-id-2 {}))
        (<?? S (timeout 1200))

        ;; Verify both synced
        (let [client-1 (<?? S (k/get *client-mem-store* [user crdt-id-1]))
              client-2 (<?? S (k/get *client-mem-store* [user crdt-id-2]))]
          (is (some? (:state client-1)))
          (is (some? (:state client-2)))
          (is (= :lwwr (:crdt client-1)))
          (is (= :lwwr (:crdt client-2))))))))

;; =============================================================================
;; CDVCS Integration Tests (Commit Graph Syncing)
;; =============================================================================

(deftest cdvcs-handshake-sync-test
  (testing "CDVCS commit graph syncs via pubsub handshake"
    (with-crdt-peers
      (let [user "cdvcs-user"
            crdt-id #uuid "cccccccc-dddd-eeee-ffff-000000000000"
            ;; Create a CDVCS with a commit graph
            ;; Simulates: initial commit -> second commit
            cdvcs-state (map->CDVCS {:commit-graph {1 []    ;; root commit
                                                    2 [1]}  ;; second commit
                                     :heads #{2}
                                     :version 1})]

        ;; Store CDVCS state on server
        (<?? S (k/assoc-in *server-mem-store* [[user crdt-id]]
                           {:crdt :cdvcs
                            :state cdvcs-state}))

        ;; Register CDVCS on server
        (rpubsub/register-crdt! *server-peer* S *server-cold-store* *server-mem-store*
                                user crdt-id {})

        ;; Subscribe from client
        (<?? S (rpubsub/subscribe-crdt! *client-peer* S *client-cold-store* *client-mem-store*
                                        user crdt-id {}))

        ;; Wait for sync
        (<?? S (timeout 1000))

        ;; Verify client received the CDVCS with commit graph
        (let [client-crdt (<?? S (k/get *client-mem-store* [user crdt-id]))
              client-state (:state client-crdt)]
          (is (some? client-state) "Client should have CDVCS state")
          (is (= :cdvcs (:crdt client-crdt)) "CRDT type should be :cdvcs")
          (is (= {1 [] 2 [1]} (:commit-graph client-state)) "Commit graph should match")
          (is (= #{2} (:heads client-state)) "Heads should match"))))))

(deftest cdvcs-downstream-commit-test
  (testing "CDVCS downstream commit operations sync via pubsub"
    (with-crdt-peers
      (let [user "cdvcs-user"
            crdt-id #uuid "dddddddd-eeee-ffff-0000-111111111111"
            ;; Initial CDVCS with one commit
            initial-state (map->CDVCS {:commit-graph {1 []}
                                       :heads #{1}
                                       :version 1})]

        ;; Store initial state on server
        (<?? S (k/assoc-in *server-mem-store* [[user crdt-id]]
                           {:crdt :cdvcs
                            :state initial-state}))

        ;; Register CDVCS on server
        (rpubsub/register-crdt! *server-peer* S *server-cold-store* *server-mem-store*
                                user crdt-id {})

        ;; Subscribe from client
        (<?? S (rpubsub/subscribe-crdt! *client-peer* S *client-cold-store* *client-mem-store*
                                        user crdt-id {}))
        (<?? S (timeout 800))

        ;; Verify initial sync
        (let [client-crdt (<?? S (k/get *client-mem-store* [user crdt-id]))]
          (is (= #{1} (-> client-crdt :state :heads)) "Initial heads should be #{1}"))

        ;; Simulate a new commit on server: add commit 2 -> [1]
        (let [commit-op {:method :commit
                         :commit-graph {2 [1]}
                         :heads #{2}
                         :version 1}
              downstream {:crdt :cdvcs
                          :method :commit
                          :op commit-op}]
          ;; Apply to server first
          (<?? S (k/update-in *server-mem-store* [[user crdt-id]]
                              (fn [{:keys [state] :as current}]
                                (assoc current :state (-downstream state commit-op)))))
          ;; Publish to subscribers
          (<?? S (rpubsub/publish-downstream! *server-peer* user crdt-id downstream)))

        ;; Wait for sync
        (<?? S (timeout 500))

        ;; Verify client received the commit
        (let [client-crdt (<?? S (k/get *client-mem-store* [user crdt-id]))
              client-state (:state client-crdt)]
          (is (= {1 [] 2 [1]} (:commit-graph client-state)) "Commit graph should include new commit")
          (is (= #{2} (:heads client-state)) "Heads should be updated to #{2}"))))))
