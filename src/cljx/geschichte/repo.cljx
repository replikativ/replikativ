(ns geschichte.repo
  "Implementing core repository functions.
   Use this namespace to manage your repositories.

   Metadata is designed as a commutative replicative data type, so it
   can be synched between different servers without coordination. Don't
   add fields as this is part of the network specification."
  (:refer-clojure :exclude [merge])
  (:require [clojure.set :as set]
            [hasch.core :refer [uuid]]
            [geschichte.platform :refer [now]]
            [geschichte.platform-log :refer [debug info]]
            [geschichte.meta :refer [consistent-causal? lowest-common-ancestors
                                     merge-ancestors isolate-branch]]))


(def ^:dynamic *id-fn*
  "DO NOT REBIND EXCEPT FOR TESTING OR YOU MIGHT CORRUPT DATA.
   Determines unique ids, possibly from a value.
   Hashed UUID (from hasch) is defined as public format."
  uuid)

(def ^:dynamic *date-fn*
  "DO NOT REBIND EXCEPT FOR TESTING."
  now)

;; standardisation for blob commits (used by stage)
(def ^:dynamic *custom-store-fn* nil)

(def store-blob-trans-value
  "Transparent transaction function value to just store (binary) blobs.
  Rebind *custom-store-fn* to track when this happens."
  '(fn store-blob-trans [old params]
     (if *custom-store-fn*
       (*custom-store-fn* old params)
       old)))

(def store-blob-trans-id (*id-fn* store-blob-trans-value))

(defn store-blob-trans [old params]
  "Transparent transaction function value to just store (binary) blobs.
  Rebing *custom-store-fn* to be track when this happens."
  (if *custom-store-fn*
    (*custom-store-fn* old params)
    old))


(defn new-repository
  "Create a (unique) repository for an initial value. Returns a map with
   new metadata and initial commit value in branch \"master."
  [author description & {:keys [is-public?] :or {is-public? false}}]
  (let [now (*date-fn*)
        commit-val {:transactions [] ;; common base commit (not allowed elsewhere)
                   :parents []
                   :ts now
                   :author author}
        commit-id (*id-fn* (dissoc commit-val :ts :author))
        repo-id (*id-fn*)
        branch "master"
        new-meta  {:id repo-id
                   :description description
                   :schema {:type "http://github.com/ghubber/geschichte"
                            :version 1}
                   :public is-public?
                   :causal-order {commit-id []}
                   :branches {branch #{commit-id}}
                   :head branch
                   :last-update now
                   :pull-requests {}}]
    {:meta new-meta

     :transactions {branch []}
     :op :meta-sub
     :new-values {branch {commit-id commit-val}}}))


(defn fork
  "Fork (clone) a remote branch as your working copy.
   Pull in more branches as needed separately."
  [remote-meta branch is-public]
  (let [branch-meta (-> remote-meta :branches (get branch))
        meta {:id (:id remote-meta)
              :description (:description remote-meta)
              :schema (:schema remote-meta)
              :causal-order (isolate-branch remote-meta branch)
              :branches {branch branch-meta}
              :head branch
              :last-update (*date-fn*)
              :pull-requests {}}]
    {:meta meta

     :transactions {branch []}
     :op :meta-sub}))

(defn- raw-commit
  "Commits to meta in branch with a value for an ordered set of parents.
   Returns a map with metadata and value+inlined metadata."
  [{:keys [meta transactions] :as repo} parents author branch]
  (when-not (consistent-causal? (:causal-order meta))
    (throw (ex-info "Causal order does not contain commits of all referenced parents."
                    {:type :inconsistent-causal-order
                     :meta meta})))
  (when (empty? transactions)
    (throw (ex-info "No transactions to commit."
                     {:type :no-transactions
                      :repo repo
                      :branch branch})))
  (let [branch-heads (get-in meta [:branches branch])
        ts (*date-fn*)
        ;; turn trans-pairs into new-values
        btrans (get transactions branch)
        trans-ids (mapv (fn [[trans-fn params]]
                          [(*id-fn* trans-fn) (*id-fn* params)]) btrans)
        commit-value {:transactions trans-ids
                      :ts ts
                      :parents (vec parents)
                      :author author}
        id (*id-fn* (select-keys commit-value #{:transactions :parents}))
        new-meta (-> meta
                     (assoc-in [:causal-order id] (vec parents))
                     (update-in [:branches branch] set/difference (set parents))
                     (update-in [:branches branch] conj id)
                     (assoc-in [:last-update] ts))
        new-values (clojure.core/merge
                    {id commit-value}
                    (zipmap (apply concat trans-ids)
                            (apply concat btrans)))]
    (debug "committing to " branch ": " id commit-value)
    (-> repo
        (assoc :meta new-meta :op :meta-pub)
        (assoc-in [:transactions branch] [])
        (update-in [:new-values branch] clojure.core/merge new-values))))


(defn commit
  "Commits to meta in branch with a value for a set of parents.
   Returns a map with metadata and value+inlined metadata."
  [repo author branch]
  (let [heads (get-in repo [:meta :branches branch])]
    (if (= (count heads) 1)
      (raw-commit repo (vec heads) author branch)
      (throw (ex-info "Branch has multiple heads."
                      {:type :multiple-branch-heads
                       :meta (:meta repo)
                       :branch branch
                       :heads heads})))))


(defn branch
  "Create a new branch with parent."
  [{:keys [meta] :as repo} name parent]
  (when (get-in meta [:branches name])
    (throw (ex-info "Branch already exists."
                    {:type :branch-exists
                     :branch name})))
  (let [new-meta (-> meta
                     (assoc-in [:branches name] #{parent})
                     (assoc-in [:last-update] (*date-fn*)))]
    (-> repo
        (assoc :meta new-meta :op :meta-pub)
        (assoc-in [:transactions name] []))))


(defn checkout
  "Checkout a branch."
  [{:keys [meta] :as repo} branch]
  (let [new-meta (assoc (:meta repo)
                   :head branch
                   :last-update (*date-fn*))]
    (assoc repo
      :meta new-meta
      :op :meta-pub)))


(defn multiple-branch-heads?
  "Checks whether branch has multiple heads."
  [meta branch]
  (> (count (get-in meta [:branches branch])) 1))


(defn pull
  "Pull all commits into branch from remote-tip (only its ancestors)."
  ([repo branch remote-meta remote-tip] (pull repo branch remote-meta remote-tip false))
  ([{:keys [meta] :as repo} branch remote-meta remote-tip allow-induced-conflict?]
     (when (and (not allow-induced-conflict?)
                (multiple-branch-heads? meta branch))
       (throw (ex-info "Cannot pull into conflicting repository, use merge instead."
                       {:type :conflicting-meta
                        :meta meta
                        :branch branch
                        :heads (get-in meta [:branches branch])})))
     (when (meta remote-tip)
       (throw (ex-info "No pull necessary."
                       {:type :pull-unnecessary
                        :meta meta
                        :branch branch
                        :remote-meta remote-meta
                        :remote-tip remote-tip})))
     (let [branch-heads (get-in meta [:branches branch])
           {:keys [cut returnpaths-a returnpaths-b]}
           (lowest-common-ancestors (:causal-order meta) branch-heads
                                    (:causal-order remote-meta) #{remote-tip})
           remote-causal (isolate-branch (:causal-order remote-meta) #{remote-tip} {})
           new-meta (-> meta
                        (assoc-in [:last-update] (if (< (compare (:last-update meta) (:last-update remote-meta)) 0)
                                                   (:last-update remote-meta)
                                                   (:last-update meta)))
                        #_(update-in [:causal-order] merge-ancestors cut returnpaths-b)
                        (update-in [:causal-order] #(clojure.core/merge remote-causal %))
                        (update-in [:branches branch] set/difference branch-heads)
                        (update-in [:branches branch] conj remote-tip))
           new-causal (:causal-order new-meta)]
       (when (and (not allow-induced-conflict?)
                  (not (set/superset? cut branch-heads)))
         (throw (ex-info "Remote meta is not pullable (a superset). "
                         {:type :not-superset
                          :meta meta
                          :branch branch
                          :remote-meta remote-meta
                          :remote-tip remote-tip
                          :cut cut})))
       (when (and (not allow-induced-conflict?)
                  (multiple-branch-heads? new-meta branch))
         (throw (ex-info "Cannot pull without inducing conflict, use merge instead."
                         {:type :multiple-branch-heads
                          :meta new-meta
                          :branch branch
                          :heads (get-in new-meta [:branches branch])})))
       (debug "pulling: from cut " cut " returnpaths: " returnpaths-b " new meta: " new-meta)
       (assoc repo
         :meta new-meta
         :op :meta-pub))))


(defn merge-heads
  "Constructs a vector of heads. You can reorder them."
  [meta-a branch-a meta-b branch-b]
  (let [heads-a (get-in meta-a [:branches branch-a])
        heads-b (get-in meta-b [:branches branch-b])]
    (distinct (concat heads-a heads-b))))


(defn merge
  "Merge a repository either with itself, or with remote metadata and
optionally supply the order in which parent commits should be
supplied. Otherwise see merge-heads how to get and manipulate them."
  ([{:keys [meta] :as repo} author branch]
     (merge repo author branch meta))
  ([{:keys [meta] :as repo} author branch remote-meta]
     (merge repo author branch remote-meta (merge-heads meta branch remote-meta branch)))
  ([{:keys [meta] :as repo} author branch remote-meta heads]
     (let [source-heads (get-in meta [:branches branch])
           remote-heads (get-in remote-meta [:branches branch])
           lcas (lowest-common-ancestors (:causal-order meta)
                                         source-heads
                                         (:causal-order remote-meta)
                                         remote-heads)
           new-causal (merge-ancestors (:causal-order meta) (:cut lcas) (:returnpaths-b lcas))]
       (debug "merging: into " author (:id meta) lcas)
       (raw-commit (assoc-in repo [:meta :causal-order] new-causal) (vec heads) author branch))))



(comment

  (def repo-a {:description "topiq discourse.",
               :schema {:type "http://github.com/ghubber/geschichte", :version 1},
               :pull-requests {},
               :causal-order
               {#uuid "193ecad3-3dd6-520d-b1b8-e940dc240d9e"
                [#uuid "22c0a518-0147-5178-9c3f-d602d1edf801"
                 #uuid "259bdc0a-c928-58b9-9f8e-27e810fc4e66"],
                #uuid "0d988d74-169b-5291-85c0-9f6c7770fc29"
                [#uuid "1fd5a42f-73c9-523e-b5d5-6290e01b8035"],
                #uuid "2380d26f-7d0b-5608-a945-d5047475e743"
                [#uuid "1ededcbb-2338-5fa4-ba05-1002f2432147"],
                #uuid "27fe4598-ca9b-5aa2-af9b-022fa0170434"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "1fe0192b-3432-5379-b119-37a43803acdf"],
                #uuid "38db351c-e4c6-5afe-a848-e49ecdb04c5d"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "3e917467-8760-54d5-8180-52e06a45bdda"],
                #uuid "26d7c639-df02-509b-a72f-72b30ea38390"
                [#uuid "22839968-b992-5b83-b37d-67ec4c2e6f5e"
                 #uuid "236a1dd2-8990-521d-baf6-ddbbb220d94e"],
                #uuid "2e6eb3aa-6466-54bb-80c7-4c6c7dac9b3f"
                [#uuid "2380d26f-7d0b-5608-a945-d5047475e743"
                 #uuid "24c2cca0-2f41-57ec-97c9-1ad22262d30f"],
                #uuid "2c83e314-37f1-52b1-b5b3-a76fba623368"
                [#uuid "12330d72-fb1f-5bfb-b118-1d9a2ae69a1a"
                 #uuid "1ededcbb-2338-5fa4-ba05-1002f2432147"],
                #uuid "011bf147-88dc-5f49-8b39-5f5e7fe3f7b2"
                [#uuid "257fdac9-d674-5dbd-9a6e-19ac5ceebf57"
                 #uuid "257fdac9-d674-5dbd-9a6e-19ac5ceebf57"
                 #uuid "1367ae2f-63aa-5b2e-830d-a5128312bbbb"
                 #uuid "1e2b5716-599e-553a-a255-ada32139123f"],
                #uuid "3f35c137-8dbf-52e5-b4e8-6ba84b244599"
                [#uuid "2380d26f-7d0b-5608-a945-d5047475e743"
                 #uuid "2c83e314-37f1-52b1-b5b3-a76fba623368"],
                #uuid "22c0a518-0147-5178-9c3f-d602d1edf801"
                [#uuid "072b5485-8325-55fd-981b-a749e09d333a"],
                #uuid "35a7db45-3478-553e-8c63-755253c96caa"
                [#uuid "2d897608-ea72-556f-906d-35e5eb5bbff9"
                 #uuid "193ecad3-3dd6-520d-b1b8-e940dc240d9e"],
                #uuid "0ad48d1c-3f03-59b8-85d6-52cb0d262f4a"
                [#uuid "1e4620ef-d099-5dfe-9bf6-abaaff96162f"],
                #uuid "0b82153c-6b7f-5abf-9aad-8f2364ddb545"
                [#uuid "335105d2-24d8-50ac-b99b-f8974bdd9c31"
                 #uuid "35a7db45-3478-553e-8c63-755253c96caa"],
                #uuid "3165f35c-2f8c-5395-b52a-ea5a4a0acdd5"
                [#uuid "1de1d686-a279-5f03-885b-c9a5c022ee77"
                 #uuid "0663c69e-e76b-5612-8304-b7c59cb600ba"],
                #uuid "0b0618b9-d919-56da-886d-47e05d3afa25"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "265ed783-715b-587f-8f38-39fe7dad9533"],
                #uuid "1b6c120a-4103-5b87-aff2-5ca4a0b14dae"
                [#uuid "1c43e64b-2118-59f0-9b6b-a5079bf714e5"
                 #uuid "12dc0dc3-40c6-57c4-9282-b2bc51d7bff7"],
                #uuid "061d8a1e-b0a8-55c4-8736-ed0e39f30b9c" [],
                #uuid "0da8c830-d07a-5031-8898-374d216fba84"
                [#uuid "061d8a1e-b0a8-55c4-8736-ed0e39f30b9c"],
                #uuid "3e917467-8760-54d5-8180-52e06a45bdda"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "1cbc8268-108f-5519-95cc-910ed426dcf5"],
                #uuid "12c6e8c5-19f8-56b2-8afb-6d0b208d80f4"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "27fe4598-ca9b-5aa2-af9b-022fa0170434"],
                #uuid "1367ae2f-63aa-5b2e-830d-a5128312bbbb"
                [#uuid "257fdac9-d674-5dbd-9a6e-19ac5ceebf57"],
                #uuid "3db41cfa-8879-539f-8128-63ae816ccc02"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "09d1086c-de40-54d6-84df-9ebf2bb80cde"],
                #uuid "12330d72-fb1f-5bfb-b118-1d9a2ae69a1a"
                [#uuid "3838f7b0-5c65-53a6-93b2-367fe9cfc23e"],
                #uuid "1fe0192b-3432-5379-b119-37a43803acdf"
                [#uuid "2d466d00-cb88-58d3-94aa-5544eb295266"
                 #uuid "0fc757c9-c6f2-5190-be53-2dd95fdf541a"],
                #uuid "37b376b8-db77-5c7a-abf7-373e18575976"
                [#uuid "26d7c639-df02-509b-a72f-72b30ea38390"
                 #uuid "098097a5-c58d-5e50-9a0e-0e4fdf034e64"],
                #uuid "1cbc8268-108f-5519-95cc-910ed426dcf5"
                [#uuid "2c83e314-37f1-52b1-b5b3-a76fba623368"
                 #uuid "3f35c137-8dbf-52e5-b4e8-6ba84b244599"
                 #uuid "24c2cca0-2f41-57ec-97c9-1ad22262d30f"
                 #uuid "2e6eb3aa-6466-54bb-80c7-4c6c7dac9b3f"
                 #uuid "2acac649-85c9-53c1-9478-0ec3dbc8ab5b"
                 #uuid "04d5baec-3b9c-5be7-afaf-ab0e824b56fd"
                 #uuid "23e03369-837a-5a33-849a-b49b6ff4fbd9"
                 #uuid "35596c69-0848-5015-90fe-23a6fc894bd6"
                 #uuid "35fc8eaf-4c87-5cef-8781-dcbac2abc855"
                 #uuid "2ac92664-4dfa-51e1-81a2-9c4e6f2cdba7"
                 #uuid "2d466d00-cb88-58d3-94aa-5544eb295266"
                 #uuid "0fc757c9-c6f2-5190-be53-2dd95fdf541a"
                 #uuid "1fe0192b-3432-5379-b119-37a43803acdf"
                 #uuid "27fe4598-ca9b-5aa2-af9b-022fa0170434"
                 #uuid "12c6e8c5-19f8-56b2-8afb-6d0b208d80f4"
                 #uuid "1c02bc89-4789-5482-af11-42bba5899204"
                 #uuid "3c84108f-169b-5acb-8bb1-dcd6cfb4cbe7"
                 #uuid "265ed783-715b-587f-8f38-39fe7dad9533"
                 #uuid "0b0618b9-d919-56da-886d-47e05d3afa25"
                 #uuid "2f62c6c7-2615-5dae-95e1-6d6f8f1d4258"
                 #uuid "1c43e64b-2118-59f0-9b6b-a5079bf714e5"
                 #uuid "12dc0dc3-40c6-57c4-9282-b2bc51d7bff7"
                 #uuid "1b6c120a-4103-5b87-aff2-5ca4a0b14dae"
                 #uuid "0c29c9da-6c8a-524c-a1f8-cf2b9f31e480"
                 #uuid "0980479a-20bf-56b9-8bfa-1d80449feef6"
                 #uuid "2c83e314-37f1-52b1-b5b3-a76fba623368"
                 #uuid "3f35c137-8dbf-52e5-b4e8-6ba84b244599"
                 #uuid "24c2cca0-2f41-57ec-97c9-1ad22262d30f"
                 #uuid "2e6eb3aa-6466-54bb-80c7-4c6c7dac9b3f"
                 #uuid "2acac649-85c9-53c1-9478-0ec3dbc8ab5b"
                 #uuid "04d5baec-3b9c-5be7-afaf-ab0e824b56fd"
                 #uuid "23e03369-837a-5a33-849a-b49b6ff4fbd9"
                 #uuid "35596c69-0848-5015-90fe-23a6fc894bd6"
                 #uuid "35fc8eaf-4c87-5cef-8781-dcbac2abc855"
                 #uuid "2ac92664-4dfa-51e1-81a2-9c4e6f2cdba7"
                 #uuid "2d466d00-cb88-58d3-94aa-5544eb295266"
                 #uuid "0fc757c9-c6f2-5190-be53-2dd95fdf541a"
                 #uuid "1fe0192b-3432-5379-b119-37a43803acdf"
                 #uuid "27fe4598-ca9b-5aa2-af9b-022fa0170434"
                 #uuid "12c6e8c5-19f8-56b2-8afb-6d0b208d80f4"
                 #uuid "1c02bc89-4789-5482-af11-42bba5899204"
                 #uuid "3c84108f-169b-5acb-8bb1-dcd6cfb4cbe7"
                 #uuid "265ed783-715b-587f-8f38-39fe7dad9533"
                 #uuid "0b0618b9-d919-56da-886d-47e05d3afa25"
                 #uuid "2f62c6c7-2615-5dae-95e1-6d6f8f1d4258"],
                #uuid "3e443ec9-4245-5b8e-805e-b563105e774e"
                [#uuid "0da8c830-d07a-5031-8898-374d216fba84"],
                #uuid "14509f2a-4a1d-5a56-b6a5-3198bf3a170e"
                [#uuid "2a748c0e-2d1c-5646-ba1c-47f5001e25e6"],
                #uuid "287a02f9-db45-5027-a2e6-06727c893f46"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "3db41cfa-8879-539f-8128-63ae816ccc02"],
                #uuid "072b5485-8325-55fd-981b-a749e09d333a"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "3165f35c-2f8c-5395-b52a-ea5a4a0acdd5"],
                #uuid "098d7b81-e25a-576c-913d-a7319129d897"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "322ad887-13db-53c6-ab9b-3bc6fee7f3db"],
                #uuid "0a95a27e-c3cf-51ab-afce-fafdf4b1de43"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "1e9cc7b2-d46f-586a-b545-a710225282d6"],
                #uuid "3d85df7b-5a9e-571a-995d-b9881243ee82"
                [#uuid "26d7c639-df02-509b-a72f-72b30ea38390"
                 #uuid "098097a5-c58d-5e50-9a0e-0e4fdf034e64"],
                #uuid "35596c69-0848-5015-90fe-23a6fc894bd6"
                [#uuid "04d5baec-3b9c-5be7-afaf-ab0e824b56fd"
                 #uuid "23e03369-837a-5a33-849a-b49b6ff4fbd9"],
                #uuid "2ac92664-4dfa-51e1-81a2-9c4e6f2cdba7"
                [#uuid "35596c69-0848-5015-90fe-23a6fc894bd6"
                 #uuid "35fc8eaf-4c87-5cef-8781-dcbac2abc855"],
                #uuid "267d751f-0b86-52f7-a47f-d4cab6c6cfae"
                [#uuid "0c29c9da-6c8a-524c-a1f8-cf2b9f31e480"
                 #uuid "1b5d2c1c-7af4-568f-b120-3ab71214539e"],
                #uuid "0c98fea3-08cc-5398-86c4-37c3c6817f94"
                [#uuid "3d85df7b-5a9e-571a-995d-b9881243ee82"
                 #uuid "37b376b8-db77-5c7a-abf7-373e18575976"],
                #uuid "0ab10dc3-898e-58eb-9418-5fbe08f5d5ba"
                [#uuid "011bf147-88dc-5f49-8b39-5f5e7fe3f7b2"
                 #uuid "1367ae2f-63aa-5b2e-830d-a5128312bbbb"
                 #uuid "1e2b5716-599e-553a-a255-ada32139123f"],
                #uuid "12dc0dc3-40c6-57c4-9282-b2bc51d7bff7"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "1c43e64b-2118-59f0-9b6b-a5079bf714e5"],
                #uuid "0ff722ff-5dff-5228-9114-064116363658"
                [#uuid "287a02f9-db45-5027-a2e6-06727c893f46"],
                #uuid "257fdac9-d674-5dbd-9a6e-19ac5ceebf57"
                [#uuid "066877cb-cdb4-5aa6-a4a7-42a90f563c74"],
                #uuid "1a82a692-4b68-5edb-b346-e3c68df7a180"
                [#uuid "14509f2a-4a1d-5a56-b6a5-3198bf3a170e"],
                #uuid "35fc8eaf-4c87-5cef-8781-dcbac2abc855"
                [#uuid "23e03369-837a-5a33-849a-b49b6ff4fbd9"
                 #uuid "35596c69-0848-5015-90fe-23a6fc894bd6"],
                #uuid "2acac649-85c9-53c1-9478-0ec3dbc8ab5b"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "2e6eb3aa-6466-54bb-80c7-4c6c7dac9b3f"],
                #uuid "1e2b5716-599e-553a-a255-ada32139123f"
                [#uuid "066877cb-cdb4-5aa6-a4a7-42a90f563c74"
                 #uuid "1367ae2f-63aa-5b2e-830d-a5128312bbbb"],
                #uuid "0cc35715-7530-5ee0-a33b-13c6aa116023"
                [#uuid "37b376b8-db77-5c7a-abf7-373e18575976"
                 #uuid "3d85df7b-5a9e-571a-995d-b9881243ee82"],
                #uuid "066877cb-cdb4-5aa6-a4a7-42a90f563c74"
                [#uuid "0d988d74-169b-5291-85c0-9f6c7770fc29"],
                #uuid "24c2cca0-2f41-57ec-97c9-1ad22262d30f"
                [#uuid "27ba4fef-6620-528a-8760-03fed35d27a9"
                 #uuid "3f35c137-8dbf-52e5-b4e8-6ba84b244599"],
                #uuid "2d466d00-cb88-58d3-94aa-5544eb295266"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "2ac92664-4dfa-51e1-81a2-9c4e6f2cdba7"],
                #uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                [#uuid "2380d26f-7d0b-5608-a945-d5047475e743"],
                #uuid "265ed783-715b-587f-8f38-39fe7dad9533"
                [#uuid "1c02bc89-4789-5482-af11-42bba5899204"
                 #uuid "3c84108f-169b-5acb-8bb1-dcd6cfb4cbe7"],
                #uuid "27ba4fef-6620-528a-8760-03fed35d27a9"
                [#uuid "12330d72-fb1f-5bfb-b118-1d9a2ae69a1a"],
                #uuid "10b412fb-8ab6-54e3-9cdf-6858fbe7cc32"
                [#uuid "04d57671-a4e5-5cca-93d5-f4f5ccf6b85a"],
                #uuid "0663c69e-e76b-5612-8304-b7c59cb600ba"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "0bcbc247-142f-5b1c-8616-a4378265debb"],
                #uuid "1e4620ef-d099-5dfe-9bf6-abaaff96162f"
                [#uuid "267d751f-0b86-52f7-a47f-d4cab6c6cfae"],
                #uuid "1fd5a42f-73c9-523e-b5d5-6290e01b8035"
                [#uuid "05dfc6d2-731c-54d3-a879-bea754e69452"],
                #uuid "1c02bc89-4789-5482-af11-42bba5899204"
                [#uuid "27fe4598-ca9b-5aa2-af9b-022fa0170434"
                 #uuid "12c6e8c5-19f8-56b2-8afb-6d0b208d80f4"],
                #uuid "05dfc6d2-731c-54d3-a879-bea754e69452"
                [#uuid "1a82a692-4b68-5edb-b346-e3c68df7a180"],
                #uuid "04d57671-a4e5-5cca-93d5-f4f5ccf6b85a"
                [#uuid "3e443ec9-4245-5b8e-805e-b563105e774e"],
                #uuid "1b8236da-3192-5d67-90b6-98e41de6300d"
                [#uuid "0ff722ff-5dff-5228-9114-064116363658"
                 #uuid "1e67774c-8cda-5e16-bd5a-0f641e639ba7"],
                #uuid "3c84108f-169b-5acb-8bb1-dcd6cfb4cbe7"
                [#uuid "12c6e8c5-19f8-56b2-8afb-6d0b208d80f4"
                 #uuid "1c02bc89-4789-5482-af11-42bba5899204"],
                #uuid "0980479a-20bf-56b9-8bfa-1d80449feef6"
                [#uuid "1b6c120a-4103-5b87-aff2-5ca4a0b14dae"
                 #uuid "0c29c9da-6c8a-524c-a1f8-cf2b9f31e480"],
                #uuid "3838f7b0-5c65-53a6-93b2-367fe9cfc23e"
                [#uuid "1e110215-5277-5fbb-8866-3456a3c5b965"],
                #uuid "2175f837-e00f-58eb-be7c-3cd13016659a"
                [#uuid "1e9cc7b2-d46f-586a-b545-a710225282d6"],
                #uuid "1de1d686-a279-5f03-885b-c9a5c022ee77"
                [#uuid "0bcbc247-142f-5b1c-8616-a4378265debb"],
                #uuid "0bcbc247-142f-5b1c-8616-a4378265debb"
                [#uuid "2175f837-e00f-58eb-be7c-3cd13016659a"
                 #uuid "0a95a27e-c3cf-51ab-afce-fafdf4b1de43"],
                #uuid "04d5baec-3b9c-5be7-afaf-ab0e824b56fd"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "2acac649-85c9-53c1-9478-0ec3dbc8ab5b"],
                #uuid "335105d2-24d8-50ac-b99b-f8974bdd9c31"
                [#uuid "193ecad3-3dd6-520d-b1b8-e940dc240d9e"
                 #uuid "2d897608-ea72-556f-906d-35e5eb5bbff9"],
                #uuid "23e03369-837a-5a33-849a-b49b6ff4fbd9"
                [#uuid "2acac649-85c9-53c1-9478-0ec3dbc8ab5b"
                 #uuid "04d5baec-3b9c-5be7-afaf-ab0e824b56fd"],
                #uuid "1e67774c-8cda-5e16-bd5a-0f641e639ba7"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "287a02f9-db45-5027-a2e6-06727c893f46"],
                #uuid "1c43e64b-2118-59f0-9b6b-a5079bf714e5"
                [#uuid "0b0618b9-d919-56da-886d-47e05d3afa25"
                 #uuid "2f62c6c7-2615-5dae-95e1-6d6f8f1d4258"],
                #uuid "22839968-b992-5b83-b37d-67ec4c2e6f5e"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "0cf71f20-a505-56b8-bb3e-396e4a1bd3cf"],
                #uuid "0cf71f20-a505-56b8-bb3e-396e4a1bd3cf"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "0b82153c-6b7f-5abf-9aad-8f2364ddb545"],
                #uuid "1ededcbb-2338-5fa4-ba05-1002f2432147"
                [#uuid "27ba4fef-6620-528a-8760-03fed35d27a9"],
                #uuid "236a1dd2-8990-521d-baf6-ddbbb220d94e"
                [#uuid "0cf71f20-a505-56b8-bb3e-396e4a1bd3cf"],
                #uuid "0fc757c9-c6f2-5190-be53-2dd95fdf541a"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "2d466d00-cb88-58d3-94aa-5544eb295266"],
                #uuid "1b5d2c1c-7af4-568f-b120-3ab71214539e"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "38db351c-e4c6-5afe-a848-e49ecdb04c5d"],
                #uuid "2a748c0e-2d1c-5646-ba1c-47f5001e25e6"
                [#uuid "10b412fb-8ab6-54e3-9cdf-6858fbe7cc32"],
                #uuid "2d897608-ea72-556f-906d-35e5eb5bbff9"
                [#uuid "22c0a518-0147-5178-9c3f-d602d1edf801"
                 #uuid "259bdc0a-c928-58b9-9f8e-27e810fc4e66"],
                #uuid "09d1086c-de40-54d6-84df-9ebf2bb80cde"
                [#uuid "098d7b81-e25a-576c-913d-a7319129d897"
                 #uuid "35ef1a73-a835-5fe6-8e22-47b6d047159f"],
                #uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                [#uuid "0ad48d1c-3f03-59b8-85d6-52cb0d262f4a"],
                #uuid "1dcd8025-a66b-5452-89ac-27f709ed33bc"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "1b8236da-3192-5d67-90b6-98e41de6300d"],
                #uuid "322ad887-13db-53c6-ab9b-3bc6fee7f3db"
                [#uuid "267d751f-0b86-52f7-a47f-d4cab6c6cfae"
                 #uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"],
                #uuid "35ef1a73-a835-5fe6-8e22-47b6d047159f"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "322ad887-13db-53c6-ab9b-3bc6fee7f3db"],
                #uuid "0c29c9da-6c8a-524c-a1f8-cf2b9f31e480"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "1b6c120a-4103-5b87-aff2-5ca4a0b14dae"],
                #uuid "1e110215-5277-5fbb-8866-3456a3c5b965"
                [#uuid "0ab10dc3-898e-58eb-9418-5fbe08f5d5ba"],
                #uuid "0bd96dbf-f1ee-5ff3-9459-65dff046ff6e"
                [#uuid "0cc35715-7530-5ee0-a33b-13c6aa116023"
                 #uuid "0c98fea3-08cc-5398-86c4-37c3c6817f94"],
                #uuid "259bdc0a-c928-58b9-9f8e-27e810fc4e66"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "072b5485-8325-55fd-981b-a749e09d333a"],
                #uuid "2f62c6c7-2615-5dae-95e1-6d6f8f1d4258"
                [#uuid "265ed783-715b-587f-8f38-39fe7dad9533"
                 #uuid "0b0618b9-d919-56da-886d-47e05d3afa25"],
                #uuid "098097a5-c58d-5e50-9a0e-0e4fdf034e64"
                [#uuid "236a1dd2-8990-521d-baf6-ddbbb220d94e"
                 #uuid "22839968-b992-5b83-b37d-67ec4c2e6f5e"],
                #uuid "1e9cc7b2-d46f-586a-b545-a710225282d6"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "1dcd8025-a66b-5452-89ac-27f709ed33bc"]},
               :public false,
               :branches {"master" #{#uuid "0bd96dbf-f1ee-5ff3-9459-65dff046ff6e"}},
               :head "master",
               :last-update #inst "2014-10-19T23:35:17.577-00:00",
               :id #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"})

  (def repo-b {:description "topiq discourse.",
               :schema {:type "http://github.com/ghubber/geschichte", :version 1},
               :pull-requests {},
               :causal-order
               {#uuid "193ecad3-3dd6-520d-b1b8-e940dc240d9e"
                [#uuid "259bdc0a-c928-58b9-9f8e-27e810fc4e66"
                 #uuid "22c0a518-0147-5178-9c3f-d602d1edf801"],
                #uuid "0d988d74-169b-5291-85c0-9f6c7770fc29"
                [#uuid "1fd5a42f-73c9-523e-b5d5-6290e01b8035"],
                #uuid "2380d26f-7d0b-5608-a945-d5047475e743"
                [#uuid "1ededcbb-2338-5fa4-ba05-1002f2432147"],
                #uuid "27fe4598-ca9b-5aa2-af9b-022fa0170434"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "1fe0192b-3432-5379-b119-37a43803acdf"],
                #uuid "38db351c-e4c6-5afe-a848-e49ecdb04c5d"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "3e917467-8760-54d5-8180-52e06a45bdda"],
                #uuid "26d7c639-df02-509b-a72f-72b30ea38390"
                [#uuid "22839968-b992-5b83-b37d-67ec4c2e6f5e"
                 #uuid "236a1dd2-8990-521d-baf6-ddbbb220d94e"],
                #uuid "2e6eb3aa-6466-54bb-80c7-4c6c7dac9b3f"
                [#uuid "2380d26f-7d0b-5608-a945-d5047475e743"
                 #uuid "24c2cca0-2f41-57ec-97c9-1ad22262d30f"],
                #uuid "2c83e314-37f1-52b1-b5b3-a76fba623368"
                [#uuid "12330d72-fb1f-5bfb-b118-1d9a2ae69a1a"
                 #uuid "1ededcbb-2338-5fa4-ba05-1002f2432147"],
                #uuid "011bf147-88dc-5f49-8b39-5f5e7fe3f7b2"
                [#uuid "257fdac9-d674-5dbd-9a6e-19ac5ceebf57"
                 #uuid "257fdac9-d674-5dbd-9a6e-19ac5ceebf57"
                 #uuid "1367ae2f-63aa-5b2e-830d-a5128312bbbb"
                 #uuid "1e2b5716-599e-553a-a255-ada32139123f"],
                #uuid "3f35c137-8dbf-52e5-b4e8-6ba84b244599"
                [#uuid "2380d26f-7d0b-5608-a945-d5047475e743"
                 #uuid "2c83e314-37f1-52b1-b5b3-a76fba623368"],
                #uuid "22c0a518-0147-5178-9c3f-d602d1edf801"
                [#uuid "072b5485-8325-55fd-981b-a749e09d333a"
                 #uuid "3b2fa93e-28d9-568e-a306-77fc562bff66"],
                #uuid "35a7db45-3478-553e-8c63-755253c96caa"
                [#uuid "193ecad3-3dd6-520d-b1b8-e940dc240d9e"
                 #uuid "2d897608-ea72-556f-906d-35e5eb5bbff9"],
                #uuid "192ba46f-ec4c-5de6-baa5-e1a626579b2b"
                [#uuid "1dcd8025-a66b-5452-89ac-27f709ed33bc"
                 #uuid "304972ce-d801-568f-93a9-d44fd6f592e1"],
                #uuid "0ad48d1c-3f03-59b8-85d6-52cb0d262f4a"
                [#uuid "1e4620ef-d099-5dfe-9bf6-abaaff96162f"],
                #uuid "0b82153c-6b7f-5abf-9aad-8f2364ddb545"
                [#uuid "35a7db45-3478-553e-8c63-755253c96caa"
                 #uuid "335105d2-24d8-50ac-b99b-f8974bdd9c31"],
                #uuid "3165f35c-2f8c-5395-b52a-ea5a4a0acdd5"
                [#uuid "0663c69e-e76b-5612-8304-b7c59cb600ba"
                 #uuid "1de1d686-a279-5f03-885b-c9a5c022ee77"],
                #uuid "0b0618b9-d919-56da-886d-47e05d3afa25"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "265ed783-715b-587f-8f38-39fe7dad9533"],
                #uuid "1b6c120a-4103-5b87-aff2-5ca4a0b14dae"
                [#uuid "1c43e64b-2118-59f0-9b6b-a5079bf714e5"
                 #uuid "12dc0dc3-40c6-57c4-9282-b2bc51d7bff7"],
                #uuid "061d8a1e-b0a8-55c4-8736-ed0e39f30b9c" [],
                #uuid "0da8c830-d07a-5031-8898-374d216fba84"
                [#uuid "061d8a1e-b0a8-55c4-8736-ed0e39f30b9c"],
                #uuid "3e917467-8760-54d5-8180-52e06a45bdda"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "1cbc8268-108f-5519-95cc-910ed426dcf5"],
                #uuid "12c6e8c5-19f8-56b2-8afb-6d0b208d80f4"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "27fe4598-ca9b-5aa2-af9b-022fa0170434"],
                #uuid "1367ae2f-63aa-5b2e-830d-a5128312bbbb"
                [#uuid "257fdac9-d674-5dbd-9a6e-19ac5ceebf57"],
                #uuid "3db41cfa-8879-539f-8128-63ae816ccc02"
                [#uuid "09d1086c-de40-54d6-84df-9ebf2bb80cde"],
                #uuid "12330d72-fb1f-5bfb-b118-1d9a2ae69a1a"
                [#uuid "3838f7b0-5c65-53a6-93b2-367fe9cfc23e"],
                #uuid "1fe0192b-3432-5379-b119-37a43803acdf"
                [#uuid "2d466d00-cb88-58d3-94aa-5544eb295266"
                 #uuid "0fc757c9-c6f2-5190-be53-2dd95fdf541a"],
                #uuid "37b376b8-db77-5c7a-abf7-373e18575976"
                [#uuid "098097a5-c58d-5e50-9a0e-0e4fdf034e64"
                 #uuid "26d7c639-df02-509b-a72f-72b30ea38390"],
                #uuid "33172994-63eb-5450-bad9-1f95e355037b"
                [#uuid "0663c69e-e76b-5612-8304-b7c59cb600ba"
                 #uuid "1de1d686-a279-5f03-885b-c9a5c022ee77"],
                #uuid "1cbc8268-108f-5519-95cc-910ed426dcf5"
                [#uuid "2c83e314-37f1-52b1-b5b3-a76fba623368"
                 #uuid "3f35c137-8dbf-52e5-b4e8-6ba84b244599"
                 #uuid "24c2cca0-2f41-57ec-97c9-1ad22262d30f"
                 #uuid "2e6eb3aa-6466-54bb-80c7-4c6c7dac9b3f"
                 #uuid "2acac649-85c9-53c1-9478-0ec3dbc8ab5b"
                 #uuid "04d5baec-3b9c-5be7-afaf-ab0e824b56fd"
                 #uuid "23e03369-837a-5a33-849a-b49b6ff4fbd9"
                 #uuid "35596c69-0848-5015-90fe-23a6fc894bd6"
                 #uuid "35fc8eaf-4c87-5cef-8781-dcbac2abc855"
                 #uuid "2ac92664-4dfa-51e1-81a2-9c4e6f2cdba7"
                 #uuid "2d466d00-cb88-58d3-94aa-5544eb295266"
                 #uuid "0fc757c9-c6f2-5190-be53-2dd95fdf541a"
                 #uuid "1fe0192b-3432-5379-b119-37a43803acdf"
                 #uuid "27fe4598-ca9b-5aa2-af9b-022fa0170434"
                 #uuid "12c6e8c5-19f8-56b2-8afb-6d0b208d80f4"
                 #uuid "1c02bc89-4789-5482-af11-42bba5899204"
                 #uuid "3c84108f-169b-5acb-8bb1-dcd6cfb4cbe7"
                 #uuid "265ed783-715b-587f-8f38-39fe7dad9533"
                 #uuid "0b0618b9-d919-56da-886d-47e05d3afa25"
                 #uuid "2f62c6c7-2615-5dae-95e1-6d6f8f1d4258"
                 #uuid "1c43e64b-2118-59f0-9b6b-a5079bf714e5"
                 #uuid "12dc0dc3-40c6-57c4-9282-b2bc51d7bff7"
                 #uuid "1b6c120a-4103-5b87-aff2-5ca4a0b14dae"
                 #uuid "0c29c9da-6c8a-524c-a1f8-cf2b9f31e480"
                 #uuid "0980479a-20bf-56b9-8bfa-1d80449feef6"
                 #uuid "2c83e314-37f1-52b1-b5b3-a76fba623368"
                 #uuid "3f35c137-8dbf-52e5-b4e8-6ba84b244599"
                 #uuid "24c2cca0-2f41-57ec-97c9-1ad22262d30f"
                 #uuid "2e6eb3aa-6466-54bb-80c7-4c6c7dac9b3f"
                 #uuid "2acac649-85c9-53c1-9478-0ec3dbc8ab5b"
                 #uuid "04d5baec-3b9c-5be7-afaf-ab0e824b56fd"
                 #uuid "23e03369-837a-5a33-849a-b49b6ff4fbd9"
                 #uuid "35596c69-0848-5015-90fe-23a6fc894bd6"
                 #uuid "35fc8eaf-4c87-5cef-8781-dcbac2abc855"
                 #uuid "2ac92664-4dfa-51e1-81a2-9c4e6f2cdba7"
                 #uuid "2d466d00-cb88-58d3-94aa-5544eb295266"
                 #uuid "0fc757c9-c6f2-5190-be53-2dd95fdf541a"
                 #uuid "1fe0192b-3432-5379-b119-37a43803acdf"
                 #uuid "27fe4598-ca9b-5aa2-af9b-022fa0170434"
                 #uuid "12c6e8c5-19f8-56b2-8afb-6d0b208d80f4"
                 #uuid "1c02bc89-4789-5482-af11-42bba5899204"
                 #uuid "3c84108f-169b-5acb-8bb1-dcd6cfb4cbe7"
                 #uuid "265ed783-715b-587f-8f38-39fe7dad9533"
                 #uuid "0b0618b9-d919-56da-886d-47e05d3afa25"
                 #uuid "2f62c6c7-2615-5dae-95e1-6d6f8f1d4258"],
                #uuid "3b2fa93e-28d9-568e-a306-77fc562bff66"
                [#uuid "3165f35c-2f8c-5395-b52a-ea5a4a0acdd5"
                 #uuid "33172994-63eb-5450-bad9-1f95e355037b"],
                #uuid "3e443ec9-4245-5b8e-805e-b563105e774e"
                [#uuid "0da8c830-d07a-5031-8898-374d216fba84"],
                #uuid "14509f2a-4a1d-5a56-b6a5-3198bf3a170e"
                [#uuid "2a748c0e-2d1c-5646-ba1c-47f5001e25e6"],
                #uuid "287a02f9-db45-5027-a2e6-06727c893f46"
                [#uuid "3db41cfa-8879-539f-8128-63ae816ccc02"],
                #uuid "072b5485-8325-55fd-981b-a749e09d333a"
                [#uuid "3165f35c-2f8c-5395-b52a-ea5a4a0acdd5"],
                #uuid "098d7b81-e25a-576c-913d-a7319129d897"
                [#uuid "322ad887-13db-53c6-ab9b-3bc6fee7f3db"
                 #uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"],
                #uuid "0a95a27e-c3cf-51ab-afce-fafdf4b1de43"
                [#uuid "1e9cc7b2-d46f-586a-b545-a710225282d6"],
                #uuid "3d85df7b-5a9e-571a-995d-b9881243ee82"
                [#uuid "098097a5-c58d-5e50-9a0e-0e4fdf034e64"
                 #uuid "26d7c639-df02-509b-a72f-72b30ea38390"],
                #uuid "35596c69-0848-5015-90fe-23a6fc894bd6"
                [#uuid "04d5baec-3b9c-5be7-afaf-ab0e824b56fd"
                 #uuid "23e03369-837a-5a33-849a-b49b6ff4fbd9"],
                #uuid "2ac92664-4dfa-51e1-81a2-9c4e6f2cdba7"
                [#uuid "35596c69-0848-5015-90fe-23a6fc894bd6"
                 #uuid "35fc8eaf-4c87-5cef-8781-dcbac2abc855"],
                #uuid "267d751f-0b86-52f7-a47f-d4cab6c6cfae"
                [#uuid "0c29c9da-6c8a-524c-a1f8-cf2b9f31e480"
                 #uuid "1b5d2c1c-7af4-568f-b120-3ab71214539e"],
                #uuid "0c98fea3-08cc-5398-86c4-37c3c6817f94"
                [#uuid "37b376b8-db77-5c7a-abf7-373e18575976"
                 #uuid "3d85df7b-5a9e-571a-995d-b9881243ee82"],
                #uuid "0ab10dc3-898e-58eb-9418-5fbe08f5d5ba"
                [#uuid "011bf147-88dc-5f49-8b39-5f5e7fe3f7b2"
                 #uuid "1367ae2f-63aa-5b2e-830d-a5128312bbbb"
                 #uuid "1e2b5716-599e-553a-a255-ada32139123f"],
                #uuid "12dc0dc3-40c6-57c4-9282-b2bc51d7bff7"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "1c43e64b-2118-59f0-9b6b-a5079bf714e5"],
                #uuid "0ff722ff-5dff-5228-9114-064116363658"
                [#uuid "287a02f9-db45-5027-a2e6-06727c893f46"
                 #uuid "1ebc3ca1-c972-52e4-ac12-5348f63a9976"],
                #uuid "257fdac9-d674-5dbd-9a6e-19ac5ceebf57"
                [#uuid "066877cb-cdb4-5aa6-a4a7-42a90f563c74"],
                #uuid "1a82a692-4b68-5edb-b346-e3c68df7a180"
                [#uuid "14509f2a-4a1d-5a56-b6a5-3198bf3a170e"],
                #uuid "35fc8eaf-4c87-5cef-8781-dcbac2abc855"
                [#uuid "23e03369-837a-5a33-849a-b49b6ff4fbd9"
                 #uuid "35596c69-0848-5015-90fe-23a6fc894bd6"],
                #uuid "304972ce-d801-568f-93a9-d44fd6f592e1"
                [#uuid "1b8236da-3192-5d67-90b6-98e41de6300d"
                 #uuid "36d4477b-1aed-5b4a-9f26-bb0e6d275c2c"],
                #uuid "2acac649-85c9-53c1-9478-0ec3dbc8ab5b"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "2e6eb3aa-6466-54bb-80c7-4c6c7dac9b3f"],
                #uuid "1e2b5716-599e-553a-a255-ada32139123f"
                [#uuid "066877cb-cdb4-5aa6-a4a7-42a90f563c74"
                 #uuid "1367ae2f-63aa-5b2e-830d-a5128312bbbb"],
                #uuid "0cc35715-7530-5ee0-a33b-13c6aa116023"
                [#uuid "37b376b8-db77-5c7a-abf7-373e18575976"
                 #uuid "3d85df7b-5a9e-571a-995d-b9881243ee82"],
                #uuid "066877cb-cdb4-5aa6-a4a7-42a90f563c74"
                [#uuid "0d988d74-169b-5291-85c0-9f6c7770fc29"],
                #uuid "24c2cca0-2f41-57ec-97c9-1ad22262d30f"
                [#uuid "27ba4fef-6620-528a-8760-03fed35d27a9"
                 #uuid "3f35c137-8dbf-52e5-b4e8-6ba84b244599"],
                #uuid "2d466d00-cb88-58d3-94aa-5544eb295266"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "2ac92664-4dfa-51e1-81a2-9c4e6f2cdba7"],
                #uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                [#uuid "2380d26f-7d0b-5608-a945-d5047475e743"],
                #uuid "265ed783-715b-587f-8f38-39fe7dad9533"
                [#uuid "1c02bc89-4789-5482-af11-42bba5899204"
                 #uuid "3c84108f-169b-5acb-8bb1-dcd6cfb4cbe7"],
                #uuid "21525547-f7ad-5fbd-9c98-04045478d52f"
                [#uuid "35a7db45-3478-553e-8c63-755253c96caa"
                 #uuid "335105d2-24d8-50ac-b99b-f8974bdd9c31"],
                #uuid "27ba4fef-6620-528a-8760-03fed35d27a9"
                [#uuid "12330d72-fb1f-5bfb-b118-1d9a2ae69a1a"],
                #uuid "10b412fb-8ab6-54e3-9cdf-6858fbe7cc32"
                [#uuid "04d57671-a4e5-5cca-93d5-f4f5ccf6b85a"],
                #uuid "36d4477b-1aed-5b4a-9f26-bb0e6d275c2c"
                [#uuid "1e67774c-8cda-5e16-bd5a-0f641e639ba7"
                 #uuid "0ff722ff-5dff-5228-9114-064116363658"],
                #uuid "0663c69e-e76b-5612-8304-b7c59cb600ba"
                [#uuid "0bcbc247-142f-5b1c-8616-a4378265debb"],
                #uuid "1e4620ef-d099-5dfe-9bf6-abaaff96162f"
                [#uuid "267d751f-0b86-52f7-a47f-d4cab6c6cfae"],
                #uuid "1fd5a42f-73c9-523e-b5d5-6290e01b8035"
                [#uuid "05dfc6d2-731c-54d3-a879-bea754e69452"],
                #uuid "1c02bc89-4789-5482-af11-42bba5899204"
                [#uuid "27fe4598-ca9b-5aa2-af9b-022fa0170434"
                 #uuid "12c6e8c5-19f8-56b2-8afb-6d0b208d80f4"],
                #uuid "05dfc6d2-731c-54d3-a879-bea754e69452"
                [#uuid "1a82a692-4b68-5edb-b346-e3c68df7a180"],
                #uuid "3f61e9c2-5946-5da5-89d9-8733efd026e0"
                [#uuid "09d1086c-de40-54d6-84df-9ebf2bb80cde"
                 #uuid "20eb6a46-ad79-5b82-94f2-a10b4db1a359"],
                #uuid "04d57671-a4e5-5cca-93d5-f4f5ccf6b85a"
                [#uuid "3e443ec9-4245-5b8e-805e-b563105e774e"],
                #uuid "1b8236da-3192-5d67-90b6-98e41de6300d"
                [#uuid "1e67774c-8cda-5e16-bd5a-0f641e639ba7"
                 #uuid "0ff722ff-5dff-5228-9114-064116363658"],
                #uuid "3c84108f-169b-5acb-8bb1-dcd6cfb4cbe7"
                [#uuid "12c6e8c5-19f8-56b2-8afb-6d0b208d80f4"
                 #uuid "1c02bc89-4789-5482-af11-42bba5899204"],
                #uuid "0980479a-20bf-56b9-8bfa-1d80449feef6"
                [#uuid "1b6c120a-4103-5b87-aff2-5ca4a0b14dae"
                 #uuid "0c29c9da-6c8a-524c-a1f8-cf2b9f31e480"],
                #uuid "34865626-d476-546e-8dfc-501debc0fce0"
                [#uuid "0a95a27e-c3cf-51ab-afce-fafdf4b1de43"
                 #uuid "2175f837-e00f-58eb-be7c-3cd13016659a"],
                #uuid "20eb6a46-ad79-5b82-94f2-a10b4db1a359"
                [#uuid "35ef1a73-a835-5fe6-8e22-47b6d047159f"
                 #uuid "098d7b81-e25a-576c-913d-a7319129d897"],
                #uuid "3838f7b0-5c65-53a6-93b2-367fe9cfc23e"
                [#uuid "1e110215-5277-5fbb-8866-3456a3c5b965"],
                #uuid "2175f837-e00f-58eb-be7c-3cd13016659a"
                [#uuid "1e9cc7b2-d46f-586a-b545-a710225282d6"
                 #uuid "192ba46f-ec4c-5de6-baa5-e1a626579b2b"],
                #uuid "1de1d686-a279-5f03-885b-c9a5c022ee77"
                [#uuid "0bcbc247-142f-5b1c-8616-a4378265debb"
                 #uuid "34865626-d476-546e-8dfc-501debc0fce0"],
                #uuid "2b5e3f40-856b-56ca-a8d0-b04f562ed6d2"
                [#uuid "0b82153c-6b7f-5abf-9aad-8f2364ddb545"
                 #uuid "21525547-f7ad-5fbd-9c98-04045478d52f"],
                #uuid "0bcbc247-142f-5b1c-8616-a4378265debb"
                [#uuid "0a95a27e-c3cf-51ab-afce-fafdf4b1de43"
                 #uuid "2175f837-e00f-58eb-be7c-3cd13016659a"],
                #uuid "04d5baec-3b9c-5be7-afaf-ab0e824b56fd"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "2acac649-85c9-53c1-9478-0ec3dbc8ab5b"],
                #uuid "335105d2-24d8-50ac-b99b-f8974bdd9c31"
                [#uuid "193ecad3-3dd6-520d-b1b8-e940dc240d9e"
                 #uuid "2d897608-ea72-556f-906d-35e5eb5bbff9"],
                #uuid "23e03369-837a-5a33-849a-b49b6ff4fbd9"
                [#uuid "2acac649-85c9-53c1-9478-0ec3dbc8ab5b"
                 #uuid "04d5baec-3b9c-5be7-afaf-ab0e824b56fd"],
                #uuid "1e67774c-8cda-5e16-bd5a-0f641e639ba7"
                [#uuid "287a02f9-db45-5027-a2e6-06727c893f46"],
                #uuid "1c43e64b-2118-59f0-9b6b-a5079bf714e5"
                [#uuid "0b0618b9-d919-56da-886d-47e05d3afa25"
                 #uuid "2f62c6c7-2615-5dae-95e1-6d6f8f1d4258"],
                #uuid "22839968-b992-5b83-b37d-67ec4c2e6f5e"
                [#uuid "0cf71f20-a505-56b8-bb3e-396e4a1bd3cf"],
                #uuid "0cf71f20-a505-56b8-bb3e-396e4a1bd3cf"
                [#uuid "0b82153c-6b7f-5abf-9aad-8f2364ddb545"],
                #uuid "2b7462bd-170b-599d-909d-5b0a4449b1b0"
                [#uuid "0c98fea3-08cc-5398-86c4-37c3c6817f94"
                 #uuid "0cc35715-7530-5ee0-a33b-13c6aa116023"],
                #uuid "1ededcbb-2338-5fa4-ba05-1002f2432147"
                [#uuid "27ba4fef-6620-528a-8760-03fed35d27a9"],
                #uuid "236a1dd2-8990-521d-baf6-ddbbb220d94e"
                [#uuid "0cf71f20-a505-56b8-bb3e-396e4a1bd3cf"
                 #uuid "2b5e3f40-856b-56ca-a8d0-b04f562ed6d2"],
                #uuid "0fc757c9-c6f2-5190-be53-2dd95fdf541a"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "2d466d00-cb88-58d3-94aa-5544eb295266"],
                #uuid "1b5d2c1c-7af4-568f-b120-3ab71214539e"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "38db351c-e4c6-5afe-a848-e49ecdb04c5d"],
                #uuid "2a748c0e-2d1c-5646-ba1c-47f5001e25e6"
                [#uuid "10b412fb-8ab6-54e3-9cdf-6858fbe7cc32"],
                #uuid "1ebc3ca1-c972-52e4-ac12-5348f63a9976"
                [#uuid "3db41cfa-8879-539f-8128-63ae816ccc02"
                 #uuid "3f61e9c2-5946-5da5-89d9-8733efd026e0"],
                #uuid "2d897608-ea72-556f-906d-35e5eb5bbff9"
                [#uuid "259bdc0a-c928-58b9-9f8e-27e810fc4e66"
                 #uuid "22c0a518-0147-5178-9c3f-d602d1edf801"],
                #uuid "09d1086c-de40-54d6-84df-9ebf2bb80cde"
                [#uuid "35ef1a73-a835-5fe6-8e22-47b6d047159f"
                 #uuid "098d7b81-e25a-576c-913d-a7319129d897"],
                #uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                [#uuid "0ad48d1c-3f03-59b8-85d6-52cb0d262f4a"],
                #uuid "1dcd8025-a66b-5452-89ac-27f709ed33bc"
                [#uuid "1b8236da-3192-5d67-90b6-98e41de6300d"],
                #uuid "322ad887-13db-53c6-ab9b-3bc6fee7f3db"
                [#uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"
                 #uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"],
                #uuid "35ef1a73-a835-5fe6-8e22-47b6d047159f"
                [#uuid "322ad887-13db-53c6-ab9b-3bc6fee7f3db"
                 #uuid "313a1c04-0ff1-5c37-8c66-0ddc28a9fdf3"],
                #uuid "0c29c9da-6c8a-524c-a1f8-cf2b9f31e480"
                [#uuid "0d4a78bd-92f0-53f4-aeda-4e541336a20c"
                 #uuid "1b6c120a-4103-5b87-aff2-5ca4a0b14dae"],
                #uuid "1e110215-5277-5fbb-8866-3456a3c5b965"
                [#uuid "0ab10dc3-898e-58eb-9418-5fbe08f5d5ba"],
                #uuid "259bdc0a-c928-58b9-9f8e-27e810fc4e66"
                [#uuid "072b5485-8325-55fd-981b-a749e09d333a"],
                #uuid "2f62c6c7-2615-5dae-95e1-6d6f8f1d4258"
                [#uuid "265ed783-715b-587f-8f38-39fe7dad9533"
                 #uuid "0b0618b9-d919-56da-886d-47e05d3afa25"],
                #uuid "098097a5-c58d-5e50-9a0e-0e4fdf034e64"
                [#uuid "22839968-b992-5b83-b37d-67ec4c2e6f5e"
                 #uuid "236a1dd2-8990-521d-baf6-ddbbb220d94e"],
                #uuid "1e9cc7b2-d46f-586a-b545-a710225282d6"
                [#uuid "1dcd8025-a66b-5452-89ac-27f709ed33bc"]},
               :public false,
               :branches {"master" #{#uuid "2b7462bd-170b-599d-909d-5b0a4449b1b0"}},
               :head "master",
               :last-update #inst "2014-10-19T23:35:17.780-00:00",
               :id #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"})

  (set/difference  (-> repo-b :causal-order keys set) (-> repo-a :causal-order keys set))
  (set/difference  (-> repo-a :causal-order keys set) (-> repo-b :causal-order keys set))

  (set/difference (-> (isolate-branch repo-b "master") keys set)
                  (-> (isolate-branch repo-a "master") keys set))

  (pull {:meta repo-a }
        "master"
        repo-b
        #uuid "2b7462bd-170b-599d-909d-5b0a4449b1b0")

  (consistent-causal? (:causal-order (pull {:meta repo-a }
                                           "master"
                                           repo-b
                                           #uuid "2b7462bd-170b-599d-909d-5b0a4449b1b0"))))
