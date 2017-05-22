(ns replikativ.crdt.merging-ormap.stage
  (:refer-clojure :exclude [get assoc! dissoc!])
  (:require [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!
                                      ensure-crdt #?(:clj go-try-locked)]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.crdt.merging-ormap.core :as mormap]
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



(defn create-merging-ormap!
  [stage merge-code merge-fn
   & {:keys [user is-public? description id]
      :or {is-public? false
           description ""}}]
  (go-try-locked stage
                 (let [{{S :supervisor} :volatile} @stage
                       user (or user (get-in @stage [:config :user]))
                       normap (assoc (mormap/new-merging-ormap merge-code merge-fn)
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
  (ensure-crdt replikativ.crdt.MergingORMap stage [user ormap-id])
  (let [{{S :supervisor} :volatile} @stage]
    (go-try S
     (let [store (get-in @stage [:volatile :store])]
       (-> (get-in @stage [user ormap-id])
           (mormap/or-get key))))))

(defn assoc!
  [stage [user ormap-id] key val]
  (let [{{S :supervisor} :volatile} @stage]
    (go-try-locked stage
                   (ensure-crdt replikativ.crdt.MergingORMap stage [user ormap-id])
                   (->> (<? S (sync! (swap! stage (fn [old]
                                                  (update-in old [user ormap-id] mormap/or-assoc key val user)))
                                   [user ormap-id]))
                        (cleanup-ops-and-new-values! stage {user #{ormap-id}})))))


(defn dissoc!
  [stage [user ormap-id] key]
  (let [{{S :supervisor} :volatile} @stage]
    (go-try-locked stage
                   (ensure-crdt replikativ.crdt.MergingORMap stage [user ormap-id])
                   (->> (<? S (sync! (swap! stage (fn [old]
                                                    (update-in old [user ormap-id] mormap/or-dissoc key user)))
                                     [user ormap-id]))
                        (cleanup-ops-and-new-values! stage {user #{ormap-id}})))))


