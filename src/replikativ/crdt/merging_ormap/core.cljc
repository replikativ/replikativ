(ns replikativ.crdt.merging-ormap.core
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id store-blob-trans]]
            [replikativ.protocols :refer [PExternalValues]]
            #?(:clj [kabel.platform-log :refer [debug info]])
            [replikativ.crdt :refer [map->MergingORMap]]
            [replikativ.crdt.utils :refer [extract-crdts]])
  #?(:cljs (:require-macros [kabel.platform-log :refer [debug info]])))


;; TODO
;; - ensure same merge function on handshake

(def merge-map (atom {}))

(defn new-merging-ormap
  "Create a new map. You can provide an immutable(!) merge
  function code and function to resolve conflicts."
  [merge-code merge-fn]
  (swap! merge-map assoc
         merge-code (fn [a b]
                      (cond (not a) b
                            (not b) a
                            :else (merge-fn a b))))
  (let [new-state {:adds {}
                   :removals {}
                   :merge-code merge-code}]
    {:state (map->MergingORMap new-state)
     :prepared []
     :downstream {:crdt :merging-ormap
                  :op (assoc new-state :method :handshake)}
     :new-values {}}))


(defn or-get [ormap key]
  (let [adds (get-in ormap [:state :adds key])
        removals (get-in ormap [:state :removals key])
        cut (->> (set/difference (set (keys adds))
                                 (set (keys removals)))
                 (into (sorted-set)))]
    (when cut
      (reduce (@merge-map (get-in ormap [:state :merge-code]))
              nil
              (map (get-in ormap [:state :adds key]) cut)))))


(defn or-dissoc
  "Dissoc element in the map."
  ([ormap key author]
   (if-let [uids (set/difference (set (keys (get-in ormap [:state :adds key])))
                                 (set (keys (get-in ormap [:state :removals key]))))]
     (or-dissoc ormap key uids author)
     ormap))
  ([ormap key uids author]
   (let [{{:keys [merge-code]} :state} ormap
         up-state (update-in ormap [:state :removals key]
                             (fn [old]
                               (reduce #(assoc %1 %2 nil) old uids)))]
     (-> up-state
         ;; TODO use set unlike ormap
         (assoc :downstream {:crdt :merging-ormap
                             :op {:removals {key (get-in up-state [:state :removals key])}
                                  :merge-code merge-code}
                             :method :dissoc})))))


(defn or-assoc
  "Assoc element in the map. If the element exists, it will be merged
  with the value due to the merge function of this ormap."
  ([ormap key val author]
   (let [adds (get-in ormap [:state :adds key])]
     (or-assoc ormap key (or (ffirst adds) (*id-fn*)) val author)))
  ([ormap key uid val author]
   (let [{:keys [merge-code]} (:state ormap)
         up-state (update-in ormap [:state :adds key uid] (@merge-map merge-code) val)]
     (assoc up-state :downstream {:crdt :merging-ormap
                                  :op {:adds {key {uid
                                                   (get-in up-state [:state :adds
                                                                     key uid])}}
                                       :merge-code merge-code}
                                  :method :assoc})
     )))


(defn downstream
  [{:keys [adds removals merge-code] :as or-map}
   {op-adds :adds op-removals :removals op-merge-code :merge-code :as op}]
  ;; TODO purge removed values from add map
  (assoc or-map
         :adds (merge-with (partial merge-with (@merge-map (or merge-code op-merge-code)))
                           adds op-adds)
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
