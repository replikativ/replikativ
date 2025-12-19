(ns replikativ.peer
  "Managing the peers which bind everything together."
  (:require [replikativ.crdt.materialize :refer [crdt-read-handlers crdt-write-handlers]]
            [replikativ.environ :refer [*id-fn* store-blob-trans-id store-blob-trans-value]]
            [replikativ.pubsub :as rpubsub]
            [konserve.core :as k]
            [konserve.memory :refer [new-mem-store]]
            [kabel.peer :as peer]
            #?(:clj [kabel.http-kit :refer [create-http-kit-handler!]])
            #?(:clj [kabel.platform-log :refer [debug info warn error]])
            #?(:clj [superv.async :refer [<? go-try]])
            #?(:cljs [clojure.core.async :as async :include-macros true])
            #?(:cljs [superv.async :refer [<? go-try] :include-macros true]))
  #?(:cljs (:require-macros [kabel.platform-log :refer [debug info warn error]])))

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
  "Creates a client-side peer using pubsub-based sync.

   Options:
   - :read-handlers - Custom transit read handlers
   - :write-handlers - Custom transit write handlers
   - :id - Peer ID (default: generated UUID)
   - :with-fetch? - Use fetch middleware for lazy loading (default: false)"
  [S cold-store & {:keys [read-handlers write-handlers id with-fetch?]
                   :or {read-handlers {}
                        write-handlers {}
                        id (*id-fn*)
                        with-fetch? false}}]
  (go-try S
   (let [mem-store (<? S (new-mem-store))
         middleware (if with-fetch?
                      (rpubsub/fetch-pubsub-middleware)
                      (rpubsub/pubsub-middleware))
         {:keys [id]} (<? S (ensure-init S cold-store id))
         peer (peer/client-peer S id middleware identity)]
     (swap! (:read-handlers cold-store) merge crdt-read-handlers read-handlers)
     (swap! (:write-handlers cold-store) merge crdt-write-handlers write-handlers)
     (swap! (:read-handlers mem-store) merge crdt-read-handlers read-handlers)
     (swap! (:write-handlers mem-store) merge crdt-write-handlers write-handlers)
     (swap! peer (fn [old]
                   (-> old
                       (assoc-in [:volatile :supervisor] S)
                       (assoc-in [:volatile :cold-store] cold-store)
                       (assoc-in [:volatile :mem-store] mem-store))))
     peer)))

#?(:clj
   (defn server-peer
     "Constructs a listening peer using pubsub-based sync.
      You need to integrate [:volatile :handler] into your http-kit to run it.

     Options:
     - :read-handlers - Custom transit read handlers
     - :write-handlers - Custom transit write handlers
     - :id - Peer ID (default: generated UUID)
     - :handler - Custom HTTP handler (default: http-kit handler)
     - :with-fetch? - Use fetch middleware for lazy loading (default: false)"
     [S cold-store uri & {:keys [read-handlers write-handlers id handler with-fetch?]
                          :or {read-handlers {}
                               write-handlers {}
                               id (*id-fn*)
                               with-fetch? false}}]
     (go-try S
      (let [mem-store (<? S (new-mem-store))
            middleware (if with-fetch?
                         (rpubsub/fetch-pubsub-middleware)
                         (rpubsub/pubsub-middleware))
            {:keys [id]} (<? S (ensure-init S cold-store id))
            handler (if handler handler (create-http-kit-handler! S uri id))
            peer (peer/server-peer S handler id middleware identity)]
        (swap! (:read-handlers cold-store) merge crdt-read-handlers read-handlers)
        (swap! (:write-handlers cold-store) merge crdt-write-handlers write-handlers)
        (swap! (:read-handlers mem-store) merge crdt-read-handlers read-handlers)
        (swap! (:write-handlers mem-store) merge crdt-write-handlers write-handlers)
        (swap! peer (fn [old]
                      (-> old
                          (assoc-in [:volatile :supervisor] S)
                          (assoc-in [:volatile :cold-store] cold-store)
                          (assoc-in [:volatile :mem-store] mem-store))))
        peer))))


;; Backwards compatibility aliases
(def client-peer-pubsub client-peer)
#?(:clj (def server-peer-pubsub server-peer))
