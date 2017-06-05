(ns replikativ.peer
  "Managing the peers which bind everything together."
  (:require [replikativ.crdt.materialize :refer [crdt-read-handlers crdt-write-handlers]]
            [replikativ.environ :refer [*id-fn* store-blob-trans-id store-blob-trans-value]]
            [replikativ.core :refer [wire]]
            [replikativ.p2p.fetch :refer [fetch]]
            [konserve.core :as k]
            [konserve.memory :refer [new-mem-store]]
            [kabel.peer :as peer]
            [kabel.middleware.transit :refer [transit]]
            #?(:clj [kabel.http-kit :refer [create-http-kit-handler!]])
            #?(:clj [kabel.platform-log :refer [debug info warn error]])
            #?(:clj [superv.async :refer [<? go-try]]))
  #?(:cljs (:require-macros [superv.async :refer [<? go-try]]
                            [kabel.platform-log :refer [debug info warn error]])))

(defn ensure-init [S store id]
  (go-try S
   (<? S (k/assoc-in store [store-blob-trans-id] store-blob-trans-value))
   (second
    (<? S (k/update-in store [:peer-config]
                     (fn [{{subs :subscriptions} :sub sid :id :as c}]
                       (-> c
                           (assoc :id (cond id id
                                            sid sid
                                            :else (*id-fn*)))
                           (assoc-in [:sub :subscriptions] (or subs {})))))))))


(defn client-peer
  "Creates a client-side peer only."
  [S cold-store & {:keys [middleware read-handlers write-handlers id extend-subs?]
                   :or {read-handlers {}
                        write-handlers {}
                        id (*id-fn*)
                        extend-subs? false}}]
  (go-try S
   (let [mem-store (<? S (new-mem-store))
         middleware (or middleware fetch)
         {:keys [id]} (<? S (ensure-init S cold-store id))
         peer (peer/client-peer S id (comp wire middleware))]
     (<? S (k/assoc-in cold-store [:peer-config :sub :extend?] extend-subs?))
     (swap! (:read-handlers cold-store) merge crdt-read-handlers read-handlers)
     (swap! (:write-handlers cold-store) merge crdt-write-handlers write-handlers)
     (swap! (:read-handlers mem-store) merge crdt-read-handlers read-handlers)
     (swap! (:write-handlers mem-store) merge crdt-write-handlers write-handlers)
     (swap! peer (fn [old]
                   (-> old
                       (assoc-in [:volatile :cold-store] cold-store)
                       (assoc-in [:volatile :mem-store] mem-store))))
     peer)))


#?(:clj
   (defn server-peer
     "Constructs a listening peer. You need to integrate
  [:volatile :handler] into your http-kit to run it."
     [S cold-store uri & {:keys [middleware read-handlers write-handlers id handler extend-subs?]
                          :or {read-handlers {}
                               write-handlers {}
                               if (*id-fn*)
                               extend-subs? true}}]
     (go-try S
      (let [mem-store (<? S (new-mem-store))
            middleware (or middleware fetch)
            {:keys [id]} (<? S (ensure-init S cold-store id))
            handler (if handler handler (create-http-kit-handler! S uri id))
            peer (peer/server-peer S handler id (comp wire middleware))]
        (<? S (k/assoc-in cold-store [:peer-config :sub :extend?] extend-subs?))
        (swap! (:read-handlers cold-store) merge crdt-read-handlers read-handlers)
        (swap! (:write-handlers cold-store) merge crdt-write-handlers write-handlers)
        (swap! (:read-handlers mem-store) merge crdt-read-handlers read-handlers)
        (swap! (:write-handlers mem-store) merge crdt-write-handlers write-handlers)
        (swap! peer (fn [old]
                      (-> old
                          (assoc-in [:volatile :cold-store] cold-store)
                          (assoc-in [:volatile :mem-store] mem-store))))
        peer))))


