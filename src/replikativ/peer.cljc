(ns replikativ.peer
  "Managing the peers which bind everything together."
  (:require [replikativ.crdt.materialize :refer [crdt-read-handlers crdt-write-handlers]]
            [replikativ.core :refer [wire]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.p2p.filter-subs :refer [filtered-subscriptions]]
            [konserve.core :as k]
            [kabel.peer :as peer]
            #?(:clj [kabel.platform :refer [create-http-kit-handler!]])
            [replikativ.platform-log :refer [debug info warn error]]
            #?(:clj [full.async :refer [<? go-try]]))
  #?(:cljs (:require-macros [full.cljs.async :refer [<? go-try]])))

(defn ensure-id [store id]
  (go-try
   (second
    (<? (k/update-in store [:peer-config]
                     (fn [{sid :id :as c}]
                       (assoc c
                              :id
                              (cond id id
                                    sid sid
                                    :else (*id-fn*)))))))))


(defn client-peer
  "Creates a client-side peer only."
  [store err-ch & {:keys [middleware read-handlers write-handlers id]
                   :or {middleware (comp (partial fetch store (atom {}) err-ch)
                                         ensure-hash
                                         filtered-subscriptions)
                        read-handlers {}
                        write-handlers {}}}]
  (go-try
   (let [{:keys [id]} (<? (ensure-id store id))
         peer (peer/client-peer id err-ch (comp wire middleware))]
     (swap! (:read-handlers store) merge crdt-read-handlers read-handlers)
     (swap! (:write-handlers store) merge crdt-write-handlers write-handlers)
     (swap! peer (fn [old] (assoc-in old [:volatile :store] store)))
     peer)))


#?(:clj
   (defn server-peer
     "Constructs a listening peer. You need to integrate
  the returned :handler to run it."
     [store err-ch uri & {:keys [middleware read-handlers write-handlers id handler]
                          :or {middleware (comp (partial fetch store (atom {}) err-ch)
                                                ensure-hash
                                                filtered-subscriptions)
                               read-handlers {}
                               write-handlers {}}}]
     (go-try
      (let [{:keys [id]} (<? (ensure-id store id))
            handler (if handler handler (create-http-kit-handler! uri err-ch id))
            peer (peer/server-peer handler id err-ch (comp wire middleware))]
        (swap! (:read-handlers store) merge crdt-read-handlers read-handlers)
        (swap! (:write-handlers store) merge crdt-write-handlers write-handlers)
        (swap! peer (fn [old] (assoc-in old [:volatile :store] store)))
        peer))))
