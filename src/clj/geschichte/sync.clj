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


(defn create-peer!
  "Constructs a peer for ip and port, with repository to peer
   mapping peers and subscriptions subs."
  [ip port store]
  {:volatile (merge (start-server! ip port)
                    {:store store})
   :ip ip
   :port port})


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


(defn- new-commits [new-meta old-meta]
  (set/difference (set (keys (:causal-order new-meta)))
                  (set (keys (:causal-order old-meta)))))


(defn- new-commits! [store new-meta old-meta]
  (->> (map #(go [(not (<!(-exists? store [%]))) %])
            (new-commits new-meta old-meta))
       (async/merge)
       (filter< first)
       (map< second)
       (async/into #{})))


(defn print-chan
  ([pre] (print-chan pre (chan)))
  ([pre c]
     (let [pc (chan)]
       (async/tap (async/mult c) pc)
       (go-loop [e (<! pc)]
                (println pre e)
                (recur (<! pc)))
       c)))


(defn subscribe [sub-ch bus-out out]
  (go-loop [{:keys [user repo] :as s} (<! sub-ch)]
             (async/sub bus-out [user repo] out)
             (println "subscribed: " [user repo])
             (recur (<! sub-ch))))


(defn publish [pub-ch fetch-ch store bus-in out]
  (go-loop [{:keys [user meta] :as ps} (<! pub-ch)]
           (let [repo (:id meta)
                 nc (<! (new-commits! store meta (<! (-get-in store [user repo]))))]
             (println "fetching" nc)
             (>! out {:type :fetch
                      :ids nc})
             (doseq [[repo-id val] (:fetched (<! fetch-ch))]
               (<! (-assoc-in store [repo-id] val)))
             (<! (-update-in store [user repo] #(if % (update % meta) meta)))
             (>! bus-in ps)
             (println "published:" [user repo])
             (recur (<! pub-ch)))))


(defn connect [conn-ch bus-in]
  (go-loop [{:keys [ip4 port]} (<! conn-ch)]
             (let [[c-in c-out] (client-connect! ip4 port)]
               (go-loop [i (<! c-in)] (>! bus-in i)))))


(defn wire [peer [out in]]
  (go (let [{:keys [store chans]} (:volatile peer)
            [bus-in bus-out] chans
            p (async/pub in :type)
            pub-ch (chan)
            conn-ch (chan)
            sub-ch (chan)
            fetch-ch (chan)]
        (async/sub p :new-meta sub-ch false)
        (subscribe sub-ch bus-out (print-chan "SUBSOUT" out))

        (async/sub p :meta-up pub-ch false)
        (async/sub p :fetched fetch-ch false)
        (publish pub-ch fetch-ch store bus-in out)

        (async/sub p :connect conn-ch false)
        (connect conn-ch bus-in)

        (async/sub p nil (print-chan "UNSUPPORTED TYPE") false))))




(defn start [state]
  nil)

(defn stop [state]
  ((get-in @state [:volatile :server])))

#_(def mem-store (new-store))

#_(stop peer-a)

;; define live coding vars

#_(def peer-a (create-peer! "127.0.0.1"
                          9090
                          mem-store))

;; subscribe to remote peer(s) as well
#_(def peer-b (create-peer "127.0.0.1"
                         9091
                         mem-store))


#_(def stage (atom nil))


(defn- ensure-conn [peer {:keys [chans] :as stage}]
  (go (if chans stage
          (let [[in out] [(chan) (chan)]]
            (<! (wire peer [in out]))
            (assoc stage :chans [in out])))))


(defn sync! [peer stage]
  (go (let [{:keys [type author chans new-values meta]} (<! (ensure-conn peer stage))
            [in out] chans
            p (async/pub in :type)
            fch (chan)]

        (case type
          :meta-up (>! out {:type :meta-up :user author :meta meta})
          :new-meta (do
                      (>! out {:type :new-meta :user author :repo (:id meta)})
                      (>! out {:type :meta-up :user author :meta meta})))

        (async/sub p :fetch fch)
        (let [to-fetch (select-keys new-values (:ids (<! fch)))]
          (>! out {:type :fetched
                   :fetched to-fetch}))

        (dissoc stage :type :new-values))))


#_(let [p (fake-peer mem-store)]
    (go  (<! (sync!
                       p
                       (repo/new-repository "me@mail.com"
                                            {:type "s" :version 1}
                                            "Testing."
                                            false
                                            {:some 42})))
      (println "pubz:" (<! (first (:chans (:volatile p)))))))


(defn fake-peer [store]
  (let [fake-id (str "FAKE" (rand-int 100))
        in (print-chan (str fake-id "-IN"))
        out (print-chan (str fake-id "-OUT")
                        (async/pub in (fn [{:keys [user id]}] [user id])))]
    {:volatile {:server nil
                :chans [in out]
                :store store}
     :ip fake-id}))


#_(let [in (chan)
      out (chan)
      peer (fake-peer mem-store)
      p (async/onto-chan out [{:type :new-meta

                               :user "john" :repo 1}
                              #_{:type :publish
                                 :user "maria" :repo 3 :meta {:win :ning}}
                              #_{:type :subscribe
                                 :user "maria" :repo 3}
                              {:type :meta-up
                               :user "john" :meta {:id 1
                                                   :causal-order {1 #{}
                                                                  2 #{1}}
                                                   :last-update (java.util.Date.)}}]
                         false)]
  (go (wire peer [in out]))

  (go-loop [i (<! in)]
           (println "received:" i)
           (>! out {:type :fetched :fetched {1 2
                                             2 42
                                             3 43}})
           (recur (<! in))))


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
