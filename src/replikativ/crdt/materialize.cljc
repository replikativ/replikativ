(ns replikativ.crdt.materialize
  (:require [konserve.core :as k]
            [replikativ.crdt :refer [map->CDVCS map->SimpleGSet map->ORMap map->LWWR
                                     map->MergingORMap]]
            [replikativ.protocols :refer [-downstream -handshake]]
            ;; loading protocol extensions
            [replikativ.crdt.cdvcs.impl] 
            [replikativ.crdt.simple-gset.impl]
            [replikativ.crdt.ormap.impl]
            [replikativ.crdt.merging-ormap.impl]
            [replikativ.crdt.lwwr.impl]
            #?(:clj [superv.async :refer [<? go-try]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! go put! go-loop sub unsub pub close!]]
                    :cljs [cljs.core.async :as async
                           :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [<? go-try]])))

;; incognito handlers
(def crdt-read-handlers {'replikativ.crdt.CDVCS map->CDVCS
                         'replikativ.crdt.SimpleGSet map->SimpleGSet
                         'replikativ.crdt.LWWR map->LWWR
                         'replikativ.crdt.ORMap map->ORMap})

(def crdt-write-handlers {})


(defmulti key->crdt "This is needed to instantiate records of the CRDT
  type where their protocols are needed. This is somewhat redundant,
  but this multimethod is only here to allow the definition of such
  constructors for empty (bootstrapped) CRDTs externally." identity)

(defmethod key->crdt :cdvcs
  [_]
  (map->CDVCS {:version 1}))

(defmethod key->crdt :simple-gset
  [_]
  (map->SimpleGSet {:version 1}))

(defmethod key->crdt :ormap
  [_]
  (map->ORMap {:version 1}))

(defmethod key->crdt :lwwr
  [_]
  (map->LWWR {:version 1}))

(defmethod key->crdt :merging-ormap
  [_]
  (map->MergingORMap {:version 1}))

(defmethod key->crdt :default
  [crdt-type]
  (throw (ex-info "Cannot materialize CRDT for publication."
                  {:crdt-type crdt-type})))


;; TODO refactor and move
(defn get-crdt [S cold-store mem-store [user crdt-id]]
  (go-try S
   (let [mem-val (<? S (k/get-in mem-store [[user crdt-id]]))]
     (if mem-val mem-val
         (let [[_ last-id first-id] (<? S (k/get-in cold-store [[user crdt-id :log]]))
               ;; last log entry
               {{:keys [crdt]} :elem
                prev :prev}
               (<? S (k/get-in cold-store [first-id]))]
           (when crdt
             (let [cold-val (<? S (k/reduce-log cold-store
                                              [user crdt-id :log]
                                              (fn [acc pub]
                                                (-downstream acc (:op pub)))
                                              (key->crdt crdt)))
                   new-val (second (<? S (k/update-in mem-store [[user crdt-id] :state]
                                                    (fn [old]
                                                      (if old old
                                                          cold-val)))))]
               {:crdt crdt
                :state new-val})))))))

(defn ensure-crdt [S cold-store store [user crdt-id] type]
  (go-try S (if-let [s (:state (<? S (get-crdt S cold-store store [user crdt-id])))]
            s
            (key->crdt type))))
