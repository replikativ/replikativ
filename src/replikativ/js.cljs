(ns replikativ.js
  "Experimental JavaScript API."
  (:require [replikativ.peer :as peer]
            [replikativ.stage :as stage]
            [replikativ.crdt.lwwr.stage :as lwwr-stage]
            [replikativ.crdt.ormap.stage :as ormap-stage]
            [goog.net.WebSocket]
            [goog.Uri]
            [goog.events]
            [replikativ.crdt.ormap.realize :as ormap-realize]
            [replikativ.crdt.lwwr.realize :as lwwr-realize]
            [konserve.memory :as mem]
            [kabel.client :refer [client-connect!]]
            [cljs.core.async :refer [chan take! <! >!]]
            [superv.async :refer [S]]
            [taoensso.timbre :as timbre]
            [replikativ.crdt.lwwr.core :as lwwr]
            [replikativ.crdt.ormap.core :as ormap])
  (:require-macros [superv.async :refer [go-loop-try go-try]]))

(defn on-node? []
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

(defn ^:export createORMap [stage opts]
  (let [opts (js->clj opts)]
    (promise (ormap-stage/create-ormap! stage :id (get opts "id") :description (get opts "description")))))


(defn ^:export createLWWR [stage opts]
  (let [opts (js->clj opts)]
    (promise (lwwr-stage/create-lwwr! stage :id (get opts "id") :description (get opts "description")))))

(defn ^:export setRegister [stage user crdt-id register]
  (promise (lwwr-stage/set-register! stage [user crdt-id] (js->clj register))))

(defn ^:export associate
  [stage user crdt-id tx-key txs]
  (let [txs (js->clj txs)]
    (promise (ormap-stage/assoc! stage
                                 [user crdt-id]
                                 tx-key
                                 (mapv (comp js->clj vec) txs)))))


(defn ^:export getFromOrMap
  [stage user crdt-id key]
  (promise-convert (ormap-stage/get stage [user crdt-id] key)))


(defn eval-fns->js [eval-fns]
  (let [eval-fns (js->clj eval-fns)]
    (->> (for [[k v] eval-fns]
           [k (fn [S old params]
                ;; TODO: check params if binary
                (v S old (clj->js params)))])
         (reduce (fn [m [k v]] (assoc m k v)) {}))))


(defn ^:export streamORMapIntoIdentity [stage user crdt-id stream-eval-fns target]
  (ormap-realize/stream-into-identity! stage [user crdt-id] (eval-fns->js stream-eval-fns) target))

(defn ^:export streamLWWR [stage user crdt-id cb]
  (let [val-atom (atom nil)
        _ (add-watch val-atom :watcher (fn [_ _ _ new-state]
                                         (cb (clj->js new-state))))]
    (lwwr-realize/stream-into-atom! stage [user crdt-id] val-atom)))

(defn ^:export createUUID [s]
  (cljs.core/uuid s))

(defn ^:export toEdn [o] (js->clj o))

(defn ^:export hashIt [o] (hasch.core/uuid o))

(when ^boolean js/COMPILED
  (set! js/goog.global js/global))

(set! (.-exports js/module) #js {:clientPeer    clientPeer
                                 :connect       connect
                                 :onNode        on-node?
                                 :createStage   createStage
                                 :newMemStore   newMemStore
                                 :LWWR #js {:create createLWWR
                                            :stream streamLWWR
                                            :set setRegister}
                                 :ORMap         #js {:createORMap        createORMap
                                                     :streamIntoIdentity streamORMapIntoIdentity
                                                     :associate          associate}
                                 :clientConnect client-connect!
                                 :createUUID    cljs.core/uuid
                                 :toEdn         cljs.core/js->clj
                                 :hashIt        hasch.core/uuid})

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
  )
