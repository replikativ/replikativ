(ns replikativ.crdt.ormap.core
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id store-blob-trans]]
            [replikativ.protocols :refer [PExternalValues]]
            #?(:clj [kabel.platform-log :refer [debug info]])
            [replikativ.crdt :refer [map->ORMap]]
            [replikativ.crdt.utils :refer [extract-crdts]])
  #?(:cljs (:require-macros [kabel.platform-log :refer [debug info]])))





(defn new-ormap
  "Create a new map."
  []
  (let [new-state {:adds {}
                   :removals {}}]
    {:state (map->ORMap new-state)
     :prepared []
     :downstream {:crdt :ormap
                  :op (assoc new-state :method :handshake)}
     :new-values {}}))

(defn or-get [ormap key]
  (let [adds (get-in ormap [:state :adds key])
        removals (get-in ormap [:state :removals key])
        cut (->> (set/difference (set (keys adds))
                                 (set (keys removals)))
                 (into (sorted-set)))]
    (when cut
      (set (map (get-in ormap [:state :adds key]) cut)))))


(defn or-dissoc
  "Dissoc element in the map."
  ([ormap key revert-txs author]
   (if-let [uids (set/difference (set (keys (get-in ormap [:state :adds key])))
                                 (set (keys (get-in ormap [:state :removals key]))))]
     (cond (zero? (count uids))
           ormap

           (= (count uids) 1)
           (or-dissoc ormap key (first uids) revert-txs author)

           :else
           (throw (ex-info "Multiple keys to remove. Pick uid directly." {:uids uids})))
     ormap))
  ([ormap key uid revert-txs author]
   (let [trans-ids (mapv (fn [[trans-fn params]]
                           [(*id-fn* trans-fn) (*id-fn* params)]) revert-txs)
         commit-value {:transactions trans-ids
                       :ts (*date-fn*)
                       :author author
                       :uid uid
                       :version 1
                       :crdt :ormap}
         id (*id-fn* (select-keys commit-value #{:transactions}))
         new-values (clojure.core/merge
                     {id commit-value}
                     (zipmap (apply concat trans-ids)
                             (apply concat revert-txs)))]
     (-> ormap
         (assoc-in [:state :removals key uid] id)
         (assoc :downstream {:crdt :ormap
                             :op {:removals {key {uid id}}}
                             :method :dissoc}
                :new-values new-values)))))


(defn or-assoc
  "Assoc element in the map."
  ([ormap key txs author]
   (or-assoc ormap key (*id-fn*) txs author))
  ([ormap key uid txs author]
   #_(when-not (empty? (or-get ormap key))
     (throw (ex-info "Element exists." {:key key})))
   (let [trans-ids (mapv (fn [[trans-fn params]]
                           [(*id-fn* trans-fn) (*id-fn* params)]) txs)
         commit-value {:transactions trans-ids
                       :ts (*date-fn*)
                       :author author
                       :uid uid
                       :version 1
                       :crdt :ormap}
         id (*id-fn* (select-keys commit-value #{:transactions}))
         new-values (clojure.core/merge
                     {id commit-value}
                     (zipmap (apply concat trans-ids)
                             (apply concat txs)))]
     (-> ormap 
         (assoc-in [:state :adds key uid] id)
         ;; depends on dissoc downstream map
         (assoc :downstream {:crdt :ormap
                             :op {:adds {key {uid id}}}
                             :method :assoc}
                :new-values new-values)))))


(defn downstream
  [{:keys [adds removals] :as or-map}
   {op-adds :adds op-removals :removals :as op}]
  ;; TODO purge removed values from add map
  (assoc or-map
         :adds (merge-with merge adds op-adds)
         :removals (merge-with merge removals op-removals)))


(comment

  (def ormap (new-ormap))

  (let [as (or-assoc ormap 12 [['+ 42]] "john")
        [cid] (seq (or-get as 12))]
    ((:new-values as) cid))

  (or-assoc ormap 12 [['+ 42]] "john")

  (-> ormap
      (or-assoc 12 42)
      (or-dissoc 12)
      (or-get 12))

  (-> ormap
      (or-assoc 12 42)
      (or-assoc 12 44)
      (or-get 12))

  (downstream (:state ormap) (get-in (-> ormap (or-assoc 12 [['+ 42]] "john")) [:downstream :op]))
  (downstream (:state ormap) (get-in (-> ormap (or-assoc 12 [['+ 42]] "john")
                                         (or-dissoc 12 [['- 42]] "john")) [:downstream :op]))

  (downstream
   (downstream (:state ormap)
               (get-in (-> ormap (or-assoc 12 [['+ 42]] "john")) [:downstream :op]))
   (get-in (-> ormap (or-assoc 12 [['+ 42]] "john")
               (or-dissoc 12 [['- 42]] "john")) [:downstream :op]))

  )
