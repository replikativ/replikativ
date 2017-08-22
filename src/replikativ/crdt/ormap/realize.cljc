(ns replikativ.crdt.ormap.realize
  "Functions to realize the value represented by a reduction over the
  entries in OR-Map."
(:require [clojure.set :as set]
          [konserve.core :as k]
          [konserve.memory :refer [new-mem-store]]
          [replikativ.environ :refer [store-blob-trans-id store-blob-trans-value store-blob-trans]]
          [replikativ.protocols :refer [-downstream -handshake]]
          [replikativ.realize :as real]
          [replikativ.crdt.materialize :refer [ensure-crdt]]
          [replikativ.crdt.ormap.core :as core]
          [replikativ.crdt.ormap.stage :as ors]
          #?(:clj [kabel.platform-log :refer [debug info warn]])
          #?(:clj [superv.async :refer [<? go-try go-loop-super >?]])
          #?(:clj [clojure.core.async :as async
                    :refer [>! timeout chan alt! put! sub unsub pub close! go-loop]]
              :cljs [cljs.core.async :as async
                    :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [<? go-try go-loop-super >?]]
                            [kabel.platform-log :refer [debug info warn]])))


(defn commit-history [ormap {:keys [adds removals]}]
  ;; do not add if already removed
  (let [adds (merge-with (fn [a r]
                           (reduce dissoc a (keys r)))
                         adds
                         removals)]
    (concat
     (for [[k uid->cid] adds
           [uid cid] uid->cid]
       cid)
     (for [[k uid->cid] removals
           [uid cid] uid->cid
           ;; (re-)add other entry if one exists
           :let [[ouid other] (first (dissoc (get-in ormap [:adds k]) uid))]
           ;; do not readd if already added in this run
           :when (not (get-in adds [k ouid]))]
       (or other cid)))))


(defn new-conflicts
  "Ormap already must have the op applied, returns a list of conflicts."
  [ormap {:keys [adds removals] :as op}]
  (let [adds (merge-with (fn [a r]
                           (reduce dissoc a (keys r)))
                         adds
                         removals)]
    (->> (for [[k _] adds
               :let [vs (core/or-get {:state ormap} k)]
               :when (>= (count vs) 2)]
           [k vs])
         (reduce #(assoc %1 (first %2) (second %2))
                 {}))))


(defn stream-into-identity!
  "Streaming due to the OR-Map. During a conflict different replicas might see
  different values for a key, but once you resolve the conflict for the key, all
  replicas converge. You can provide a conflict callback which is called with a
  map of {key #{assoc-trans1 assoc-trans2}}. You can use this for deterministic
  conflict resolution. You should not resolve the conflicts differently on
  different peers inifinitely often or the system will diverge. The stream is
  not blocked by the conflict callback.


  WIP, different streaming semantics are very well possible."
  [stage [u id] eval-fn ident
   & {:keys [applied-log conflict-cb]}]
  (let [{{[p _] :chans
          :keys [store err-ch]
          S :supervisor} :volatile} @stage
        pub-ch (chan 10000)
        applied-ch (chan 10000)]
    (async/sub p :pub/downstream pub-ch)
    ;; stage is set up, now lets kick the update loop
    (go-try S
     ;; trigger an update for us if the crdt is already on stage
     ;; this ormap version is as far or ahead of the stage publications
     ;; (no gap in publication chain)
     (let [ormap (<? S (ensure-crdt S store (<? S (new-mem-store)) [u id] :ormap))]
       (when-not (empty? (:adds ormap))
         (put! pub-ch {:downstream {:method :handshake
                                    :crdt :ormap
                                    :op (-handshake ormap S)}
                       :user u :crdt-id id}))
       ;; fight method body too large exception (JVM) problem by anon-fn
       (go-loop-super S [{{{new-removals :removals
                               new-adds :adds :as op} :op
                              method :method}
                             :downstream :as pub
                             :keys [user crdt-id]} (<? S pub-ch)
                            ormap ormap
                            applied (if applied-log
                                      (<? S (k/reduce-log store applied-log set/union #{}))
                                      #{})]
                         #_(when pub
                           (debug {:event :streaming-ormap :id (:id pub)})
                           (cond (not (and (= user u)
                                           (= crdt-id id)))
                                 (recur (<? S pub-ch) ormap applied)

                                 :else
                                 (let [new-commits (filter (comp not applied)
                                                           (commit-history ormap op))
                                       ormap (-downstream ormap op)
                                       conflicts (new-conflicts ormap op)]
                                   (when (and conflict-cb (not (empty? conflicts)))
                                     (conflict-cb conflicts))
                                   (debug {:event :ormap-batch-update
                                           :count (+ (count new-adds) (count new-removals))})
                                   (when applied-log
                                     (<? S (k/append store applied-log (set new-commits))))
                                   (<? S (real/reduce-commits S store eval-fn
                                                              ident
                                                              new-commits))
                                   (>? S applied-ch pub)
                                   (recur (<? S pub-ch)
                                          ormap
                                          (set/union applied (set new-commits))))))) 
       ))
    {:close-ch pub-ch
     :applied-ch applied-ch}))

