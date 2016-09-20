(ns replikativ.crdt.lwwr.stage
  (:require [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!
                                      ensure-crdt go-try-locked]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.crdt.lwwr.core :as lwwr]
            [replikativ.protocols :refer [-downstream]]
            [kabel.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [go-try <? put?]])
            #?(:clj [full.lab :refer [go-loop-super]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.async :refer [go-try <? put?]]
                            [full.lab :refer [go-loop-super]]))
  #?(:clj (:import [replikativ.crdt LWWR])))


(defn create-lwwr!
  "Create new LWWR in stage"
  [stage & {:keys [user is-public? description id init-val]
            :or {is-public? false
                 description ""}}]
  (go-try-locked stage
                 (let [user (or user (get-in @stage [:config :user]))
                       lwwr (assoc (lwwr/create-lwwr :init-val init-val)
                                   :public is-public?
                                   :description description)
                       id (or id (*id-fn*))
                       _ (when (get-in @stage [user id])
                           (throw (ex-info "CRDT already exists." {:user user :id id})))
                       identities {user #{id}}
                       new-stage (swap!
                                  stage
                                  (fn [old]
                                    (-> old
                                       (assoc-in [user id] lwwr)
                                       (update-in [:config :subs user] #(conj (or % #{}) id)))))]
                   (debug {:event :creating-new-lwwr :crdt [user id]})
                   (<? (subscribe-crdts! stage (get-in new-stage [:config :subs])))
                   (->> (<? (sync! new-stage [user id]))
                      (cleanup-ops-and-new-values! stage identities))
                   id)))


(defn set-register!
  "Set LWWR"
  [stage [user lwwr-id] register]
  (go-try
   (ensure-crdt replikativ.crdt.LWWR stage [user lwwr-id])
   (let [{{:keys [sync-token]} :volatile} @stage
         _ (<? sync-token)]
     (->> (<? (sync!
             (swap! stage
                    (fn [old]
                      (update-in old [user lwwr-id] lwwr/set-register register)))
             [user lwwr-id]))
        (cleanup-ops-and-new-values! stage {user #{lwwr-id}}))
     (put? sync-token :stage))))
