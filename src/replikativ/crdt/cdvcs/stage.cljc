(ns replikativ.crdt.cdvcs.stage
  "Upstream interaction for CDVCS with the stage."
  (:require [konserve.core :as k]
            [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!
                                      ensure-crdt #?(:clj go-try-locked)]]
            [replikativ.environ :refer [*id-fn* store-blob-trans-id store-blob-trans-value]]
            [replikativ.crdt.cdvcs.core :as cdvcs]
            [replikativ.crdt.cdvcs.impl :as impl]
            [replikativ.crdt.cdvcs.meta :as meta]
            [replikativ.crdt.materialize :refer [get-crdt]]
            #?(:clj [kabel.platform-log :refer [debug info warn]])
            #?(:clj [superv.async :refer [go-try <? put?]])
            [hasch.core :refer [uuid]]
            [clojure.set :as set]
            #?(:cljs [superv.async :refer [put?]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [go-try <? put?]]
                            [replikativ.stage :refer [go-try-locked]]
                            [kabel.platform-log :refer [debug info warn]])))


(defn create-cdvcs!
  "Create a CDVCS given a description. Returns go block to synchronize."
  [stage & {:keys [user is-public? id description]
            :or {is-public? false
                 description ""}}]
  (go-try-locked stage
                 (let [{{S :supervisor} :volatile} @stage
                       user (or user (get-in @stage [:config :user]))
                       ncdvcs (assoc (cdvcs/new-cdvcs user)
                                     :public is-public?
                                     :description description)
                       id (or id (*id-fn*))
                       _ (when (get-in @stage [user id])
                           (throw (ex-info "CRDT already exists." {:user user :id id})))
                       identities {user #{id}}
                       ;; id is random uuid, safe swap!
                       new-stage (swap! stage (fn [old]
                                                (-> old
                                                    (assoc-in [user id] ncdvcs)
                                                    (update-in [:config :subs user] #(conj (or % #{}) id)))))
                       ]
                   (debug {:event :creating-new-cdvcs :crdt [user id]})
                   (<? S (subscribe-crdts! stage (get-in new-stage [:config :subs])))
                   (->> (<? S (sync! new-stage [user id]))
                        (cleanup-ops-and-new-values! stage identities))
                   id)))


(defn fork!
  "Forks from one staged user's CDVCS into a new CDVCS for the stage
  user. Returns go block to synchronize."
  [stage [user cdvcs-id] & {:keys [into-user description is-public?]
                           :or {is-public? false
                                description ""}}]
  (go-try-locked stage
   (ensure-crdt replikativ.crdt.CDVCS stage [user cdvcs-id])
   (let [{{S :supervisor} :volatile} @stage
         suser (or into-user (get-in @stage [:config :user]))
         identities {suser #{cdvcs-id}}
         ;; atomic swap! and sync, safe
         new-stage (swap! stage (fn [old]
                                  (if (get-in old [suser cdvcs-id])
                                    (throw (ex-info "CDVCS already exists, use pull."
                                                    {:type :forking-impossible
                                                     :user user :id cdvcs-id}))
                                    (-> old
                                        (assoc-in [suser cdvcs-id]
                                                  (assoc (cdvcs/fork (get-in old [user cdvcs-id :state]))
                                                         :public is-public?
                                                         :description description))
                                        (update-in [:config :subs suser] #(conj (or % #{}) cdvcs-id))))))]
     (debug {:event :forking-cdvcs :crdt [user cdvcs-id] :for suser})
     (<? S (subscribe-crdts! stage (get-in new-stage [:config :subs])))
     (->> (<? S (sync! new-stage [user cdvcs-id]))
          (cleanup-ops-and-new-values! stage identities)))))


(defn checkout!
  "Tries to check out and waits until the CDVCS is available.
  This possibly blocks forever if the CDVCS cannot be fetched from
  some peer."
  [stage [user cdvcs-id]]
  (subscribe-crdts! stage (update-in (get-in @stage [:config :subs])
                                     [user] conj cdvcs-id)))


(defn transact!
  "Transact txs into CDVCS.  Returns go block to
  synchronize. This change is not necessarily propagated atomicly."
  [stage [user cdvcs-id] txs]
  (go-try-locked stage
   (ensure-crdt replikativ.crdt.CDVCS stage [user cdvcs-id])
   ;; ensure we don't miss commits from the peer
   (let [{{:keys [peer]
           S :supervisor} :volatile} @stage
         {{:keys [mem-store cold-store]} :volatile} @peer]
     (loop []
       (let [sc (count (get-in @stage [user cdvcs-id :state :commit-graph]))
             pc (count (get-in (<? S (get-crdt S cold-store mem-store [user cdvcs-id]))
                               [:state :commit-graph]))]
         (when (< sc pc)
           (debug {:event :cdvcs-not-synched-with-store-yet :crdt [user cdvcs-id]})
           (<? S (timeout 1000))
           (recur))))

     ;; atomic swap and sync, safe
     (->> (<? S (sync! (swap! stage (fn [old]
                                    (-> old
                                        (update-in [user cdvcs-id :prepared] concat txs)
                                        (update-in [user cdvcs-id] #(cdvcs/commit % user))
                                        )))
                       [user cdvcs-id]))
          (cleanup-ops-and-new-values! stage {user #{cdvcs-id}})))))

(defn pull!
  "Pull from remote-user (can be the same) into CDVCS.
  Defaults to stage user as into-user. Returns go-block to
  synchronize."
  [stage [remote-user cdvcs-id] & {:keys [into-user allow-induced-conflict?
                                          rebase-transactions?]
                                   :or {allow-induced-conflict? false
                                        rebase-transactions? false}}]
  (go-try-locked stage
   (ensure-crdt replikativ.crdt.CDVCS stage [remote-user cdvcs-id])
   (let [{{u :user} :config
          {S :supervisor} :volatile} @stage
         user (or into-user u)]
     (when-not (and (not rebase-transactions?)
                    (empty? (get-in stage [user cdvcs-id :prepared])))
       (throw (ex-info "There are prepared transactions, which could conflict. Either commit or drop them."
                       {:type :transactions-pending-might-conflict
                        :transactions (get-in stage [user cdvcs-id :prepared])})))
     ;; atomic swap! and sync!, safe
     (<? S (sync! (swap! stage (fn [{{{{remote-heads :heads :as
                                       remote-meta} :state} cdvcs-id}
                                       remote-user :as stage-val}]
                                 (when (not= (count remote-heads) 1)
                                   (throw (ex-info "Cannot pull from conflicting CDVCS."
                                                   {:type :conflicting-remote-meta
                                                    :remote-user remote-user :cdvcs cdvcs-id})))
                                 (update-in stage-val [user cdvcs-id]
                                            #(cdvcs/pull % remote-meta (first remote-heads)
                                                         allow-induced-conflict? rebase-transactions?))))
                  [user cdvcs-id])))))


(defn merge-cost
  "Estimates cost for adding a further merge to the CDVCS by taking
the ratio between merges and normal commits of the commit-graph into account."
  [graph]
  (let [merges (count (filter (fn [[k v]] (> (count v) 1)) graph))
        ratio (double (/ merges (count graph)))]
    (int (* (- (#?(:clj Math/log :cljs js/Math.log) (- 1 ratio)))
            100000))))


(defn merge!
  "Merge multiple heads in a CDVCS. Use heads-order to decide in which
  order commits contribute to the value. By adding older commits
  before their parents, you can enforce to realize them (and their
  past) first for this merge (commit-reordering). Only reorder parts
  of the concurrent history, not of the sequential common
  past. Returns go channel to synchronize."
  [stage [user cdvcs-id] heads-order
   & {:keys [wait? correcting-transactions]
      :or {wait? true
           correcting-transactions []}}]
  (let [{{S :supervisor} :volatile} @stage
        heads (get-in @stage [user cdvcs-id :state :heads])
        graph (get-in @stage [user cdvcs-id :state :commit-graph])]
    (go-try-locked stage
     (ensure-crdt replikativ.crdt.CDVCS stage [user cdvcs-id])
     (when-not (= (set heads-order) heads)
       (throw (ex-info "Supplied heads don't match CDVCS heads."
                       {:type :heads-dont-match-cdvcs
                        :heads heads
                        :supplied-heads heads-order})))
     (let [identities {user #{cdvcs-id}}]
       (when wait?
         (let [to (rand-int (merge-cost graph))]
           (debug {:event :waiting-for-merge-timeout :timeout to})
           (<? S (timeout to))))
       (when-not (= heads (get-in @stage [user cdvcs-id :state :heads]))
         (throw (ex-info "Heads changed, merge aborted."
                         {:type :heads-changed
                          :old-heads heads
                          :new-heads (get-in @stage [user cdvcs-id :state :heads])})))
       ;; atomic swap! and sync!, safe
       (->> (<? S (sync! (swap! stage (fn [{{u :user} :config :as old}]
                                      (update-in old [user cdvcs-id]
                                                 #(cdvcs/merge % u (:state %) heads-order
                                                               correcting-transactions))))
                       [user cdvcs-id]))
            (cleanup-ops-and-new-values! stage identities))))))
