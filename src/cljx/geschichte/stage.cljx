(ns geschichte.stage
    (:require [konserve.protocols :refer [-get-in -assoc-in]]
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
       (map (fn [[param-id trans-id]]
              (go<? [(<? (-get-in store [param-id]))
                    (<? (-get-in store [trans-id]))])))
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


(defn trans-apply [eval-fn val [params trans-fn]]
  (if (= trans-fn repo/store-blob-trans-value)
    (repo/store-blob-trans val params)
    ((eval-fn trans-fn) val params)))

;; TODO use store
(def ^:private commit-value-cache (atom {}))

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


(defn branch-value
  "Realizes the value of a branch of a staged repository with
help of store and an application specific eval-fn (e.g. map from
source/symbols to fn.). The metadata has the form {:meta {:causal-order ...}, :transactions [[p fn]...] ...}. Returns go block to synchronize."
  [store eval-fn repo branch]
  (when (repo/multiple-branch-heads? (:meta repo) branch)
    (throw (ex-info "Branch has multiple heads!"
                    {:type :multiple-branch-heads
                     :branch branch
                     :meta (:meta repo)})))
  (go<? (reduce (partial trans-apply eval-fn)
                (<? (commit-value store eval-fn (-> repo :meta :causal-order)
                                  (first (get-in repo [:meta :branches branch]))))
                (get-in repo [:transactions branch]))))



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

             meta-pubs (reduce #(assoc-in %1 %2 (get-in stage-val (concat %2 [:meta])))
                               {}
                               (for [[u repos] metas
                                     [id repo] repos
                                     :when (or (= (get-in stage-val [u id :op]) :meta-pub)
                                               (= (get-in stage-val [u id :op]) :meta-sub))]
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
         (when-not (empty? meta-pubs)
           (>! out (with-meta {:topic :meta-pub :metas meta-pubs :peer id}
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
                                     (update-in (butlast %2) dissoc :op)
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
        connedch (chan)]
    (sub p :connected connedch)
    (put! out {:topic :connect
               :url url})
    (go-loop<? [{u :url} (<? connedch)]
      (when u
        (if-not (= u url)
          (recur (<? connedch))
          (do (info "connect!: connected " url)
              stage))))))


(defrecord Conflict [lca-value commits-a commits-b])

(defn summarize-conflict
  "Summarizes a conflict situation between to branch heads in a Conflict
record. Returns go block to synchronize."
  [store eval-fn repo-meta branch]
  (when-not (repo/multiple-branch-heads? repo-meta branch)
    (throw (ex-info "Conflict missing for summary."
                    {:type :missing-conflict-for-summary
                     :meta repo-meta
                     :branch branch})))
  (go<? (let [[head-a head-b] (seq (get-in repo-meta [:branches branch]))
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
          (<? (-assoc-in store [repo/trans-blob-id] repo/store-blob-trans-value))
          (<? (wire peer (block-detector stage-id [out in])))
          (sub p :meta-pub pub-ch)
          (go-loop>? err-ch [{:keys [metas] :as mp} (<? pub-ch)]
            (when mp
              (info "stage: pubing metas " metas)
              ;; TODO swap! once per update
              (doseq [[u repos] metas
                      [id repo] repos]
                (swap! stage update-in [u id :meta] #(if % (meta/update % repo) repo)))
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
                                                             (<! (branch-value store eval-fn {:meta new-meta} b)))
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
                                           [repo-id _] rs]
                                       [user repo-id])
                                     (filter #(nil? (get-in @stage %)))))]
            (loop [na (not-avail)]
              (when (not (empty? na))
                (debug "waiting for repos in stage: " na)
                (<! (timeout 100))
                (recur (not-avail)))))
          ;; [:config :subs] only managed by subscribe-repos! => safe
          (swap! stage assoc-in [:config :subs] repos)
          nil)))


(defn create-repo! [stage description & {:keys [branch user]}]
  "Create a repo given a description. Defaults to stage user and
  new-repository default arguments. Returns go block to synchronize."
  (go<? (let [user (or user (get-in @stage [:config :user]))
              nrepo (repo/new-repository user description :branch branch)
              id (get-in nrepo [:meta :id])
              metas {user {id #{branch}}}
              ;; id is random uuid, safe swap!
              new-stage (swap! stage #(-> %
                                          (assoc-in [user id] nrepo)
                                          (assoc-in [:config :subs user id] #{branch})))]
          (debug "creating new repo for " user "with id" id)
          (<? (sync! new-stage metas))
          (cleanup-ops-and-new-values! stage metas)
          (<? (subscribe-repos! stage (get-in new-stage [:config :subs])))
          id)))


(defn fork! [stage [user repo-id branch] & {:keys [into-user]}]
  "Forks from one staged user's repo a branch into a new repository for the
stage user into having repo-id. Returns go block to synchronize."
  (go<? (let [suser (or into-user (get-in @stage [:config :user]))
              metas {suser {repo-id #{branch}}}
              ;; atomic swap! and sync, safe
              new-stage (swap! stage #(if (get-in % [suser repo-id])
                                        (throw (ex-info "Repository already exists, use pull."
                                                        {:type :forking-impossible
                                                         :user user :id repo-id}))
                                        (-> %
                                            (assoc-in [suser repo-id] (repo/fork (get-in % [user repo-id :meta])
                                                                                 branch
                                                                                 false))
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
                                             (update-in [user repo] #(repo/branch % branch-name parent-commit))
                                             (update-in [:config :subs user repo] #(conj (or %1 #{}) %2) branch-name))))
         new-subs (get-in new-stage [:config :subs])]
     (<? (sync! new-stage {user {repo #{name}}}))
     (<? (subscribe-repos! stage new-subs)))))


(defn transact
  "Transact a transaction function trans-fn-code (given as quoted code: '(fn [old params] (merge old params))) on previous value of user's repository branch and params.
THIS DOES NOT COMMIT YET, you have to call commit! explicitly afterwards. It can still abort resulting in a staged geschichte.stage.Abort value for the repository. Returns go block to synchronize."
  ([stage [user repo branch] params trans-fn-code]
   (transact stage [user repo branch] [[params trans-fn-code]]))
  ([stage [user repo branch] transactions]
   (go<? (when-not (get-in @stage [user repo :meta :branches branch])
           (throw (ex-info "Branch does not exist!"
                           {:type :branch-missing
                            :user user :repo repo :branch branch})))
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
  (transact stage [user repo branch] [[blob repo/store-blob-trans-value]]))

(defn commit!
  "Commit all branches synchronously on stage given by the repository map,
e.g. {user1 {repo1 #{branch1}} user2 {repo1 #{branch1 branch2}}}.
Returns go block to synchronize."
  [stage repos]
  (go<?
   ;; atomic swap and sync, safe
   (<? (sync! (swap! stage (fn [old]
                             (reduce (fn [old [user id branch]]
                                       (update-in old [user id] #(repo/commit % user branch)))
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
  [stage [remote-user repo branch] into-branch & {:keys [into-user allow-induced-conflict?]
                                                  :or {allow-induced-conflict? false}}]
  ;; atomic swap! and sync!, safe
  (let [{{u :user} :config} @stage
        user (or into-user u)]
    (sync! (swap! stage (fn [{
                             {{{{remote-heads branch} :branches :as remote-meta} :meta} repo} remote-user :as stage-val}]
                          (when (not= (count remote-heads) 1)
                            (throw (ex-info "Cannot pull from conflicting repo."
                                            {:type :conflicting-remote-meta
                                             :remote-user remote-user :repo repo :branch branch})))
                          (update-in stage-val [user repo]
                                     #(repo/pull % into-branch remote-meta (first remote-heads) allow-induced-conflict?))))
           {user {repo #{into-branch}}})))


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
  ([stage [user repo branch] heads-order wait?]
   (go<?
     (let [causal (get-in @stage [user repo :meta :causal-order])
           metas {user {repo #{branch}}}]
       (when wait?
         (<! (timeout (rand-int (merge-cost causal)))))
       (if (= causal (get-in @stage [user repo :meta :causal-order]))
         ;; TODO retrigger
         (do
           ;; atomic swap! and sync!, safe
           (<? (sync! (swap! stage (fn [{{u :user} :config :as old}]
                                     (update-in old [user repo]
                                                #(repo/merge % u branch (:meta %) heads-order))))
                      metas))
           (cleanup-ops-and-new-values! stage metas)
           true)
         false)))))

;; Quick fix until new hasch version is ready.
(require '[hasch.benc :refer [IHashCoercion]])


(extend (Class/forName "[B")
  IHashCoercion
  {:-coerce (fn [^bytes this hash-fn]
              (hash-fn this))})



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
