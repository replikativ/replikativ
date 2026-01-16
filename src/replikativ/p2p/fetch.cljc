(ns replikativ.p2p.fetch
  "Fetching middleware for replikativ pubsub. This middleware wraps
   CRDTSyncStrategy to fetch missing commits/transactions/blobs before
   applying downstream operations.

   Architecture:
   ```
   Application -> fetch-pubsub-middleware -> pubsub-middleware -> kabel
   ```

   The fetch-pubsub middleware intercepts pubsub handshake and publish
   messages to pull missing data before the strategy applies them."
  (:require [replikativ.environ :refer [store-blob-trans-id *id-fn*]]
            [replikativ.protocols :refer [-missing-commits -downstream]]
            [replikativ.crdt.materialize :refer [ensure-crdt]]
            [kabel.pubsub.protocol :as proto]
            #?(:clj [kabel.platform-log :refer [debug info warn error]])
            #?(:clj [superv.async :refer [<? <<? go-try go-loop-try go-super go-loop-super put?]])
            #?(:cljs [superv.async :refer [<? <<? go-try go-loop-try go-super go-loop-super put?]
                      :include-macros true])
            [konserve.core :as k]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [<! >! timeout chan alt! go put! pub sub unsub close!]]
               :cljs [clojure.core.async :as async
                      :refer [<! >! timeout chan put! pub sub unsub close!]
                      :include-macros true]))
  #?(:cljs (:require-macros [kabel.platform-log :refer [debug info warn error]])))


;; =============================================================================
;; Fetch Utilities (adapted from replikativ.p2p.fetch)
;; =============================================================================

(defn- not-in-store?! [S store transactions pred]
  (go-loop-try S [not-in-store #{}
                  [tx & rtxs] transactions]
    (if-not tx
      not-in-store
      (if-not (pred (first tx))
        (recur not-in-store rtxs)
        (recur (loop [[id & rids] tx
                      not-in-store not-in-store]
                 (if-not id
                   not-in-store
                   (recur rids (if (<? S (k/exists? store id))
                                 not-in-store
                                 (conj not-in-store id)))))
               rtxs)))))

(defn- new-transactions! [S store transactions]
  (not-in-store?! S store transactions #(not= % store-blob-trans-id)))

(defn- new-blobs! [S store transactions]
  (go-try S
    (->> (not-in-store?! S store transactions #(= % store-blob-trans-id))
         (<? S)
         (filter #(not= % store-blob-trans-id)))))

(defn- fetch-values-from-channel [S fetched-ch]
  "Collect values from fetch response channel until :final is received."
  (go-loop-try S [f (<? S fetched-ch)
                  vs {}]
    (if-not f
      (do
        (debug {:event :fetching-values-interrupted :fetched-values vs})
        (throw (ex-info "Fetching values interrupted." {:fetched-values vs})))
      (let [v (:values f)]
        (if (:final f)
          (merge vs v)
          (recur (<? S fetched-ch) (merge vs v)))))))


;; =============================================================================
;; Fetch Request/Response Handling
;; =============================================================================

(defn- send-fetch-request!
  "Send a fetch request and wait for response."
  [S out fetch-response-ch ids pub-id]
  (go-try S
    (when-not (empty? ids)
      (debug {:event :sending-fetch-request :ids ids :pub-id pub-id})
      (>! out {:type :fetch/edn
               :id pub-id
               :ids (set ids)})
      (<? S (fetch-values-from-channel S fetch-response-ch)))))

(defn- fetch-commit-values!
  "Fetch missing commit values."
  [S out fetch-response-ch cold-store pub-id ncs]
  (go-try S
    (when-not (empty? ncs)
      (info {:event :fetching-commits :count (count ncs) :pub-id pub-id})
      (<? S (send-fetch-request! S out fetch-response-ch ncs pub-id)))))

(defn- fetch-and-store-txs!
  "Fetch and store transaction values."
  [S out fetch-response-ch store txs pub-id hash?]
  (go-loop-try S [ntc (<? S (new-transactions! S store txs))
                  first? true]
    (let [size 1000
          slice (set (take size ntc))
          rest-ids (drop size ntc)]
      (when-not (empty? slice)
        (info {:event :fetching-transactions :count (count slice) :pub-id pub-id})
        (when first?
          (>! out {:type :fetch/edn
                   :id pub-id
                   :ids slice}))
        (when-not (empty? rest-ids)
          (>! out {:type :fetch/edn
                   :id pub-id
                   :ids (set (take size rest-ids))}))
        (loop [f (<? S fetch-response-ch)]
          (if f
            (let [tvs (:values f)]
              (doseq [[id val] tvs]
                (when (and hash? (not= id (*id-fn* val)))
                  (throw (ex-info "Critical hashing error."
                                  {:expected-id id
                                   :hashed-id (*id-fn* val)
                                   :value val})))
                (debug {:event :storing-transaction :id id})
                (<? S (k/assoc-in store [id] val)))
              (when-not (:final f)
                (recur (<? S fetch-response-ch))))
            (throw (ex-info "Fetching transactions disrupted." {:to-fetch slice}))))
        (recur rest-ids false)))))

(defn- store-commits!
  "Store commit values in cold store."
  [S store cvs hash?]
  (go-try S
    (doseq [[id val] cvs]
      (when (and hash? (not= (*id-fn* (select-keys val #{:transactions :parents})) id))
        (throw (ex-info "Critical hashing error."
                        {:expected-id id
                         :hashed-id (*id-fn* (select-keys val #{:transactions :parents}))
                         :value val})))
      (<? S (k/assoc-in store [id] val)))
    true))

(defn- fetch-all-for-downstream!
  "Fetch all missing commits, transactions, and blobs for a downstream operation."
  [S cold-store mem-store out fetch-response-ch user crdt-id downstream pub-id hash?]
  (go-try S
    (let [crdt (<? S (ensure-crdt S cold-store mem-store [user crdt-id]
                                  (:crdt downstream)))
          ncs (<? S (-missing-commits crdt S cold-store out fetch-response-ch
                                      (:op downstream)))]
      (when-not (empty? ncs)
        (info {:event :fetching-for-downstream
               :crdt [user crdt-id]
               :missing-count (count ncs)
               :pub-id pub-id})
        ;; Fetch commit values
        (let [cvs (<? S (fetch-commit-values! S out fetch-response-ch cold-store pub-id ncs))
              txs (when cvs (mapcat :transactions (vals cvs)))]
          ;; Fetch and store transaction values
          (when txs
            (<? S (fetch-and-store-txs! S out fetch-response-ch cold-store txs pub-id hash?)))
          ;; Store commits
          (when cvs
            (<? S (store-commits! S cold-store cvs hash?)))))
      true)))


;; =============================================================================
;; Fetching CRDT Sync Strategy Wrapper
;; =============================================================================

(defrecord FetchingCRDTSyncStrategy
    [S                ; Supervisor
     cold-store       ; Persistent store
     mem-store        ; In-memory store
     user             ; User ID
     crdt-id          ; CRDT ID
     role             ; :server or :client
     on-downstream    ; Optional callback
     out-ch           ; Channel to send fetch requests
     fetch-response-ch ; Channel for fetch responses
     hash?])          ; Whether to verify hashes

(extend-type FetchingCRDTSyncStrategy
  proto/PSyncStrategy

  (-init-client-state [this]
    ;; Delegate to underlying strategy behavior
    (let [{:keys [S cold-store mem-store user crdt-id role]} this
          ch (chan 1)]
      (if (= :client role)
        (go
          (try
            (let [{:keys [state]} (<? S (k/get mem-store [user crdt-id]))]
              (when state
                (put! ch {:user user
                          :crdt-id crdt-id
                          :state (replikativ.protocols/-handshake state S)})))
            (catch #?(:clj Exception :cljs js/Error) e
              (debug {:event :init-client-state-error :error e})))
          (close! ch))
        (close! ch))
      ch))

  (-handshake-items [this client-state]
    ;; Server sends handshake - no fetching needed
    (let [{:keys [S cold-store mem-store user crdt-id role]} this
          ch (chan 1)]
      (if (= :server role)
        (go
          (try
            (let [{:keys [crdt state]} (<? S (k/get mem-store [user crdt-id]))]
              (when state
                (debug {:event :sending-handshake :crdt [user crdt-id]})
                (put! ch {:crdt crdt
                          :method :handshake
                          :op (replikativ.protocols/-handshake state S)})))
            (catch #?(:clj Exception :cljs js/Error) e
              (error {:event :handshake-items-error :error e})))
          (close! ch))
        (close! ch))
      ch))

  (-apply-handshake-item [this item]
    ;; Client applies handshake - fetch missing commits first
    (let [{:keys [S cold-store mem-store user crdt-id on-downstream
                  out-ch fetch-response-ch hash?]} this
          ch (chan 1)]
      (go
        (try
          (debug {:event :apply-handshake-with-fetch :crdt [user crdt-id]})
          ;; Fetch any missing commits before applying
          (let [pub-id (*id-fn*)]
            (<? S (fetch-all-for-downstream! S cold-store mem-store
                                             out-ch fetch-response-ch
                                             user crdt-id item pub-id hash?)))
          ;; Now apply the handshake
          (<? S (k/update-in mem-store [[user crdt-id]]
                             (fn [{:keys [state crdt] :as current}]
                               (let [crdt-type (or crdt (:crdt item))
                                     state (or state (replikativ.crdt.materialize/key->crdt crdt-type))]
                                 (assoc current
                                        :crdt crdt-type
                                        :state (replikativ.protocols/-downstream state (:op item)))))))
          ;; Append to log
          (<? S (k/append cold-store [user crdt-id :log] item))
          (when on-downstream
            (on-downstream [user crdt-id] item))
          (put! ch {:ok true})
          (catch #?(:clj Exception :cljs js/Error) e
            (error {:event :apply-handshake-error :crdt [user crdt-id] :error e})
            (put! ch {:error e})))
        (close! ch))
      ch))

  (-apply-publish [this {:keys [downstream] :as payload}]
    ;; Apply publish - fetch missing commits first
    (let [{:keys [S cold-store mem-store user crdt-id on-downstream
                  out-ch fetch-response-ch hash?]} this
          ch (chan 1)]
      (go
        (try
          (debug {:event :apply-publish-with-fetch :crdt [user crdt-id]})
          ;; Fetch any missing commits before applying
          (let [pub-id (*id-fn*)]
            (<? S (fetch-all-for-downstream! S cold-store mem-store
                                             out-ch fetch-response-ch
                                             user crdt-id downstream pub-id hash?)))
          ;; Now apply the downstream
          (let [[old-state new-state]
                (<? S (k/update-in mem-store [[user crdt-id]]
                                   (fn [{:keys [state crdt] :as current}]
                                     (let [crdt-type (or crdt (:crdt downstream))
                                           state (or state (replikativ.crdt.materialize/key->crdt crdt-type))]
                                       (assoc current
                                              :crdt crdt-type
                                              :state (replikativ.protocols/-downstream state (:op downstream)))))))]
            ;; Append to log
            (<? S (k/append cold-store [user crdt-id :log] downstream))
            (when (and on-downstream (not= old-state new-state))
              (on-downstream [user crdt-id] downstream))
            (put! ch {:ok true :changed? (not= old-state new-state)}))
          (catch #?(:clj Exception :cljs js/Error) e
            (error {:event :apply-publish-error :crdt [user crdt-id] :error e})
            (put! ch {:error e})))
        (close! ch))
      ch)))


;; =============================================================================
;; Strategy Constructor
;; =============================================================================

(defn fetching-crdt-sync-strategy
  "Create a FetchingCRDTSyncStrategy that fetches missing commits before applying.

   Parameters:
   - S: Supervisor
   - cold-store: Persistent konserve store
   - mem-store: In-memory konserve store
   - user: User ID
   - crdt-id: CRDT ID
   - out-ch: Channel to send fetch requests through
   - fetch-response-ch: Channel to receive fetch responses
   - opts: Options map
     - :role - :server or :client
     - :on-downstream - (fn [[user crdt-id] downstream]) callback
     - :hash? - Whether to verify content hashes (default false)"
  [S cold-store mem-store user crdt-id out-ch fetch-response-ch opts]
  (->FetchingCRDTSyncStrategy
   S cold-store mem-store user crdt-id
   (or (:role opts) :client)
   (:on-downstream opts)
   out-ch
   fetch-response-ch
   (get opts :hash? false)))


;; =============================================================================
;; Fetch Response Handler (runs alongside pubsub)
;; =============================================================================

(defn- handle-fetch-requests
  "Handle incoming fetch requests by responding with values from store."
  [S cold-store in out]
  (go-loop-super S [{:keys [type ids id blob-id] :as m} (<? S in)]
    (when m
      (case type
        :fetch/edn
        (do
          (info {:event :responding-to-fetch :count (count ids) :pub-id id})
          (let [values (loop [result {}
                              [fid & rest-ids] (seq ids)]
                         (if-not fid
                           result
                           (recur (assoc result fid (<? S (k/get cold-store fid)))
                                  rest-ids)))]
            (>! out {:type :fetch/edn-ack
                     :values values
                     :id id
                     :final true})))

        :fetch/binary
        (do
          (info {:event :responding-to-binary-fetch :blob-id blob-id :pub-id id})
          (let [value (<? S (k/bget cold-store blob-id identity))]
            (>! out {:type :fetch/binary-ack
                     :value value
                     :blob-id blob-id
                     :id id})))

        ;; Pass through other messages
        (>! out m))
      (recur (<? S in)))))

(defn- route-fetch-responses
  "Route fetch responses to the appropriate response channel."
  [S in fetch-response-chs out]
  (go-loop-super S [{:keys [type] :as m} (<? S in)]
    (when m
      (case type
        (:fetch/edn-ack :fetch/binary-ack)
        (do
          (debug {:event :routing-fetch-response :type type})
          ;; Route to all registered response channels
          (doseq [ch @fetch-response-chs]
            (put! ch m)))

        ;; Pass through other messages
        (>! out m))
      (recur (<? S in)))))


;; =============================================================================
;; Middleware
;; =============================================================================

(defn fetch-pubsub-middleware
  "Create middleware that handles fetch requests/responses alongside pubsub.

   This middleware:
   1. Routes :fetch/edn and :fetch/binary requests to response handler
   2. Routes :fetch/edn-ack and :fetch/binary-ack to registered response channels
   3. Passes other messages through to pubsub

   Returns a middleware function for use with kabel peers."
  ([]
   (fetch-pubsub-middleware {}))
  ([opts]
   (fn [[S peer [in out]]]
     (let [{{:keys [cold-store]} :volatile} @peer
           new-in (chan)
           new-out (chan)
           fetch-in (chan)
           fetch-response-chs (atom #{})]

       ;; Split incoming messages
       (go-loop-super S [{:keys [type] :as m} (<? S in)]
         (when m
           (case type
             (:fetch/edn :fetch/binary)
             (>! fetch-in m)

             (:fetch/edn-ack :fetch/binary-ack)
             (do
               (doseq [ch @fetch-response-chs]
                 (put! ch m))
               ;; Also pass through for other handlers
               (>! new-in m))

             ;; Pass through
             (>! new-in m))
           (recur (<? S in))))

       ;; Handle fetch requests
       (handle-fetch-requests S cold-store fetch-in out)

       ;; Store response channels atom in peer for strategies to register
       (swap! peer assoc-in [:volatile :fetch-response-chs] fetch-response-chs)

       [S peer [new-in out]]))))
