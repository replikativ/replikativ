(ns replikativ.crdt.materialize
  (:require [konserve.protocols :refer [-get-in -assoc-in -bget -bassoc]]
            [replikativ.crdt :refer [map->Repository]]
            [replikativ.crdt.repo.impl :refer :all]
            [full.async :refer [<? go-try]]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! go put! go-loop sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]])))


;; make extendable? multimethod?
(defn pub->crdt
  ([crdt-type]
   (go-try (case crdt-type
             :repo (map->Repository {:version 1}))))
  ([store [user crdt-id] crdt-type]
   (go-try (case crdt-type
             :repo
             (map->Repository (assoc (<? (-get-in store [[user crdt-id] :state]))
                                     :version 1
                                     :cursor [[user crdt-id] :state]
                                     :store store))

             (throw (ex-info "Cannot materialize CRDT for publication."
                             {:user user
                              :crdt-id crdt-id
                              :crdt-type crdt-type}))))))
