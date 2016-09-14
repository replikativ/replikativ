(ns replikativ.crdt.cdvcs.impl
  "Implementation of the CRDT replication protocol."
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id]]
            [replikativ.protocols :refer [POpBasedCRDT -downstream
                                          PExternalValues -missing-commits -commit-value
                                          PPullOp -pull]]
            [kabel.platform-log :refer [debug info error]]
            #?(:clj [full.async :refer [go-try go-loop-try <? <<?]])
            #?(:clj [full.lab :refer [go-for]])
            [replikativ.crdt.cdvcs.core :refer [multiple-heads? pull]]
            [replikativ.crdt.cdvcs.meta :refer [downstream]]
            [konserve.core :as k]
            #?(:clj [clojure.core.async :as async
                    :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [full.async :refer [go-try go-loop-try <? <<?]]
                            [full.lab :refer [go-for]])))


;; fetching related ops
(defn- all-commits
  [commit-graph]
  (keys commit-graph))

(defn- missing-commits [store cdvcs op]
  (let [missing (all-commits (:commit-graph op))
        #_(set/difference (all-commits (:commit-graph op))
                          (all-commits (:commit-graph cdvcs)))]
    (go-loop-try [not-in-store #{}
                  [f & r] (seq missing)]
                 (if f
                   (recur (if (and (not (get (:commit-graph cdvcs) f))
                                   (not (<? (k/exists? store f))))
                            (conj not-in-store f)
                            not-in-store)
                          r)
                   not-in-store))))


;; pull-hook
(defn inducing-conflict-pull!? [atomic-pull-store [user cdvcs] pulled-op b-cdvcs]
  (go-try
   (let [[old new] (<? (k/update-in atomic-pull-store [user cdvcs]
                                    ;; ensure updates inside atomic swap
                                    #(cond (not %) b-cdvcs
                                           (multiple-heads?
                                            (-downstream % pulled-op)) %
                                           :else (-downstream % pulled-op))))]
     ;; not perfectly elegant to reconstruct the value of inside the transaction, but safe
     (when (= old new) (not= (-downstream old pulled-op) new)))))


(defn pull-cdvcs!
  [store atomic-pull-store
   [[a-user _ a-cdvcs]
    [b-user b-cdvcs-id b-cdvcs]
    integrity-fn
    allow-induced-conflict?]]
  (go-try
   (let [{:keys [commit-graph heads]} a-cdvcs
         [head-a head-b] (seq heads)]
     (cond head-b
           (do (debug "Cannot pull from conflicting CDVCS: " a-cdvcs ": " heads)
               :rejected)

           (not (:commit-graph b-cdvcs))
           (do (debug "Pulling into empty CDVCS: " b-cdvcs)
               {:crdt :cdvcs
                :op {:method :pull
                     :version 1
                     :commit-graph commit-graph
                     :heads heads}})

           :else
           (let [pulled (try
                          (pull {:state b-cdvcs} a-cdvcs head-a allow-induced-conflict? false)
                          (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                            (let [{:keys [type]} (ex-data e)]
                              (if (or (= type :multiple-heads)
                                      (= type :not-superset)
                                      (= type :conflicting-meta)
                                      (= type :pull-unnecessary))
                                :rejected
                                (do (debug e) (throw e))))))
                 new-commits (set/difference (-> pulled :state :commit-graph keys set)
                                             (-> b-cdvcs :commit-graph keys set))]
             (cond (= pulled :rejected)
                   :rejected

                   (and (not allow-induced-conflict?)
                        (<? (inducing-conflict-pull!? atomic-pull-store
                                                      [b-user b-cdvcs-id]
                                                      (:downstream pulled)
                                                      b-cdvcs)))
                   (do
                     (debug "Pull would induce conflict: " b-user b-cdvcs-id (:state pulled))
                     :rejected)

                   (<? (integrity-fn store new-commits))
                   (:downstream pulled)

                   :else
                   (do
                     (debug "Integrity check on " new-commits " pulled from " a-user a-cdvcs " failed.")
                     :rejected)))))))




(extend-type replikativ.crdt.CDVCS
  POpBasedCRDT
  (-handshake [this] (into {} this))
  (-downstream [this op] (downstream this op))

  PExternalValues
  (-missing-commits [this store out fetched-ch op]
    (missing-commits store this op))
  (-commit-value [this commit]
    (select-keys commit #{:transactions :parents}))

  PPullOp
  (-pull [this store atomic-pull-store hooks]
    (pull-cdvcs! store atomic-pull-store hooks)))




(comment
  (require '[replikativ.crdt.materialize :refer [ensure-crdt]]
           '[konserve.memory :refer [new-mem-store]])

  (<!! (-downstream (<!! (ensure-crdt (<!! (new-mem-store)) ["a" 1] {:crdt :cdvcs}))
                    {:method :foo
                     :commit-graph {1 []
                                    2 [1]}
                     :heads #{2}}))


  )
