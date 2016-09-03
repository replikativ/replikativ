(ns replikativ.crdt.lwwr.core
  (:require [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id store-blob-trans]]
            [replikativ.protocols :refer [PExternalValues]]
            [kabel.platform-log :refer [debug info]]
            [replikativ.crdt :refer [map->LWWR]]
            [replikativ.crdt.utils :refer [extract-crdts]]))

(defn create-lwwr
  "Create new last writer wins register"
  [& {:keys [init-val]}]
  (let [new-state {:register init-val
                   :timestamp (java.util.Date. 1970)}]
    {:state (map->LWWR new-state)
     :prepared []
     :downstream {:crdt :lwwr
                  :op (assoc new-state :method :handshake)}
     :new-values {}}))

(defn set-register
  "Sets register value"
  [lwwr register]
  (let [now (java.util.Date.)]
    (-> lwwr
       (assoc-in [:state :register] register)
       (assoc-in [:state :timestamp] now)
       (assoc :downstream {:crdt :lwwr
                           :op {:register register
                                :timestamp now}}))))

(defn downstream
  "Downstream operations applied to lwwr state"
  [{:keys [register timestamp] :as lwwr}
   {op-register :register op-timestamp :timestamp :as op}]
  (if timestamp
    (let [time-diff (- (.getTime op-timestamp) (.getTime timestamp))]
      (-> lwwr
          (update-in [:register]
                     (fn [old new]
                       (if (>= time-diff 0) new old))
                     op-register)
          (update-in [:timestamp]
                     (fn [old new]
                       (if (>= time-diff 0) new old))
                     op-timestamp)))
    (-> lwwr
        (assoc-in [:register] op-register)
        (assoc-in [:timestamp] op-timestamp))))
