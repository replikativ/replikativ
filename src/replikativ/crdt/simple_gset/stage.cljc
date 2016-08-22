(ns replikativ.crdt.simple-gset.stage
  (:require [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!
                                      ensure-crdt]]
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
  (:import [replikativ.crdt SimpleGSet]))

(defn ensure-simple-gset
  [stage [user simple-gset-id]]
  (let [val (get-in @stage [user simple-gset-id :state])]
    (when-not (= (type val)
                 replikativ.crdt.SimpleGSet)
      (if val
        (throw (ex-info "You cannot apply SimpleGSet operations on this value."
                        {:user user
                         :simple-gset-id simple-gset-id
                         :value val}))
        (throw (ex-info "There is no SimpleGSet here. Have you forgot to create one?"
                        {:user user
                         :simple-gset-id simple-gset-id}))))))


(defn create-simple-gset!
  "Create a new simple Grow-Set. All values are inlined in the metadata, which
  means this datatype works best with small collections of not too large values."
  [stage & {:keys [user is-public? description id]
            :or {is-public? false
                 description ""}}]
  (go-try (let [{{:keys [sync-token]} :volatile} @stage
                _ (<? sync-token)
                user (or user (get-in @stage [:config :user]))
                ngset (assoc (gset/new-simple-gset)
                             :public is-public?
                             :description description)
                id (or id (*id-fn*))
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
            (put? sync-token :stage)
            id)))


(defn add!
  "Add an element to this SimpleGSet."
  [stage [user simple-gset-id] element]
  (go-try
   (ensure-simple-gset stage [user simple-gset-id])
   (let [{{:keys [sync-token]} :volatile} @stage
         _ (<? sync-token)]
     (->> (<? (sync! (swap! stage (fn [old]
                                  (update-in old [user simple-gset-id] gset/add element)))
                   [user simple-gset-id]))
        (cleanup-ops-and-new-values! stage {user #{simple-gset-id}}))
     (put? sync-token :stage))))


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
