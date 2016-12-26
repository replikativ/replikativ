(ns replikativ.crdt.simple-gset.stage
  (:require [replikativ.stage :refer [sync! cleanup-ops-and-new-values! subscribe-crdts!
                                      ensure-crdt #?(:clj go-try-locked)]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.crdt.simple-gset.core :as gset]
            [replikativ.protocols :refer [-downstream]]
            #?(:clj [kabel.platform-log :refer [debug info warn]])
            #?(:clj [superv.async :refer [go-try <? put? go-loop-super]])
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [go-try <? put? go-loop-super]] 
                            [replikativ.stage :refer [go-try-locked]]
                            [kabel.platform-log :refer [debug info warn]]))
  #?(:clj (:import [replikativ.crdt SimpleGSet])))


(defn create-simple-gset!
  "Create a new simple Grow-Set. All values are inlined in the metadata, which
  means this datatype works best with small collections of not too large values."
  [stage & {:keys [user is-public? description id]
            :or {is-public? false
                 description ""}}]
  (go-try-locked stage
                 (let [{{S :supervisor} :volatile} @stage
                       user (or user (get-in @stage [:config :user]))
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
                   (debug {:event :creating-new-simplegset :crdt [user id]})
                   (<? S (subscribe-crdts! stage (get-in new-stage [:config :subs])))
                   (->> (<? S (sync! new-stage [user id]))
                        (cleanup-ops-and-new-values! stage identities))
                   id)))


(defn add!
  "Add an element to this SimpleGSet."
  [stage [user simple-gset-id] element]
  (let [{{S :supervisor} :volatile} @stage]
    (go-try-locked stage
                   (ensure-crdt replikativ.crdt.SimpleGSet stage [user simple-gset-id])
                   (->> (<? S (sync! (swap! stage (fn [old]
                                                    (update-in old [user simple-gset-id] gset/add element)))
                                     [user simple-gset-id]))
                        (cleanup-ops-and-new-values! stage {user #{simple-gset-id}})))))


(defn stream-into-atom! [stage [u id] val-atom]
  (let [{{[p _] :chans
          :keys [store err-ch]
          S :supervisor} :volatile} @stage
        pub-ch (chan)]
    (async/sub p :pub/downstream pub-ch)
    (go-loop-super S [{:keys [user crdt-id downstream]} (<? S pub-ch)]
                   (when pub
                     (debug {:event :streaming :pub pub})
                     (let [gset (or (get-in @stage [u id :state])
                                    (key->crdt :simple-gset))]
                       (reset! val-atom (:elements (-downstream gset downstream)))
                       (recur (<? S pub-ch)))))
    pub-ch))
