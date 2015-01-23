(ns geschichte.p2p.hooks
  "Allows pull hooks to automatically update publications by pulling/merging
  to more repositories inline."
  (:require [geschichte.platform-log :refer [debug info warn error]]
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
    (let [branches (get-in a-state [:branches a-branch])
          [head-a head-b] (seq branches)]
      (if head-b
        (do (debug "Cannot pull from conflicting meta: " a-state a-branch ": " branches)
            :rejected)
        (let [pulled (try
                       (r/pull {:state b-state} b-branch a-state head-a allow-induced-conflict? false)
                       (catch #+clj clojure.lang.ExceptionInfo #+cljs ExceptionInfo e
                              (let [{:keys [type]} (ex-data e)]
                                (if (or (= type :multiple-branch-heads)
                                        (= type :not-superset)
                                        (= type :conflicting-meta)
                                        (= type :pull-unnecessary))
                                  (do (debug e) :rejected)
                                  (do (debug e) (throw e))))))
              new-commits (set/difference (-> pulled :state :causal-order keys set)
                                          (-> b-state :causal-order keys set))]
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
                [[b-user b-repo] (:state pulled)]

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
        metas-branch (keys (:branches metas-repo))
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
              b-state-old (<! (-get-in store [b-user b-repo]))]
          [[metas-user metas-repo-id metas-branch metas-repo]
           [b-user b-repo b-branch (update b-state-old (or b-state b-state-old))]
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


(comment
  ;; merges (multiple heads) are not pulled into eve through hook

  ;; eve
  {#uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"
   {:description "topiq discourse.",
    :schema {:type "http://github.com/ghubber/geschichte", :version 1},
    :pull-requests {},
    :causal-order
    {#uuid "2d5526d8-8f0b-5763-b894-2b652ffe9fde"
     [#uuid "1d3a0bd2-27e4-542c-b3a5-31b4b47c6bef"],
     #uuid "183b45ec-abc0-598e-b501-989aa7062558"
     [#uuid "1b195e93-c19f-591c-abe8-0773a2736b56"],
     #uuid "14a68aae-3372-5262-8a90-6196aa103f3a"
     [#uuid "2d1a2887-4cd9-5e34-b0b8-4a92cae86ab2"],
     #uuid "11d23a61-e7a5-5b25-af99-9c594195b63d"
     [#uuid "2d5526d8-8f0b-5763-b894-2b652ffe9fde"],
     #uuid "17cecb00-3173-512a-9f6d-1a9163a0e1ba"
     [#uuid "11c093d9-b1ac-5e2b-9bd0-43d58343b9d1"],
     #uuid "37251587-e215-521f-b606-947e1929ea84"
     [#uuid "07dbd4ed-21c5-524b-a816-175b948d0207"],
     #uuid "124ac771-1efd-5fc9-ae48-147f634d1c63"
     [#uuid "12a87e54-7217-5f19-90ae-58572e284624"],
     #uuid "148a0ad3-2339-504a-b380-6a33740cbd0e"
     [#uuid "39017a72-7ae1-5e52-a323-8ef3ee6d36ba"],
     #uuid "25e53d2d-eb18-55db-9812-feced33bc53c"
     [#uuid "1af54379-00fe-515a-90de-64c37691f048"],
     #uuid "00c1cf1b-8f33-5d76-b5f4-33d70d241e7e"
     [#uuid "3c298755-6828-5bd0-a704-e74e7424591c"],
     #uuid "1d3a0bd2-27e4-542c-b3a5-31b4b47c6bef"
     [#uuid "0bf9a9db-523a-5700-b045-049efb307c73"],
     #uuid "2ac96697-1b21-5428-ad79-f0e6532ac63f"
     [#uuid "28f00d3b-003e-5476-ac49-3abf1e3c4724"],
     #uuid "2bf907aa-badb-56d9-be31-c2a5e56f3bb0"
     [#uuid "3cf92159-70a0-57aa-a47d-0d8c724b3a99"],
     #uuid "3cf92159-70a0-57aa-a47d-0d8c724b3a99"
     [#uuid "32118080-b7c0-5fc5-afde-d717f3f0ebfb"],
     #uuid "061d8a1e-b0a8-55c4-8736-ed0e39f30b9c" [],
     #uuid "2f10ac48-0cc0-5d8f-aeaf-c9c53a14be2a"
     [#uuid "183b45ec-abc0-598e-b501-989aa7062558"],
     #uuid "1af54379-00fe-515a-90de-64c37691f048"
     [#uuid "233ff6b5-ac35-58e0-9bd9-3b902d66991b"],
     #uuid "39017a72-7ae1-5e52-a323-8ef3ee6d36ba"
     [#uuid "11d23a61-e7a5-5b25-af99-9c594195b63d"],
     #uuid "3b4bde46-25f7-5ae1-992a-f79b6e4c7395"
     [#uuid "30ed820a-23aa-58e8-a7d6-02ec059fe1b7"],
     #uuid "32118080-b7c0-5fc5-afde-d717f3f0ebfb"
     [#uuid "3a8e9e67-9667-5116-a1b1-bab4bf2e708a"],
     #uuid "1b195e93-c19f-591c-abe8-0773a2736b56"
     [#uuid "061d8a1e-b0a8-55c4-8736-ed0e39f30b9c"],
     #uuid "2722d185-d93f-5f1e-92d9-46ad7270313d"
     [#uuid "14aaa8ba-9a47-53bf-a6ef-ef2124d33c19"],
     #uuid "19d0c810-4bfe-54dd-8ee3-901766697716"
     [#uuid "25e53d2d-eb18-55db-9812-feced33bc53c"],
     #uuid "0d316d18-cf30-5dc5-8cd5-5e30ac77fbe0"
     [#uuid "14a68aae-3372-5262-8a90-6196aa103f3a"],
     #uuid "2fed7878-1bc3-58b4-8717-99c42db984a5"
     [#uuid "31bf9909-8a9c-5707-991e-20d84e88d9ec"],
     #uuid "28258f5f-468e-5d97-8606-03d313ef4ad6"
     [#uuid "00c1cf1b-8f33-5d76-b5f4-33d70d241e7e"],
     #uuid "34c5905d-28dc-5934-8380-1735c0b00a52"
     [#uuid "17cecb00-3173-512a-9f6d-1a9163a0e1ba"],
     #uuid "0c7d3397-8bf2-56f1-82a1-2dd2b90388db"
     [#uuid "0fac861c-6be8-5396-af9e-792fde6a1897"],
     #uuid "3a8e9e67-9667-5116-a1b1-bab4bf2e708a"
     [#uuid "29b8db0f-4ce5-5a83-bb36-46d707228203"],
     #uuid "29e461c4-b0bc-5bd5-88da-53b28a9c6492"
     [#uuid "28c161f1-33d4-5edb-82e5-20a9745c5a9a"],
     #uuid "31ea53d8-d5b9-5fc6-8b75-73a27405152d"
     [#uuid "2fed7878-1bc3-58b4-8717-99c42db984a5"],
     #uuid "0fac861c-6be8-5396-af9e-792fde6a1897"
     [#uuid "2bf907aa-badb-56d9-be31-c2a5e56f3bb0"],
     #uuid "03aee177-6741-5b96-bf6a-037f0524abcc"
     [#uuid "37251587-e215-521f-b606-947e1929ea84"],
     #uuid "17b1aa38-8acd-5662-a5ee-fbc19af49f08"
     [#uuid "0c7d3397-8bf2-56f1-82a1-2dd2b90388db"],
     #uuid "2d1a2887-4cd9-5e34-b0b8-4a92cae86ab2"
     [#uuid "03aee177-6741-5b96-bf6a-037f0524abcc"],
     #uuid "28f00d3b-003e-5476-ac49-3abf1e3c4724"
     [#uuid "27fe07af-1a82-5249-b2f1-0da53727ecbd"],
     #uuid "3a21580c-2947-5bf7-a3fa-e96b6858a706"
     [#uuid "2ac96697-1b21-5428-ad79-f0e6532ac63f"],
     #uuid "0bf9a9db-523a-5700-b045-049efb307c73"
     [#uuid "28258f5f-468e-5d97-8606-03d313ef4ad6"],
     #uuid "11c093d9-b1ac-5e2b-9bd0-43d58343b9d1"
     [#uuid "3b4bde46-25f7-5ae1-992a-f79b6e4c7395"],
     #uuid "3c298755-6828-5bd0-a704-e74e7424591c"
     [#uuid "34c5905d-28dc-5934-8380-1735c0b00a52"],
     #uuid "29b8db0f-4ce5-5a83-bb36-46d707228203"
     [#uuid "2722d185-d93f-5f1e-92d9-46ad7270313d"],
     #uuid "28c161f1-33d4-5edb-82e5-20a9745c5a9a"
     [#uuid "3a21580c-2947-5bf7-a3fa-e96b6858a706"],
     #uuid "30ed820a-23aa-58e8-a7d6-02ec059fe1b7"
     [#uuid "31ea53d8-d5b9-5fc6-8b75-73a27405152d"],
     #uuid "09e4567c-abf2-5c9f-88f5-d9bbe29ffa81"
     [#uuid "17b1aa38-8acd-5662-a5ee-fbc19af49f08"],
     #uuid "31bf9909-8a9c-5707-991e-20d84e88d9ec"
     [#uuid "0d316d18-cf30-5dc5-8cd5-5e30ac77fbe0"],
     #uuid "07dbd4ed-21c5-524b-a816-175b948d0207"
     [#uuid "09e4567c-abf2-5c9f-88f5-d9bbe29ffa81"],
     #uuid "14aaa8ba-9a47-53bf-a6ef-ef2124d33c19"
     [#uuid "2f10ac48-0cc0-5d8f-aeaf-c9c53a14be2a"],
     #uuid "233ff6b5-ac35-58e0-9bd9-3b902d66991b"
     [#uuid "124ac771-1efd-5fc9-ae48-147f634d1c63"],
     #uuid "27fe07af-1a82-5249-b2f1-0da53727ecbd"
     [#uuid "148a0ad3-2339-504a-b380-6a33740cbd0e"],
     #uuid "12a87e54-7217-5f19-90ae-58572e284624"
     [#uuid "29e461c4-b0bc-5bd5-88da-53b28a9c6492"]},
    :public false,
    :branches {"master" #{#uuid "19d0c810-4bfe-54dd-8ee3-901766697716"}},
    :head "master",
    :last-update #inst "2014-10-29T18:50:15.766-00:00",
    :id #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"}}


  ;; whilo
  {#uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"
   {:description "topiq discourse.",
    :schema {:type "http://github.com/ghubber/geschichte", :version 1},
    :pull-requests {},
    :causal-order
    {#uuid "2d5526d8-8f0b-5763-b894-2b652ffe9fde"
     [#uuid "1d3a0bd2-27e4-542c-b3a5-31b4b47c6bef"],
     #uuid "183b45ec-abc0-598e-b501-989aa7062558"
     [#uuid "1b195e93-c19f-591c-abe8-0773a2736b56"],
     #uuid "14a68aae-3372-5262-8a90-6196aa103f3a"
     [#uuid "2d1a2887-4cd9-5e34-b0b8-4a92cae86ab2"],
     #uuid "11d23a61-e7a5-5b25-af99-9c594195b63d"
     [#uuid "2d5526d8-8f0b-5763-b894-2b652ffe9fde"],
     #uuid "17cecb00-3173-512a-9f6d-1a9163a0e1ba"
     [#uuid "11c093d9-b1ac-5e2b-9bd0-43d58343b9d1"],
     #uuid "37251587-e215-521f-b606-947e1929ea84"
     [#uuid "07dbd4ed-21c5-524b-a816-175b948d0207"],
     #uuid "124ac771-1efd-5fc9-ae48-147f634d1c63"
     [#uuid "12a87e54-7217-5f19-90ae-58572e284624"],
     #uuid "148a0ad3-2339-504a-b380-6a33740cbd0e"
     [#uuid "39017a72-7ae1-5e52-a323-8ef3ee6d36ba"],
     #uuid "25e53d2d-eb18-55db-9812-feced33bc53c"
     [#uuid "1af54379-00fe-515a-90de-64c37691f048"],
     #uuid "00c1cf1b-8f33-5d76-b5f4-33d70d241e7e"
     [#uuid "3c298755-6828-5bd0-a704-e74e7424591c"],
     #uuid "1d3a0bd2-27e4-542c-b3a5-31b4b47c6bef"
     [#uuid "0bf9a9db-523a-5700-b045-049efb307c73"],
     #uuid "2ac96697-1b21-5428-ad79-f0e6532ac63f"
     [#uuid "28f00d3b-003e-5476-ac49-3abf1e3c4724"],
     #uuid "2bf907aa-badb-56d9-be31-c2a5e56f3bb0"
     [#uuid "3cf92159-70a0-57aa-a47d-0d8c724b3a99"],
     #uuid "3cf92159-70a0-57aa-a47d-0d8c724b3a99"
     [#uuid "32118080-b7c0-5fc5-afde-d717f3f0ebfb"],
     #uuid "061d8a1e-b0a8-55c4-8736-ed0e39f30b9c" [],
     #uuid "2f10ac48-0cc0-5d8f-aeaf-c9c53a14be2a"
     [#uuid "183b45ec-abc0-598e-b501-989aa7062558"],
     #uuid "1af54379-00fe-515a-90de-64c37691f048"
     (#uuid "365205df-2cdc-5762-816f-e30b6071f86a"
            #uuid "233ff6b5-ac35-58e0-9bd9-3b902d66991b"),
     #uuid "39017a72-7ae1-5e52-a323-8ef3ee6d36ba"
     [#uuid "11d23a61-e7a5-5b25-af99-9c594195b63d"],
     #uuid "3b4bde46-25f7-5ae1-992a-f79b6e4c7395"
     [#uuid "30ed820a-23aa-58e8-a7d6-02ec059fe1b7"],
     #uuid "32118080-b7c0-5fc5-afde-d717f3f0ebfb"
     [#uuid "3a8e9e67-9667-5116-a1b1-bab4bf2e708a"],
     #uuid "1b195e93-c19f-591c-abe8-0773a2736b56"
     [#uuid "061d8a1e-b0a8-55c4-8736-ed0e39f30b9c"],
     #uuid "2722d185-d93f-5f1e-92d9-46ad7270313d"
     [#uuid "14aaa8ba-9a47-53bf-a6ef-ef2124d33c19"],
     #uuid "19d0c810-4bfe-54dd-8ee3-901766697716"
     (#uuid "27ab1f8e-bc21-5003-a5f4-4e94fac9c885"
            #uuid "25e53d2d-eb18-55db-9812-feced33bc53c"),
     #uuid "0d316d18-cf30-5dc5-8cd5-5e30ac77fbe0"
     [#uuid "14a68aae-3372-5262-8a90-6196aa103f3a"],
     #uuid "2fed7878-1bc3-58b4-8717-99c42db984a5"
     [#uuid "31bf9909-8a9c-5707-991e-20d84e88d9ec"],
     #uuid "28258f5f-468e-5d97-8606-03d313ef4ad6"
     [#uuid "00c1cf1b-8f33-5d76-b5f4-33d70d241e7e"],
     #uuid "34c5905d-28dc-5934-8380-1735c0b00a52"
     [#uuid "17cecb00-3173-512a-9f6d-1a9163a0e1ba"],
     #uuid "0c7d3397-8bf2-56f1-82a1-2dd2b90388db"
     [#uuid "0fac861c-6be8-5396-af9e-792fde6a1897"],
     #uuid "3a8e9e67-9667-5116-a1b1-bab4bf2e708a"
     [#uuid "29b8db0f-4ce5-5a83-bb36-46d707228203"],
     #uuid "29e461c4-b0bc-5bd5-88da-53b28a9c6492"
     [#uuid "28c161f1-33d4-5edb-82e5-20a9745c5a9a"],
     #uuid "31ea53d8-d5b9-5fc6-8b75-73a27405152d"
     [#uuid "2fed7878-1bc3-58b4-8717-99c42db984a5"],
     #uuid "0fac861c-6be8-5396-af9e-792fde6a1897"
     [#uuid "2bf907aa-badb-56d9-be31-c2a5e56f3bb0"],
     #uuid "03aee177-6741-5b96-bf6a-037f0524abcc"
     [#uuid "37251587-e215-521f-b606-947e1929ea84"],
     #uuid "17b1aa38-8acd-5662-a5ee-fbc19af49f08"
     [#uuid "0c7d3397-8bf2-56f1-82a1-2dd2b90388db"],
     #uuid "2d1a2887-4cd9-5e34-b0b8-4a92cae86ab2"
     [#uuid "03aee177-6741-5b96-bf6a-037f0524abcc"],
     #uuid "28f00d3b-003e-5476-ac49-3abf1e3c4724"
     [#uuid "27fe07af-1a82-5249-b2f1-0da53727ecbd"],
     #uuid "3a21580c-2947-5bf7-a3fa-e96b6858a706"
     [#uuid "2ac96697-1b21-5428-ad79-f0e6532ac63f"],
     #uuid "0bf9a9db-523a-5700-b045-049efb307c73"
     [#uuid "28258f5f-468e-5d97-8606-03d313ef4ad6"],
     #uuid "11c093d9-b1ac-5e2b-9bd0-43d58343b9d1"
     [#uuid "3b4bde46-25f7-5ae1-992a-f79b6e4c7395"],
     #uuid "00f30a2f-75b5-55a4-85a5-d6fce48c0f40"
     [#uuid "12a87e54-7217-5f19-90ae-58572e284624"],
     #uuid "3c298755-6828-5bd0-a704-e74e7424591c"
     [#uuid "34c5905d-28dc-5934-8380-1735c0b00a52"],
     #uuid "30082adc-5c17-5def-ada7-7afdae6b7319"
     [#uuid "28c161f1-33d4-5edb-82e5-20a9745c5a9a"],
     #uuid "29b8db0f-4ce5-5a83-bb36-46d707228203"
     [#uuid "2722d185-d93f-5f1e-92d9-46ad7270313d"],
     #uuid "28c161f1-33d4-5edb-82e5-20a9745c5a9a"
     [#uuid "3a21580c-2947-5bf7-a3fa-e96b6858a706"],
     #uuid "30ed820a-23aa-58e8-a7d6-02ec059fe1b7"
     [#uuid "31ea53d8-d5b9-5fc6-8b75-73a27405152d"],
     #uuid "09e4567c-abf2-5c9f-88f5-d9bbe29ffa81"
     [#uuid "17b1aa38-8acd-5662-a5ee-fbc19af49f08"],
     #uuid "31bf9909-8a9c-5707-991e-20d84e88d9ec"
     [#uuid "0d316d18-cf30-5dc5-8cd5-5e30ac77fbe0"],
     #uuid "07dbd4ed-21c5-524b-a816-175b948d0207"
     [#uuid "09e4567c-abf2-5c9f-88f5-d9bbe29ffa81"],
     #uuid "27ab1f8e-bc21-5003-a5f4-4e94fac9c885"
     [#uuid "1af54379-00fe-515a-90de-64c37691f048"],
     #uuid "365205df-2cdc-5762-816f-e30b6071f86a"
     (#uuid "124ac771-1efd-5fc9-ae48-147f634d1c63"
            #uuid "00f30a2f-75b5-55a4-85a5-d6fce48c0f40"),
     #uuid "14aaa8ba-9a47-53bf-a6ef-ef2124d33c19"
     [#uuid "2f10ac48-0cc0-5d8f-aeaf-c9c53a14be2a"],
     #uuid "233ff6b5-ac35-58e0-9bd9-3b902d66991b"
     (#uuid "00f30a2f-75b5-55a4-85a5-d6fce48c0f40"
            #uuid "124ac771-1efd-5fc9-ae48-147f634d1c63"),
     #uuid "27fe07af-1a82-5249-b2f1-0da53727ecbd"
     [#uuid "148a0ad3-2339-504a-b380-6a33740cbd0e"],
     #uuid "12a87e54-7217-5f19-90ae-58572e284624"
     (#uuid "29e461c4-b0bc-5bd5-88da-53b28a9c6492"
            #uuid "30082adc-5c17-5def-ada7-7afdae6b7319")},
    :public false,
    :branches {"master" #{#uuid "19d0c810-4bfe-54dd-8ee3-901766697716"}},
    :head "master",
    :last-update #inst "2014-10-29T18:50:15.766-00:00",
    :id #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"}}



  ;; eve
  {#uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"
   {:description "topiq discourse.",
    :schema {:type "http://github.com/ghubber/geschichte", :version 1},
    :pull-requests {},
    :causal-order
    {#uuid "2d5526d8-8f0b-5763-b894-2b652ffe9fde"
     [#uuid "1d3a0bd2-27e4-542c-b3a5-31b4b47c6bef"],
     #uuid "183b45ec-abc0-598e-b501-989aa7062558"
     [#uuid "1b195e93-c19f-591c-abe8-0773a2736b56"],
     #uuid "14a68aae-3372-5262-8a90-6196aa103f3a"
     [#uuid "2d1a2887-4cd9-5e34-b0b8-4a92cae86ab2"],
     #uuid "11d23a61-e7a5-5b25-af99-9c594195b63d"
     [#uuid "2d5526d8-8f0b-5763-b894-2b652ffe9fde"],
     #uuid "17cecb00-3173-512a-9f6d-1a9163a0e1ba"
     [#uuid "11c093d9-b1ac-5e2b-9bd0-43d58343b9d1"],
     #uuid "37251587-e215-521f-b606-947e1929ea84"
     [#uuid "07dbd4ed-21c5-524b-a816-175b948d0207"],
     #uuid "26b8a07a-d91c-5a55-91a6-a896839ee19a"
     [#uuid "17576a16-66ca-52a2-ab94-5f5345534bd7"],
     #uuid "124ac771-1efd-5fc9-ae48-147f634d1c63"
     [#uuid "12a87e54-7217-5f19-90ae-58572e284624"],
     #uuid "148a0ad3-2339-504a-b380-6a33740cbd0e"
     [#uuid "39017a72-7ae1-5e52-a323-8ef3ee6d36ba"],
     #uuid "25e53d2d-eb18-55db-9812-feced33bc53c"
     [#uuid "1af54379-00fe-515a-90de-64c37691f048"],
     #uuid "00c1cf1b-8f33-5d76-b5f4-33d70d241e7e"
     [#uuid "3c298755-6828-5bd0-a704-e74e7424591c"],
     #uuid "1d3a0bd2-27e4-542c-b3a5-31b4b47c6bef"
     [#uuid "0bf9a9db-523a-5700-b045-049efb307c73"],
     #uuid "3640c7ea-c740-57db-befd-075ed16f7c8a"
     [#uuid "0ccc1efb-ef03-5f05-8148-c64a41176ed9"],
     #uuid "2ac96697-1b21-5428-ad79-f0e6532ac63f"
     [#uuid "28f00d3b-003e-5476-ac49-3abf1e3c4724"],
     #uuid "2bf907aa-badb-56d9-be31-c2a5e56f3bb0"
     [#uuid "3cf92159-70a0-57aa-a47d-0d8c724b3a99"],
     #uuid "335b6a4b-f24e-55fb-a32a-48d15d631787"
     [#uuid "3640c7ea-c740-57db-befd-075ed16f7c8a"],
     #uuid "3cf92159-70a0-57aa-a47d-0d8c724b3a99"
     [#uuid "32118080-b7c0-5fc5-afde-d717f3f0ebfb"],
     #uuid "09330520-4831-5136-87ca-18de60e37c20"
     [#uuid "335b6a4b-f24e-55fb-a32a-48d15d631787"],
     #uuid "061d8a1e-b0a8-55c4-8736-ed0e39f30b9c" [],
     #uuid "2f10ac48-0cc0-5d8f-aeaf-c9c53a14be2a"
     [#uuid "183b45ec-abc0-598e-b501-989aa7062558"],
     #uuid "1af54379-00fe-515a-90de-64c37691f048"
     [#uuid "233ff6b5-ac35-58e0-9bd9-3b902d66991b"],
     #uuid "39017a72-7ae1-5e52-a323-8ef3ee6d36ba"
     [#uuid "11d23a61-e7a5-5b25-af99-9c594195b63d"],
     #uuid "3b4bde46-25f7-5ae1-992a-f79b6e4c7395"
     [#uuid "30ed820a-23aa-58e8-a7d6-02ec059fe1b7"],
     #uuid "32118080-b7c0-5fc5-afde-d717f3f0ebfb"
     [#uuid "3a8e9e67-9667-5116-a1b1-bab4bf2e708a"],
     #uuid "1b195e93-c19f-591c-abe8-0773a2736b56"
     [#uuid "061d8a1e-b0a8-55c4-8736-ed0e39f30b9c"],
     #uuid "2722d185-d93f-5f1e-92d9-46ad7270313d"
     [#uuid "14aaa8ba-9a47-53bf-a6ef-ef2124d33c19"],
     #uuid "19d0c810-4bfe-54dd-8ee3-901766697716"
     [#uuid "25e53d2d-eb18-55db-9812-feced33bc53c"],
     #uuid "0d316d18-cf30-5dc5-8cd5-5e30ac77fbe0"
     [#uuid "14a68aae-3372-5262-8a90-6196aa103f3a"],
     #uuid "0ccc1efb-ef03-5f05-8148-c64a41176ed9"
     [#uuid "26b8a07a-d91c-5a55-91a6-a896839ee19a"],
     #uuid "2fed7878-1bc3-58b4-8717-99c42db984a5"
     [#uuid "31bf9909-8a9c-5707-991e-20d84e88d9ec"],
     #uuid "28258f5f-468e-5d97-8606-03d313ef4ad6"
     [#uuid "00c1cf1b-8f33-5d76-b5f4-33d70d241e7e"],
     #uuid "34c5905d-28dc-5934-8380-1735c0b00a52"
     [#uuid "17cecb00-3173-512a-9f6d-1a9163a0e1ba"],
     #uuid "084bf7a0-d353-5afc-96ea-1b3958511a0a"
     [#uuid "19d0c810-4bfe-54dd-8ee3-901766697716"],
     #uuid "0c7d3397-8bf2-56f1-82a1-2dd2b90388db"
     [#uuid "0fac861c-6be8-5396-af9e-792fde6a1897"],
     #uuid "3a8e9e67-9667-5116-a1b1-bab4bf2e708a"
     [#uuid "29b8db0f-4ce5-5a83-bb36-46d707228203"],
     #uuid "17576a16-66ca-52a2-ab94-5f5345534bd7"
     [#uuid "18bb8780-a264-5501-9794-aca484869f94"],
     #uuid "29e461c4-b0bc-5bd5-88da-53b28a9c6492"
     [#uuid "28c161f1-33d4-5edb-82e5-20a9745c5a9a"],
     #uuid "18bb8780-a264-5501-9794-aca484869f94"
     [#uuid "1f46f94c-4533-513f-a36d-f68871dd484f"],
     #uuid "31ea53d8-d5b9-5fc6-8b75-73a27405152d"
     [#uuid "2fed7878-1bc3-58b4-8717-99c42db984a5"],
     #uuid "0fac861c-6be8-5396-af9e-792fde6a1897"
     [#uuid "2bf907aa-badb-56d9-be31-c2a5e56f3bb0"],
     #uuid "03aee177-6741-5b96-bf6a-037f0524abcc"
     [#uuid "37251587-e215-521f-b606-947e1929ea84"],
     #uuid "17b1aa38-8acd-5662-a5ee-fbc19af49f08"
     [#uuid "0c7d3397-8bf2-56f1-82a1-2dd2b90388db"],
     #uuid "2d1a2887-4cd9-5e34-b0b8-4a92cae86ab2"
     [#uuid "03aee177-6741-5b96-bf6a-037f0524abcc"],
     #uuid "1f46f94c-4533-513f-a36d-f68871dd484f"
     [#uuid "084bf7a0-d353-5afc-96ea-1b3958511a0a"],
     #uuid "28f00d3b-003e-5476-ac49-3abf1e3c4724"
     [#uuid "27fe07af-1a82-5249-b2f1-0da53727ecbd"],
     #uuid "3a21580c-2947-5bf7-a3fa-e96b6858a706"
     [#uuid "2ac96697-1b21-5428-ad79-f0e6532ac63f"],
     #uuid "0bf9a9db-523a-5700-b045-049efb307c73"
     [#uuid "28258f5f-468e-5d97-8606-03d313ef4ad6"],
     #uuid "11c093d9-b1ac-5e2b-9bd0-43d58343b9d1"
     [#uuid "3b4bde46-25f7-5ae1-992a-f79b6e4c7395"],
     #uuid "3c298755-6828-5bd0-a704-e74e7424591c"
     [#uuid "34c5905d-28dc-5934-8380-1735c0b00a52"],
     #uuid "29b8db0f-4ce5-5a83-bb36-46d707228203"
     [#uuid "2722d185-d93f-5f1e-92d9-46ad7270313d"],
     #uuid "28c161f1-33d4-5edb-82e5-20a9745c5a9a"
     [#uuid "3a21580c-2947-5bf7-a3fa-e96b6858a706"],
     #uuid "30ed820a-23aa-58e8-a7d6-02ec059fe1b7"
     [#uuid "31ea53d8-d5b9-5fc6-8b75-73a27405152d"],
     #uuid "09e4567c-abf2-5c9f-88f5-d9bbe29ffa81"
     [#uuid "17b1aa38-8acd-5662-a5ee-fbc19af49f08"],
     #uuid "31bf9909-8a9c-5707-991e-20d84e88d9ec"
     [#uuid "0d316d18-cf30-5dc5-8cd5-5e30ac77fbe0"],
     #uuid "07dbd4ed-21c5-524b-a816-175b948d0207"
     [#uuid "09e4567c-abf2-5c9f-88f5-d9bbe29ffa81"],
     #uuid "14aaa8ba-9a47-53bf-a6ef-ef2124d33c19"
     [#uuid "2f10ac48-0cc0-5d8f-aeaf-c9c53a14be2a"],
     #uuid "233ff6b5-ac35-58e0-9bd9-3b902d66991b"
     [#uuid "124ac771-1efd-5fc9-ae48-147f634d1c63"],
     #uuid "27fe07af-1a82-5249-b2f1-0da53727ecbd"
     [#uuid "148a0ad3-2339-504a-b380-6a33740cbd0e"],
     #uuid "12a87e54-7217-5f19-90ae-58572e284624"
     [#uuid "29e461c4-b0bc-5bd5-88da-53b28a9c6492"]},
    :public false,
    :branches {"master" #{#uuid "09330520-4831-5136-87ca-18de60e37c20"}},
    :head "master",
    :last-update #inst "2014-10-29T19:32:16.720-00:00",
    :id #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"}}


  ;; run server with log
  ;; cause a conflict
  ;; find out how hook worked.

  (when merge-order-fn
    (go (<! (timeout (rand-int 10000)))
        (let [merged
              (r/merge {:meta b-state}
                       b-user b-branch
                       a-state
                       (<! (merge-order-fn
                            store
                            (r/merge-heads a-state a-branch
                                           b-state b-branch))))
              new-commits (set/difference (-> merged :meta :causal-order keys set)
                                          (-> b-state :causal-order keys set))]
          (when (and (not (<! (inducing-conflict-pull!? atomic-pull-store
                                                        [b-user b-repo b-branch]
                                                        (:meta merged))))
                     (<! (integrity-fn store new-commits)))

            (debug "Merging: " e b-state b-user b-branch a-state)
            (doseq [[id value] (get-in merged [:new-values b-branch])]
              (<! (-assoc-in store [id] value)))
            (>! delayed-merge-chan {:topic :meta-pub
                                    :metas {b-user {b-repo (:state merged)}}})))))


(defn default-order
  "Sort UUIDs in a globally consistent way, assumes commutatity!
  Returns a sorted list of new heads."
  [store order]
  (go (sort (fn [a b] (compare (str a) (str b)))
            order))))
