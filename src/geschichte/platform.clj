(ns geschichte.platform
  "Platform specific UUID generation."
  (:use ; org.httpkit.server
   [lamina.core :refer [enqueue read-channel wait-for-result
                        receive channel siphon map* channel->lazy-seq
                        receive-all]]
        [aleph.http :refer [websocket-client start-http-server]]
        [clojure.set :as set]
        #_[geschichte.meta :refer [publish]]
        [geschichte.synch :refer [IPeer subscribe -subscribe -publish]]))


(defn uuid
  ([] (java.util.UUID/randomUUID))
  ([val] (java.util.UUID/randomUUID)))


(defn date [] (java.util.Date.))

;; WIP sample pubsub websocket implementation

(defprotocol IActivity
  (-start [this])
  (-stop [this]))


(def publish merge)


(defn- dispatch [peer ch msg]
  (let [{:keys [type] :as data} (read-string msg)]
    (println "receiving publish " data)
    (case type
      :subscribe (let [{:keys [address subscriptions]} data]
                   (-subscribe peer address subscriptions ch))
      :publish (let [{:keys [user repo meta]} data]
                (-publish peer user repo meta))
      (println "no dispatch value for: " data))))

(defn- handler [peer ch handshake]
  (receive-all ch #(dispatch peer ch %)))

(defn- subscribe!
  "Returns state with peer channels connected and subscribed."
  [peer state]
  (let [{:keys [volatile ip port subscriptions]} state
        {:keys [network]} volatile
        wss (map (fn [addr] [addr @(websocket-client {:url (str "ws://" addr)})])
                 (keys network))]
    (doseq [[_ ws] wss]
      (enqueue ws (str {:type :subscribe
                        :address (str ip ":" port)
                        :subscriptions subscriptions}))
      (receive-all ws #(dispatch peer ws %)))
    (reduce (fn [s [h ws]] (-> s
                              (update-in [:peers] subscribe subscriptions h)
                              (assoc-in [:volatile :network h] ws))) state wss)))

(defrecord WSPeer [state]
  IActivity
  (-start [this]
    (let [server (start-http-server (partial handler this) {:port (:port @state)
                                                            :websocket true})
          subscribed (subscribe! this @state)]
      (swap! state (fn [_]
                     (-> subscribed
                         (assoc-in [:volatile :server] server))))))
  (-stop [this]
    ((get-in @state [:volatile :server]))
                                        ; TODO unsubscribe
    )
  IPeer
  (-publish [this user repo new-meta]
    (println "updating: " user repo new-meta)
    (let [old @state ;; eventual consistent, race condition ignorable
          new (swap! state update-in [:subscriptions user repo] publish new-meta)
          new-meta* (get-in new [:subscriptions user repo])]
      (when (not= new old) ;; notify peers
        (doseq [peer (get-in old [:peers user repo])]
          (enqueue (get-in old [:volatile :network peer])
                   (str {:type :publish
                         :user user
                         :repo repo
                         :meta new-meta*}))))
      {:new new-meta*
       :new-revs (set/difference (set (keys new-meta))
                                 (set (keys (get-in old [user repo]))))}))

  (-subscribe [this address subs chan]
    (println "subscribing " address subs chan)
    (let [new (swap! state #(-> % (update-in [:peers] subscribe subs address)
                                (assoc-in [:volatile :network address] chan)))]
      (doseq [user (keys subs)
              repo (keys (subs user))]
        (-publish this user repo (get-in subs [user repo])))
      (select-keys @state (keys subs)))))

(defn create-peer [ip port remotes]
  (WSPeer. (atom {:volatile {:network remotes}
                  :peers {}
                  :ip ip
                  :port port
                  :subscriptions {"user@mail.com" {1 {1 42}}}})))

;; start listening for incoming websocket connections

#_(def peer-a (create-peer "127.0.0.1" 9090 {}))
#_(-start peer-a)
;; subscribe to remote peer(s) as well TODO list of peers?
#_(def peer-b (create-peer "127.0.0.1" 9091 {"127.0.0.1:9090" nil}))
#_(-start peer-b)

;; publish and then check for update of meta in subscriptions of other peer

#_(-publish peer-a "user@mail.com" 1 {1 42 2 43 3 44})
#_(-publish peer-b "user@mail.com" 1 {1 42 2 43})
#_(-publish peer-b "user@mail.com" 1 {4 45})

#_(-stop peer-a)
#_(-stop peer-b)
