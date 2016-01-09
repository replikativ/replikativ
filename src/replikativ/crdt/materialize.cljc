(ns replikativ.crdt.materialize
  (:require [konserve.core :as k]
            [replikativ.crdt :refer [map->CDVCS]]
            [replikativ.crdt.cdvcs.impl] ;; loading protocol extensions for CDVCS
            #?(:clj [full.async :refer [<? go-try throw-if-throwable]]
                    :cljs [full.cljs.async :refer [throw-if-throwable]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! go put! go-loop sub unsub pub close!]]
                    :cljs [cljs.core.async :as async
                           :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [full.cljs.async :refer [<? go-try]])))

;; incognito handlers
(def crdt-read-handlers {'replikativ.crdt.CDVCS map->CDVCS})

(def crdt-write-handlers {})


(defmulti key->crdt "This is needed to instantiate records of the CRDT
  type where their protocols are needed. This is somewhat redundant,
  but this multimethod is only here to allow the definition of such
  external constructors." identity)

(defmethod key->crdt :repo
  [_]
  (go-try (map->CDVCS {:version 1})))


(defmethod key->crdt :default
  [crdt-type]
  (go-try (throw (ex-info "Cannot materialize CRDT for publication."
                          {:crdt-type crdt-type}))))


(defn ensure-crdt [store [user crdt-id] pub]
  (go-try (or (<? (k/get-in store [[user crdt-id] :state]))
              (<? (key->crdt (:crdt pub))))))
