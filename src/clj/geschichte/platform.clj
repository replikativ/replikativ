(ns geschichte.platform
  "Platform specific io operations."
  (:use [clojure.set :as set])
  (:require [geschichte.debug-channels :as debug]
            [clojure.core.async :as async
             :refer [<! >! timeout chan alt! go go-loop]]
            [org.httpkit.server :refer :all]
            [http.async.client :as cli]))


(def log println)


(defn now [] (java.util.Date.))


(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
Only supports websocket at the moment, but is supposed to dispatch on protocol of url."
  [url]
  (let [http-client (cli/create-client) ;; TODO use as singleton var?
        in (chan)
        out (chan)
        opener (chan)]
    (try
      (cli/websocket http-client url
                     :open (fn [ws]
                             (log "ws-opened" ws)
                             (go-loop [m (<! out)]
                                      (when m
                                        (log "client sending msg to:" url m)
                                        (cli/send ws :text (pr-str m))
                                        (recur (<! out))))
                             (async/put! opener [in out])
                             (async/close! opener))
                     :text (fn [ws ms]
                             (let [m (read-string ms)]
                               (log "client received msg from:" url m)
                               (async/put! in m)))
                     :close (fn [ws code reason]
                              (log "closing" ws code reason)
                              (async/close! in)
                              (async/close! out))
                     :error (fn [ws err] (log "ws-error" url err)
                              (.printStackTrace err)
                              (async/close! opener)))
      (catch Exception e
        (log "client-connect error:" url e)))
    opener))


(defn create-http-kit-handler!
  "Creates a server handler described by url, e.g. wss://myhost:8080/geschichte.
Returns a map to run a peer with a platform specific server handler under :handler."
  [url]
  (let [channel-hub (atom {})
        conns (chan)
        ch-log (atom {})
        handler (fn [request]
                  (let [in (debug/chan ch-log [url :in])
                        out (debug/chan ch-log [url :out])]
                    (async/put! conns [in out])
                    (with-channel request channel
                      (swap! channel-hub assoc channel request)
                      (go-loop [m (<! out)]
                               (when m
                                 (log "server sending msg:" url (pr-str m))
                                 (send! channel (pr-str m))
                                 (log "msg sent")
                                 (recur (<! out))))
                      (on-close channel (fn [status]
                                          (log "channel closed:" status)
                                          (swap! channel-hub dissoc channel)
                                          (async/close! in)
                                          (async/close! out)))
                      (on-receive channel (fn [data]
                                            (log "server received data:" url data)
                                            (async/put! in (read-string data)))))))]
    {:new-conns conns
     :channel-hub channel-hub
     :url url
     :handler-log log
     :handler handler}))




(defn start [peer]
  (when-let [handler (-> @peer :volatile :handler)]
    (println "starting" (:name @peer))
    (swap! peer assoc-in [:volatile :server]
           (run-server handler {:port (->> (-> @peer :volatile :url)
                                           (re-seq #":(\d+)")
                                           first
                                           second
                                           read-string)}))))


(defn stop [peer]
  (when-let [stop-fn (get-in @peer [:volatile :server])]
    (stop-fn))
  (when-let [in (-> @peer :volatile :chans first)]
    (async/close! in)))


#_(do (require '[geschichte.sync :refer [client-peer server-peer wire]])
      (require '[konserve.store :refer [new-mem-store]])
      (def peer-a (atom nil))
      (def peer (atom nil))
      (def stage-log (atom nil)))


#_(go (stop peer-a)
      (reset! peer-a @(server-peer (create-http-kit-handler! "ws://127.0.0.1:9090")
                                (<! (new-mem-store))))
      (start peer-a)
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
      #_(>! b-out {:topic :connect :url  "ws://127.0.0.1:9090"})
      (<! (timeout 100))
      #_(>! b-out {:topic :meta-sub :metas {"john" #{1}}})
      (<! (timeout 100))

                                        ;      (<! in)
      (>! out {:topic :connect
               :url"ws://127.0.0.1:9090"})
      (<! (timeout 1000)) ;; timing issue, 100 is too little
      (>! out {:topic :meta-sub :metas {"john" #{1}}})
      #_(>! out {:topic :connect
                 :url "ws://127.0.0.1:9091"})
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


(comment
  (def handler (create-handler! "ws://127.0.0.1:19090"))
  (def server (run-server (:handler handler) {:port 19090}))
  ((:server server)))
