(ns ^:shared geschichte.synch
    "Synching related pub-sub protocols."
    (:use [geschichte.meta :refer [update]]
          [geschichte.protocols])
    (:require [clojure.set :as set]
              [geschichte.platform :refer [put! take-all! client-connect!
                                           start-server!]]))


(defn dispatch
  "Dispatch for peer on channel with message."
  [peer ch msg]
  (let [{:keys [type] :as data} (read-string msg)]
    (println "receiving data " data " on peer "
             (:ip @(:state peer)) ":" (:port @(:state peer)))
    (case type
      :subscribe (let [{:keys [address subscriptions]} data]
                   (-subscribe peer address subscriptions ch))
      :publish (let [{:keys [user repo meta]} data]
                (-publish peer user repo meta))
      (println "no dispatch value for: " data))))

(defn subscribe
  "Add subscriptions for new-subs (user/repo map of peer)
   to orig-subs map for address of peer."
  [orig-subs new-subs address]
  (reduce (fn [s user]
            (assoc s user
                   (reduce (fn [repos nrepo]
                             (update-in repos [nrepo]
                                        #(conj (or %1 #{}) %2) address))
                           (orig-subs user)
                           (keys (new-subs user)))))
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
  [peer state]
  (let [{:keys [users->peers ip port subscriptions]} state
        chs (map (fn [addr] [addr (client-connect! addr)])
                 (all-peers users->peers))]
    (doseq [[_ ch] chs]
      (put! ch (str {:type :subscribe
                     :address (str ip ":" port)
                     :subscriptions subscriptions}))
      (take-all! ch #(dispatch peer ch %)))
    (reduce (fn [s [h ch]] (-> s
                              (update-in [:users->peers] subscribe subscriptions h)
                              (assoc-in [:volatile :network h] ch))) state chs)))

(defrecord WSPeer [state]
  IActivity
  (-start [this]
    (let [server (start-server! this (partial dispatch this))
          subscribed (connect-and-subscribe! this @(:state this))]
      (swap! (:state this) (fn [_]
                             (-> subscribed
                                 (assoc-in [:volatile :server] server))))))

  (-stop [this] ; TODO unsubscribe
    ((get-in @(:state this) [:volatile :server])))

  IPeer
  (-publish [this user repo new-meta]
    (println "publishing: " user repo new-meta)
    (let [old @state ;; eventual consistent, race condition ignorable
          new (swap! state update-in [:subscriptions user repo] update new-meta)
          new-meta* (get-in new [:subscriptions user repo])]
      (when (not= new old) ;; notify peers
        (doseq [peer (get-in old [:users->peers user repo])]
          (put! (get-in old [:volatile :network peer])
                (str {:type :publish
                      :user user
                      :repo repo
                      :meta new-meta*}))))
      {:new new-meta*
       :new-revs (set/difference (set (keys new-meta))
                                 (set (keys (get-in old [user repo]))))}))

  (-subscribe [this address subs chan]
    (println "subscribing " address subs chan)
    (let [new (swap! state #(-> %
                                (update-in [:users->peers] subscribe subs address)
                                (assoc-in [:volatile :network address] chan)))]
      (doseq [user (keys subs)
              repo (keys (subs user))]
        (-publish this user repo (get-in subs [user repo])))
      (select-keys @state (keys subs)))))

(defn create-peer
  "Constructs a peer for ip and port, with repository to peer
   mapping peers and subscriptions subs."
  [ip port peers subs]
  (WSPeer. (atom {:volatile {:network {}}
                  :ip ip
                  :port port
                  :users->peers peers
                  :subscriptions subs})))



;; start listening for incoming websocket connections
#_(do (use '[geschichte.repo])
    (def schema {:type ::schema :version 1})
    (def repo {:meta
               {:causal-order
                {#uuid "b189b9f4-0901-4a39-a1c9-a0266254fbd3" #{},
                 :root #uuid "b189b9f4-0901-4a39-a1c9-a0266254fbd3"},
                :last-update #inst "2013-12-04T23:03:45.465-00:00",
                :head "master",
                :public true,
                :branches {"master" #{#uuid "b189b9f4-0901-4a39-a1c9-a0266254fbd3"}},
                :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
                :pull-requests {},
                :id #uuid "22aa0537-6e66-43e4-bda2-2b4211e0e4ec",
                :description "test repo"},
               :value
               {:geschichte.meta/meta
                {:ts #inst "2013-12-04T23:03:45.465-00:00",
                 :author "user@mail.com",
                 :schema {:version 1, :type :geschichte.synch/schema},
                 :branch "master",
                 :id #uuid "b189b9f4-0901-4a39-a1c9-a0266254fbd3"},
                :value 42}})
    (def meta (:meta repo))
    (def repo-up (commit meta "user@mail.com" schema "master"
                         (first ((:branches meta) (:head meta))) {:value 43})))

#_(def peer-a (create-peer "127.0.0.1"
                           9090
                           {}
                           #_{"user@mail.com" {1 {1 42}}}
                           {"user@mail.com" {(:id meta) meta}}))
#_(-start peer-a)
;; subscribe to remote peer(s) as well
#_(def peer-b (create-peer "127.0.0.1"
                           9091
                           {"user@mail.com" {(:id meta) #{"127.0.0.1:9090"}} }
                           #_{"user@mail.com" {1 {1 42}}}
                           {"user@mail.com" {(:id meta) meta}}))
#_(-start peer-b)

;; publish and then check for update of meta in subscriptions of other peer

#_(-publish peer-b "user@mail.com" (:id meta) (:meta repo-up))
#_(-publish peer-a "user@mail.com" 1 {1 42 2 43 3 44})
#_(-publish peer-b "user@mail.com" 1 {4 45})

#_(-stop peer-a)
#_(-stop peer-b)




;; dumb in memory peer for testing TODO move to tests

;; for testing; idempotent, commutative
#_(def update merge)

(declare network)
(defrecord Peer [state]
  IPeer
  (-publish [this user repo new-meta]
    (let [old @state ;; eventual consistent, race condition ignorable
          new (swap! state update-in [user repo] update new-meta)
          new-meta* (get-in new [user repo])]
      (when (not= new old) ;; notify peers
        (doseq [peer (get-in old [:users->peers user repo])]
          (-publish (network peer) user repo new-meta*)))
      {:new new-meta*
       :new-revs (set/difference (set (keys new-meta))
                                 (set (keys (get-in old [user repo]))))}))
  (-subscribe [this address subs chan]
    (let [new (swap! state update-in [:users->peers] subscribe subs address)]
      (doseq [user (keys subs)
              repo (keys (subs user))]
        (-publish this user repo (get-in subs [user repo])))
      (select-keys @state (keys subs)))))


#_(def network {"1.1.1.1" (Peer. (atom {"user@mail.com" {1 {1 42}
                                                         2 {1 314}}
                                        "other@mail.com" {1 {1 42
                                                             2 43}
                                                          3 {1 628}}
                                      :users->peers {}}))
                "1.2.3.4" (Peer. (atom {"user@mail.com" {1 {1 42
                                                            2 43}}
                                      :users->peers {}}))})


#_(-subscribe (network "1.1.1.1")
            "1.2.3.4"
            (dissoc @(.-state (network "1.2.3.4")) :users->peers)
            nil)

#_(-publish (network "1.1.1.1") "user@mail.com" 1 {1 42
                                                  2 43
                                                  3 44
                                                  4 45})

#_(subscribe {"user@mail.com"{1 #{"1.1.1.1"}}}
           {"user@mail.com" {1 {:a 1} 2 {:a 2}}
            "other@mail.com" {1 {:a 2}
                              3 {:b 4}}}
           "1.2.3.4")
