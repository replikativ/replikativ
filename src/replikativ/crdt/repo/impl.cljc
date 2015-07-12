(ns replikativ.crdt.repo.impl
  "Implementation of the CRDT replication protocol."
  (:require [clojure.set :as set]
            [replikativ.environ :refer [*id-fn* *date-fn* store-blob-trans-id]]
            [replikativ.protocols :refer [PHasIdentities -identities -select-identities
                                          POpBasedCRDT -apply-downstream! -downstream
                                          PExternalValues -missing-commits
                                          PPullOp -pull]]
            [replikativ.platform-log :refer [debug info error]]
            [full.async :refer [go-try go-loop-try go-for <?]]
            [replikativ.crdt.repo.repo :as repo]
            [replikativ.crdt.repo.meta :refer [downstream isolate-branch]]
            [konserve.protocols :refer [-exists? -assoc-in -bassoc -update-in]]
            #?(:clj [clojure.core.async :as async
                    :refer [>! timeout chan alt! put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))

  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])))


;; fetching related ops
(defn- all-commits
  [commit-graph]
  (set (keys commit-graph)))

(defn- missing-commits [store op graph]
  (let [missing (set/difference (all-commits (:commit-graph op))
                                (all-commits graph))]
    (->> (go-for [m missing
                  :when (not (<? (-exists? store m)))]
                 m)
         (async/into #{}))))


;; pull-hook

(defn inducing-conflict-pull!? [atomic-pull-store [user repo branch] pulled-op]
  (go-try (let [[old new] (<? (-update-in atomic-pull-store [user repo]
                                          ;; ensure updates inside atomic swap
                                          #(cond (not %) pulled-op
                                                 (repo/multiple-branch-heads?
                                                  (-downstream % pulled-op) branch) %
                                                 :else (-downstream % pulled-op))))]
            ;; not perfectly elegant to reconstruct the value of inside the transaction, but safe
            (and (= old new) (not= (-downstream old pulled-op) new)))))


(defn pull-repo!
  [store atomic-pull-store
   [[a-user a-repo a-branch a-crdt]
    [b-user b-repo b-branch b-crdt]
    integrity-fn
    allow-induced-conflict?]]
  (go-try
    (let [conflicts (get-in a-crdt [:branches a-branch])
          [head-a head-b] (seq conflicts)]
      (if head-b
        (do (debug "Cannot pull from conflicting CRDT: " a-crdt a-branch ": " conflicts)
            :rejected)
        (let [pulled (try
                       (repo/pull {:state b-crdt} b-branch a-crdt head-a allow-induced-conflict? false)
                       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                              (let [{:keys [type]} (ex-data e)]
                                (if (or (= type :multiple-branch-heads)
                                        (= type :not-superset)
                                        (= type :conflicting-meta)
                                        (= type :pull-unnecessary))
                                  (do (debug e) :rejected)
                                  (do (debug e) (throw e))))))
              new-commits (set/difference (-> pulled :state :commit-graph keys set)
                                          (-> b-crdt :commit-graph keys set))]
          (cond (= pulled :rejected)
                :rejected

                (and (not allow-induced-conflict?)
                     (<? (inducing-conflict-pull!? atomic-pull-store
                                                   [b-user b-repo b-branch]
                                                   (:downstream pulled))))
                (do
                  (debug "Pull would induce conflict: " b-user b-repo (:state pulled))
                  :rejected)

                (<? (integrity-fn store new-commits))
                [[b-user b-repo] (:downstream pulled)]

                :else
                (do
                  (debug "Integrity check on " new-commits " pulled from " a-user a-crdt " failed.")
                  :rejected)))))))




;; CRDT is responsible for all writes to store!
(defrecord Repository [commit-graph branches cursor store]
  PHasIdentities
  (-identities [this] (set (keys branches)))
  (-select-identities [this branches op]
    (let [branches-graph (apply set/union
                                 (map (comp set keys (partial isolate-branch op))
                                      branches))]
      (-> op
          (update-in [:commit-graph] select-keys branches-graph)
          (update-in [:branches] select-keys branches))))

  POpBasedCRDT
  (-downstream [this op] (merge this (downstream this op)))
  (-apply-downstream! [this op]
    (-update-in store cursor #(downstream % op)))

  PExternalValues
  (-missing-commits [this out fetched-ch] (missing-commits store this nil))
  (-missing-commits [this out fetched-ch op] (missing-commits store this op))

  PPullOp
  (-pull [this atomic-pull-store hooks]
    (pull-repo! store atomic-pull-store hooks)))




(comment
  (require '[replikativ.crdt.materialize :refer [pub->crdt]]
           '[konserve.store :refer [new-mem-store]])

  (<!! (pub->crdt (<!! (new-mem-store)) ["a" 1] :repo))

  (<!! (-downstream (<!! (pub->crdt (<!! (new-mem-store)) ["a" 1] :repo)) {:method :foo
                                                                           :commit-graph {1 []
                                                                                          2 [1]}
                                                                           :branches {"master" #{2}}}))


  )
