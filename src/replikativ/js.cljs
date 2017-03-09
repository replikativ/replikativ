(ns replikativ.js
  "Experimental JavaScript API."
  (:require [replikativ.peer :as peer]
            [replikativ.stage :as stage]
            [replikativ.crdt.cdvcs.stage :as cs]
            [replikativ.crdt.cdvcs.realize :as real]
            [konserve.memory :as mem]
            [kabel.client :refer [client-connect!]]
            [cljs.core.async :refer [chan take!]]
            [cljs.nodejs :as nodejs]
            [superv.async :refer [S]])
  (:require-macros [superv.async :refer [go-loop-try go-try]]))

(defn on-node? []
  (and (exists? js/process)
       (exists? js/process.versions)
       (exists? js/process.versions.node)
       true))

(defn ^:export new_mem_store [cb]
  (take! (mem/new-mem-store) cb))

(defn ^:export client_peer [store cb]
  (take! (peer/client-peer S store (chan)) cb))

(defn ^:export connect [stage url cb]
  (take! (stage/connect! stage url) cb))

(defn ^:export create_stage [user peer cb]
  (take! (stage/create-stage! user peer) cb))

(defn- convert-crdt-map [crdt-map]
  (->> (for [[u crdts] crdt-map
             crdt crdts]
         [u (uuid crdt)])
       (reduce
        (fn [m [u crdt]]
          (update-in m [u]
                     #(conj (or % #{}) crdt)))
        {})))

(defn ^:export create_cdvcs [stage cb]
  (take! (cs/create-cdvcs! stage) (fn [id] (cb (.toString id)))))

(defn ^:export transact [stage user crdt-id txs cb]
  (take! (cs/transact! stage
                      [user (uuid crdt-id)]
                      (map vec txs))
         cb))

(defn ^:export head_value [stage eval-fns user cdvcs-id cb]
  (let [store (get-in @stage [:volatile :store])
        S (get-in @stage [:volatile :supervisor])]
    (take! (real/head-value S store (js->clj eval-fns)
                            (get-in @stage [user (uuid cdvcs-id) :state]))
           cb)))

(defn ^:export -main [& args]
  (.log js/console "Loading replikativ node code."))

;; TODO not sufficient, goog.global needs to be set to this on startup before core.async
(when ^boolean js/COMPILED
  (set! js/goog.global js/global))
(nodejs/enable-util-print!)
(set! cljs.core/*main-cli-fn* -main)
(set! (.-exports js/module) #js {:client_peer client_peer
                                 :connect connect
                                 :create_stage create_stage
                                 :new_mem_store new_mem_store
                                 :create_cdvcs create_cdvcs
                                 :transact transact
                                 :head_value head_value})
