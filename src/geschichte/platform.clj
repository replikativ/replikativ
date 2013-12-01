(ns geschichte.platform
  "Platform specific UUID generation."
  (:use org.httpkit.server
        [lamina.core :refer [enqueue read-channel wait-for-result]]
        [aleph.http :refer [websocket-client]]
        [clojure.set :as set]
        #_[geschichte.meta :refer [update]]
        [geschichte.synch :refer [IPeer subscribe -subscribe -update]]))


(defn uuid
  ([] (java.util.UUID/randomUUID))
  ([val] (java.util.UUID/randomUUID)))


(defn date [] (java.util.Date.))

(def update merge)

(def network (atom {}))
(def peer (WSPeer. (atom {:peers {}})))
#_@(:state peer)


(defrecord WSPeer [state]
  IPeer
  (-update [this user repo new-meta]
    (let [old @state ;; eventual consistent, race condition ignorable
          new (swap! state update-in [user repo] update new-meta)
          new-meta* (get-in new [user repo])]
      (when (not= new old) ;; notify peers
        (doseq [peer (get-in old [:peers user repo])]
          (send! (@network peer) (str [user repo new-meta*]))))
      {:new new-meta*
       :new-revs (set/difference (set (keys new-meta))
                                 (set (keys (get-in old [user repo]))))}))
  (-subscribe [this address subs]
    (let [new (swap! state update-in [:peers] subscribe subs address)]
      (doseq [user (keys subs)
              repo (keys (subs user))]
        (-update this user repo (get-in subs [user repo])))
      (select-keys @state (keys subs)))))





(defn dispatch [{:keys [type value] :as data}]
  (println "dispatching on " data)
  (case type
    :subscribe (let [{:keys [address subscriptions]} data]
                 (-subscribe peer address subscriptions))
    :update (let [{:keys [user repo meta]} data]
              (-update peer user repo meta))
    (println "no dispatch value for: " data)))


(defn handler [request]
  (with-channel request channel
    (swap! network assoc (:remote-addr request) (:async-channel request))
    (on-close channel (fn [status] (println "channel closed" status)
                        (swap! network dissoc (:remote-addr ))))
    (on-receive channel #(dispatch (read-string %)))))


#_(def stop-server (run-server handler {:port 9090}))
#_(stop-server)


;; client-side




(defn ws-client []
  (websocket-client {:url "ws://localhost:9090"}))

#_(def ws (ws-client))


#_(enqueue (wait-for-result ws 1000)
         (str {:type :subscribe
               :address "127.0.0.1"
               :subscriptions {"user@mail.com" {1 {1 42}}}}))


#_(enqueue (wait-for-result ws 1000)
         (str {:type :update
               :user "user@mail.com"
               :repo 1
               :meta {1 42
                      2 43}}))
