(ns replikativ.peer
  "Managing the peers which bind everything together."
  (:require [replikativ.crdt.materialize :refer [crdt-read-handlers crdt-write-handlers]]
            [replikativ.core :refer [wire]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [kabel.peer :as peer]
            [replikativ.platform-log :refer [debug info warn error]]))


(defn client-peer
  "Creates a client-side peer only."
  [name store err-ch & {:keys [middleware read-handlers write-handlers]
                        :or {middleware (comp (partial fetch store (atom {}) err-ch)
                                              ensure-hash)
                             read-handlers {}
                             write-handlers {}}}]
  (let [peer (peer/client-peer name err-ch (comp wire middleware))]
    (swap! (:read-handlers store) merge crdt-read-handlers read-handlers)
    (swap! (:write-handlers store) merge crdt-write-handlers write-handlers)
    (swap! peer (fn [old]
                  (-> old
                      (assoc-in [:volatile :store] store)
                      (assoc-in [:subscriptions] {}))))
    peer))


(defn server-peer
  "Constructs a listening peer. You need to integrate
  the returned :handler to run it."
  [handler name store err-ch & {:keys [middleware read-handlers write-handlers]
                                :or {middleware (comp (partial fetch store (atom {}) err-ch)
                                                      ensure-hash)
                                     read-handlers {}
                                     write-handlers {}}}]
  (let [peer (peer/server-peer handler name err-ch (comp wire middleware))]
    (swap! (:read-handlers store) merge crdt-read-handlers read-handlers)
    (swap! (:write-handlers store) merge crdt-write-handlers write-handlers)
    (swap! peer (fn [old]
                  (-> old
                      (assoc-in [:volatile :store] store)
                      (assoc-in [:subscriptions] {}))))
    peer))
