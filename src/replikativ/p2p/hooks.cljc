(ns replikativ.p2p.hooks
  "Allows pull hooks to automatically update publications by pulling/merging
  to more CRDTs synchronously to the update propagation."
  (:require #?(:clj
                [clojure.core.async :as async
                 :refer [>! timeout chan alt! go put! go-loop pub sub unsub close! chan]]
               :cljs
                [cljs.core.async :as async :refer [>! timeout chan put! pub sub unsub close!]])

            [replikativ.crdt.materialize :refer [pub->crdt]]
            [replikativ.platform-log :refer [debug info warn error]]
            [replikativ.protocols :refer [PHasIdentities PPullOp -identities -downstream -pull]]
            [full.async :refer [go-for <? go-try] #?(:cljs :include-macros)]
            [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
            [konserve.memory :refer [new-mem-store]])
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])))


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


(defn match-pubs [store atomic-pull-store pubs hooks]
  (go-for [[user repos] (seq pubs)
           [repo-id pub] repos
           :let [a-crdt (<? (pub->crdt store [user repo-id] (:crdt pub)))
                 a-crdt (-downstream a-crdt (:op pub))]
           branch (if (extends? PHasIdentities (type a-crdt))
                    (-identities a-crdt)
                    [nil])
           [[a-user a-repo a-branch]
            [[b-user b-repo b-branch]
             integrity-fn
             allow-induced-conflict?]] (seq hooks)
             :when (and (or (and (= (type a-user) #?(:clj java.util.regex.Pattern :cljs js/RegExp))
                               (re-matches a-user user))
                          (= a-user user))
                      (not= user b-user)
                      (= repo-id a-repo)
                      (= branch a-branch))
           :let [{{b-pub b-repo} b-user} pubs
                 b-crdt (<? (pub->crdt store [b-user b-repo] (:crdt pub)))
                 b-crdt (if b-pub (-downstream b-crdt (:op b-pub)) b-crdt)]] ;; expand only relevant hooks
          (<? (-pull a-crdt atomic-pull-store
                     [[a-user a-repo a-branch a-crdt]
                      [b-user b-repo b-branch b-crdt]
                      (or integrity-fn default-integrity-fn)]))))


(defn pull [hooks store pub-ch new-in]
  (go-try (let [atomic-pull-store (<? (new-mem-store))]
            (go-loop [{:keys [downstream] :as p} (<? pub-ch)]
              (when p
                (->> (match-pubs store atomic-pull-store downstream @hooks)
                     ;; TODO translate to transducers instead
                     (async/into [])
                     <?
                     (filter (partial not= :rejected))
                     (reduce (fn [ms [ur v]] (assoc-in ms ur v)) downstream)
                     (assoc p :downstream)
                     ((fn log [p] (debug "HOOK: passed " p) p))
                     (>! new-in))
                (recur (<? pub-ch)))))))


(defn hook
  "Configure automatic pulling (or merging) from CRDTs during a publication in atomic synchronisation with the original publication. This happens through a hooks atom containing a map, e.g. {[user-to-pull repo-to-pull branch-to-pull] [[user-to-pull-into repo-to-pull-into branch-to-pull-into] integrity-fn merge-order-fn] ...} for each pull hook.
  user-to-pull can be the wildcard :* to pull from all users of the repository. This allows to have a central server repository/app state. You should shield this through authentication first. integrity-fn is given a set of new commit-ids to determine whether pulling is safe. merge-order-fn can reorder the commits for merging in case of conflicts."
  [hooks store [in out]]
  (let [new-in (chan)
        p (pub in hook-dispatch)
        pub-ch (chan)]
    (sub p :pub/downstream pub-ch)
    (pull hooks store pub-ch new-in)

    (sub p :unrelated new-in)
    [new-in out]))
