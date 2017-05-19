(ns replikativ.crdt.ormap.stage
  (:refer-clojure :exclude [get assoc! dissoc!])
  (:require [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!
                                      ensure-crdt #?(:clj go-try-locked)]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.crdt.ormap.core :as ormap]
            [replikativ.protocols :refer [-downstream]]
            [replikativ.realize :refer [commit-transactions]]
            [konserve.core :as k]
            #?(:clj [kabel.platform-log :refer [debug info warn]])
            #?(:clj [superv.async :refer [go-try <? put? <<? go-for]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [replikativ.stage :refer [go-try-locked]]
                            [superv.async :refer [go-try <? put? <<? go-for]]
                            [kabel.platform-log :refer [debug info warn]])))



(defn create-ormap!
  [stage & {:keys [user is-public? description id]
            :or {is-public? false
                 description ""}}]
  (go-try-locked stage
                 (let [{{S :supervisor} :volatile} @stage
                       user (or user (get-in @stage [:config :user]))
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
                   (debug {:event :creating-ormap :user user :id id})
                   (<? S (subscribe-crdts! stage (get-in new-stage [:config :subs])))
                   (->> (<? S (sync! new-stage [user id]))
                        (cleanup-ops-and-new-values! stage identities)) 
                   id)))


(defn get
  [stage [user ormap-id] key]
  (ensure-crdt replikativ.crdt.ORMap stage [user ormap-id])
  (let [{{S :supervisor} :volatile} @stage]
    (go-try S
     (let [store (get-in @stage [:volatile :store])
           commit-ids (-> (get-in @stage [user ormap-id])
                          (ormap/or-get key))
           res (loop [[cid & r] commit-ids
                      res []]
                 (if cid
                   (let [cva (<? S (k/get-in store [cid]))]
                     (recur r (conj res (assoc cva :transactions
                                               (<? S (commit-transactions S store cva))))))
                   res))]
       (when-not (empty? res)
         res)))))

(defn assoc!
  [stage [user ormap-id] key txs]
  (let [{{S :supervisor} :volatile} @stage]
    (go-try-locked stage
                   (ensure-crdt replikativ.crdt.ORMap stage [user ormap-id])
                   (->> (<? S (sync! (swap! stage (fn [old]
                                                  (update-in old [user ormap-id] ormap/or-assoc key txs user)))
                                   [user ormap-id]))
                        (cleanup-ops-and-new-values! stage {user #{ormap-id}})))))


(defn dissoc!
  [stage [user ormap-id] key revert-txs]
  (let [{{S :supervisor} :volatile} @stage]
    (go-try-locked stage
                   (ensure-crdt replikativ.crdt.ORMap stage [user ormap-id])
                   (->> (<? S (sync! (swap! stage (fn [old]
                                                    (update-in old [user ormap-id] ormap/or-dissoc key revert-txs user)))
                                     [user ormap-id]))
                        (cleanup-ops-and-new-values! stage {user #{ormap-id}})))))
