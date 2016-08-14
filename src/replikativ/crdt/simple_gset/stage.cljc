(ns replikativ.crdt.simple-gset.stage
  (:require [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!
                                      ensure-crdt]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.simple-gset.core :as gset]
            [kabel.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [go-try <?]])
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.async :refer [go-try <?]])))


(defn create-simple-gset!
  [stage & {:keys [user is-public? description id]
            :or {is-public? false
                 description ""}}]
  (go-try (let [user (or user (get-in @stage [:config :user]))
                ngset (assoc (gset/new-simple-gset)
                             :public is-public?
                              :description description)
                id (or id (*id-fn*))
                identities {user #{id}}
                new-stage (swap!
                           stage
                           (fn [old]
                             (-> old
                                (assoc-in [user id] ngset)
                                (update-in [:config :subs user] #(conj (or % #{}) id)))))]
            (debug "creating new SimpleGSet for " user "with id" id)
            (<? (subscribe-crdts! stage (get-in new-stage [:config :subs])))
            (locking stage
              (->> (<? (sync! new-stage [user id]))
                   (cleanup-ops-and-new-values! stage identities)))
            id)))


(defn add!
  [stage [user simple-gset-id] element]
  (go-try
   (locking stage
     (->> (<?? (sync! (swap! stage #(update-in % [user simple-gset-id] gset/add element)
                            {user #{simple-gset-id}})))
          (cleanup-ops-and-new-values! stage {user #{simple-gset-id}})))))
