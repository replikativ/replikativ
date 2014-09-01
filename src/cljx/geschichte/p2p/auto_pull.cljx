(ns geschichte.p2p.auto-pull
  "Allows pull hooks to automatically update publications by pulling to more repositories inline."
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


(defn pull-dispatch [{:keys [topic]}]
  (case topic
    :meta-pub :meta-pub
    :unrelated))


(defn pull-repo [new-metas [[a-user a-repo a-branch a-meta]
                            [b-user b-repo b-branch b-meta]
                            integrity-fn
                            merge-order-fn]]
  (let [[head-a head-b] (seq (get-in a-meta [:branches a-branch]))]
    (if head-b
      (do (debug "Cannot pull from conflicting meta: " a-user a-meta " into: " b-user b-meta)
          new-metas)
      (let [pulled (try
                     (:meta (r/pull {:meta b-meta} b-branch a-meta head-a))
                     (catch clojure.lang.ExceptionInfo e
                       (:meta (r/merge {:meta b-meta} b-user b-branch
                                       a-meta
                                       (merge-order-fn
                                        (r/merge-heads a-meta a-branch
                                                       b-meta b-branch))))))
            new-commits (set/difference (-> pulled :causal-order keys set)
                                        (-> b-meta :causal-order keys set))]
        (if (integrity-fn new-commits)
          (assoc-in new-metas [b-user b-repo] pulled)
          (do
            (debug "Integrity check on " new-commits " pulled from " a-user a-meta " failed.")
            new-metas))))))


(defn puller [hooks store pub-ch new-in]
  (go-loop [{:keys [metas] :as p} (<! pub-ch)]
    (when p
      (->> (for [[[a-user a-repo a-branch]
                  [b-user b-repo b-branch]
                  integrity-fn
                  merge-order-fn] hooks
                  :when (get-in metas [a-user a-repo])] ;; expand only relevant hooks
             ;; fetch relevant metadata from db
             (go (let [integrity-fn (or integrity-fn (fn always-true [commit-ids] true))
                       merge-order-fn (or merge-order-fn identity)
                       {{a-meta a-repo} a-user
                        {b-meta b-repo} b-user} metas
                       b-meta-old (<! (-get-in store [b-user b-repo]))]
                   [[a-user a-repo a-branch a-meta]
                    [b-user b-repo b-branch (update b-meta-old (or b-meta b-meta-old))]
                    integrity-fn
                    merge-order-fn])))
           async/merge
           (async/into [])
           <!
           ((fn [hooks] (println "HOOKS: " hooks) hooks))
           (reduce pull-repo metas)
           (assoc p :metas)
           (>! new-in))
      (recur (<! pub-ch)))))


(defn pull [hooks store [in out]]
  (let [new-in (chan)
        p (pub in pull-dispatch)
        pub-ch (chan)]
    (sub p :meta-pub pub-ch)
    (puller hooks store pub-ch new-in)

    (sub p :unrelated new-in)
    [new-in out]))
