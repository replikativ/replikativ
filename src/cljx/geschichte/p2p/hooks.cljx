(ns geschichte.p2p.hooks
  "Allows pull hooks to automatically update publications by pulling/merging
  to more CRDTs synchronously to the update propagation."
  (:require #+clj
            [clojure.core.async :as async
             :refer [<! >! >!! <!! timeout chan alt! go put!
                     filter< map< go-loop pub sub unsub close!]]
            #+cljs
            [cljs.core.async :as async
             :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]]

            [clojure.data :refer [diff]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [geschichte.crdt.materialize :refer [pub->crdt]]
            [geschichte.platform-log :refer [debug info warn error]]
            [geschichte.platform :refer [<? go<?]]
            [geschichte.protocols :refer [-identities -downstream]]
            [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
            [konserve.store :refer [new-mem-store]])
  #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


(defn hook-dispatch [{:keys [topic]}]
  (case topic
    :meta-pub :meta-pub
    :unrelated))

(defn default-integrity-fn
  "Is always true."
  [store commit-ids] (go true))


(defn match-pubs [store pubs hooks]
  #_(go<?
     (for [[user repos] (seq pubs)
           [repo-id pub] repos
           :let [crdt (<? (pub->crdt store [user repo-id] (:crdt pub)))]
           branch (-identities crdt)
           [[a-user a-repo a-branch]
            [[b-user b-repo b-branch]
             integrity-fn
             allow-induced-conflict?]] (seq hooks)
           :when (and (or (and (= (type a-user) #+clj java.util.regex.Pattern #+cljs js/RegExp)
                               (re-matches a-user user))
                          (= a-user user))
                      (not= user b-user)
                      (= repo-id a-repo)
                      (= branch a-branch))] ;; expand only relevant hooks
       ;; fetch relevant crdt from db
       (go<? (let [integrity-fn (or integrity-fn default-integrity-fn)
                   {{b-pub b-repo} b-user} pubs
                   b-crdt-old (<? (pub->crdt store [b-user b-repo] (:crdt pub)))]
               [[user repo-id branch (-downstream crdt pubs)]
                [b-user b-repo b-branch b-crdt-old]
                integrity-fn
                allow-induced-conflict?])))))


(defn pull [hooks store pub-ch new-in]
  #_(go (let [atomic-pull-store (<? (new-mem-store))]
        (go-loop [{:keys [metas] :as p} (<? pub-ch)]
          (when p
            (->> (<? (match-pubs store metas @hooks))
                 async/merge
                 (async/into [])
                 <?
                 (map (partial pull-repo! store atomic-pull-store))
                 async/merge
                 (async/into [])
                 <?
                 (filter (partial not= :rejected))
                 (reduce (fn [ms [ur v]] (assoc-in ms ur v)) metas)
                 (assoc p :metas)
                 ((fn log [p] (debug "hook: passed " p) p))
                 (>! new-in))
            (recur (<? pub-ch)))))))


(defn hook
  "Configure automatic pulling (or merging) from repositories during a metadata publication in sync with original publication through a hooks atom containing a map, e.g. {[user-to-pull repo-to-pull branch-to-pull] [[user-to-pull-into repo-to-pull-into branch-to-pull-into] integrity-fn merge-order-fn] ...} for each pull operation.
  user-to-pull can be a wildcard :* to pull from all users (shield through authentication first) of the repository (to have central server repository/app state). integrity-fn is given a set of new commit-ids to determine whether pulling is safe. merge-order-fn can reorder the commits for merging in case of conflicts."
  [hooks store [in out]]
  (let [new-in (chan)
        p (pub in hook-dispatch)
        pub-ch (chan)]
    (sub p :meta-pub pub-ch)
    (pull hooks store pub-ch new-in)

    (sub p :unrelated new-in)
    [new-in out]))
