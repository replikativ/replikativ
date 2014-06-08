(ns geschichte.platform
  "Platform specific io operations."
  (:require [clojure.set :as set]
            [clojure.edn :as edn]
            [geschichte.platform-log :refer [debug info warn error]]
            [konserve.platform :refer [read-string-safe]]
            [hasch.benc :refer [IHashCoercion -coerce]]
            [clojure.core.async :as async
             :refer [<! >! timeout chan alt! go go-loop]]
            [org.httpkit.server :refer :all]
            [http.async.client :as cli]))


(defn now [] (java.util.Date.))


(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
Only supports websocket at the moment, but is supposed to dispatch on
protocol of url. tag-table is an atom"
  [url tag-table]
  (let [http-client (cli/create-client) ;; TODO use as singleton var?
        in (chan)
        out (chan)
        opener (chan)]
    (try
      (cli/websocket http-client url
                     :open (fn [ws]
                             (info "ws-opened" ws)
                             (go-loop [m (<! out)]
                                      (when m
                                        (debug "client sending msg to:" url m)
                                        (cli/send ws :text (pr-str m))
                                        (recur (<! out))))
                             (async/put! opener [in out])
                             (async/close! opener))
                     :text (fn [ws ms]
                             (let [m (read-string-safe @tag-table ms)]
                               (debug "client received msg from:" url m)
                               (async/put! in m)))
                     :close (fn [ws code reason]
                              (info "closing" ws code reason)
                              (async/close! in)
                              (async/close! out))
                     :error (fn [ws err] (error "ws-error" url err)
                              (error (.printStackTrace err))
                              (async/close! opener)))
      (catch Exception e
        (error "client-connect error:" url e)))
    opener))


(defn create-http-kit-handler!
  "Creates a server handler described by url, e.g. wss://myhost:8443/geschichte/ws.
Returns a map to run a peer with a platform specific server handler
under :handler.  tag-table is an atom according to clojure.edn/read, it
should be the same as for the peer's store."
  ([url]
     (create-http-kit-handler! url (atom {})))
  ([url tag-table]
     (let [channel-hub (atom {})
           conns (chan)
           handler (fn [request]
                     (let [client-id (gensym)
                           in (chan)
                           out (chan)]
                       (async/put! conns [in out])
                       (with-channel request channel
                         (swap! channel-hub assoc channel request)
                         (go-loop [m (<! out)]
                           (when m
                             (debug "server sending msg:" url (pr-str m))
                             (send! channel (pr-str m))
                             (recur (<! out))))
                         (on-close channel (fn [status]
                                             (info "channel closed:" status)
                                             (swap! channel-hub dissoc channel)
                                             (async/close! in)))
                         (on-receive channel (fn [data]
                                               (debug "server received data:" url data)
                                               (async/put! in (read-string-safe @tag-table data)))))))]
       {:new-conns conns
        :channel-hub channel-hub
        :url url
        :handler handler})))




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
    (stop-fn :timeout 100))
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
