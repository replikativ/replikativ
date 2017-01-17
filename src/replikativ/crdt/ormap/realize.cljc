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
            #?(:clj [kabel.platform-log :refer [debug info warn]])
            #?(:clj [superv.async :refer [<? go-try go-loop-super >?]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [<? go-try go-loop-super >?]]
                            [kabel.platform-log :refer [debug info warn]])))

(defn commit-history [ormap]
  (for [[k uid->cid] (concat (:adds ormap) (:removals ormap))
        [uid cid] uid->cid]
    cid))


(defn stream-into-identity! [stage [u id] eval-fn ident
                             & {:keys [applied-log reset-fn]
                                :or {reset-fn reset!}}]
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
       (go-loop-super S [{{{new-removals :removals
                          new-adds :adds :as op} :op
                         method :method}
                        :downstream :as pub
                        :keys [user crdt-id]} (<? S pub-ch)
                       ormap ormap
                       applied (if applied-log
                                 (<? S (k/reduce-log store applied-log set/union #{}))
                                 #{})]
                      (when pub
                        (debug {:event :streaming-ormap :id (:id pub)})
                        (cond (not (and (= user u)
                                        (= crdt-id id)))
                              (recur (<? S pub-ch) ormap applied)

                              :else
                              (let [new-commits (filter (comp not applied)
                                                        (commit-history op))]
                                (when (> (+ (count new-adds) (count new-removals)) 1)
                                  (info {:event :ormap-batch-update :count (+ (count new-adds) (count new-removals))}))
                                (when applied-log
                                  (<? S (k/append store applied-log (set new-commits))))
                                (<? S (real/reduce-commits S store eval-fn
                                                           ident
                                                           new-commits))
                                (>? S applied-ch pub)
                                (recur (<? S pub-ch) ormap (set/union applied (set new-commits)))))))))
    {:close-ch pub-ch
     :applied-ch applied-ch}))
