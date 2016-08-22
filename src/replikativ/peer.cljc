(ns replikativ.peer
  "Managing the peers which bind everything together."
  (:require [replikativ.crdt.materialize :refer [crdt-read-handlers crdt-write-handlers]]
            [replikativ.environ :refer [*id-fn* store-blob-trans-id store-blob-trans-value]]
            [replikativ.core :refer [wire]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [konserve.core :as k]
            [kabel.peer :as peer]
            #?(:clj [kabel.http-kit :refer [create-http-kit-handler!]])
            [kabel.platform-log :refer [debug info warn error]]
            #?(:clj [full.async :refer [<? go-try]]))
  #?(:cljs (:require-macros [full.async :refer [<? go-try]])))

(defn ensure-init [store id]
  (go-try
   (<? (k/assoc-in store [store-blob-trans-id] store-blob-trans-value))
   (second
    (<? (k/update-in store [:peer-config]
                     (fn [{{subs :subscriptions} :sub sid :id :as c}]
                       (-> c
                           (assoc :id (cond id id
                                            sid sid
                                            :else (*id-fn*)))
                           (assoc-in [:sub :subscriptions] (or subs {})))))))))


(defn client-peer
  "Creates a client-side peer only."
  [store & {:keys [middleware read-handlers write-handlers id extend-subs?]
            :or {middleware (comp (partial fetch store)
                                  ensure-hash)
                 read-handlers {}
                 write-handlers {}
                 id (*id-fn*)
                 extend-subs? false}}]
  (go-try
   (let [{:keys [id]} (<? (ensure-init store id))
         peer (peer/client-peer id (comp wire middleware))]
     (<? (k/assoc-in store [:peer-config :sub :extend?] extend-subs?))
     (swap! (:read-handlers store) merge crdt-read-handlers read-handlers)
     (swap! (:write-handlers store) merge crdt-write-handlers write-handlers)
     (swap! peer (fn [old] (assoc-in old [:volatile :store] store)))
     peer)))


#?(:clj
   (defn server-peer
     "Constructs a listening peer. You need to integrate
  [:volatile :handler] into your http-kit to run it."
     [store uri & {:keys [middleware read-handlers write-handlers id handler extend-subs?]
                   :or {middleware (comp (partial fetch store)
                                         ensure-hash)
                        read-handlers {}
                        write-handlers {}
                        if (*id-fn*)
                        extend-subs? true}}]
     (go-try
      (let [{:keys [id]} (<? (ensure-init store id))
            handler (if handler handler (create-http-kit-handler! uri id))
            peer (peer/server-peer handler id (comp wire middleware))]
        (<? (k/assoc-in store [:peer-config :sub :extend?] extend-subs?))
        (swap! (:read-handlers store) merge crdt-read-handlers read-handlers)
        (swap! (:write-handlers store) merge crdt-write-handlers write-handlers)
        (swap! peer (fn [old] (assoc-in old [:volatile :store] store)))
        peer))))
