(ns replikativ.pubsub
  "CRDTSyncStrategy for kabel.pubsub - synchronizes CRDTs via pubsub.

   This module provides integration with kabel.pubsub for CRDT replication.
   Each CRDT (identified by [user crdt-id]) becomes a pubsub topic.

   The strategy handles:
   - Initial handshake: Send CRDT state via -handshake protocol
   - Downstream operations: Apply via -downstream protocol
   - Subscription management: Track which CRDTs a peer is subscribed to

   ## Architecture

   ```
   fetch-pubsub-middleware -> pubsub-middleware -> kabel
   ```

   The fetch-pubsub middleware handles lazy-loading of missing commits,
   transactions, and blobs before applying downstream operations."
  (:require [replikativ.protocols :refer [-downstream -handshake]]
            [replikativ.crdt.materialize :refer [key->crdt get-crdt ensure-crdt]]
            [replikativ.p2p.fetch :as fetch]
            [konserve.core :as k]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            #?(:clj [kabel.platform-log :refer [debug info warn error]])
            #?(:clj [superv.async :refer [<? go-try go-loop-try]])
            #?(:cljs [superv.async :refer [<?] :refer-macros [go-try go-loop-try]])
            #?(:clj [clojure.core.async :as async :refer [go chan put! close! <!]]
               :cljs [clojure.core.async :as async :refer [chan put! close!] :refer-macros [go]]))
  #?(:cljs (:require-macros [kabel.platform-log :refer [debug info warn error]])))

;; =============================================================================
;; CRDT Sync Strategy
;; =============================================================================

(defrecord CRDTSyncStrategy
  [S                ; Supervisor
   cold-store       ; Persistent store for CRDTs
   mem-store        ; In-memory store for fast access
   user             ; User ID for this CRDT
   crdt-id          ; CRDT ID
   role             ; :server or :client
   on-downstream])  ; Optional callback (fn [crdt-id downstream])

(defn- get-crdt-state
  "Get the current CRDT state from stores."
  [S cold-store mem-store user crdt-id]
  (go-try S
    (let [{:keys [crdt state]} (<? S (get-crdt S cold-store mem-store [user crdt-id]))]
      {:crdt crdt :state state})))

(defn- apply-downstream!
  "Apply a downstream operation to a CRDT.
   Updates both memory store and cold store."
  [S cold-store mem-store user crdt-id downstream]
  (go-try S
    (let [{:keys [crdt method op]} downstream]
      (debug {:event :apply-downstream :crdt [user crdt-id] :method method})
      ;; Ensure we have the CRDT in memory first
      (<? S (get-crdt S cold-store mem-store [user crdt-id]))
      ;; Update the in-memory state
      (let [[old-state new-state]
            (<? S (k/update-in mem-store [[user crdt-id]]
                               (fn [{:keys [description public state crdt] :as current}]
                                 (let [crdt-type (or crdt (:crdt downstream))
                                       state (or state (key->crdt crdt-type))]
                                   (assoc current
                                          :crdt crdt-type
                                          :state (-downstream state op))))))]
        ;; Append to the log for persistence
        (<? S (k/append cold-store [user crdt-id :log] downstream))
        {:old-state old-state :new-state new-state}))))

(extend-type CRDTSyncStrategy
  proto/PSyncStrategy

  (-init-client-state [this]
    ;; Client sends its current CRDT state version for differential sync
    ;; For CRDTs, we send the current handshake state
    (let [{:keys [S cold-store mem-store user crdt-id role]} this
          ch (chan 1)]
      (if (= :client role)
        (go
          (try
            (let [{:keys [state]} (<? S (get-crdt-state S cold-store mem-store user crdt-id))]
              (when state
                (put! ch {:user user
                          :crdt-id crdt-id
                          :state (-handshake state S)})))
            (catch #?(:clj Exception :cljs js/Error) e
              (debug {:event :init-client-state-error :error e})))
          (close! ch))
        ;; Server doesn't send client state
        (close! ch))
      ch))

  (-handshake-items [this client-state]
    ;; Server sends its CRDT state as a single handshake item
    (let [{:keys [S cold-store mem-store user crdt-id role]} this
          ch (chan 1)]
      (if (= :server role)
        (go
          (try
            (let [{:keys [crdt state]} (<? S (get-crdt-state S cold-store mem-store user crdt-id))]
              (when state
                (debug {:event :sending-handshake :crdt [user crdt-id]})
                (put! ch {:crdt crdt
                          :method :handshake
                          :op (-handshake state S)})))
            (catch #?(:clj Exception :cljs js/Error) e
              (error {:event :handshake-items-error :error e})))
          (close! ch))
        ;; Client doesn't produce handshake items
        (close! ch))
      ch))

  (-apply-handshake-item [this item]
    ;; Client applies the handshake (initial sync) from server
    (let [{:keys [S cold-store mem-store user crdt-id on-downstream]} this
          ch (chan 1)]
      (go
        (try
          (debug {:event :apply-handshake :crdt [user crdt-id]})
          (<? S (apply-downstream! S cold-store mem-store user crdt-id item))
          (when on-downstream
            (on-downstream [user crdt-id] item))
          (put! ch {:ok true})
          (catch #?(:clj Exception :cljs js/Error) e
            (error {:event :apply-handshake-error :crdt [user crdt-id] :error e})
            (put! ch {:error e})))
        (close! ch))
      ch))

  (-apply-publish [this {:keys [downstream] :as payload}]
    ;; Apply an incoming downstream publication
    (let [{:keys [S cold-store mem-store user crdt-id on-downstream]} this
          ch (chan 1)]
      (go
        (try
          (debug {:event :apply-publish :crdt [user crdt-id]})
          (let [{:keys [old-state new-state]}
                (<? S (apply-downstream! S cold-store mem-store user crdt-id downstream))]
            (when (and on-downstream (not= old-state new-state))
              (on-downstream [user crdt-id] downstream))
            (put! ch {:ok true :changed? (not= old-state new-state)}))
          (catch #?(:clj Exception :cljs js/Error) e
            (error {:event :apply-publish-error :crdt [user crdt-id] :error e})
            (put! ch {:error e})))
        (close! ch))
      ch)))

;; =============================================================================
;; Strategy Constructors
;; =============================================================================

(defn crdt-sync-strategy
  "Create a CRDTSyncStrategy.

   Parameters:
   - S: Supervisor
   - cold-store: Persistent konserve store
   - mem-store: In-memory konserve store
   - user: User ID
   - crdt-id: CRDT ID
   - opts: Options map
     - :role - :server or :client
     - :on-downstream - (fn [[user crdt-id] downstream]) callback"
  [S cold-store mem-store user crdt-id opts]
  (->CRDTSyncStrategy S cold-store mem-store user crdt-id
                      (or (:role opts) :client)
                      (:on-downstream opts)))

;; =============================================================================
;; Topic Helpers
;; =============================================================================

(defn crdt-topic
  "Create a topic identifier for a CRDT."
  [user crdt-id]
  [:crdt user crdt-id])

(defn topic->crdt-id
  "Extract [user crdt-id] from a topic."
  [topic]
  (when (and (vector? topic) (= :crdt (first topic)))
    [(second topic) (nth topic 2)]))

;; =============================================================================
;; Server-Side: Register CRDTs
;; =============================================================================

(defn register-crdt!
  "Register a CRDT for sync via pubsub (server-side).

   Parameters:
   - peer: The kabel peer atom
   - S: Supervisor
   - cold-store: Persistent store
   - mem-store: In-memory store
   - user: User ID
   - crdt-id: CRDT ID
   - opts: Options map
     - :on-downstream - (fn [[user crdt-id] downstream]) callback

   Returns the topic."
  [peer S cold-store mem-store user crdt-id opts]
  (let [topic (crdt-topic user crdt-id)
        strategy (crdt-sync-strategy S cold-store mem-store user crdt-id
                                     (assoc opts :role :server))]
    (info {:event :register-crdt :topic topic})
    (pubsub/register-topic! peer topic {:strategy strategy})
    topic))

(defn unregister-crdt!
  "Unregister a CRDT from pubsub."
  [peer user crdt-id]
  (let [topic (crdt-topic user crdt-id)]
    (info {:event :unregister-crdt :topic topic})
    (pubsub/unregister-topic! peer topic)))

;; =============================================================================
;; Client-Side: Subscribe to CRDTs
;; =============================================================================

(defn subscribe-crdt!
  "Subscribe to a CRDT via pubsub (client-side).

   Parameters:
   - peer: The kabel peer atom
   - S: Supervisor
   - cold-store: Persistent store
   - mem-store: In-memory store
   - user: User ID
   - crdt-id: CRDT ID
   - opts: Options map
     - :on-downstream - (fn [[user crdt-id] downstream]) callback

   Returns a channel that yields {:ok true} or {:error ...}."
  [peer S cold-store mem-store user crdt-id opts]
  (let [topic (crdt-topic user crdt-id)
        strategy (crdt-sync-strategy S cold-store mem-store user crdt-id
                                     (assoc opts :role :client))]
    (info {:event :subscribe-crdt :topic topic})
    (pubsub/subscribe! peer #{topic} {:strategies {topic strategy}})))

(defn unsubscribe-crdt!
  "Unsubscribe from a CRDT."
  [peer user crdt-id]
  (let [topic (crdt-topic user crdt-id)]
    (info {:event :unsubscribe-crdt :topic topic})
    (pubsub/unsubscribe! peer #{topic})))

;; =============================================================================
;; Publishing Downstream Operations
;; =============================================================================

(defn publish-downstream!
  "Publish a downstream operation to all subscribers (server-side).

   Parameters:
   - peer: The kabel peer atom
   - user: User ID
   - crdt-id: CRDT ID
   - downstream: The downstream operation {:crdt :method :op}

   Returns a channel that yields {:ok true} or {:error ...}."
  [peer user crdt-id downstream]
  (let [topic (crdt-topic user crdt-id)]
    (debug {:event :publish-downstream :topic topic})
    (pubsub/publish! peer topic {:downstream downstream})))

;; =============================================================================
;; Middleware
;; =============================================================================

;; =============================================================================
;; Multi-CRDT Subscription (like :sub/identities)
;; =============================================================================

(defn subscribe-crdts!
  "Subscribe to multiple CRDTs at once (client-side).

   This is the pubsub equivalent of :sub/identities.

   Parameters:
   - peer: The kabel peer atom
   - S: Supervisor
   - cold-store: Persistent store
   - mem-store: In-memory store
   - identities: Map of {user -> #{crdt-ids}}
   - opts: Options map
     - :on-downstream - (fn [[user crdt-id] downstream]) callback

   Returns a channel that yields {:ok topics} when all subscriptions complete,
   or {:error ...} on failure."
  [peer S cold-store mem-store identities opts]
  (let [topics-and-strategies
        (for [[user crdt-ids] identities
              crdt-id crdt-ids]
          (let [topic (crdt-topic user crdt-id)
                strategy (crdt-sync-strategy S cold-store mem-store user crdt-id
                                             (assoc opts :role :client))]
            [topic strategy]))
        topics (set (map first topics-and-strategies))
        strategies (into {} topics-and-strategies)]
    (info {:event :subscribe-crdts :identities identities :topic-count (count topics)})
    (pubsub/subscribe! peer topics {:strategies strategies})))

(defn register-crdts!
  "Register multiple CRDTs for sync (server-side).

   Parameters:
   - peer: The kabel peer atom
   - S: Supervisor
   - cold-store: Persistent store
   - mem-store: In-memory store
   - identities: Map of {user -> #{crdt-ids}}
   - opts: Options map

   Returns set of registered topics."
  [peer S cold-store mem-store identities opts]
  (let [topics (for [[user crdt-ids] identities
                     crdt-id crdt-ids]
                 (register-crdt! peer S cold-store mem-store user crdt-id opts))]
    (info {:event :register-crdts :identities identities :topic-count (count topics)})
    (set topics)))

;; =============================================================================
;; Middleware
;; =============================================================================

(defn pubsub-middleware
  "Create kabel middleware for CRDT sync via pubsub.

   This wraps kabel.pubsub middleware."
  ([]
   (pubsub-middleware {}))
  ([opts]
   (pubsub/make-pubsub-peer-middleware opts)))

;; =============================================================================
;; Combined Middleware with Fetch
;; =============================================================================

(defn fetch-pubsub-middleware
  "Create combined fetch + pubsub middleware.

   This middleware stack handles:
   1. Fetch requests/responses for lazy-loading commits
   2. Pubsub protocol for CRDT sync

   Use this when you need to sync CRDTs that have external values
   (like CDVCS with commits and transactions).

   For simple CRDTs without external values (like LWWR), use
   pubsub-middleware instead."
  ([]
   (fetch-pubsub-middleware {}))
  ([opts]
   (fn [[S peer [in out]]]
     ;; First apply fetch-pubsub middleware, then pubsub middleware
     (->> [S peer [in out]]
          ((fetch/fetch-pubsub-middleware opts))
          ((pubsub/make-pubsub-peer-middleware opts))))))
