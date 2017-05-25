(ns replikativ.js
  "Experimental JavaScript API."
  (:require [replikativ.peer :as peer]
            [replikativ.stage :as stage]
            [replikativ.crdt.ormap.stage :as ormap-stage]
            [replikativ.crdt.ormap.realize :as ormap-realize]
            [konserve.memory :as mem]
            [kabel.client :refer [client-connect!]]
            [cljs.core.async :refer [chan take! <! >!]]
            [superv.async :refer [S]])
  (:require-macros [superv.async :refer [go-loop-try go-try]]))

#_(defn on-node? []
  (and (exists? js/process)
       (exists? js/process.versions)
       (exists? js/process.versions.node)
       true))

(defn- promise [ch]
  (js/Promise.
   (fn [resolve reject]
     (try
       (take! ch resolve)
       (catch js/Error e (reject e))))))

(defn ^:export newMemStore
  []
  (promise (mem/new-mem-store)))

(defn ^:export clientPeer [store]
  (promise (peer/client-peer S store (chan))))

(defn ^:export connect
  [stage url]
  (promise (stage/connect! stage url)))

(defn ^:export createStage [user peer]
  (promise (stage/create-stage! user peer)))

(defn ^:export createOrMap [stage opts]
  (let [{:keys [description id]} (js->clj opts)]
    (promise (ormap-stage/create-ormap! stage :id id :description description))))

(defn ^:export associate
  [stage user crdt-id tx-key txs]
  (promise (ormap-stage/assoc! stage
                               [user (uuid crdt-id)]
                               tx-key
                               (map vec txs))))

(defn ^:export get
  [stage user crdt-id key]
  (promise (get stage [user crdt-id] key)))


(defn ^:export streamIntoIdentity [stage user crdt-id stream-eval-fns target]
  (ormap-realize/stream-into-identity! stage [user crdt-id] stream-eval-fns target))



#_(defn ^:export -main [& args]
  (.log js/console "Loading replikativ js code."))

;; TODO not sufficient, goog.global needs to be set to this on startup before core.async

(comment
  (when ^boolean js/COMPILED
    (set! js/goog.global js/global))
  (nodejs/enable-util-print!)
  (set! cljs.core/*main-cli-fn* -main)
  (set! (.-exports js/module) #js {:clientPeer clientPeer
                                   :connect connect
                                   :createStage createStage
                                   :newMemStore newMemStore
                                   :createOrMap createOrMap
                                   :associate associate
                                   :streamIntoIdentity streamIntoIdentity}))
