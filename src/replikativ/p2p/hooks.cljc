(ns replikativ.p2p.hooks
  "Allows pull hooks to automatically update publications by pulling/merging
  to more CRDTs synchronously to the update propagation."
  (:require #?(:clj
               [clojure.core.async :as async
                :refer [>! timeout chan alt! go put! go-loop pub sub unsub close! chan onto-chan]]
               :cljs
               [cljs.core.async :as async :refer [>! timeout chan put! pub sub unsub close! onto-chan]])

            [konserve.core :as k]
            [replikativ.crdt.materialize :refer [ensure-crdt]]
            [kabel.platform-log :refer [debug info warn error]]
            [replikativ.protocols :refer [PPullOp -downstream -pull]]
            #?(:clj [full.async :refer [<? go-try <<?]])
            #?(:clj [full.lab :refer [go-for go-loop-super]])
            [konserve.memory :refer [new-mem-store]])
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [full.async :refer [<? <<? go-try go-loop-try alt?]]
                            [full.lab :refer [go-for go-loop-super]])))


;; requirement for pull-hooks:
;; - atomic, may not accidentally introduce conflicts/unwanted inconsistencies
;; - only create downstream update, do not change crdt state here!
;; - like an atomic membrane for this middleware, track state internally


(defn hook-dispatch [{:keys [type]}]
  (case type
    :pub/downstream :pub/downstream
    :unrelated))

(defn default-integrity-fn
  "Is always true."
  [store commit-ids] (go true))


(defn match-pubs [cold-store mem-store atomic-pull-store [user crdt-id] {:keys [downstream] :as pub} hooks]
  (go-for [[[a-user a-crdt-id]
            [[b-user b-crdt-id]
             integrity-fn
             allow-induced-conflict?]] (seq hooks)
           ;; expand only relevant hooks
           :when (and (or (and (= (type a-user) #?(:clj java.util.regex.Pattern :cljs js/RegExp))
                               (re-matches a-user user))
                          (= a-user user))
                      (not= user b-user)
                      (= crdt-id a-crdt-id))
           :let [a-crdt (if-let [a-crdt (<? (k/get-in atomic-pull-store [a-user a-crdt-id]))]
                          a-crdt
                          (<? (ensure-crdt cold-store mem-store [a-user a-crdt-id] downstream)))
                 a-crdt (-downstream a-crdt (:op downstream))
                 _ (k/assoc-in atomic-pull-store [a-user a-crdt-id] a-crdt)
                 b-crdt (if-let [b-crdt (<? (k/get-in atomic-pull-store [b-user b-crdt-id]))]
                          b-crdt
                          (<? (ensure-crdt cold-store mem-store [b-user b-crdt-id] downstream)))
                 pulled (<? (-pull a-crdt cold-store atomic-pull-store
                                   [[a-user a-crdt-id a-crdt]
                                    [b-user b-crdt-id b-crdt]
                                    (or integrity-fn default-integrity-fn)
                                    allow-induced-conflict?]))]
           :when (not= pulled :rejected)]
          (assoc pub :user b-user :crdt-id b-crdt-id :downstream pulled)))


(defn pull [hooks cold-store mem-store pub-ch new-in]
  (go-try
   (let [atomic-pull-store (<? (new-mem-store))]
     (go-loop-super [{:keys [downstream user crdt-id] :as p} (<? pub-ch)]
                    (when p
                      (debug "passing through pub: " (:id p))
                      (>! new-in p)
                      (let [pulled (<<? (match-pubs cold-store mem-store
                                                    atomic-pull-store [user crdt-id] p @hooks))]
                        (debug "hooks passed: " (:id p))
                        (<? (onto-chan new-in pulled false)))
                      (recur (<? pub-ch)))))))


(defn hook
  "Configure automatic pulling (or merging) from CRDTs during a publication in atomic synchronisation with the original publication. This happens through a hooks atom containing a map, e.g. {[user-to-pull crdt-to-pull] [[user-to-pull-into crdt-to-pull-into] integrity-fn merge-order-fn] ...} for each pull hook.
  user-to-pull can be the wildcard :* to pull from all users of the crdtsitory. This allows to have a central server crdtsitory/app state. You should shield this through authentication first. integrity-fn is given a set of new commit-ids to determine whether pulling is safe. merge-order-fn can reorder the commits for merging in case of conflicts."
  [hooks [peer [in out]]]
  (let [{{:keys [cold-store mem-store]} :volatile} @peer
        new-in (chan)
        p (pub in hook-dispatch)
        pub-ch (chan)]
    (sub p :pub/downstream pub-ch)
    (pull hooks cold-store mem-store pub-ch new-in)

    (sub p :unrelated new-in)
    [peer [new-in out]]))
