(ns geschichte.stage
    (:require [konserve.protocols :refer [-get-in]]
              [geschichte.repo :as repo]
              [geschichte.sync :refer [wire]]
              [hasch.core :refer [uuid]]
              [clojure.set :as set]
              #+clj [clojure.core.async :as async
                     :refer [<! >! timeout chan alt! go put! filter< map< go-loop]]
              #+cljs [cljs.core.async :as async
                      :refer [<! >! timeout chan put! filter< map<]])
    #+cljs (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]))


(defn transact
  "add a transaction to the stage."
  [stage params trans-code]
  (update-in stage [:transactions] conj [params trans-code]))


(defn transact-new
  "add a transaction to the stage."
  [stage path params trans-code]
  (update-in stage (concat (butlast path)
                           [:transactions
                            (last path)]) conj [params trans-code]))


(defn commit-history
  "Returns the linear commit history for a stage."
  ([stage]
     (let [{:keys [head branches causal-order]} (:meta stage)]
       (commit-history [] [(first (get branches head))] causal-order)))
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
  "Realizes the value of the current stage with help of store and an
application specific eval-fn (e.g. map from source/symbols to fn.).
Does not memoize yet!"
  [stage store eval-fn]
  (go (let [commit-hist (commit-history stage)
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
                (:transactions stage)))))


(defn sync!
  "Synchronize (push) the results of a geschichte.repo command with storage and other peers.
This does not automatically update the stage."
  [{:keys [id type author chans new-values meta] :as stage}]
  (go (let [[p out] chans
            fch (chan)
            pch (chan)
            sch (chan)
            repo (:id meta)
            fetch-loop (go-loop [to-fetch  (:ids (<! fch))]
                         (when to-fetch
                           (>! out {:topic :fetched
                                    :user author :repo repo
                                    :values (select-keys new-values to-fetch)
                                    :peer (str "STAGE " id)})
                           (recur (:ids (<! fch)))))]

        (async/sub p :fetch fch)
        (async/sub p :meta-pubed pch)
        (async/sub p :meta-subed sch)
        (case type
          :meta-pub (>! out {:topic :meta-pub :meta meta :user author :peer (str "STAGE " id)})
          :meta-sub (do
                      (>! out {:topic :meta-sub
                               :metas {author {repo {(:head meta) #{}}}}
                               :peer (str "STAGE " id)})
                      (<! sch)
                      (async/unsub p :meta-subed sch)
                      (>! out {:topic :meta-pub :meta meta :user author :peer (str "STAGE " id)})))



        (let [m (alt! pch (timeout 10000))]
          (when-not m
            (throw #+clj (IllegalStateException. (str "No meta-pubed ack received for" meta))
                   #+cljs (str "No meta-pubed ack received for" meta))))
        (async/unsub p :meta-pubed pch)
        (async/unsub p :fetch fch)
        (async/close! fch)

        stage)))


(let [metas {"john" {42 {:transactions {"master" [[1 2]]}}}}]
  (for [[u repos] {"john" {42 #{"master" "slave"}}}
        [r branches] repos
        b branches]
    (get-in metas [u r :transactions b])))


(defn sync-new!
  "Synchronize (push) the results of a geschichte.repo command with storage and other peers.
This does not automatically update the stage."
  [stage metas]
  (go (let [{:keys [id user]} @stage
            [p out] (get-in @stage [:volatile :chans])
            fch (chan)
            pch (chan)
            sch (chan)
            repo-id (:id meta)
            new-values (reduce merge {} (for [[u repos] metas
                                              [r branches] repos
                                              b branches]
                                          (get-in @stage [u r :new-values b])))

            _ (println "nv:" new-values)
            fetch-loop (go-loop [to-fetch (:ids (<! fch))]
                         (when to-fetch
                           (>! out {:topic :fetched
                                    :user user :repo-id repo-id
                                    :values (select-keys new-values to-fetch)
                                    :peer (str "STAGE " id)})
                           (recur (:ids (<! fch)))))]

        (async/sub p :fetch fch)
        (async/sub p :meta-pubed pch)
        (async/sub p :meta-subed sch)
        (case type
          :meta-pub (>! out {:topic :meta-pub :meta meta :user user :peer (str "STAGE " id)})
          :meta-sub (do
                      (>! out {:topic :meta-sub
                               :metas metas
                               :peer (str "STAGE " user " " id)})
                      (<! sch)
                      (async/unsub p :meta-subed sch)
                      (>! out {:topic :meta-pub :meta meta :user user :peer (str "STAGE " id)})))



        (let [m (alt! pch (timeout 10000))]
          (when-not m
            (throw #+clj (IllegalStateException. (str "No meta-pubed ack received for" meta))
                   #+cljs (str "No meta-pubed ack received for" meta))))
        (async/unsub p :meta-pubed pch)
        (async/unsub p :fetch fch)
        (async/close! fch)

        (swap! stage #(update-in % [user id] dissoc :op :new-values))
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



(defn create-stage! [user peer]
  (go (let [in (chan)
            out (chan)
            {:keys [store]} (:volatile @peer)]
        (<! (wire peer [in (async/pub out :topic)]))
        (atom {:id (uuid)
               :user user
               :volatile {:chans [(async/pub in :topic) out]
                          :store store
                          :peer peer}}))))


(defn create-repo! [stage user description init-val]
  (go (let [nrepo (repo/new-repository user description false init-val)
            id (get-in nrepo [:meta :id])]
        (swap! stage assoc-in [user id] nrepo)
        (<! (sync-new! stage {user {id #{"master"}}}))
        id)))


(defn subscribe-repos! [stage repos]
  )

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
  (go (def stage (<! (create-stage! "john" peer))))
  (go (println (<! (create-repo! stage "john" "Test repository." {:init 42}))))
  ;; => repo-id
  (subscribe-repos! stage {"jim" {42 {"master" {:update-fn update-fn
                                                :merge-fn merge-fn
                                                :pull-fn pull-fn}}}})
  (remove-repos! stage {"john" #{42}})

  ["jim" 42 123]        ;; value
  ["jim" 42 "featureX"] ;; identity
  (branch! stage ["jim" 42 123] "featureX")
  (checkout! stage ["jim" 42 "featureX"])
  (transact stage ["jim" 42 "featureX"] {:a 1} 'clojure.core/merge)
  (commit! stage ["jim" 42 "master"])
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
