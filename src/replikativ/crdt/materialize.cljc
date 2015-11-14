(ns replikativ.crdt.materialize
  (:require [konserve.core :as k]
            [replikativ.crdt :refer [map->Repository]]
            #?(:clj [full.async :refer [<? go-try throw-if-throwable]]
               :cljs [full.cljs.async :refer [throw-if-throwable]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! go put! go-loop sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.cljs.async :refer [<? go-try]])))


;; make extendable? multimethod?
(defn pub->crdt
  ([crdt-type]
   (go-try (case crdt-type
             :repo (map->Repository {:version 1}))))
  ([store [user crdt-id] crdt-type]
   (go-try (case crdt-type
             :repo
             (map->Repository (assoc (<? (k/get-in store [[user crdt-id] :state]))
                                     :version 1
                                     :cursor [[user crdt-id] :state]
                                     :store store))

             (throw (ex-info "Cannot materialize CRDT for publication."
                             {:user user
                              :crdt-id crdt-id
                              :crdt-type crdt-type}))))))
