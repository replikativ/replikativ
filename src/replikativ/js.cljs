(ns replikativ.js
  "Experimental JavaScript API."
  (:require [replikativ.peer :as peer]
            [replikativ.stage :as stage]
            [replikativ.crdt.ormap.stage :as ormap-stage]
            [replikativ.crdt.ormap.realize :as ormap-realize]
            [konserve.memory :as mem]
            [kabel.client :refer [client-connect!]]
            [cljs.core.async :refer [chan take! <! >!]]
            [superv.async :refer [S]]
            [replikativ.crdt.ormap.core :as ormap])
  (:require-macros [superv.async :refer [go-loop-try go-try]]))

(defn- promise [ch]
  (js/Promise.
   (fn [resolve reject]
     (try
       (take! ch resolve)
       (catch js/Error e (reject e))))))

(defn- promise-convert [ch]
  (js/Promise.
   (fn [resolve reject]
     (try
       (take! ch (fn [result] (resolve (clj->js result))))
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
  (let [opts (js->clj opts)]
    (promise (ormap-stage/create-ormap! stage :id (get opts "id") :description (get opts "description")))))

(defn ^:export associate
  [stage user crdt-id tx-key txs]
  (let [txs (js->clj txs)]
    (promise (ormap-stage/assoc! stage
                                 [user crdt-id]
                                 tx-key
                                 (mapv vec txs)))))

(defn ^:export getFromOrMap
  [stage user crdt-id key]
  (promise-convert (ormap-stage/get stage [user crdt-id] key)))


(defn eval-fns->js [eval-fns]
  (let [eval-fns (js->clj eval-fns)]
    (->> (for [[k v] eval-fns]
           [k (fn [old params]
                ;; TODO: check params if binary
                (v old (clj->js params)))])
         (reduce (fn [m [k v]] (assoc m k v)) {}))))


(defn ^:export streamIntoIdentity [stage user crdt-id stream-eval-fns target]
  (ormap-realize/stream-into-identity! stage [user crdt-id] (eval-fns->js stream-eval-fns) target))


(comment
  (defn on-node? []
    (and (exists? js/process)
         (exists? js/process.versions)
         (exists? js/process.versions.node)
         true))
  (defn ^:export -main [& args]
    (.log js/console "Loading replikativ js code."))
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
