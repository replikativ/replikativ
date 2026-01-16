(ns replikativ.p2p.hooks
  "Pubsub-compatible hooks for automatic pulling/merging CRDTs.

   This is the pubsub equivalent of replikativ.p2p.hooks. It provides
   the same hook functionality but integrates with the pubsub protocol
   instead of the wire protocol.

   Usage:
   ```clojure
   ;; Define hooks
   (def hooks (atom {[user-a crdt-a] [[user-b crdt-b] integrity-fn allow-conflict?]}))

   ;; Use with stage
   (def stage (create-stage! user peer))
   (set-hooks! stage hooks)
   ```"
  (:require [konserve.core :as k]
            [replikativ.crdt.materialize :refer [ensure-crdt]]
            [replikativ.protocols :refer [PPullOp -downstream -pull]]
            [replikativ.pubsub :as rpubsub]
            [konserve.memory :refer [new-mem-store]]
            #?(:clj [kabel.platform-log :refer [debug info warn error]])
            #?(:clj [superv.async :refer [<? go-try <<? go-for go-loop-super]])
            #?(:cljs [superv.async :refer [<? go-try <<? go-for go-loop-super] :include-macros true])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close! onto-chan]]
               :cljs [clojure.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close! onto-chan] :include-macros true]))
  #?(:cljs (:require-macros [kabel.platform-log :refer [debug info warn error]])))


(defn default-integrity-fn
  "Is always true."
  [S store commit-ids] (go true))


(defn match-pubs
  "Match a publication against hooks and produce pulled publications.

   Returns a channel yielding a sequence of additional publications
   that should be sent due to hook matches."
  [S cold-store mem-store atomic-pull-store [user crdt-id] downstream hooks]
  (go-for S [[[a-user a-crdt-id]
              [[b-user b-crdt-id]
               integrity-fn
               allow-induced-conflict?]] (seq hooks)
             ;; expand only relevant hooks
             :when (and (or (and (= (type a-user) #?(:clj java.util.regex.Pattern :cljs js/RegExp))
                                 (re-matches a-user user))
                            (= a-user user))
                        (not= user b-user)
                        (= crdt-id a-crdt-id))
             :let [a-crdt (if-let [a-crdt (<? S (k/get-in atomic-pull-store [user a-crdt-id]))]
                            a-crdt
                            (<? S (ensure-crdt S cold-store mem-store [user a-crdt-id] (:crdt downstream))))
                   a-crdt (-downstream a-crdt (:op downstream))
                   _ (<? S (k/assoc-in atomic-pull-store [user a-crdt-id] a-crdt))
                   b-crdt (if-let [b-crdt (<? S (k/get-in atomic-pull-store [b-user b-crdt-id]))]
                            b-crdt
                            (<? S (ensure-crdt S cold-store mem-store [b-user b-crdt-id] (:crdt downstream))))
                   pulled (<? S (-pull a-crdt S cold-store atomic-pull-store
                                       [[a-user a-crdt-id a-crdt]
                                        [b-user b-crdt-id b-crdt]
                                        (or integrity-fn default-integrity-fn)
                                        allow-induced-conflict?]))]
             :when (not= pulled :rejected)]
          {:user b-user
           :crdt-id b-crdt-id
           :downstream pulled}))


(defn process-hooks!
  "Process hooks for a downstream publication.

   Evaluates hooks and publishes any resulting pulled publications.

   Parameters:
   - stage: The pubsub stage
   - user: User ID of the original publication
   - crdt-id: CRDT ID of the original publication
   - downstream: The downstream operation
   - hooks: Atom containing hooks map

   Returns channel yielding {:ok true :pulled-count N}"
  [stage user crdt-id downstream hooks]
  (let [{{:keys [peer store mem-store]
          S :supervisor} :volatile} @stage]
    (go-try S
      (let [atomic-pull-store (<? S (new-mem-store))
            pulled (<<? S (match-pubs S store mem-store atomic-pull-store
                                       [user crdt-id] downstream @hooks))]
        (debug {:event :hooks-pubsub-processed
                :source [user crdt-id]
                :pulled-count (count pulled)})
        ;; Publish pulled results
        (doseq [{:keys [user crdt-id downstream]} pulled]
          (<? S (rpubsub/publish-downstream! peer user crdt-id downstream)))
        {:ok true :pulled-count (count pulled)}))))


(defn make-hooked-on-downstream
  "Create an on-downstream handler that processes hooks.

   Wraps the original on-downstream callback to also process hooks
   for each received publication.

   Parameters:
   - stage: The pubsub stage
   - hooks: Atom containing hooks map
   - original-handler: Optional original on-downstream callback to also invoke

   Returns an on-downstream callback function."
  [stage hooks original-handler]
  (fn [[user crdt-id] downstream]
    ;; Call original handler first
    (when original-handler
      (original-handler [user crdt-id] downstream))
    ;; Process hooks asynchronously
    (process-hooks! stage user crdt-id downstream hooks)))


;; =============================================================================
;; Stage Integration
;; =============================================================================

(defn set-hooks!
  "Set hooks on a pubsub stage.

   Hooks are a map of:
   {[source-user source-crdt] [[target-user target-crdt] integrity-fn allow-conflict?]}

   When a publication arrives for [source-user source-crdt], the hook
   will pull changes into [target-user target-crdt].

   source-user can be a regex pattern to match multiple users.

   Parameters:
   - stage: The pubsub stage atom
   - hooks: Atom containing hooks map"
  [stage hooks]
  (swap! stage assoc-in [:volatile :hooks] hooks))

(defn get-hooks
  "Get hooks from a pubsub stage."
  [stage]
  (get-in @stage [:volatile :hooks]))


;; =============================================================================
;; Subscribe with Hooks
;; =============================================================================

(defn subscribe-crdts-with-hooks!
  "Subscribe to CRDTs with hooks processing enabled.

   This is like subscribe-crdts! but automatically processes hooks
   for each received downstream publication.

   Parameters:
   - stage: The pubsub stage
   - crdts: Map of {user -> #{crdt-ids}}
   - hooks: Atom containing hooks map
   - opts: Additional options
     - :on-downstream - Optional callback (fn [[user crdt-id] downstream])

   Returns go block to synchronize."
  [stage crdts hooks & {:keys [on-downstream]}]
  (let [{{:keys [peer store mem-store]
          S :supervisor} :volatile} @stage
        hooked-handler (make-hooked-on-downstream stage hooks on-downstream)]
    (go-try S
      (info {:event :subscribe-crdts-with-hooks :crdts crdts})
      ;; Subscribe with hooked handler
      (<? S (rpubsub/subscribe-crdts! peer S store mem-store crdts
                                       {:on-downstream hooked-handler}))
      ;; Update stage config
      (swap! stage assoc-in [:config :subs] crdts)
      nil)))
