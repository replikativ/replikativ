(ns ^:shared geschichte.sync
    "Synching related pub-sub protocols."
    (:require [geschichte.meta :refer [update]]
              [geschichte.stage :as s]
              [geschichte.store :refer [new-mem-store]] ;; TODO remove after testing
              [geschichte.protocols :refer [IAsyncKeyValueStore -assoc-in -get-in -update-in]]
              [geschichte.debug-channels :as debug]
              [clojure.set :as set]
              [geschichte.platform :refer [client-connect! start-server! now new-couch-store]]
              #+clj [clojure.core.async :as async
                     :refer [<! >! timeout chan alt! go put! filter< map< go-loop]]
              #+cljs [cljs.core.async :as async
                     :refer [<! >! timeout chan put! filter< map<]])
    #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


(declare wire)
(defn client-peer
  "Creates a client-side peer only."
  [name store]
  (let [log (atom {})
        in (debug/chan log [name :in])
        out (async/pub in :topic)]
    (atom {:volatile {:server nil
                      :log log
                      :chans [in out]
                      :store store}
           :meta-sub {}
           :ip name})))


(defn server-peer
  "Constructs a peer for ip and port, with repository to peer
   mapping peers."
  [ip port store]
  (let [{:keys [new-conns] :as server} (start-server! ip port)
        log (atom {})
        in (debug/chan log [ip :in])
        out (async/pub in :topic)
        peer (atom {:volatile (merge server
                                     {:store store
                                      :log log
                                      :chans [in out]})
                    :meta-sub {}
                    :ip ip
                    :port port})]
    (go-loop [[in out] (<! new-conns)]
             (wire peer [out (async/pub in :topic)])
             (recur (<! new-conns)))
    peer))


(defn start [peer]
  nil)


(defn stop [peer]
  (when-let [stop-fn (get-in @peer [:volatile :server])]
    (stop-fn))
  (when-let [in (-> @peer :volatile :chans first)]
    (async/close! in)))


(defn load-stage!
  ([peer author repo schema]
     (load-stage! peer author repo schema (chan) (chan)))
  ([peer author repo schema [in out]]
     (go (<! (wire peer [in (async/pub out :topic)]))
         {:author author
          :schema schema
          :meta (<! (-get-in (:store (:volatile @peer)) [author repo]))
          :chans [in out]
          :transactions []})))


(defn- new-commits [meta-sub old-meta]
  (set/difference (set (keys (:causal-order meta-sub)))
                  (set (keys (:causal-order old-meta)))))


(defn- new-commits! [store meta-sub old-meta]
  (->> (map #(go [(not (<!(-get-in store [%]))) %])
            (new-commits meta-sub old-meta))
       async/merge
       (filter< first)
       (map< second)
       (async/into #{})))


(defn subscribe
  "Store and propagate subscription requests."
  [peer sub-ch out]
  (let [[bus-in bus-out] (-> @peer :volatile :chans)
        pubs-ch (chan)
        p (async/pub pubs-ch (fn [{{r :id} :meta u :user}] [u r]))]
    (async/sub bus-out :meta-sub out)
    (async/sub bus-out :meta-pub pubs-ch)
    (go-loop [{:keys [metas] :as s} (<! sub-ch)]
             (when s
               (doseq [user (keys metas)
                       repo (get metas user)]
                 (async/sub p [user repo] out))

               ;; incrementally propagate subscription to other peers
               (let [new-subs (->> (:meta-sub @peer)
                                   (merge-with set/difference metas)
                                   (filter (fn [[k v]] (not (empty? v))))
                                   (into {}))]
                 (when-not (empty? new-subs)
                   (>! bus-in {:topic :meta-sub :metas new-subs})))

               (swap! peer update-in [:meta-sub] (partial merge-with set/union) metas)

               (>! out {:topic :meta-subed :metas metas})

               (recur (<! sub-ch))))))



(defn publish
  "Synchronize metadata publications by fetching missing repository values."
  [peer pub-ch fetched-ch store bus-in out]
  (go-loop [{:keys [user meta] :as p} (<! pub-ch)]
           (when p
             (let [repo (:id meta)
                   nc (<! (new-commits! store meta (<! (-get-in store [user repo]))))]
               (when-not (empty? nc)
                 (>! out {:topic :fetch
                          :ids nc})
                 ;; TODO check hash
                 (doseq [[trans-id val] (:values (<! fetched-ch))] ;; TODO timeout
                   (<! (-assoc-in store [trans-id] val))))

               (>! out {:topic :meta-pubed})
               (let [[old-meta up-meta]
                     (<! (-update-in store [user repo] #(if % (update % meta)
                                                            (update meta meta))))]
                 (when (not= old-meta up-meta)
                   (>! bus-in p)))
               (recur (<! pub-ch))))))



(defn connect
  "Service connection requests."
  [peer conn-ch out]
  (go-loop [{:keys [ip4 port] :as c} (<! conn-ch)]
           (when c
             (let [[bus-in bus-out] (:chans (:volatile @peer))
                   address (str ip4 ":" port)
                   log (:log (:volatile @peer))
                   [c-in c-out] [(debug/chan log [address :in])
                                 (debug/chan log [address :out])]
                   subs (:meta-sub @peer)
                   subed-ch (chan)]
               (swap! peer assoc-in [:volatile :client-hub address]
                      (<! (client-connect! ip4 port c-in c-out))) ;; TODO timeout
                                        ; handshake
               (>! c-out {:topic :meta-sub :metas subs})
               (put! subed-ch (<! c-in))
               (subscribe peer subed-ch c-out)

               (<! (wire peer [c-out (async/pub c-in :topic)]))
               (>! out {:topic :connected
                        :ip4 ip4 :port port})
               (recur (<! conn-ch))))))


(defn fetch
  "Service (remote) fetch requests."
  [peer fetch-ch out]
  (go-loop [{:keys [user repo ids] :as m} (<! fetch-ch)]
           (when m
             (let [store (:store (:volatile @peer))
                   fetched (->> ids
                                (map (fn [id] (go [id (<! (-get-in store [id]))])))
                                async/merge
                                (async/into {})
                                <!)]
               (>! out {:topic :fetched
                        :values fetched})
               (recur (<! fetch-ch))))))


(defn wire
  "Wire a peer to an output (response) channel and a publication by :topic of the input."
  [peer [out p]]
  (go (let [{:keys [store chans log]} (:volatile @peer)
            ip (:ip @peer)
            [bus-in bus-out] chans
            ;; unblock on fast publications, newest always superset
            pub-ch (debug/chan log [ip :pub] (async/sliding-buffer 1))
            conn-ch (debug/chan log [ip :conn])
            sub-ch (debug/chan log [ip :sub])
            fetch-ch (debug/chan log [ip :fetch])
            fetched-ch (debug/chan log [ip :fetched])]
        ;; HACK drop those for cljs core.async pub
        (async/sub bus-out :meta-subed (chan (async/sliding-buffer 1)))
        (async/sub bus-out :meta-pubed (chan (async/sliding-buffer 1)))
        (async/sub p :meta-subed (chan (async/sliding-buffer 1)))
        (async/sub p :meta-pubed (chan (async/sliding-buffer 1)))

        (async/sub p :meta-sub sub-ch)
        (subscribe peer sub-ch out)

        (async/sub p :meta-pub pub-ch)
        (async/sub p :fetched fetched-ch)
        (publish peer pub-ch fetched-ch store bus-in out)

        (async/sub p :fetch fetch-ch)
        (fetch peer fetch-ch out)

        (async/sub p :connect conn-ch)
        (connect peer conn-ch out)

        (async/sub p nil (debug/chan log [ip :unsupported]) false))))

;; fire up repl
#_(do
    (ns dev)
    (def repl-env (reset! cemerick.austin.repls/browser-repl-env
                         (cemerick.austin/repl-env)))
    (cemerick.austin.repls/cljs-repl repl-env))



#_(do (def peer-a (atom nil))
      (def peer (atom nil))
      (def stage-log (atom nil)))

#_(go (stop peer-a)
      (reset! peer-a @(server-peer "127.0.0.1"
                                9090
                                (<! (new-couch-store "geschichte"))))
      (reset! peer @(client-peer "CLIENT" (<! (new-mem-store))))
      (reset! stage-log {}))
#_(clojure.pprint/pprint @(:log (:volatile @peer)))
#_(clojure.pprint/pprint @(:log (:volatile @peer-a)))
#_(-> @peer-a :volatile :store :state deref)
#_(-> @peer :volatile)
#_(clojure.pprint/pprint @stage-log)
#_(let [in (debug/chan stage-log [:stage :in])
      out (debug/chan stage-log [:stage :out])
      a-in (debug/chan stage-log [:peer-a :in])
      a-out (debug/chan stage-log [:peer-a :out])]
  (go-loop [m (<! a-in)]
           (when m
             (println "PEERA-IN" m)
             (recur (<! a-in))))
  (go (<! (wire peer [in (async/pub out :topic)]))
      (<! (wire peer-a [a-in (async/pub a-out :topic)]))
      #_(>! b-out {:topic :connect :ip4 "127.0.0.1" :port 9090})
      (<! (timeout 100))
      #_(>! b-out {:topic :meta-sub :metas {"john" #{1}}})
      (<! (timeout 100))

                                        ;      (<! in)
      (>! out {:topic :connect
               :ip4 "127.0.0.1"
               :port 9090})
      (<! (timeout 1000)) ;; timing issue, 100 is too little
      (>! out {:topic :meta-sub :metas {"john" #{1}}})
      #_(>! out {:topic :connect
                 :ip4 "127.0.0.1"
                 :port 9091})
      (<! (timeout 100))
      (>! out {:topic :meta-pub
               :user "john"
               :meta {:id 1
                      :causal-order {1 #{}
                                     2 #{1}}
                      :last-update (now)
                      :schema {:topic ::geschichte
                               :version 1}}})
      (<! (timeout 100))
                                        ;     (<! in)
      (>! out {:topic :fetched :values {1 2
                                        2 42}})
      (<! (timeout 100))
                                        ;     (println "1" (:topic (<! in)))
                                        ;     (println "2" (:topic (<! in)))
      (>! out {:topic :meta-pub
               :user "john"
               :meta {:id 1
                      :causal-order {1 #{}
                                     2 #{1}
                                     3 #{2}}
                      :last-update (now)
                      :schema {:topic ::geschichte
                               :version 1}}})
      (<! (timeout 100))
      (>! out {:topic :fetched :values {3 43}})
                                        ;     (println "4" (:topic (<! in)))
                                        ;     (println "5" (:topic (<! in)))
      (<! (timeout 500)))


  (go-loop [i (<! in)]
           (when i
             (println "RECEIVED:" i)
             (recur (<! in)))))



(defn wire-stage
  "Wire a stage to a peer."
  [peer {:keys [chans] :as stage}]
  (go (if chans stage
          (let [log (atom {})
                in (debug/chan log ["STAGE" :in] 10)
                out (debug/chan log ["STAGE" :out])]
            (<! (wire peer [in (async/pub out :topic)]))
            (assoc stage :chans [(async/pub in :topic) out])))))


;; push perspective
(defn sync!
  "Synchronize the results of a geschichte.repo command with storage and other peers."
  [{:keys [type author chans new-values meta] :as stage}]
  (go (let [[p out] chans
            fch (chan)
            pch (chan)
            repo (:id meta)]

        (async/sub p :fetch fch)
        (async/sub p :meta-pubed pch)
        (case type
          :meta-pub  (>! out {:topic :meta-pub :meta meta :user author})
          :meta-sub (do
                      (>! out {:topic :meta-sub :metas {author #{repo}}})
                      (>! out {:topic :meta-pub :meta meta :user author})))

        (go (let [to-fetch (select-keys new-values (:ids (<! fch)))]
              (>! out {:topic :fetched
                       :user author :repo repo
                       :values to-fetch})))

        (let [m (alt! pch (timeout 10000))]
          (when-not m
            (throw #+clj (IllegalStateException. (str "No meta-pubed ack received for" meta))
                   #+cljs (str "No meta-pubed ack received for" meta))))
        (async/unsub p :meta-pubed fch)
        (async/unsub p :fetch fch)

        (dissoc stage :type :new-values))))


(comment
  (require '[geschichte.repo :as repo])
  (def peer (client-peer "CLIENT" (new-store)))
  (require '[clojure.core.incubator :refer [dissoc-in]])
  (dissoc-in @peer [:volatile :log])
  @(get-in @peer-a [:volatile :store :state])
  (clojure.pprint/pprint (get-in @peer [:volatile :log]))
  (-> @peer-a :volatile :store)

  (go (let [printfn (partial println "STAGE:")
            stage (repo/new-repository "me@mail.com"
                                       {:type "s" :version 1}
                                       "Testing."
                                       false
                                       {:some 42})
            stage (<! (wire-stage peer stage))
            [in out]  (:chans stage)
            connedch (chan)
            _ (async/sub in :connected connedch)
            _ (go-loop [conned (<! connedch)]
                       (println "CONNECTED-TO:" conned)
                       (recur (<! connedch)))
            _ (<! (timeout 100))
            _ (>! out {:topic :meta-sub
                       :metas {"me@mail.com" #{(:id (:meta stage))}}})
            _ (<! (timeout 100))
            _ (>! out {:topic :connect
                       :ip4 "127.0.0.1"
                       :port 9090})
            _ (<! (timeout 1000))
             new-stage (<! (sync! stage))
            ]
        (-> new-stage
            (s/transact {:helo :world} '(fn more [old params] old))
            repo/commit
            sync!
            <!
            s/realize-value
            printfn))))





(comment
  (def stage (atom nil))

  (go (println (<! (s/realize-value @stage (-> @peer :volatile :store) eval))))
  (go (println
       (let [new-stage (->> (repo/new-repository "me@mail.com"
                                                 {:type "s" :version 1}
                                                 "Testing."
                                                 false
                                                 {:some 43})
                            (wire-stage peer)
                            <!
                            sync!
                            <!)]
         (reset! stage new-stage)
         #_(swap! stage (fn [old stage] stage)
                  (->> (s/transact new-stage
                                   {:other 43}
                                   '(fn merger [old params] (merge old params)))
                       repo/commit
                       sync!
                       <!))))))
