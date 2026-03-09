(ns dev.simple
  (:require
   [kabel.peer :refer [start]]
   [konserve.memory :refer [new-mem-store]]
   [replikativ.crdt.lwwr.realize :refer [stream-into-atom!]]
   [replikativ.crdt.lwwr.stage :as lwwr]
   [replikativ.peer :refer [client-peer server-peer]]
   [replikativ.stage :refer [connect! create-stage!]]
   [superv.async :refer [<?? S]]))

(comment
  ;; Create storage server
  (def server-store (<?? S (new-mem-store)))

  ;; Create peer with WebSocket endpoint
  (def s-peer (<?? S (server-peer S server-store "ws://localhost:47297")))

  ;; Create stage for this user
  (def server-stage (<?? S (create-stage! "alice@example.com" s-peer)))

  ;; Start accepting connections
  (start s-peer)

  ;; Create a shared CRDT with initial value
  (def server-lwwr-id #uuid "550e8400-e29b-41d4-a716-446655440000")

  (<?? S (lwwr/create-lwwr! server-stage
                            :id server-lwwr-id
                            :description "Shared counter"
                            :init-val {:counter 0}))

  (<?? S (lwwr/set-register! server-stage ["alice@example.com" server-lwwr-id]
                             {:counter 4})))


(comment
  ;; Create storage
  (def client-store (<?? S (new-mem-store)))

   ;; Create client peer (no server endpoint)
  (def c-peer (<?? S (client-peer S client-store)))

   ;; Create stage
  (def client-stage (<?? S (create-stage! "bob@example.com" c-peer)))

   ;; Use the same CRDT ID as server
  (def client-lwwr-id #uuid "550e8400-e29b-41d4-a716-446655440000")
  (def client-user "alice@example.com")

   ;; Create local CRDT (required before streaming)
  (<?? S (lwwr/create-lwwr! client-stage :id client-lwwr-id))

   ;; Set up reactive atom to receive updates
  (def client-val-atom (atom nil))

  ;; Stream CRDT changes into atom
  (stream-into-atom! client-stage [client-user client-lwwr-id] client-val-atom)

  ;; Connect to server - triggers initial sync
  (<?? S (connect! client-stage "ws://localhost:47297"))

  (println "Current value:" @client-val-atom)
  ;; => {:counter 0}

  ;; Modify the CRDT - automatically syncs to server
  (<?? S (lwwr/set-register! client-stage [client-user client-lwwr-id]
                             {:counter (inc (:counter @client-val-atom))}))

  (Thread/sleep 100) ;; Wait for sync
  (println "Updated value:" @client-val-atom))
  ;; => {:counter 1}
