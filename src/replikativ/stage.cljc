(ns replikativ.stage
  "A stage allows to execute upstream operations of each CRDT and
   communicates them downstream to a peer through synchronous (blocking)
   operations.

   This implementation uses kabel.pubsub for CRDT synchronization."
  (:require [konserve.core :as k]
            [konserve.memory :refer [new-mem-store]]
            [kabel.peer :as kpeer]
            [kabel.pubsub :as kpubsub]
            [replikativ.protocols :refer [-downstream]]
            [replikativ.environ :refer [*id-fn* store-blob-trans-id store-blob-trans-value]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.pubsub :as rpubsub]
            #?(:clj [kabel.platform-log :refer [debug info warn error]])
            #?(:clj [superv.async :refer [<? <<? go-try go-loop-try alt? put?
                                          go-for go-loop-super >?]])
            #?(:cljs [superv.async :refer [<? <<? go-try go-loop-try alt? put?
                                           go-for go-loop-super >?] :include-macros true])
            [hasch.core :refer [uuid]]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [<! >! timeout chan put! sub unsub pub close! alt! onto-chan mult tap untap]]
               :cljs [clojure.core.async :as async
                      :refer [<! >! timeout chan put! sub unsub pub close! onto-chan mult tap untap] :include-macros true]))
  #?(:cljs (:require-macros [replikativ.stage :refer [go-try-locked]]
                            [kabel.platform-log :refer [debug info warn error]])))


;; =============================================================================
;; Locking Macro
;; =============================================================================

#?(:clj
   (defmacro go-try-locked [stage & code]
     {:style/indent 1}
     `(let [{{S# :supervisor} :volatile} (deref ~stage)]
        (go-try S#
         (let [{{sync-token# :sync-token} :volatile} (deref ~stage)]
           (<? S# sync-token#)
           (try
             ~@code
             (finally
               (put? S# sync-token# :stage))))))))


;; =============================================================================
;; Stage State Structure
;; =============================================================================

;; Stage atom structure:
;; {:config {:id <stage-id>
;;           :user <user>
;;           :subs {<user> #{<crdt-id> ...}}}
;;  :volatile {:peer <peer-atom>
;;             :supervisor <S>
;;             :store <cold-store>
;;             :sync-token <chan>
;;             :downstream-mult <mult>      ;; Multiplexed downstream notifications
;;             :downstream-ch <chan>}       ;; Source channel for downstream-mult
;;  <user> {<crdt-id> {:state <crdt-state>
;;                     :new-values {<id> <value> ...}
;;                     :downstream <last-downstream>}}}


;; =============================================================================
;; Internal Helpers
;; =============================================================================

(defn- notify-downstream!
  "Send downstream notification to multiplexed channel."
  [stage user crdt-id downstream]
  (let [{{:keys [downstream-ch]} :volatile} @stage]
    (when downstream-ch
      (put! downstream-ch {:user user
                           :crdt-id crdt-id
                           :downstream downstream}))))

(defn- make-on-downstream-handler
  "Create an on-downstream callback that updates stage state and notifies listeners."
  [stage]
  (fn [[user crdt-id] downstream]
    (debug {:event :stage-pubsub-downstream :crdt [user crdt-id] :method (:method downstream)})
    ;; Update local stage state
    (swap! stage update-in [user crdt-id :state]
           (fn [old vanilla]
             (-downstream (or old vanilla) (:op downstream)))
           (key->crdt (:crdt downstream)))
    ;; Notify listeners (for realize functions)
    (notify-downstream! stage user crdt-id downstream)))


;; =============================================================================
;; Core API
;; =============================================================================

(defn ensure-crdt [crdt-class stage [user crdt-id]]
  (let [val (get-in @stage [user crdt-id :state])
        t (type val)]
    (when-not (= t crdt-class)
      (if val
        (throw (ex-info "You cannot apply operations on this type."
                        {:user user
                         :expected-type crdt-class
                         :actual-type t
                         :crdt-id crdt-id
                         :value val}))
        (throw (ex-info "There is no CRDT here. Have you forgot to initialize one?"
                        {:user user
                         :expected-type crdt-class
                         :crdt-id crdt-id}))))))


(defn sync!
  "Synchronize (push) the results of an upstream CRDT command with
  storage and other peers via pubsub. Returns go block to synchronize."
  [stage-val [user crdt-id]]
  (let [{{:keys [peer supervisor store mem-store]} :volatile
         {:keys [id]} :config
         {{:keys [new-values downstream]} crdt-id} user} stage-val
        S supervisor
        ;; Capture function to avoid go macro alias resolution issues
        publish-downstream-fn rpubsub/publish-downstream!]
    (go-try S
      (debug {:event :sync-pubsub :crdt [user crdt-id]})
      ;; Store new values in cold store for fetch requests
      (doseq [[k v] new-values]
        (<? S (k/assoc-in store [k] v)))
      ;; Update local mem-store with the downstream
      (<? S (k/update-in mem-store [[user crdt-id]]
                         (fn [{:keys [state crdt] :as current}]
                           (let [crdt-type (or crdt (:crdt downstream))
                                 state (or state (key->crdt crdt-type))]
                             (assoc current
                                    :crdt crdt-type
                                    :state (-downstream state (:op downstream)))))))
      ;; Append to log for persistence
      (<? S (k/append store [user crdt-id :log] downstream))
      ;; Publish downstream to subscribers
      (<? S (publish-downstream-fn peer user crdt-id downstream))
      ;; Return the keys that were stored
      (set (keys new-values)))))


(defn cleanup-ops-and-new-values! [stage upstream fetched-vals]
  (swap! stage
         (fn [old]
           (reduce (fn [old [u id]]
                     (update-in old [u id :new-values]
                                #(apply dissoc % fetched-vals)))
                   old
                   (for [[user crdts] upstream
                         id crdts]
                     [user id]))))
  nil)


(defn connect!
  "Connect stage to a remote url of another peer,
   e.g. ws://remote.peer.net:1234/replikativ/ws. Returns go block to
   synchronize.

   Note: For pubsub, this uses kabel.peer/connect directly."
  [stage url & {:keys [retries] :or {retries #?(:clj Long/MAX_VALUE
                                                :cljs js/Infinity)}}]
  (let [{{:keys [peer]
          S :supervisor} :volatile} @stage]
    (go-try S
      (info {:event :connecting-pubsub :url url})
      ;; Use kabel's connect directly
      (<? S (kpeer/connect S peer url))
      ;; Return a close channel (simplified - actual close handling TBD)
      (chan))))


(defn create-stage!
  "Create a pubsub-based stage for user, given peer.

   The peer should be created with server-peer or client-peer.
   Returns go block to synchronize."
  [user peer]
  (let [{store :cold-store
         mem-store :mem-store
         S :supervisor} (:volatile @peer)]
    (go-try S
      (let [downstream-ch (chan 10000)
            downstream-mult (mult downstream-ch)
            stage-id (str "STAGE-PUBSUB-" (subs (str (uuid)) 0 4))
            sync-token (chan)
            _ (put! sync-token :stage)
            stage (atom {:config {:id stage-id
                                  :user user
                                  :subs {}}
                         :volatile {:peer peer
                                    :supervisor S
                                    :store store
                                    :mem-store mem-store
                                    :sync-token sync-token
                                    :downstream-ch downstream-ch
                                    :downstream-mult downstream-mult}})]
        (info {:event :created-stage-pubsub :id stage-id})
        stage))))


(defn subscribe-crdts!
  "Subscribe stage to crdts map, e.g. {user #{crdt-id}}.
   This is not additive, but only these identities are
   subscribed on the stage afterwards. Returns go block to synchronize."
  [stage crdts]
  (let [{{:keys [peer store mem-store]
          S :supervisor} :volatile
         {:keys [subs]} :config} @stage
        on-downstream (make-on-downstream-handler stage)
        ;; Compute topics outside go block to avoid macro expansion issues
        old-topics (set (for [[user crdt-ids] subs
                              crdt-id crdt-ids]
                          (rpubsub/crdt-topic user crdt-id)))
        new-topics (set (for [[user crdt-ids] crdts
                              crdt-id crdt-ids]
                          (rpubsub/crdt-topic user crdt-id)))
        to-unsub (set/difference old-topics new-topics)
        ;; Capture functions to avoid go macro alias resolution issues
        unsubscribe-fn kpubsub/unsubscribe!
        subscribe-crdts-fn rpubsub/subscribe-crdts!]
    (go-try S
      (info {:event :subscribe-crdts-pubsub :crdts crdts})
      ;; Unsubscribe from old subscriptions that are not in new crdts
      (when (seq to-unsub)
        (debug {:event :unsubscribing-old :topics to-unsub})
        (<? S (unsubscribe-fn peer to-unsub)))

      ;; Subscribe to new CRDTs
      (<? S (subscribe-crdts-fn peer S store mem-store crdts
                                 {:on-downstream on-downstream}))

      ;; Update stage config
      (swap! stage assoc-in [:config :subs] crdts)
      nil)))


(defn remove-crdts!
  "Remove crdts map from stage, e.g. {user #{crdt-id}}.
  Returns go block to synchronize."
  [stage crdts]
  (let [{{:keys [peer]
          S :supervisor} :volatile
         {:keys [subs]} :config} @stage
        ;; Compute topics to unsubscribe BEFORE modifying state
        topics-to-unsub (set (for [[user crdt-ids] crdts
                                   crdt-id crdt-ids]
                               (rpubsub/crdt-topic user crdt-id)))
        ;; Capture function to avoid go macro alias issues
        unsubscribe-fn kpubsub/unsubscribe!]
    ;; Remove from config subs and stage state
    (swap! stage (fn [old]
                   (reduce (fn [state [u id]]
                             (-> state
                                 ;; Remove from subs config (use fnil to handle nil case)
                                 (update-in [:config :subs u] (fnil disj #{}) id)
                                 ;; Also remove the in-memory state
                                 (update u dissoc id)))
                           old
                           (for [[u rs] crdts
                                 id rs]
                             [u id]))))
    ;; Unsubscribe from the topics
    (go-try S
      (when (seq topics-to-unsub)
        (debug {:event :unsubscribing-removed :topics topics-to-unsub})
        (<? S (unsubscribe-fn peer topics-to-unsub)))
      nil)))


;; =============================================================================
;; Server-Side: Register CRDTs for Subscription
;; =============================================================================

(defn register-crdts!
  "Register CRDTs on the server for pubsub subscription.

   This must be called on the server peer before clients can subscribe.
   Returns set of registered topics."
  [stage crdts]
  (let [{{:keys [peer store mem-store]
          S :supervisor} :volatile} @stage]
    (rpubsub/register-crdts! peer S store mem-store crdts {})))


;; =============================================================================
;; Downstream Channel for Realize Functions
;; =============================================================================

(defn downstream-channel
  "Get a channel that receives all downstream notifications for this stage.

   Returns a new channel that is tapped into the downstream mult.
   Close the returned channel when done to stop receiving notifications.

   Each message has the form:
   {:user <user>
    :crdt-id <crdt-id>
    :downstream {:crdt <type> :method <method> :op <op>}}"
  [stage]
  (let [{{:keys [downstream-mult]} :volatile} @stage
        ch (chan 10000)]
    (tap downstream-mult ch)
    ch))

(defn close-downstream-channel!
  "Close and untap a downstream channel."
  [stage ch]
  (let [{{:keys [downstream-mult]} :volatile} @stage]
    (untap downstream-mult ch)
    (close! ch)))


;; =============================================================================
;; Stream Into Identity (for realize functions)
;; =============================================================================

(defn stream-into-identity!
  "Stream downstream updates for a specific CRDT into an identity (atom).

   This is the pubsub equivalent of replikativ.crdt.cdvcs.realize/stream-into-identity!

   Parameters:
   - stage: The pubsub stage
   - [user crdt-id]: The CRDT identifier
   - eval-fn: Function to evaluate transactions
   - ident: Atom to store the realized value
   - opts:
     - :applied-log - Key for tracking applied commits
     - :reset-fn - Function to reset identity (default: reset!)

   Returns {:close-ch <chan> :applied-ch <chan>}"
  [stage [user crdt-id] eval-fn ident
   & {:keys [applied-log reset-fn]
      :or {reset-fn reset!}}]
  (let [{{S :supervisor
          :keys [store downstream-mult]} :volatile} @stage
        ;; Create a filtered channel for this specific CRDT
        all-downstream-ch (chan 10000)
        pub-ch (chan 10000)
        applied-ch (chan 10000)]
    ;; Tap into the downstream mult
    (tap downstream-mult all-downstream-ch)
    ;; Filter for this specific CRDT and transform to expected format
    (go-loop-super S [msg (<? S all-downstream-ch)]
      (when msg
        (when (and (= (:user msg) user)
                   (= (:crdt-id msg) crdt-id))
          (>! pub-ch {:downstream (:downstream msg)
                      :user user
                      :crdt-id crdt-id}))
        (recur (<? S all-downstream-ch))))
    ;; The actual streaming is done by the CRDT-specific realize function
    ;; which will consume from pub-ch
    {:close-ch all-downstream-ch
     :pub-ch pub-ch
     :applied-ch applied-ch}))
