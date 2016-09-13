(ns replikativ.crdt.ormap.stage
  (:refer-clojure :exclude [get assoc! dissoc!])
  (:require [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!
                                      ensure-crdt #?(:clj go-try-locked)]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.crdt.ormap.core :as ormap]
            [replikativ.protocols :refer [-downstream]]
            [konserve.core :as k]
            [kabel.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [go-try <? put? <<?]])
            #?(:clj [full.lab :refer [go-loop-super go-for]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [replikativ.stage :refer [go-try-locked]]
                            [full.async :refer [go-try <? put? <<?]]
                            [full.lab :refer [go-for]])))



(defn create-ormap!
  [stage & {:keys [user is-public? description id]
            :or {is-public? false
                 description ""}}]
  (go-try-locked stage
   (let [user (or user (get-in @stage [:config :user]))
         normap (assoc (ormap/new-ormap)
                       :public is-public?
                       :description description)
         id (or id (*id-fn*))
         _ (when (get-in @stage [user id])
             (throw (ex-info "CRDT already exists." {:user user :id id})))
         identities {user #{id}}
         new-stage (swap! stage
                          (fn [old]
                            (-> old
                                (assoc-in [user id] normap)
                                (update-in [:config :subs user] #(conj (or % #{}) id)))))]
     (debug "creating new ORMap for " user "with id" id)
     (<? (subscribe-crdts! stage (get-in new-stage [:config :subs])))
     (->> (<? (sync! new-stage [user id]))
          (cleanup-ops-and-new-values! stage identities)) 
     id)))


(defn get
  [stage [user ormap-id] key]
  (ensure-crdt replikativ.crdt.ORMap stage [user ormap-id])
  (go-try
   (let [store (get-in @stage [:volatile :store])
         res (<<? (go-for [cid (-> (get-in @stage [user ormap-id])
                                   (ormap/or-get key))
                           :let [cva (<? (k/get-in store [cid]))]]
                          (assoc cva :transactions
                                 (<? (commit-transactions store cva)))))]
     (when-not (empty? res)
       res))))

(defn assoc!
  [stage [user ormap-id] key txs]
  (go-try-locked stage
   (ensure-crdt replikativ.crdt.ORMap stage [user ormap-id])
   (->> (<? (sync! (swap! stage (fn [old]
                                  (update-in old [user ormap-id] ormap/or-assoc key txs user)))
                   [user ormap-id]))
        (cleanup-ops-and-new-values! stage {user #{ormap-id}}))))


(defn dissoc!
  [stage [user ormap-id] key revert-txs]
  (go-try-locked stage
   (ensure-crdt replikativ.crdt.ORMap stage [user ormap-id])
   (->> (<? (sync! (swap! stage (fn [old]
                                  (update-in old [user ormap-id] ormap/or-dissoc key revert-txs user)))
                   [user ormap-id]))
        (cleanup-ops-and-new-values! stage {user #{ormap-id}}))))
