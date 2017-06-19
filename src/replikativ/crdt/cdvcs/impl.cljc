(ns replikativ.crdt.cdvcs.impl
  "Implementation of the CRDT replication protocol."
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id]]
            [replikativ.protocols :refer [POpBasedCRDT -downstream
                                          PExternalValues -missing-commits
                                          PPullOp -pull]]
            #?(:clj [kabel.platform-log :refer [debug info error]])
            #?(:clj [superv.async :refer [go-try go-loop-try <? <<? go-for]])
            [replikativ.crdt.cdvcs.core :refer [multiple-heads? pull]]
            [replikativ.crdt.cdvcs.meta :refer [downstream]]
            [konserve.core :as k]
            #?(:clj [clojure.core.async :as async
                    :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [go-try go-loop-try <? <<? go-for]]
                            [kabel.platform-log :refer [debug info error]])))


;; fetching related ops
(defn- all-commits
  [commit-graph]
  (keys commit-graph))

(defn- missing-commits [S store cdvcs op]
  (let [missing (all-commits (:commit-graph op))
        #_(set/difference (all-commits (:commit-graph op))
                          (all-commits (:commit-graph cdvcs)))]
    (go-loop-try S [not-in-store #{}
                  [f & r] (seq missing)]
                 (if f
                   (recur (if (and (not (get (:commit-graph cdvcs) f))
                                   (not (<? S (k/exists? store f))))
                            (conj not-in-store f)
                            not-in-store)
                          r)
                   not-in-store))))


;; pull-hook
(defn inducing-conflict-pull!? [S atomic-pull-store [user cdvcs] pulled-op b-cdvcs]
  (go-try S
   (let [[old new] (<? S (k/update-in atomic-pull-store [user cdvcs]
                                    ;; ensure updates inside atomic swap
                                      #(cond (not %) b-cdvcs
                                             (multiple-heads?
                                              (-downstream % pulled-op)) %
                                             :else (-downstream % pulled-op))))]
     ;; not perfectly elegant to reconstruct the value of inside the transaction, but safe
     (when (= old new) (not= (-downstream old pulled-op) new)))))


(defn pull-cdvcs!
  [S store atomic-pull-store
   [[a-user _ a-cdvcs]
    [b-user b-cdvcs-id b-cdvcs]
    integrity-fn
    allow-induced-conflict?]]
  (go-try S
   (let [{:keys [commit-graph heads]} a-cdvcs
         [head-a head-b] (seq heads)]
     (cond head-b
           (do (debug {:event :cannot-pull-from-conflicting-cdvcs :cdvcs a-cdvcs :heads heads})
               :rejected)

           (not (:commit-graph b-cdvcs))
           (do (debug {:event :pulling-into-empty-cdvcs :cdvcs b-cdvcs})
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
                        (<? S (inducing-conflict-pull!? S atomic-pull-store
                                                        [b-user b-cdvcs-id]
                                                        (:downstream pulled)
                                                        b-cdvcs)))
                   (do
                     (debug {:event :pull-would-induce-conflict
                             :b-user b-user :b-cdvcs-id b-cdvcs-id
                             :state (:state pulled)})
                     :rejected)

                   (<? S (integrity-fn S store new-commits))
                   (:downstream pulled)

                   :else
                   (do
                     (debug {:event :integrity-check-failed
                             :new-commits new-commits :pulled-from [a-user a-cdvcs]})
                     :rejected)))))))




(extend-type replikativ.crdt.CDVCS
  POpBasedCRDT
  (-handshake [this S] (into {} this))
  (-downstream [this op] (downstream this op))

  PExternalValues
  (-missing-commits [this S store out fetched-ch op]
    (missing-commits S store this op))
  PPullOp
  (-pull [this S store atomic-pull-store hooks]
    (pull-cdvcs! S store atomic-pull-store hooks)))




(comment
  (require '[replikativ.crdt.materialize :refer [ensure-crdt]]
           '[konserve.memory :refer [new-mem-store]])

  (<!! (-downstream (<!! (ensure-crdt (<!! (new-mem-store)) ["a" 1] {:crdt :cdvcs}))
                    {:method :foo
                     :commit-graph {1 []
                                    2 [1]}
                     :heads #{2}}))


  )
