(ns replikativ.crdt.simple-gset.core
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id store-blob-trans]]
            [replikativ.protocols :refer [PExternalValues]]
            [replikativ.crdt :refer [map->SimpleGSet]]
            [replikativ.crdt.utils :refer [extract-crdts]]))


(defn new-simple-gset
  "Create new simple growth set"
  []
  (let [new-state {:elements #{}}]
    {:state (map->SimpleGSet new-state)
     :prepared []
     :downstream {:crdt :simple-gset
                  :op (assoc new-state :method :handshake)}
     :new-values {}}))


(defn add
  "Add new element to the growth set"
  [gset element]
  (-> gset
     (update-in [:state :elements] conj element)
     (assoc :downstream {:crdt :simple-gset
                         :op {:elements #{element}}})))


(defn downstream
  [{:keys [elements] :as gset}
   {op-elements :elements :as op}]
  (update-in gset [:elements] set/union op-elements))
