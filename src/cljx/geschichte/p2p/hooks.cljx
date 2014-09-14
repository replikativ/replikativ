(ns geschichte.p2p.hooks
  "Allows pull hooks to automatically update publications by pulling/merging
  to more repositories inline."
  (:require [geschichte.platform-log :refer [debug info warn error]]
            [geschichte.repo :as r]
            [geschichte.meta :refer [update]]
            [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
            [clojure.set :as set]
            [clojure.data :refer [diff]]
            #+clj [clojure.core.async :as async
                   :refer [<! >! >!! <!! timeout chan alt! go put!
                           filter< map< go-loop pub sub unsub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]])
  #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


(defn hook-dispatch [{:keys [topic]}]
  (case topic
    :meta-pub :meta-pub
    :unrelated))


(defn pull-repo! [store [[a-user a-repo a-branch a-meta]
                         [b-user b-repo b-branch b-meta]
                         integrity-fn
                         merge-order-fn]]
  (let [[head-a head-b] (seq (get-in a-meta [:branches a-branch]))]
    (if head-b
      (do (debug "Cannot pull from conflicting meta: " a-user a-meta " into: " b-user b-meta)
          :rejected)
      (go
        (let [pulled (try
                       (r/pull {:meta b-meta} b-branch a-meta head-a)
                       (catch clojure.lang.ExceptionInfo e
                         (r/merge {:meta b-meta} b-user b-branch
                                  a-meta
                                  (<! (merge-order-fn store
                                                      (r/merge-heads a-meta a-branch
                                                                     b-meta b-branch))))))
              new-commits (set/difference (-> pulled :meta :causal-order keys set)
                                          (-> b-meta :causal-order keys set))]
          (if (<! (integrity-fn store new-commits))
            (do (doseq [[id value] (get-in pulled [:new-values b-branch])]
                  (<! (-assoc-in store [id] value)))
                [[b-user b-repo] (:meta pulled)])
            (do
              (debug "Integrity check on " new-commits " pulled from " a-user a-meta " failed.")
              :rejected)))))))


(defn match-metas [store metas hooks]
  (for [[metas-user metas-repos] (seq metas)
        [metas-repo-id metas-repo] metas-repos
        metas-branch (keys (:branches metas-repo))
        [[a-user a-repo a-branch]
         [[b-user b-repo b-branch]
          integrity-fn
          merge-order-fn]] (seq hooks)
        :when (and (or (and (= (type a-user) #+clj java.util.regex.Pattern #+cljs js/RegExp)
                            (re-matches a-user metas-user)
                            (not= metas-user b-user))
                       (= a-user metas-user))
                   (= metas-repo-id a-repo)
                   (= metas-branch a-branch))] ;; expand only relevant hooks
    ;; fetch relevant metadata from db
    (go (let [integrity-fn (or integrity-fn (fn always-true [store commit-ids] (go true)))
              merge-order-fn (or merge-order-fn (fn default-order [store order] (go order)))
              {{b-meta b-repo} b-user} metas
              b-meta-old (<! (-get-in store [b-user b-repo]))]
          [[metas-user metas-repo-id metas-branch metas-repo]
           [b-user b-repo b-branch (update b-meta-old (or b-meta b-meta-old))]
           integrity-fn
           merge-order-fn]))))


(defn pull [hooks store pub-ch new-in]
  (go-loop [{:keys [metas] :as p} (<! pub-ch)]
    (when p
      (->> (match-metas store metas @hooks)
           async/merge
           (async/into [])
           <!
           (map (partial pull-repo! store))
           async/merge
           (async/into [])
           <!
           (filter (partial not= :rejected))
           (reduce (fn [ms [ur v]] (assoc-in ms ur v)) metas)
           (assoc p :metas)
           (>! new-in))
      (recur (<! pub-ch)))))


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
