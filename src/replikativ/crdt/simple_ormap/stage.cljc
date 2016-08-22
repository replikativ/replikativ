(ns replikativ.crdt.simple-ormap.stage
  (:require [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!
                                      ensure-crdt go-try-locked]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.crdt.simple-ormap.core :as ormap]
            [replikativ.protocols :refer [-downstream]]
            [kabel.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [go-try <? put?]])
            #?(:clj [full.lab :refer [go-loop-super]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]])))



(defn create-simple-ormap!
  [stage & {:keys [user is-public? description id]
            :or {is-public? false
                 description ""}}]
  (go-try-locked stage
   (let [user (or user (get-in @stage [:config :user]))
         normap (assoc (ormap/new-simple-ormap)
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
     (debug "creating new SimpleORMap for " user "with id" id)
     (<? (subscribe-crdts! stage (get-in new-stage [:config :subs])))
     (->> (<? (sync! new-stage [user id]))
          (cleanup-ops-and-new-values! stage identities)) 
     id)))


(defn or-get
  [stage [user simple-ormap-id] key]
  (ensure-crdt replikativ.crdt.SimpleORMap stage [user simple-ormap-id])
  (-> (get-in @stage [user simple-ormap-id])
      (ormap/or-get key)))

(defn or-assoc!
  [stage [user simple-ormap-id] key val]
  (go-try-locked stage
   (ensure-crdt replikativ.crdt.SimpleORMap stage [user simple-ormap-id])
   (->> (<? (sync! (swap! stage (fn [old]
                                  (update-in old [user simple-ormap-id] ormap/or-assoc key val)))
                   [user simple-ormap-id]))
        (cleanup-ops-and-new-values! stage {user #{simple-ormap-id}}))))


(defn or-dissoc!
  [stage [user simple-ormap-id] key]
  (go-try-locked stage
   (ensure-crdt replikativ.crdt.SimpleORMap stage [user simple-ormap-id])
   (->> (<? (sync! (swap! stage (fn [old]
                                  (update-in old [user simple-ormap-id] ormap/or-dissoc key)))
                   [user simple-ormap-id]))
        (cleanup-ops-and-new-values! stage {user #{simple-ormap-id}}))))
