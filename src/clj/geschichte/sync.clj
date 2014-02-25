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
               :refer [<! >! timeout chan alt! go put! filter< map<]]))



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





(defn- new-commits [new-meta old-meta]
  (set/difference (set (keys (:causal-order new-meta)))
                  (set (keys (:causal-order old-meta)))))

(defn- new-commits! [store new-meta old-meta]
  (go (<! (->> (map #(go [(not (<!(-exists? store [%]))) %])
                    (new-commits new-meta old-meta))
               (async/merge)
               (filter< first)
               (map< second)
               (async/into #{})))))


(comment
  (-assoc-in mem-store [1] 1)
  (go (println
       (<! (new-commits! mem-store {:causal-order {1 1 2 2}} {:causal-order {1 1}})))))



(defn- fetch! [[in out] new-ids]
  (go (when-not (empty? new-ids)
        (println "fetch!" new-ids)
        (let [cp (chan)]
          (async/tap (async/mult in) cp)
          (>! out  {:type :fetch
                    :ids new-ids})
          (println "send fetch req")
          (:fetched (<! (filter< #(do (println "trying to match" % new-ids)
                                      (and (= (:type %) :fetched)
                                           (= (set (keys (:fetched %)))
                                              new-ids)))
                                 cp)))))))





(comment
  (let [in (chan)
        out (chan)]
    (go (println "fetched" (<! (fetch! [in out] #{3}))))
    (go (let [to-fetch (<! out)]
          (println "to-fetch" to-fetch)
          (>! in {:type :fetched
                  :fetched {3 :three}})))))

#_(go (let [b (chan)
           bc (chan)]
       (async/tap (async/mult b) bc)
       (put! b :cake)
       (put! b 42)
       (println  (async/alts!  [(filter< number? b) bc (timeout 100)])
                 )))

#_(go (let [c (chan 5)]
       (put! c 5)
       (put! c :hello)
       (println [(<! (filter< number? c))
                 (<! c)])))







(defn publish! [state [in out] user {repo :id :as meta}]
  (println "publishing: " user meta)
  (go (let [store (get-in @state [:volatile :store])
            old-meta (<! (-get-in store [user repo]))
            new-ids (<! (new-commits! store meta old-meta))
            _ (println "new-ids" new-ids)
            fetched (<! (fetch! [in out] new-ids))
            _ (println "fetched" fetched)
            _ (doseq [[id val] (seq fetched)]
                (<! (-assoc-in store [id] val)))
            new-meta (<! (-update-in store [user repo] #(if % (update % meta) meta)))]
        (when (not= new-meta old-meta) ;; notify peers
          (doseq [peer (get-in @state [:users->peers user repo])]
            (put! (get-in @state [:volatile :network peer 1])
                  {:type :publish
                   :user user
                   :meta new-meta}))))))



(declare add-subscriptions)
(defn subscribe! [state address subs chans]
  (println "subscribing " address subs chan)
  (swap! state #(-> %
                    (update-in [:users->peers] add-subscriptions subs address)
                    (assoc-in [:volatile :network address] chans))))



(defn dispatch!
  "Dispatch for peer on remote [in out] channels and storage store."
  [state [in out]]
  (let [{:keys [store]} (:volatile @state)
        msgs (filter< (fn [{t :type}]
                        (println "dispatching on" t)
                        (#{:subscribe :publish :fetch} t)) in)]
    (map< (fn [{:keys [type] :as msg}]
            (println "receiving msg " msg " on peer "
                     (:ip @state) ":" (:port @state))
            (case type
              :subscribe (let [{:keys [address subscriptions]} msg]
                           (subscribe! state address subscriptions [in out]))
              :publish (let [{:keys [user meta]} msg]
                         (publish! state [in out] user meta))
              :fetch (go (let [{:keys [ids]} msg
                               vals (reduce #(assoc %1 %2 (<! (-get-in store [%2]))) {} ids)]
                           (>! out {:type :fetched
                                    :fetched vals})))
              (println "no dispatch value for: " msg)))
          msgs)))


(defn add-subscriptions
  "Add subscriptions for new-subs (user->repos map of peer)
   to orig-subs map for address of peer."
  [orig-subs new-subs address]
  (reduce (fn [s user]
            (assoc s user
                   (reduce (fn [repos nrepo]
                             (update-in repos [nrepo]
                                        #(conj (or %1 #{}) %2) address))
                           (orig-subs user)
                           (new-subs user))))
          orig-subs
          (keys new-subs)))

(defn all-peers
  "Takes the users to peers map
   and extracts all peers."
  [peers]
  (->> peers
       vals
       (map vals)
       flatten
       (apply set/union)))




(defn connect-and-subscribe!
  "Returns state with peer channels connected and subscribed."
  [state]
  (let [{:keys [users->peers ip port subscriptions]} state
        chs (doall (map (fn [addr] [addr (client-connect! addr)])
                        (all-peers users->peers)))]
    (doseq [[_ [in out]] chs]
      (put! out {:type :subscribe
                 :address (str ip ":" port)
                 :subscriptions subscriptions})
      (dispatch! state [in out]))
    (reduce (fn [s [h ch]]
              (let [new (-> s
                            (update-in [:users->peers] add-subscriptions subscriptions h)
                            (assoc-in [:volatile :network h] ch))]
                (log "new" new)
                new)) state chs)))


(defn create-peer!
  "Constructs a peer for ip and port, with repository to peer
   mapping peers and subscriptions subs."
  [ip port peers subs store]
  (let [state (atom (connect-and-subscribe! {:volatile {:network {}
                                                        :server (start-server! ip port)
                                                        :store store}
                                             :ip ip
                                             :port port
                                             :users->peers peers
                                             :subscriptions subs}))]
    state))

(defn start [state]
  (dispatch! state (get-in @state [:volatile :server :chans])))

(defn stop [state]
  ((get-in @state [:volatile :server :server])))

#_(stop peer-a)

;; define live coding vars
#_(def mem-store (new-store))

#_(def stage (atom nil))

#_(def peer-a (create-peer! "127.0.0.1"
                          9090
                          {}
                          {"user@mail.com" #{(get-in @stage [:meta :id])}}
                          mem-store))
;; start listening for incoming websocket connections
#_(start peer-a)
;; subscribe to remote peer(s) as well
#_(def peer-b (create-peer "127.0.0.1"
                         9091
                         {"user@mail.com" {(get-in @stage [:meta :id]) #{"127.0.0.1:9090"}}}
                         {"user@mail.com" #{(get-in @stage [:meta :id])}}
                         mem-store))

#_(log "started " (start peer-b))

;; publish and then check for update of rmeta in subscriptions of other peer

#_(publish! peer-b "user@mail.com" (:meta @stage))

#_(stop peer-a)
#_(stop peer-b)


(defn meta-up! [peer [in out] author meta new-values]
 (publish! peer [in out] author meta) ;; wrap new-values around pulling like remote peers
  (go (let [{:keys [ids]} (<! (filter< #(= :fetch (:type %)) out))
            to-fetch (select-keys new-values ids)]

        (when to-fetch
          (>! in {:type :fetched
                  :fetched to-fetch})
          (println "to-fetch" to-fetch))
        )))


(defn sync! [{:keys [type author chans new-values meta] :as stage} peer]
  (go (let [[in out] (if chans chans
                         (let [chans [(chan) (chan)]]
                           (dispatch! peer chans)
                           chans))]
        (case type
          :meta-up (<! (meta-up! peer [in out] author meta new-values))
          :new-meta (do
                      (subscribe! peer "app" {author #{(:id meta)}} [in out])
                      (<! (meta-up! peer [in out] author meta new-values))))
        (-> stage
            (assoc :chans [in out])
            (dissoc :type :new-values)))))



#_(go (<! (sync!
           (repo/new-repository "me@mail.com"
                                {:type "s" :version 1}
                                "Testing."
                                false
                                {:some 42})
           peer-a)))

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


#_(go (let [realized (realize-value @stage mem-store eval)]
      (println "value" (<! realized))))

#_(swap! stage
       s/transact
       {:some-more 5}
       '(fn adder [old {:keys [some-more]}]
          (update-in old [:some] + some-more)))


#_(go (println (<!
              (load-stage! "me@mail.com"
                           {:type "s" :version 42}
                           #uuid "5e20ebea-6614-4402-9829-f19d8520b51e"
                           mem-store))))

(comment
  (go (let [time (System/currentTimeMillis)
            val (async/alts! [(chan) (timeout 100)])
            new-time (System/currentTimeMillis)]
        (println [time new-time]))))


; TODO
(defn load-stage! [author schema repo store]
  (go {:meta (<! (-get-in store [author repo]))
       :author author
       :schema schema
       :transactions []}))
