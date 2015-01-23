(ns geschichte.stage
    (:require [konserve.protocols :refer [-get-in -assoc-in -bget]]
              [geschichte.repo :as repo]
              [geschichte.sync :refer [wire]]
              [geschichte.meta :as meta]
              [geschichte.p2p.block-detector :refer [block-detector]]
              [geschichte.platform-log :refer [debug info warn]]
              [geschichte.platform :refer [<? go<? go>? go-loop>? go-loop<?]]
              [hasch.core :refer [uuid]]
              [clojure.set :as set]
              #+clj [clojure.core.async :as async
                     :refer [<! <!! >! timeout chan alt! go put! filter< map< go-loop sub unsub pub close!]]
              #+cljs [cljs.core.async :as async
                      :refer [<! >! timeout chan put! filter< map< sub unsub pub close!]])
    #+cljs (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]))


(defn commit-history
  "Returns the linear commit history for a repo through depth-first
linearisation. Each commit occurs once, the first time it is found."
  ([causal-order commit]
   (commit-history causal-order [] #{} [commit]))
  ([causal-order hist hist-set stack]
   (let [[f & r] stack
         children (filter #(not (hist-set %)) (causal-order f))]
     (if f
       (if-not (empty? children)
         (recur causal-order hist hist-set (concat children stack))
         (recur causal-order
                (if-not (hist-set f)
                  (conj hist f) hist)
                (conj hist-set f)
                r))
       hist))))


(defn commit-transactions [store commit-value]
  (->> commit-value
       :transactions
       (map (fn [[trans-id param-id]]
              (go<? [(<? (-get-in store [trans-id]))
                     (<? (if (= trans-id repo/store-blob-trans-id)
                           (-bget store param-id identity)
                           (-get-in store [param-id])))])))
       async/merge
       (async/into [])))

(defn commit-history-values
  "Loads the values of the commits from store. Returns go block to
synchronize."
  [store causal commit]
  (go<? (let [commit-hist (commit-history causal commit)]
          (loop [val []
                 [f & r] commit-hist]
            (if f
              (let [cval (<? (-get-in store [f]))
                    txs (<? (commit-transactions store cval))]
                (recur (conj val (assoc cval :transactions txs :id f)) r))
              val)))))


(defn trans-apply [eval-fn val [trans-fn params]]
  (if (= trans-fn repo/store-blob-trans-value)
    (repo/store-blob-trans val params)
    ((eval-fn trans-fn) val params)))

(def commit-value-cache (atom {}))

(defn commit-value
  "Realizes the value of a commit of repository with help of store and
an application specific eval-fn (e.g. map from source/symbols to
fn.). Returns go block to synchronize. Caches old values and only applies novelty."
  ([store eval-fn causal commit]
   (commit-value store eval-fn causal commit (reverse (commit-history causal commit))))
  ([store eval-fn causal commit [f & r]]
   (go<? (when f
          (or (@commit-value-cache [eval-fn causal f])
              (let [cval (<? (-get-in store [f]))
                    transactions  (<? (commit-transactions store cval))
                    ;; HACK to break stackoverflow through recursion in mozilla js
                    _ (<! (timeout 1))
                    res (reduce (partial trans-apply eval-fn)
                                (<? (commit-value store eval-fn causal commit r))
                                transactions)]
                (swap! commit-value-cache assoc [eval-fn causal f] res)
                res))))))

#_(defn with-transactions [store eval-fn repo branch]
  (reduce (partial trans-apply eval-fn)

          (get-in repo [:transactions branch])))


(defn branch-value
  "Realizes the value of a branch of a staged repository with
help of store and an application specific eval-fn (e.g. map from
source/symbols to fn.). The metadata has the form {:state {:causal-order ...}, :transactions [[p fn]...] ...}. Returns go block to synchronize."
  [store eval-fn repo branch]
  (go<?
   (when (repo/multiple-branch-heads? (:state repo) branch)
     (throw (ex-info "Branch has multiple heads!"
                     {:type :multiple-branch-heads
                      :branch branch
                      :state (:state repo)})))
   (<? (commit-value store eval-fn (-> repo :state :causal-order)
                     (first (get-in repo [:state :branches branch]))))))



(defn sync!
  "Synchronize (push) the results of a geschichte.repo command with storage and other peers.
This does not automatically update the stage. Returns go block to synchronize."
  [stage-val metas]
  (go<? (let [{:keys [id]} (:config stage-val)
              [p out] (get-in stage-val [:volatile :chans])
              fch (chan)
              bfch (chan)
              pch (chan)
              new-values (reduce merge {} (for [[u repos] metas
                                                [r branches] repos
                                                b branches]
                                            (get-in stage-val [u r :new-values b])))

              pubs (reduce #(assoc-in %1 %2 (get-in stage-val (concat %2 [:state])))
                           {}
                           (for [[u repos] metas
                                 [id repo] repos
                                 :when (or (= (get-in stage-val [u id :stage/op]) :pub)
                                           (= (get-in stage-val [u id :stage/op]) :sub))]
                             [u id]))
              ferr-ch (chan)]
          (sub p :meta-pubed pch)
          (sub p :fetch fch)
          (go-loop>? ferr-ch [to-fetch (:ids (<? fch))]
                     (when to-fetch
                       (>! out {:topic :fetched
                                :values (select-keys new-values to-fetch)
                                :peer id})
                       (recur (:ids (<? fch)))))

          (sub p :binary-fetch bfch)
          (go>? ferr-ch
                (let [to-fetch (:ids (<? bfch))]
                  (doseq [f to-fetch]
                    (>! out {:topic :binary-fetched
                             :value (get new-values f)
                             :peer id}))))
          (when-not (empty? pubs)
            (>! out (with-meta {:topic :meta-pub :metas pubs :peer id}
                      {:host ::stage})))

          (loop []
            (alt! pch
                  ([_])
                  ferr-ch
                  ([e] (throw e))
                  (timeout 60000)
                  ([_]
                   (warn "No meta-pubed ack received after 60 secs. Continue waiting..." metas)
                   (recur))))


          (unsub p :meta-pubed pch)
          (unsub p :fetch fch)
          (unsub p :binary-fetch fch)
          (close! ferr-ch)
          (close! fch)
          (close! bfch))))


(defn cleanup-ops-and-new-values! [stage metas]
  (swap! stage (fn [old] (reduce #(-> %1
                                     (update-in (butlast %2) dissoc :stage/op)
                                     (assoc-in (concat (butlast %2) [:new-values (last %2)]) {}))
                                old
                                (for [[user repos] metas
                                      [id branches] repos
                                      b branches]
                                  [user id b])))))



(defn connect!
  "Connect stage to a remote url of another peer,
e.g. ws://remote.peer.net:1234/geschichte/ws. Returns go block to
synchronize."
  [stage url]
  (let [[p out] (get-in @stage [:volatile :chans])
        connedch (chan)
        connection-id (uuid)]
    (sub p :connected connedch)
    (put! out {:topic :connect
               :url url
               :id connection-id})
    (go-loop<? [{id :id e :error} (<? connedch)]
               (when id
                 (if-not (= id connection-id)
                   (recur (<? connedch))
                   (do (unsub p :connected connedch)
                       (when e (throw e))
                       (info "connect!: connected " url)))))))


(defrecord Conflict [lca-value commits-a commits-b])

(defn summarize-conflict
  "Summarizes a conflict situation between two branch heads in a Conflict
record. Returns go block to synchronize."
  [store eval-fn repo-meta branch]
  (go<?
   (when-not (repo/multiple-branch-heads? repo-meta branch)
     (throw (ex-info "Conflict missing for summary."
                     {:type :missing-conflict-for-summary
                      :state repo-meta
                      :branch branch})))
   (let [[head-a head-b] (seq (get-in repo-meta [:branches branch]))
         causal (:causal-order repo-meta)

         {:keys [cut returnpaths-a returnpaths-b] :as lca}
         (meta/lowest-common-ancestors causal #{head-a} causal #{head-b})

         common-history (set (keys (meta/isolate-branch causal cut {})))
         offset (count common-history)
         history-a (<? (commit-history-values store causal head-a))
         history-b (<? (commit-history-values store causal head-b))]
     ;; TODO handle non-singular cut
     (Conflict. (<? (commit-value store eval-fn causal (get-in history-a [(dec offset) :id])))
                (drop offset history-a)
                (drop offset history-b)))))


;; TODO remove value?
(defrecord Abort [new-value aborted])


(defn create-stage!
  "Create a stage for user, given peer and a safe evaluation function
for the transaction functions.  Returns go block to synchronize."
  [user peer eval-fn]
  (go<? (let [in (chan)
              out (chan)
              p (pub in :topic)
              pub-ch (chan)
              val-ch (chan (async/sliding-buffer 1))
              val-atom (atom {})
              stage-id (str "STAGE-" (uuid))
              {:keys [store]} (:volatile @peer)
              stage (atom {:config {:id stage-id
                                    :user user}
                           :volatile {:chans [p out]
                                      :peer peer
                                      :eval-fn eval-fn
                                      :val-ch val-ch
                                      :val-atom val-atom
                                      :val-mult (async/mult val-ch)}})
              err-ch (chan (async/sliding-buffer 10))] ;; TODO
          (<? (-assoc-in store [repo/store-blob-trans-id] repo/store-blob-trans-value))
          (<? (wire peer (block-detector stage-id [out in])))
          (sub p :meta-pub pub-ch)
          (go-loop>? err-ch [{:keys [metas] :as mp} (<? pub-ch)]
            (when mp
              (info "stage: pubing metas " metas)
              ;; TODO swap! once per update
              (doseq [[u repos] metas
                      [id repo] repos]
                (swap! stage update-in [u id :state] #(if % (meta/update % repo) repo)))
              #_(let [old-val @val-atom ;; TODO not consistent !!!
                      val (->> (for [[u repos] metas
                                     [id repo] repos
                                     [b heads] (:branches repo)]
                                 [u id b repo])
                               (map (fn [[u id b repo]]
                                      (let [old-meta (get-in @stage [u id :meta])
                                            new-meta (meta/update (or old-meta repo) repo)]
                                        (go
                                          (when-not (= old-meta new-meta)
                                            [u id b
                                             (let [new-val (if (repo/multiple-branch-heads? new-meta b)
                                                             (<! (summarize-conflict store eval-fn new-meta b))
                                                             (<! (branch-value store eval-fn {:state new-meta} b)))
                                                   old-abort-txs (get-in old-val [u id b :txs])]
                                               (locking stage
                                                 (let [txs (get-in @stage [u id :transactions b])]
                                                   (if-not (empty? txs)
                                                     (do
                                                       (info "aborting transactions: " txs)
                                                       (swap! stage assoc-in [u id :transactions b] [])
                                                       (Abort. new-val (concat old-abort-txs txs)))
                                                     (if-not (empty? old-abort-txs)
                                                       (Abort. new-val old-abort-txs)
                                                       new-val)))))])))))
                               async/merge
                               (async/into [])
                               <!
                               (reduce #(assoc-in %1 (butlast %2) (last %2)) old-val))]
                  (when-not (= val old-val)
                    (info "stage: new value " val)
                    (reset! val-atom val))
                  (put! val-ch val))

              (>! out {:topic :meta-pubed
                       :peer stage-id})
              (recur (<? pub-ch))))
          stage)))


(defn subscribe-repos!
  "Subscribe stage to repos map, e.g. {user {repo-id #{branch1 branch2}}}.
This is not additive, but only these repositories are
subscribed on the stage afterwards. Returns go block to synchronize."
  [stage repos]
  (go<? (let [[p out] (get-in @stage [:volatile :chans])
              subed-ch (chan)
              pub-ch (chan)
              peer-id (get-in @stage [:config :id])]
          (sub p :meta-subed subed-ch)
          (>! out
              {:topic :meta-sub
               :metas repos
               :peer peer-id})
          (<? subed-ch)
          (unsub p :meta-subed subed-ch)
          (sub p :meta-pub pub-ch)
          (>! out
              {:topic :meta-pub-req
               :metas repos
               :peer peer-id})
          (<? pub-ch)
          (unsub p :meta-pub pub-ch)
          (let [not-avail (fn [] (->> (for [[user rs] repos
                                           [repo-id branches] rs
                                           b branches]
                                       [user repo-id :state :branches b])
                                     (filter #(nil? (get-in @stage %)))))]
            (loop [na (not-avail)]
              (when (not (empty? na))
                (debug "waiting for repos in stage: " na)
                (<! (timeout 100))
                (recur (not-avail)))))
          ;; [:config :subs] only managed by subscribe-repos! => safe
          (swap! stage assoc-in [:config :subs] repos)
          nil)))


(defn create-repo! [stage description & {:keys [user is-public?]}]
  "Create a repo given a description. Defaults to stage user and
  new-repository default arguments. Returns go block to synchronize."
  (go<? (let [user (or user (get-in @stage [:config :user]))
              nrepo (repo/new-repository user description :is-public? is-public?)
              branch "master"
              id (get-in nrepo [:state :id])
              metas {user {id #{branch}}}
              ;; id is random uuid, safe swap!
              new-stage (swap! stage #(-> %
                                          (assoc-in [user id] nrepo)
                                          (assoc-in [user id :stage/op :sub])
                                          (assoc-in [:config :subs user id] #{branch})))]
          (debug "creating new repo for " user "with id" id)
          (<? (sync! new-stage metas))
          (cleanup-ops-and-new-values! stage metas)
          (<? (subscribe-repos! stage (get-in new-stage [:config :subs])))
          id)))


(defn fork! [stage [user repo-id branch] & {:keys [into-user]}]
  "Forks from one staged user's repo a branch into a new repository for the
stage user into having repo-id. Returns go block to synchronize."
  (go<?
   (when-not (get-in @stage [user repo-id :branches branch])
     (throw (ex-info "Repository or branch does not exist."
                     {:type :repository-does-not-exist
                      :user user :repo repo-id :branch branch})))
   (let [suser (or into-user (get-in @stage [:config :user]))
         metas {suser {repo-id #{branch}}}
         ;; atomic swap! and sync, safe
         new-stage (swap! stage #(if (get-in % [suser repo-id])
                                   (throw (ex-info "Repository already exists, use pull."
                                                   {:type :forking-impossible
                                                    :user user :id repo-id}))
                                   (-> %
                                       (assoc-in [suser repo-id]
                                                 (repo/fork (get-in % [user repo-id :state])
                                                            branch
                                                            false))
                                       (assoc-in [suser repo-id :stage/op] :sub)
                                       (assoc-in [:config :subs suser repo-id] #{branch}))))]
     (debug "forking " user repo-id "for" suser)
     (<? (sync! new-stage metas))
     (cleanup-ops-and-new-values! stage metas)
     (<? (subscribe-repos! stage (get-in new-stage [:config :subs])))
     nil)))


(defn remove-repos!
  "Remove repos map from stage, e.g. {user {repo-id #{branch1
branch2}}}. Returns go block to synchronize. TODO remove branches"
  [stage repos]
  (let [new-subs
        (->
         ;; can still get pubs in the mean time which undo in-memory removal, but should be safe
         (swap! stage (fn [old]
                        (reduce #(-> %1
                                     (update-in (butlast %2) dissoc (last %2))
                                     (update-in [:config :subs (first %2)] dissoc (last %)))
                                old
                                (for [[u rs] repos
                                      [id _] rs]
                                  [u id]))))
         (get-in [:config :subs]))]
    (subscribe-repos! stage new-subs)))


(defn branch!
  "Create a new branch with tip parent-commit."
  [stage [user repo] branch-name parent-commit]
  (go<?
   (let [new-stage (swap! stage (fn [old] (-> old
                                             (update-in [user repo]
                                                        #(repo/branch % branch-name parent-commit))
                                             (assoc-in [user repo :stage/op] :pub)
                                             (update-in [:config :subs user repo]
                                                        #(conj (or %1 #{}) %2) branch-name))))
         new-subs (get-in new-stage [:config :subs])]
     (<? (sync! new-stage {user {repo #{name}}}))
     (<? (subscribe-repos! stage new-subs)))))

(defn checkout!
  "Tries to check out one branch and waits until it is available.
  This possibly blocks forever if the branch cannot be fetched from some peer."
  [stage [user repo] branch]
  (subscribe-repos! stage (update-in (get-in @stage [:config :subs])
                                     [user repo] conj branch)))

(defn abort-transactions [stage [user repo branch]]
  ;; racing ...
  (let [a (Abort. nil (get-in @stage [user repo :transactions branch]))]
    (swap! stage assoc-in [user repo :transactions branch] [])
    a))

(defn transact
  "Transact a transaction function trans-fn-code (given as quoted code: '(fn [old params] (merge old params))) on previous value of user's repository branch and params.
THIS DOES NOT COMMIT YET, you have to call commit! explicitly afterwards. It can still abort resulting in a staged geschichte.stage.Abort value for the repository. Returns go block to synchronize."
  ([stage [user repo branch] trans-fn-code params]
   (transact stage [user repo branch] [[trans-fn-code params]]))
  ([stage [user repo branch] transactions]
   (go<?
    (when-not (get-in @stage [user repo :state :branches branch])
      (throw (ex-info "Branch does not exist!"
                      {:type :branch-missing
                       :user user :repo repo :branch branch})))
    (when (some nil? (flatten transactions))
      (throw (ex-info "At least one transaction contains nil."
                      {:type :nil-transaction
                       :transactions transactions})))
    (let [{{:keys [val val-ch peer eval-fn]} :volatile
           {:keys [subs]} :config} @stage

           {{repo-meta repo} user}
           (locking stage
             (swap! stage update-in [user repo :transactions branch] concat transactions))

           branch-val :foo #_(<! (branch-value (get-in @peer [:volatile :store])
                                               eval-fn
                                               repo-meta
                                               branch))

           new-val
           ;; racing...
           (swap! (get-in @stage [:volatile :val-atom]) assoc-in [user repo branch] branch-val)]

      (info "transact: new stage value after trans " transactions ": \n" new-val)
      (put! val-ch new-val)))))

(defn transact-binary
  "Transact a binary blob to reference it later."
  [stage [user repo branch] blob]
  (transact stage [user repo branch] [[repo/store-blob-trans-value blob]]))

(defn commit!
  "Commit all branches synchronously on stage given by the repository map,
e.g. {user1 {repo1 #{branch1}} user2 {repo1 #{branch1 branch2}}}.
Returns go block to synchronize."
  [stage repos]
  (go<?
   ;; atomic swap and sync, safe
   (<? (sync! (swap! stage (fn [old]
                             (reduce (fn [old [user id branch]]
                                       (-> old
                                           (update-in [user id] #(repo/commit % user branch))
                                           (assoc-in [user id :stage/op] :pub)))
                                     old
                                     (for [[user repo] repos
                                           [id branches] repo
                                           b branches]
                                       [user id b]))))
              repos))
   (cleanup-ops-and-new-values! stage repos)))

(defn pull!
  "Pull from remote-user (can be the same), repo branch in into-branch.
  Defaults to stage user as into-user. Returns go-block to synchronize."
  [stage [remote-user repo branch] into-branch & {:keys [into-user allow-induced-conflict?
                                                         rebase-transactions?]
                                                  :or {allow-induced-conflict? false
                                                       rebase-transactions false}}]
  (go<?
   (let [{{u :user} :config} @stage
         user (or into-user u)]
     ;; atomic swap! and sync!, safe
     (<? (sync! (swap! stage (fn [{{{{{remote-heads branch} :branches :as remote-meta} :state} repo}
                                  remote-user :as stage-val}]
                               (when (not= (count remote-heads) 1)
                                 (throw (ex-info "Cannot pull from conflicting repo."
                                                 {:type :conflicting-remote-meta
                                                  :remote-user remote-user :repo repo :branch branch})))
                               (-> stage-val
                                   (update-in [user repo]
                                              #(repo/pull % into-branch remote-meta (first remote-heads)
                                                          allow-induced-conflict? rebase-transactions?))
                                   (assoc-in [user repo :stage/op] :pub))))
                {user {repo #{into-branch}}})))))


(defn merge-cost
  "Estimates cost for adding a further merge to the repository by taking
the ratio between merges and normal commits of the causal-order into account."
  [causal]
  (let [merges (count (filter (fn [[k v]] (> (count v) 1)) causal))
        ratio (double (/ merges (count causal)))]
    (int (* (- (#+clj Math/log #+cljs js/Math.log (- 1 ratio)))
            100000))))


(defn merge!
  "Merge multiple heads in a branch of a repository. Use heads-order to
decide in which order commits contribute to the value. By adding older
commits before their parents, you can enforce to realize them (and their
past) first for this merge (commit-reordering). Only reorder parts of
the concurrent history, not of the sequential common past. Returns go channel
to synchronize."
  ([stage [user repo branch] heads-order]
   (merge! stage [user repo branch] heads-order true))
  ([stage [user repo branch] heads-order
    & {:keys [wait? correcting-transactions]
       :or {wait? true
            correcting-transactions []}}]
   (let [heads (get-in @stage [user repo :state :branches branch])
         causal (get-in @stage [user repo :state :causal-order])]
     (go<?
      (when-not causal
        (throw (ex-info "Repository or branch does not exist."
                        {:type :repo-does-not-exist
                         :user user :repo repo :branch branch})))
      (when-not (= (set heads-order) heads)
        (throw (ex-info "Supplied heads don't match branch heads."
                        {:type :heads-dont-match-branch
                         :heads heads
                         :supplied-heads heads-order})))
      (let [metas {user {repo #{branch}}}]
        (when wait?
          (<! (timeout (rand-int (merge-cost causal)))))
        (when-not (= heads (get-in @stage [user repo :state :branches branch]))
          (throw (ex-info "Heads changed, merge aborted."
                          {:type :heads-changed
                           :old-heads heads
                           :new-heads (get-in @stage [user repo :state :branches branch])}))
          ;; atomic swap! and sync!, safe
          (<? (sync! (swap! stage (fn [{{u :user} :config :as old}]
                                    (-> old
                                        (update-in [user repo]
                                                   #(repo/merge % u branch (:state %)
                                                                heads-order
                                                                correcting-transactions))
                                        (assoc-in [user repo :stage/op] :pub))))
                     metas))
          (cleanup-ops-and-new-values! stage metas)))))))


(comment
  (use 'aprint.core)
  (require '[geschichte.sync :refer [client-peer]])
  (require '[konserve.store :refer [new-mem-store]])
  (def peer (client-peer "TEST-PEER" (<!! (new-mem-store)) identity))
  (def stage (<!! (create-stage! "john" peer eval)))

  (def repo-id (<!! (create-repo! stage "john3" "Test repository." {:init 43} "master")))
  (<!! (fork! stage ["john3" #uuid "a4c3b82d-5d21-4f83-a97f-54d9d40ec85a" "master"]))
  (aprint (dissoc @stage :volatile))
  ;; => repo-id
  (subscribe-repos! stage {"john" {#uuid "3d48173c-d3c0-49ca-bcdf-caa340be249b" #{"master"}}})
  (remove-repos! stage {"john" #{42}})

  ["jim" 42 123]        ;; value
  ["jim" 42 "featureX"] ;; identity
  (branch! stage ["jim" 42 123] "featureX")
  (checkout! stage ["jim" 42 "featureX"])
  (transact stage ["john" #uuid "fdbb6d8c-bf76-4de0-8c53-70396b7dc8ff" "master"] {:b 2} 'clojure.core/merge)
  (go (doseq [i (range 10)]
        (<! (commit! stage {"john" {#uuid "36e02e84-a8a5-47e6-9865-e4ac0ba243d6" #{"master"}}}))))
  (merge! stage ["john" #uuid "9bc896ed-9173-4357-abbe-c7eca1512dc5" "master"])
  (pull! stage ["john" 42 "master"] ["jim" 42 "master"])


  (go (println (<! (realize-value (get-in @stage ["john"  #uuid "fdbb6d8c-bf76-4de0-8c53-70396b7dc8ff"])
                                  "master"
                                  (get-in @peer [:volatile :store])
                                  eval))))


  {:invites [["jim" 42 "conversationA63EF"]]}

  (transact server-stage
            ["shelf@polyc0l0r.net" 68 :#hashtags]
            #{:#spon}
            'clojure.set/union)

  (transact stage
            ["shelf@polyc0l0r.net" 68 :#spon]
            {:type :post
             :content "I will be there tomorrow."
             :ts 0}
            'clojure.core/merge)

  (transact stage
            ["jim" 42 "conversationA63EF"]
            {:type :post
             :content "I will be there tomorrow."
             :ts 0} 'clojure.core/merge)

  (commit! stage ["jim" 42 "conversationA63EF"])















  (def stage (atom nil))
  (go (def store (<! (new-mem-store))))
  (go (def peer (sync/client-peer "CLIENT" store)))
  ;; remote server to sync to
  (require '[geschichte.platform :refer [create-http-kit-handler! start stop]])
  (go (def remote-peer (sync/server-peer (create-http-kit-handler! "ws://localhost:9090/")
                                         (<! (new-mem-store)))))
  (start remote-peer)
  (stop remote-peer)
  (-> @remote-peer :volatile :store)

  (go (>! (second (:chans @stage)) {:topic :meta-pub-req
                                    :user "me@mail.com"
                                    :repo #uuid "94482d4c-a4ba-4069-b017-b70c9027bb9a"
                                    :metas {"master" #{}}}))

  (first (-> @peer :volatile :chans))

  (let [pub-ch (chan)]
    (sub (first (:chans @stage)) :meta-pub pub-ch)
    (go-loop [p (<! pub-ch)]
      (when p
        (println  "META-PUB:" p)
        (recur (<! pub-ch)))))


  (go (println (<! (s/realize-value @stage (-> @peer :volatile :store) eval))))
  (go (println
       (let [new-stage (-> (repo/new-repository "me@mail.com"
                                                {:type "s" :version 1}
                                                "Testing."
                                                false
                                                {:some 43})
                           (wire-stage peer)
                           <!
                           (connect! "ws://localhost:9090/")
                           <!
                           sync!
                           <!)]
         (println "NEW-STAGE:" new-stage)
         (reset! stage new-stage)
         #_(swap! stage (fn [old stage] stage)
                  (->> (s/transact new-stage
                                   {:other 43}
                                   '(fn merger [old params] (merge old params)))
                       repo/commit
                       sync!
                       <!)))))



  )
