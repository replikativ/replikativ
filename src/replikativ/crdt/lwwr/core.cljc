(ns replikativ.crdt.lwwr.core
  (:require [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id store-blob-trans]]
            [replikativ.protocols :refer [PExternalValues]]
            [replikativ.crdt :refer [map->LWWR]]
            [replikativ.crdt.utils :refer [extract-crdts]]))

(defn create-lwwr
  "Create new last writer wins register"
  [& {:keys [init-val]}]
  (let [new-state {:register init-val
                   :timestamp #?(:clj (java.util.Date. 0)
                                 :cljs (js/Date. 0))}]
    {:state (map->LWWR new-state)
     :prepared []
     :downstream {:crdt :lwwr
                  :op (assoc new-state :method :handshake)}
     :new-values {}}))

(defn set-register
  "Sets register value"
  [lwwr register]
  (let [now #?(:clj (java.util.Date.) :cljs (js/Date.))]
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
    (let [time-diff (- (.getTime op-timestamp) (.getTime timestamp))
          a {:register op-register
             :timestamp op-timestamp}
          b {:register register
             :timestamp timestamp}
          current (cond (> time-diff 0) a
                        (< time-diff 0) b
                        ;; break ties deterministically (!)
                        :else (if (pos? (compare (pr-str a)
                                                 (pr-str b)))
                                a b))]
      (-> lwwr
          (assoc-in [:register] (:register current))
          (assoc-in [:timestamp] (:timestamp current))))
    (-> lwwr
        (assoc-in [:register] op-register)
        (assoc-in [:timestamp] op-timestamp))))
