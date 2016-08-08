(ns replikativ.crdt.simple-gset.core
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id store-blob-trans]]
            [replikativ.protocols :refer [PExternalValues]]
            [kabel.platform-log :refer [debug info]]
            [replikativ.crdt :refer [map->SimpleGSet]]
            [replikativ.crdt.utils :refer [extract-crdts]]))


(defn new-simple-gset
  []
  (let [new-state {:elements #{}}]
    {:state (map->SimpleGSet new-state)
     :prepared []
     :downstream {:crdt :simple-gset
                  :op (assoc new-state :method :handshake)}
     :new-values {}}))


(defn add
  [gset element]
  (-> gset
     (update-in [:state :elements] conj element)
     (update-in [:downstream :op :elements] conj element)))


(defn downstream
  [{:keys [elements] :as gset}
   {op-elements :elements :as op}]
  (update-in gset [:elements] set/union op-elements))


(comment

  (def gset (new-gset))

  (add gset 42)

  )
