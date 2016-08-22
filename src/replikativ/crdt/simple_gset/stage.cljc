(ns replikativ.crdt.simple-gset.stage
  (:require [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!
                                      ensure-crdt go-try-locked]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.crdt.simple-gset.core :as gset]
            [replikativ.protocols :refer [-downstream]]
            [kabel.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [go-try <? put?]])
            #?(:clj [full.lab :refer [go-loop-super]])
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.async :refer [go-try <? put?]]
                            [full.lab :refer [go-loop-super]]))
  #?(:clj (:import [replikativ.crdt SimpleGSet])))


(defn create-simple-gset!
  "Create a new simple Grow-Set. All values are inlined in the metadata, which
  means this datatype works best with small collections of not too large values."
  [stage & {:keys [user is-public? description id]
            :or {is-public? false
                 description ""}}]
  (go-try-locked stage
                 (let [user (or user (get-in @stage [:config :user]))
                       ngset (assoc (gset/new-simple-gset)
                                    :public is-public?
                                    :description description)
                       id (or id (*id-fn*))
                       _ (when (get-in @stage [user id])
                           (throw (ex-info "CRDT already exists." {:user user :id id})))
                       identities {user #{id}}
                       new-stage (swap! stage
                                        (fn [old]
                                          (-> old
                                              (assoc-in [user id] ngset)
                                              (update-in [:config :subs user] #(conj (or % #{}) id)))))]
                   (debug "creating new SimpleGSet for " user "with id" id)
                   (<? (subscribe-crdts! stage (get-in new-stage [:config :subs])))
                   (->> (<? (sync! new-stage [user id]))
                        (cleanup-ops-and-new-values! stage identities))
                   id)))


(defn add!
  "Add an element to this SimpleGSet."
  [stage [user simple-gset-id] element]
  (go-try-locked stage
   (ensure-crdt replikativ.crdt.SimpleGSet stage [user simple-gset-id])
   (->> (<? (sync! (swap! stage (fn [old]
                                  (update-in old [user simple-gset-id] gset/add element)))
                   [user simple-gset-id]))
        (cleanup-ops-and-new-values! stage {user #{simple-gset-id}}))))


(defn stream-into-atom! [stage [u id] val-atom]
  (let [{{[p _] :chans
          :keys [store err-ch]} :volatile} @stage
        pub-ch (chan)]
    (async/sub p :pub/downstream pub-ch)
    (go-loop-super [{:keys [user crdt-id downstream]} (<? pub-ch)]
                   (when pub
                     (debug "streaming: " pub)
                     (let [gset (or (get-in @stage [u id :state])
                                    (key->crdt :simple-gset))]
                       (reset! val-atom (:elements (-downstream gset downstream)))
                       (recur (<? pub-ch)))))
    pub-ch))
