(ns replikativ.crdt.cdvcs.stage
  "Upstream interaction for CDVCS with the stage."
  (:require [konserve.core :as k]
            [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!]]
            [replikativ.environ :refer [*id-fn* store-blob-trans-id store-blob-trans-value]]
            [replikativ.crdt.cdvcs.core :as cdvcs]
            [replikativ.crdt.cdvcs.impl :as impl]
            [replikativ.crdt.cdvcs.meta :as meta]
            [kabel.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [go-try <?]])
            [hasch.core :refer [uuid]]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.async :refer [go-try <?]])))


(defn create-cdvcs!
  "Create a CDVCS given a description. Returns go block to synchronize."
  [stage & {:keys [user is-public? id description]
            :or {is-public? false
                 description ""}}]
  (go-try (let [user (or user (get-in @stage [:config :user]))
                ncdvcs (assoc (cdvcs/new-cdvcs user)
                              :public is-public?
                              :description description)
                id (or id (*id-fn*))
                identities {user #{id}}
                ;; id is random uuid, safe swap!
                new-stage (swap! stage (fn [old]
                                         (-> old
                                             (assoc-in [user id] ncdvcs)
                                             (assoc-in [user id :stage/op] :sub)
                                             (update-in [:config :subs user] #(conj (or % #{}) id)))))]
            (debug "creating new CDVCS for " user "with id" id)
            (<? (subscribe-crdts! stage (get-in new-stage [:config :subs])))
            (<? (sync! new-stage identities))
            (cleanup-ops-and-new-values! stage identities)
            id)))


(defn fork!
  "Forks from one staged user's CDVCS into a new CDVCS for the stage
  user. Returns go block to synchronize."
  [stage [user cdvcs-id] & {:keys [into-user description is-public?]
                           :or {is-public? false
                                description ""}}]
  (go-try
   (when-not (get-in @stage [user cdvcs-id :state :heads])
     (throw (ex-info "CDVCS does not exist."
                     {:type :cdvcs-does-not-exist
                      :user user :cdvcs cdvcs-id})))
   (let [suser (or into-user (get-in @stage [:config :user]))
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
                                        (assoc-in [suser cdvcs-id :stage/op] :sub)
                                        (update-in [:config :subs suser] #(conj (or % #{}) cdvcs-id))))))]
     (debug "forking " user cdvcs-id "for" suser)
     (<? (subscribe-crdts! stage (get-in new-stage [:config :subs])))
     (<? (sync! new-stage identities))
     (cleanup-ops-and-new-values! stage identities))))


(defn checkout!
  "Tries to check out and waits until the CDVCS is available.
  This possibly blocks forever if the CDVCS cannot be fetched from
  some peer."
  [stage [user cdvcs-id]]
  (subscribe-crdts! stage (update-in (get-in @stage [:config :subs])
                                     [user] conj cdvcs-id)))

;; TODO remove value?
(defrecord Abort [new-value aborted])

(defn abort-prepared [stage [user cdvcs-id]]
  ;; racing ...
  (let [a (Abort. nil (get-in @stage [user cdvcs-id :prepared]))]
    (swap! stage assoc-in [user cdvcs-id :prepared] [])
    a))

(defn transact
  "Transact a transaction function trans-fn-code (given as quoted code: '(fn [old params] (merge old params))) on previous value of user's CDVCS and params.
THIS DOES NOT COMMIT YET, you have to call commit! explicitly afterwards. It can still abort resulting in a staged replikativ.stage.Abort value for the CDVCS. Returns go block to synchronize."
  ([stage [user cdvcs-id] trans-fn-code params]
   (transact stage [user cdvcs-id] [[trans-fn-code params]]))
  ([stage [user cdvcs-id] transactions]
   (go-try
    (when-not (get-in @stage [user cdvcs-id :state :heads])
      (throw (ex-info "CDVCS does not exist!"
                      {:type :cdvcs-missing
                       :user user :cdvcs cdvcs-id})))
    (when (some nil? (flatten transactions))
      (throw (ex-info "At least one transaction contains nil."
                      {:type :nil-transaction
                       :transactions transactions})))
    (let [{{:keys [peer eval-fn]} :volatile
           {:keys [subs]} :config} @stage]
      #?(:clj (locking stage
                (swap! stage update-in [user cdvcs-id :prepared] concat transactions))
         :cljs (swap! stage update-in [user cdvcs-id :prepared] concat transactions))
      nil))))

(defn transact-binary
  "Transact a binary blob to reference it later, this only prepares a transaction and does not commit.
  This can support transacting files if the underlying store supports
  this (FileSystemStore)."
  [stage [user cdvcs-id] blob]
  (go-try
   #?(:clj ;; HACK efficiently short circuit addition to store
      (when (= (type blob) java.io.File)
        (let [store (-> stage deref :volatile :peer deref :volatile :store)
              id (uuid blob)]
          (<? (k/bassoc store id blob)))))
   (<? (transact stage [user cdvcs-id] [[store-blob-trans-value blob]]))))


(defn commit!
  "Commit all identities on stage given by the map,
e.g. {user1 #{cdvcs-id1} user2 #{cdvcs-id2}}.  Returns go block to
  synchronize. This change is not necessarily propagated atomicly."
  [stage cdvcs-map]
  (go-try
   ;; atomic swap and sync, safe
   (<? (sync! (swap! stage (fn [old]
                             (reduce (fn [old [user id]]
                                       (-> old
                                           (update-in [user id] #(cdvcs/commit % user))
                                           (assoc-in [user id :stage/op] :pub)))
                                     old
                                     (for [[user cdvcs-ids] cdvcs-map
                                           id cdvcs-ids]
                                       [user id]))))
              cdvcs-map))
   (cleanup-ops-and-new-values! stage cdvcs-map)))

(defn pull!
  "Pull from remote-user (can be the same) into CDVCS.
  Defaults to stage user as into-user. Returns go-block to
  synchronize."
  [stage [remote-user cdvcs-id] & {:keys [into-user allow-induced-conflict?
                                          rebase-transactions?]
                                   :or {allow-induced-conflict? false
                                        rebase-transactions? false}}]
  (go-try
   (let [{{u :user} :config} @stage
         user (or into-user u)]
     (when-not (and (not rebase-transactions?)
                    (empty? (get-in stage [user cdvcs-id :prepared])))
       (throw (ex-info "There are prepared transactions, which could conflict. Either commit or drop them."
                       {:type :transactions-pending-might-conflict
                        :transactions (get-in stage [user cdvcs-id :prepared])})))
     ;; atomic swap! and sync!, safe
     (<? (sync! (swap! stage (fn [{{{{remote-heads :heads :as remote-meta} :state} cdvcs-id}
                                  remote-user :as stage-val}]
                               (when (not= (count remote-heads) 1)
                                 (throw (ex-info "Cannot pull from conflicting CDVCS."
                                                 {:type :conflicting-remote-meta
                                                  :remote-user remote-user :cdvcs cdvcs-id})))
                               (-> stage-val
                                   (update-in [user cdvcs-id]
                                              #(cdvcs/pull % remote-meta (first remote-heads)
                                                          allow-induced-conflict? rebase-transactions?))
                                   (assoc-in [user cdvcs-id :stage/op] :pub))))
                {user #{cdvcs-id}})))))


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
  (let [heads (get-in @stage [user cdvcs-id :state :heads])
        graph (get-in @stage [user cdvcs-id :state :commit-graph])]
    (go-try
     (when-not graph
       (throw (ex-info "CDVCS does not exist."
                       {:type :cdvcs-does-not-exist
                        :user user :cdvcs cdvcs-id})))
     (when-not (= (set heads-order) heads)
       (throw (ex-info "Supplied heads don't match CDVCS heads."
                       {:type :heads-dont-match-cdvcs
                        :heads heads
                        :supplied-heads heads-order})))
     (let [identities {user #{cdvcs-id}}]
       (when wait?
         (let [to (rand-int (merge-cost graph))]
           (debug "Waiting for merge timeout " to)
           (<? (timeout to))))
       (when-not (= heads (get-in @stage [user cdvcs-id :state :heads]))
         (throw (ex-info "Heads changed, merge aborted."
                         {:type :heads-changed
                          :old-heads heads
                          :new-heads (get-in @stage [user cdvcs-id :state :heads])})))
       ;; atomic swap! and sync!, safe
       (<? (sync! (swap! stage (fn [{{u :user} :config :as old}]
                                 (-> old
                                     (update-in [user cdvcs-id]
                                                #(cdvcs/merge % u (:state %)
                                                              heads-order
                                                              correcting-transactions))
                                     (assoc-in [user cdvcs-id :stage/op] :pub))))
                  identities))
       (cleanup-ops-and-new-values! stage identities)))))
