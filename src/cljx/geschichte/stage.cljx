(ns geschichte.stage
    (:require [konserve.protocols :refer [-get-in]]
              [geschichte.repo :as repo]
              [geschichte.sync :refer [wire]]
              [geschichte.meta :as meta]
              [geschichte.platform-log :refer [debug info]]
              [hasch.core :refer [uuid]]
              [clojure.set :as set]
              #+clj [clojure.core.async :as async
                     :refer [<! >! timeout chan alt! go put! filter< map< go-loop]]
              #+cljs [cljs.core.async :as async
                      :refer [<! >! timeout chan put! filter< map<]])
    #+cljs (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]))



(defn commit-history
  "Returns the linear commit history for a repo."
  ([repo branch]
     (let [{:keys [branches causal-order]} repo]
       (commit-history [] [(first (get branches branch))] causal-order)))
  ([hist stack causal-order]
     (let [[f & r] stack
           hist-set (set hist)
           children (filter #(not (hist-set %)) (causal-order f))]
       (if f
         (if-not (empty? children)
           (recur hist (concat children stack) causal-order)
           (recur (conj hist f) r causal-order))
         hist))))



(defn realize-value
  "Realizes the value of the current repository with help of store and
an application specific eval-fn (e.g. map from source/symbols to fn.).
Does not memoize yet!"
  [repo branch store eval-fn]
  (go (let [commit-hist (commit-history repo branch)
            trans-apply (fn [val [params trans-fn]]
                          ((eval-fn trans-fn) val params))]
        (reduce trans-apply
                (loop [val nil
                       [f & r] commit-hist]
                  (if f
                    (let [txs (->> (-get-in store [f])
                                   <!
                                   :transactions
                                   (map (fn [[param-id trans-id]]
                                          (go [(<! (-get-in store [param-id]))
                                               (<! (-get-in store [trans-id]))])))
                                   async/merge
                                   (async/into [])
                                   <!)]
                      (recur (reduce trans-apply val txs) r))
                    val))
                (:transactions repo)))))


(defn sync!
  "Synchronize (push) the results of a geschichte.repo command with storage and other peers.
This does not automatically update the stage."
  [stage metas]
  (go (let [{:keys [id]} (:config @stage)
            [p out] (get-in @stage [:volatile :chans])
            fch (chan)
            pch (chan)
            sch (chan)
            new-values (reduce merge {} (for [[u repos] metas
                                              [r branches] repos
                                              b branches]
                                          (get-in @stage [u r :new-values b])))

            _ (println "nv:" new-values)
            ;; TODO might include only published incrementally
            meta-subs (reduce #(assoc-in %1 (butlast %2) (last %2))
                              {}
                              (for [[u repos] (dissoc @stage :volatile :config)
                                    [id repo] repos]
                                [u id (set (keys (:branches (:meta repo))))]))
            _ (println "meta-subs:" meta-subs)
            meta-pubs (reduce #(assoc-in %1 %2 (get-in @stage (concat %2 [:meta])))
                              {}
                              (for [[u repos] metas
                                    [id repo] repos
                                    :when (or (= (get-in @stage [u id :op]) :meta-pub)
                                              (= (get-in @stage [u id :op]) :meta-sub))]
                                [u id]))
            _ (println "meta-pubs:" meta-pubs)]

        (async/sub p :meta-subed sch)
        (when-not (empty? meta-subs)
          (>! out {:topic :meta-sub
                   :metas meta-subs
                   :peer id})
          (<! sch)
          (async/unsub p :meta-subed sch)
          (swap! stage #(assoc-in % [:config :subs] meta-subs)))

        (async/sub p :meta-pubed pch)
        (async/sub p :fetch fch)
        (go-loop [to-fetch (:ids (<! fch))]
          (when to-fetch
            (>! out {:topic :fetched
                     :values (select-keys new-values to-fetch)
                     :peer id})
            (recur (:ids (<! fch)))))
        (when-not (empty? meta-pubs)
          (>! out {:topic :meta-pub :metas meta-pubs :peer id}))

        (let [m (alt! pch (timeout 10000))]
          (when-not m
            (throw #+clj (IllegalStateException. (str "No meta-pubed ack received for" metas))
                   #+cljs (str "No meta-pubed ack received for" metas))))
        (async/unsub p :meta-pubed pch)
        (async/unsub p :fetch fch)
        (async/close! fch)

        (swap! stage (fn [old] (reduce #(-> %1
                                           (update-in (butlast %2) dissoc :op)
                                           (assoc-in (concat (butlast %2) [:new-values (last %2)]) {}))
                                      old
                                      (for [[user repos] metas
                                            [id branches] repos
                                            b branches]
                                        [user id b]))))
        stage)))



(defn connect!
  "Connect stage to a remote url of another peer,
e.g. ws://remote.peer.net:1234/geschichte/ws."
  [stage url]
  (let [[p out] (:chans stage)
        connedch (chan)]
    (async/sub p :connected connedch)
    (put! out {:topic :connect
               :url url})
    (go-loop [{u :url} (<! connedch)]
      (when u
        (if-not (= u url)
          (recur (<! connedch))
          (do (println "CONNECTED:" url)
              stage))))))



(defn create-stage!
  "Create a stage for user, given peer and a safe evaluation function for the transaction functions."
  [user peer eval-fn]
  (go (let [in (chan)
            out (chan)
            p (async/pub in :topic)
            pub-ch (chan)
            val-ch (chan)
            {:keys [store]} (:volatile @peer)
            stage (atom {:config {:id (str "STAGE " user " " (uuid))
                                  :user user}
                         :volatile {:chans [p out]
                                    :peer peer
                                    :val-ch val-ch
                                    :val-pub (async/pub val-ch :topic)}})]
        (<! (wire peer [in (async/pub out :topic)]))
        (async/sub p :meta-pub pub-ch)
        (go-loop [{:keys [metas] :as mp} (<! pub-ch)]
          (when mp
            (debug "pubing metas:" metas)
            (let [val (->> (for [[u repos] metas
                                 [id repo] repos
                                 [b heads] (:branches repo)]
                             [u id b repo])
                           (map (fn [[u id b repo]]
                                  (go [u id b (if (repo/multiple-branch-heads? repo b)
                                                :conflict ;; TODO realize all conflict values
                                                (<! (realize-value repo b store eval-fn)))])))
                           async/merge
                           (async/into [])
                           <!
                           (reduce #(assoc-in %1 (butlast %2) (last %2)) {}))]
              (info "new stage value:" val)
              (swap! stage #(assoc-in % [:volatile :val] val))
              (put! val-ch val))
            (doseq [[u repos] metas
                    [id repo] repos
                    [b heads] (:branches repo)]
              (swap! stage update-in [u id :meta] #(if % (meta/update % repo)
                                                       (meta/update repo repo))))
            (recur (<! pub-ch))))
        stage)))


(defn create-repo! [stage user description init-val branch]
  "Create a repo for user on stage, given description init-value of first (only) branch."
  (go (let [nrepo (repo/new-repository user description false init-val branch)
            id (get-in nrepo [:meta :id])]
        (swap! stage assoc-in [user id] nrepo)
        (<! (sync! stage {user {id #{branch}}}))
        id)))


(defn subscribe-repos!
  "Subscribe stage to repos map, e.g. {user {repo-id #{branch1 branch2}}}. This is not additive, but only these repositories are subscribed. Returns go block to synchronize."
  [stage repos]
  (go (let [[p out] (get-in @stage [:volatile :chans])
            subed-ch (chan)]
        (async/sub p :meta-subed subed-ch)
        (>! out
            {:topic :meta-sub
             :metas repos
             :peer (get-in @stage [:config :id])})
        (<! subed-ch)
        (async/unsub p :meta-subed subed-ch)
        (>! out
            {:topic :meta-pub-req
             :metas repos
             :peer (get-in @stage [:config :id])})
        (swap! stage #(assoc-in % [:config :subs] repos)))))


(defn remove-repos!
  "Remote repos map from stage, e.g. {user {repo-id #{branch1 branch2}}}. "
  [stage repos]
  (swap! stage (fn [old]
                 (reduce #(update-in %1 (butlast %2) dissoc (last %2))
                         old
                         (for [[u rs] repos
                               [id _] rs]
                           [u id])))))


#_(defn branch! [stage [user repo] branch-id parent-commit]
  (swap! stage (fn [old] (update-in old
                                   [user repo]
                                   #(repo/branch branch-id parent-commit))))
  (sync! stage {user {repo #{name}}}))


(defn transact
  "Transact on branch of user's repository a transaction function trans-fn-code (given as
quoted code) on previous value and params."
  [stage [user repo branch] params trans-fn-code]
  (go (let [{{repo-meta repo} user
             {:keys [val val-ch store eval-fn]} :volatile
             {:keys [subs]} :config} @stage
            repo-val (<! (realize-value repo-meta
                                        branch
                                        store
                                        eval-fn))]
        (swap! stage (fn [old]
                       (-> old
                           (update-in [user repo :transactions branch] conj [params trans-fn-code])
                           (assoc-in [:volatile :val user repo branch] repo-val))))
        (info "new stage value after trans:" params trans-fn-code val)
        (put! val-ch (:val (:volatile val))))))


(defn commit!
  "Commit all branches synchronously on stage given by the repository map,
e.g. {user1 {repo1 #{branch1}} user2 {repo1 #{branch1 branch2}}}"
  [stage repos]
  (swap! stage (fn [old]
                 (reduce (fn [old [user id branch]]
                           (update-in old [user id] #(repo/commit % user branch)))
                         old
                         (for [[user repo] repos
                               [id branches] repo
                               b branches]
                           [user id b]))))
  (sync! stage repos))


(comment
  ;; example stage
  {:volatile {:chans [nil nil]
              :store nil
              :peer nil}
   :id 123
   :user "john"
   "john" {42 {:id 42
               :causal-order {}}}}

  (require '[geschichte.sync :refer [client-peer]])
  (require '[konserve.store :refer [new-mem-store]])
  (go (def peer (client-peer "TEST-PEER" (<! (new-mem-store)))))
  (go (def stage (<! (create-stage! "john" peer eval))))
  (clojure.pprint/pprint @stage)
  (go (def repo-id (<! (create-repo! stage "john" "Test repository." {:init 42} "master"))))
  ;; => repo-id
  (subscribe-repos! stage {"john" {#uuid "3d48173c-d3c0-49ca-bcdf-caa340be249b" #{"master"}}})
  (remove-repos! stage {"john" #{42}})

  ["jim" 42 123]        ;; value
  ["jim" 42 "featureX"] ;; identity
  (branch! stage ["jim" 42 123] "featureX")
  (checkout! stage ["jim" 42 "featureX"])
  (transact stage ["john" #uuid "b9f537fa-dace-4b88-b4ec-a81a1c72e6b7" "master"] {:b 2} 'clojure.core/merge)
  (commit! stage {"john" {#uuid  "b9f537fa-dace-4b88-b4ec-a81a1c72e6b7" #{"master"}}})
  (merge! stage ["john" 42 "master"])
  (pull! stage ["john" 42 "master"] ["jim" 42 "master"])


  (realize-value stage ["john" 42] eval)


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
    (async/sub (first (:chans @stage)) :meta-pub pub-ch)
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
                       <!))))))

(comment
  (require '[geschichte.repo :as repo])
  (require '[geschichte.sync :as sync])
  (require '[konserve.store :refer [new-mem-store]])
  (go (def store (<! (new-mem-store))))
  (go (def peer (sync/client-peer "CLIENT" store)))
  (require '[clojure.core.incubator :refer [dissoc-in]])
  (dissoc-in @peer [:volatile :log])
  @(get-in @peer-a [:volatile :store :state])
  (clojure.pprint/pprint (get-in @peer [:volatile :log]))
  (-> @peer :volatile :store)

  (go (let [printfn (partial println "STAGE:")
            stage (repo/new-repository "me@mail.com"
                                       {:type "s" :version 1}
                                       "Testing."
                                       false
                                       {:some 42})
            stage (<! (wire-stage stage peer))
            [in out]  (:chans stage)
            connedch (chan)
            _ (async/sub in :connected connedch)
            _ (go-loop [conned (<! connedch)]
                       (println "CONNECTED-TO:" conned)
                       (recur (<! connedch)))
            _ (<! (timeout 100))
            _ (>! out {:topic :meta-sub
                       :metas {"me@mail.com" {(:id (:meta stage)) {:master #{}}}}})
            _ (<! (timeout 100))
             new-stage (<! (sync! stage))
            ]
        (-> new-stage
            (transact {:helo :world} '(fn more [old params] (merge old params)))
            repo/commit
            sync!
            <!
            (realize-value store {'(fn more [old params] (merge old params))
                                    (fn more [old params] (merge old params))
                                    '(fn replace [old params] params)
                                    (fn replace [old params] params)})
            <!
            printfn))))
