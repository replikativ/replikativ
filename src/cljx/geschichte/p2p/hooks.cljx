(ns geschichte.p2p.hooks
  "Allows pull hooks to automatically update publications by pulling/merging
  to more repositories inline."
  (:require [geschichte.platform-log :refer [debug info warn error]]
            [geschichte.platform :refer [<?]]
            [geschichte.repo :as r]
            [geschichte.meta :refer [update]]
            [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
            [konserve.store :refer [new-mem-store]]
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

(defn inducing-conflict-pull!? [atomic-pull-store [user repo branch] new-state]
  (go (let [[old new] (<! (-update-in atomic-pull-store [user repo]
                                      #(cond (not %) new-state
                                             (r/multiple-branch-heads? (update % new-state) branch) %
                                             :else (update % new-state))))]
        ;; not perfectly elegant to reconstruct the value of inside the transaction
        (and (= old new) (not= (update old new-state) new)))))


(defn pull-repo!
  "Pull from user 'a' into repo of user 'b', optionally verifying integrity and optionally supplying a reordering function for merges, otherwise only pulls can move a branch forward.

Uses store to access commit values for integrity-fn and atomic-pull-store to atomically synchronize pulls, disallowing induced conficts by default. Atomicity only works inside the stores atomicity boundaries (probably peer-wide). So when different peers with different stores pull through this middleware they might still induce conflicts although each one disallows them."
  [store atomic-pull-store
   [[a-user a-repo a-branch a-state]
    [b-user b-repo b-branch b-state]
    integrity-fn
    allow-induced-conflict?]]
  (go
    (let [branches (get-in a-state [:op :branches a-branch])
          [head-a head-b] (seq branches)]
      (if head-b
        (do (debug "Cannot pull from conflicting meta: " a-state a-branch ": " branches)
            :rejected)
        (let [pulled (try
                       (r/pull b-state b-branch (:op a-state) head-a allow-induced-conflict? false)
                       (catch #+clj clojure.lang.ExceptionInfo #+cljs ExceptionInfo e
                              (let [{:keys [type]} (ex-data e)]
                                (if (or (= type :multiple-branch-heads)
                                        (= type :not-superset)
                                        (= type :conflicting-meta)
                                        (= type :pull-unnecessary))
                                  (do (debug e) :rejected)
                                  (do (debug e) (throw e))))))
              new-commits (set/difference (-> pulled :state :causal-order keys set)
                                          (-> b-state :state :causal-order keys set))]
          (cond (= pulled :rejected)
                :rejected

                (and (not allow-induced-conflict?)
                     (<! (inducing-conflict-pull!? atomic-pull-store
                                                   [b-user b-repo b-branch]
                                                   (:state pulled))))
                (do
                  (debug "Pull would induce conflict: " b-user b-repo (:state pulled))
                  :rejected)

                (<! (integrity-fn store new-commits))
                [[b-user b-repo] (:op pulled)]

                :else
                (do
                  (debug "Integrity check on " new-commits " pulled from " a-user a-state " failed.")
                  :rejected)))))))

(defn default-integrity-fn
  "Is always true."
  [store commit-ids] (go true))

(defn match-metas [store metas hooks]
  (for [[metas-user metas-repos] (seq metas)
        [metas-repo-id metas-repo] metas-repos
        metas-branch (-> metas-repo :op :branches keys)
        [[a-user a-repo a-branch]
         [[b-user b-repo b-branch]
          integrity-fn
          allow-induced-conflict?]] (seq hooks)
        :when (and (or (and (= (type a-user) #+clj java.util.regex.Pattern #+cljs js/RegExp)
                            (re-matches a-user metas-user))
                       (= a-user metas-user))
                   (not= metas-user b-user)
                   (= metas-repo-id a-repo)
                   (= metas-branch a-branch))] ;; expand only relevant hooks
    ;; fetch relevant metadata from db
    (go (let [integrity-fn (or integrity-fn default-integrity-fn)
              {{b-state b-repo} b-user} metas
              metas-repo-old (<? (-get-in store [metas-user metas-repo-id]))
              b-state-old (<? (-get-in store [b-user b-repo]))]
          (println "METAS-REPO" metas-user metas-repo metas-repo-old)
          [[metas-user metas-repo-id metas-branch (update (or metas-repo-old (:op metas-repo))
                                                          metas-repo)]
           [b-user b-repo b-branch (update (or b-state-old (:op b-state)) b-state)]
           integrity-fn
           allow-induced-conflict?]))))


(defn pull [hooks store pub-ch new-in]
  (go (let [atomic-pull-store (<! (new-mem-store))]
        (go-loop [{:keys [metas] :as p} (<! pub-ch)]
          (when p
            (->> (match-metas store metas @hooks)
                 async/merge
                 (async/into [])
                 <!
                 (map (partial pull-repo! store atomic-pull-store))
                 async/merge
                 (async/into [])
                 <!
                 (filter (partial not= :rejected))
                 (reduce (fn [ms [ur v]] (assoc-in ms ur v)) metas)
                 (assoc p :metas)
                 ((fn log [p] (debug "hook: passed " p) p))
                 (>! new-in))
            (recur (<! pub-ch)))))))


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
