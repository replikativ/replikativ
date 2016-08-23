(ns replikativ.crdt.simple-ormap.core
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id store-blob-trans]]
            [replikativ.protocols :refer [PExternalValues]]
            [kabel.platform-log :refer [debug info]]
            [replikativ.crdt :refer [map->SimpleORMap]]
            [replikativ.crdt.utils :refer [extract-crdts]]))


(defn new-simple-ormap
  "Create new simple growth map."
  []
  (let [new-state {:adds {}
                   :removals {}}]
    {:state (map->SimpleORMap new-state)
     :prepared []
     :downstream {:crdt :simple-ormap
                  :op (assoc new-state :method :handshake)}
     :new-values {}}))


(defn or-get [ormap key]
  (let [adds (get-in ormap [:state :adds key])
        removals (get-in ormap [:state :removals key])
        [cut] (->> (set/difference (set (keys adds))
                                   removals)
                   (into (sorted-set))
                   seq)]
    (when cut
      (get-in ormap [:state :adds key cut]))))


(defn or-dissoc
  "Dissoc element in the map."
  ([ormap key]
   (if-let [uids (keys (get-in ormap [:state :adds key]))]
     (let [uids (set uids)]
       (-> ormap
           (update-in [:state :removals key] set/union uids)
           (assoc :downstream {:crdt :simple-ormap
                               :op {:removals {key uids}}
                               :method :dissoc})))
     ormap)))


(defn or-assoc
  "Assoc element in the map."
  ([ormap key val]
   (if (= (or-get ormap key) val)
     ormap
     (or-assoc ormap key (*id-fn*) val)))
  ([ormap key uid val]
   (-> ormap
       (or-dissoc key)
       (assoc-in [:state :adds key uid] val)
       ;; depends on dissoc downstream map
       (assoc :downstream {:crdt :simple-ormap
                           :op {:adds {key {uid val}}}
                           :method :assoc}))))


(defn downstream
  [{:keys [adds removals] :as or-map}
   {op-adds :adds op-removals :removals :as op}]
  ;; TODO purge removed values from add map
  (assoc or-map
         :adds (merge-with merge adds op-adds)
         :removals (merge-with set/union removals op-removals)))


(comment

  (def ormap (new-simple-ormap))

  (or-get (or-assoc ormap 12 42)
          12)

  (-> ormap
      (or-assoc 12 42)
      (or-dissoc 12)
      (or-get 12))

  (-> ormap
      (or-assoc 12 42)
      (or-assoc 12 44)
      (or-get 12))

  (downstream (:state ormap) (get-in (-> ormap (or-assoc 12 42)) [:downstream :op]))
  (downstream (:state ormap) (get-in (-> ormap (or-assoc 12 42) (or-dissoc 12)) [:downstream :op]))

  )
