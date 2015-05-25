(ns geschichte.crdt.materialize
  (:require [konserve.protocols :refer [-get-in -assoc-in -bget -bassoc]]
            [geschichte.crdt.repo.impl :refer [map->Repository]]
            [geschichte.platform :refer [<? go-loop<? go-loop>? go<?]
             :include-macros true]
            #+clj [clojure.core.async :as async
                   :refer [<! <!! >! timeout chan alt! go put! filter< map< go-loop sub unsub pub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< sub unsub pub close!]])
  #+cljs (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]))


;; make extendable? multimethod?
(defn pub->crdt
  ([store [user repo] crdt-type]
   (go<? (case crdt-type
           :geschichte.repo
           (map->Repository (assoc (<? (-get-in store [[user repo] :state]))
                              :cursor [[user repo] :state]
                              :store store))

           (throw (ex-info "Cannot materialize CRDT for publication."
                           {:user user
                            :repo repo
                            :crdt-type crdt-type}))))))
