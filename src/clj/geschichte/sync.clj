(ns ^:shared geschichte.sync
    "Synching related pub-sub protocols."
    (:require [geschichte.meta :refer [update]]
              [geschichte.repo :as repo] ;; TODO remove after testing
              [geschichte.stage :as s]
              [geschichte.protocols :refer [IAsyncKeyValueStore -assoc-in -get-in -update-in -exists?]]
              [clojure.set :as set]
              [geschichte.platform :refer [client-connect!
                                           start-server! log
                                           new-store]]
              [clojure.core.async :as async
               :refer [<! >! timeout chan alt! go put! filter< map< go-loop]]))


(declare wire)
(defn create-peer!
  "Constructs a peer for ip and port, with repository to peer
   mapping peers and subscriptions subs."
  [ip port store]
  (let [{:keys [new-conns] :as server} (start-server! ip port)
        in (chan)
        out (async/pub in (fn [{:keys [user meta]}] [user (:id meta)]))
        peer (atom {:volatile (merge server
                                     {:store store
                                      :chans [in out]})
                    :ip ip
                    :port port})]
    (go-loop [[in out] (<! new-conns)]
             (println "receving connection")
             (wire peer [out in])
             (recur (<! new-conns)))
    peer))


(declare wire)
(defn load-stage!
  ([peer author repo schema]
     (load-stage! peer author repo schema (chan) (chan)))
  ([peer author repo schema [in out]]
     (go (wire peer [in out])
         {:author author
          :schema schema
          :meta (<! (-get-in (:store (:volatile peer)) [author repo]))
          :chans [in out]
          :transactions []})))


(defn- new-commits [meta-sub old-meta]
  (set/difference (set (keys (:causal-order meta-sub)))
                  (set (keys (:causal-order old-meta)))))


(defn- new-commits! [store meta-sub old-meta]
  (->> (map #(go [(not (<!(-exists? store [%]))) %])
            (new-commits meta-sub old-meta))
       (async/merge)
       (filter< first)
       (map< second)
       (async/into #{})))

(def chan-log (atom []))

(defn debug-chan [pre mult]
  (let [c (chan)
        lc (chan)]
    (async/tap (async/mult c) lc)
    (go-loop [m (<! lc)]
             (swap! chan-log conj [pre m])
             (recur (<! lc)))
    c))


(defn subscribe [peer sub-ch bus-out out]
  (go-loop [{:keys [metas] :as s} (<! sub-ch)]
           (doseq [user (keys metas)
                   repo (get metas user)]
             (async/sub bus-out [user repo] out))
           (swap! peer update-in [:subscriptions] (partial merge-with
                                                           (partial merge-with set/union)) metas)
           (>! out {:type :meta-subed :metas metas})
           (println "subscribed:" metas)
           (recur (<! sub-ch))))



(defn publish [pub-ch fetch-ch store bus-in out]
  (go-loop [{:keys [user meta] :as ps} (<! pub-ch)]
           (println "new pub msg:" ps)
           (let [repo (:id meta)
                 nc (<! (new-commits! store meta (<! (-get-in store [user repo]))))]
             (>! out {:type :fetch
                      :ids nc})
             (let [fetched (<! fetch-ch)]
               (doseq [[repo-id val] (:fetched fetched)]
                 (<! (-assoc-in store [repo-id] val))))
             ;; TODO find cleaner way to atomically update
             (let [old-meta (<! (-get-in store [user repo]))
                   up-meta (<! (-update-in store [user repo] #(if % (update % meta) meta)))]
               (>! out {:type :meta-pubed :user user :repo repo})
               (when (not= old-meta up-meta)
                 (>! bus-in ps)))
             (println "published:" [user repo])
             (recur (<! pub-ch)))))


(defn connect [peer conn-ch bus-in]
  (go-loop [{:keys [ip4 port]} (<! conn-ch)]
           (println "connecting to" ip4 port)
           (let [[c-in c-out] (client-connect! ip4 port)
                 subs (:subscriptions @peer)
                 p (async/pub c-in :type)
                 subed-ch (chan)]
             (<! (wire peer [c-out c-in]))

             (async/sub p :meta-subed subed-ch)
             (>! c-out {:type :meta-sub :metas subs})
             (println "connected for: " (<! subed-ch))
             ; HACK, auto-subscribe back
             (>! c-in {:type :meta-sub :metas subs}))))



(defn wire [peer [out in]]
  (go (let [{:keys [store chans]} (:volatile @peer)
            ip (:ip @peer)
            [bus-in bus-out] chans
            p (async/pub in :type)
            pub-ch (debug-chan (str ip "-PUB"))
            conn-ch (debug-chan (str ip "-CONN"))
            sub-ch (debug-chan (str ip "-SUB"))
            fetch-ch (debug-chan (str ip "-FETCH"))]
        (async/sub p :meta-sub sub-ch)
        (subscribe peer sub-ch bus-out out)

        (async/sub p :meta-pub pub-ch)
        (async/sub p :fetched fetch-ch)
        (publish pub-ch fetch-ch store bus-in out)

        (async/sub p :connect conn-ch)
        (connect peer conn-ch bus-in)

        (async/sub p nil (debug-chan "UNSUPPORTED TYPE") false))))


#_(def mem-store (new-store))
#_(def peer (atom (fake-peer mem-store)))
#_(clojure.pprint/pprint @chan-log)
#_(let [_ (swap! chan-log (fn [o] []))
      in (debug-chan "STAGE-IN")
      out (debug-chan "STAGE-OUT")]
  (go (<! (wire peer [in out]))
      (>! out {:type :meta-sub :metas {"john" {1 #{2}}}})
      (<! (timeout 1000))
      (>! out {:type :connect
               :ip4 "127.0.0.1"
               :port 9090})
      (<! (timeout 1000))
      (>! out {:type :meta-pub
               :user "john" :meta {:id 1
                                   :causal-order {1 #{}
                                                  2 #{1}}
                                   :last-update (java.util.Date. 0)
                                   :schema {:type ::geschichte
                                            :version 1}}})
      (<! (timeout 1000))
      (>! out {:type :meta-pub
               :user "john" :meta {:id 1
                                   :causal-order {1 #{}
                                                  2 #{1}
                                                  3 #{2}}
                                   :last-update (java.util.Date. 0)
                                   :schema {:type ::geschichte
                                            :version 1}}}))

  (go-loop [i (<! in)]
           (println "received:" i)
           (flush)
           (>! out {:type :fetched :fetched {1 2
                                             2 42
                                             3 43}})
           (recur (<! in))))




(defn start [state]
  nil)

(defn stop [state]
  ((get-in @state [:volatile :server])))


#_(stop peer-a)

;; define live coding vars

#_(def peer-a  (create-peer! "127.0.0.1"
                                   9090
                                   (new-store)))

;; subscribe to remote peer(s) as well
#_(def peer-b  (create-peer! "127.0.0.1"
                                   9091
                                   mem-store))


#_(let [[in out] (client-connect! "127.0.0.1" 9091)]
    (wire peer-a [in out]))

#_(def stage (atom nil))


(defn- ensure-conn [peer {:keys [chans] :as stage}]
  (go (if chans stage
          (let [[in out] [(chan) (chan)]]
            (<! (wire peer [in out]))
            (assoc stage :chans [in out])))))


; TODO remove peer
(defn sync! [peer stage]
  (go (let [{:keys [type author chans new-values meta] :as new-stage} (<! (ensure-conn peer stage))
            [in out] chans
            p (async/pub in :type)
            fch (chan)]

        (async/sub p :fetch fch)
        (case type
          :meta-pub (>! out {:type :meta-pub :user author :meta meta})
          :meta-sub (do
                      (>! out {:type :meta-sub :user author :repo (:id meta)})
                      (>! out {:type :meta-pub :user author :meta meta})))

        (let [to-fetch (select-keys new-values (:ids (<! fch)))]
          (>! out {:type :fetched
                   :fetched to-fetch}))

        (dissoc new-stage :type :new-values))))


#_(go (let [peer (fake-peer mem-store)
          new-stage (<! (sync! peer (repo/new-repository "me@mail.com"
                                                         {:type "s" :version 1}
                                                         "Testing."
                                                         false
                                                         {:some 42})))
          in (first (:chans new-stage))]

      (println "publication:" (<! in))))



(defn fake-peer [store]
  (let [fake-id (str "FAKE" (rand-int 100))
        in (chan) #_(debug-chan (str fake-id "-IN"))
        out (async/pub in (fn [{:keys [user meta]}] [user (:id meta)]))]
    {:volatile {:server nil
                :chans [in out]
                :store store}
     :ip fake-id}))


(defn realize-value [stage store eval-fn]
  (go (let [{:keys [head branches causal-order]} (:meta stage)
            tip (branches head)
            hist (loop [c (first tip)
                        hist '()]
                   (if c
                     (recur (first (causal-order c))
                            (conj hist (:transactions
                                        (<! (-get-in store [c])))))
                     hist))]
        (reduce (fn [acc [params trans-fn]]
                  ((eval-fn trans-fn) acc params))
                nil
                (concat (apply concat hist) (:transactions stage))))))

#_(go (println
       (<! (let [new-stage (<! (sync!
                                (repo/new-repository "me@mail.com"
                                                     {:type "s" :version 1}
                                                     "Testing."
                                                     false
                                                     {:some 42})
                                peer-a))]
             (swap! stage (fn [old stage] stage)
                    (<! (sync!
                         (repo/commit (s/transact new-stage
                                                  {:other 43}
                                                  '(fn merger [old params] (merge old params))))
                         peer-a)))))))
