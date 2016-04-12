(ns replikativ.js
  "Experimental JavaScript API."
  (:require [replikativ.peer :as peer]
            [replikativ.stage :as stage]
            [replikativ.crdt.cdvcs.stage :as cs]
            [konserve.js :as k]
            [cljs.core.async :refer [chan take!]]
            [cljs.nodejs :as nodejs]))


(defn ^:export client_peer [store cb]
  (take! (peer/client-peer store (chan)) cb))

(defn ^:export connect [stage url cb]
  (take! (stage/connect! stage url) cb))

(defn ^:export create_stage [user peer cb]
  (take! (stage/create-stage! user peer (chan)) cb))

(defn- convert-crdt-map [crdt-map]
  (->> (for [[u crdts] crdt-map
             crdt crdts]
         [u (uuid crdt)])
       (reduce
        (fn [m [u crdt]]
          (update-in m [u]
                     #(conj (or % #{}) crdt)))
        {})))

(defn ^:export subscribe_crdts [stage crdt-map cb]
  (let [crdt-map (-> crdt-map js->clj convert-crdt-map)]
    (take! (stage/subscribe-crdts! stage crdt-map) cb)))

(defn ^:export create_cdvcs [stage cb]
  (take! (cs/create-cdvcs! stage) (fn [id] (cb (.toString id)))))

(defn ^:export transact [stage user crdt-id trans-fn-code params cb]
  (take! (cs/transact stage
                      [user (uuid crdt-id)]
                      trans-fn-code params)
         cb))

(defn ^:export commit [stage cdvcs-map cb]
  (take! (cs/commit! stage cdvcs-map) cb))


;; detect and setup node environment
(when-not (exists? js/window)
  (.log js/console "Loading replikativ node code.")
  (nodejs/enable-util-print!)
  (set! cljs.core/*main-cli-fn* (fn []))
  (set! (.-exports js/module) #js {:client_peer client_peer
                                   :connect connect
                                   :create_stage create_stage}))
