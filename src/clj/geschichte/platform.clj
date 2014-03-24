(ns geschichte.platform
  "Platform specific io operations."
  (:use [clojure.set :as set]
        [geschichte.protocols :refer [IByteCoercion -coerce]])
  (:require [geschichte.debug-channels :as debug]
            [hasch.core :refer [edn-hash]]
            [hasch.platform :refer [uuid5]]
            [clojure.core.async :as async
             :refer [<! >! timeout chan alt! go go-loop]]
            [org.httpkit.server :refer :all]
            [http.async.client :as cli])
  (:import java.security.MessageDigest
           java.nio.ByteBuffer))

(defn uuid3 []
  (java.util.UUID/randomUUID))

(def log println)

(defn uuid
  ([] (uuid3))
  ([val] (-> val
             edn-hash
             uuid5)))

(defn now [] (java.util.Date.))


(defn client-connect!
  "Connect a client to address and return channel."
  [ip port in out]
  (let [http-client (cli/create-client)] ;; TODO use as singleton var?
    (try
      (cli/websocket http-client (str "ws://" ip ":" port)
                     :open (fn [ws] (println "ws-opened" ws)
                             (go-loop [m (<! out)]
                                      (when m
                                        (println "client sending msg to:" ip port m)
                                        (cli/send ws :text (str m))
                                        (recur (<! out)))))
                     :text (fn [ws ms]
                             (let [m (read-string ms)]
                               (println "client received msg from:" ip port m)
                               (async/put! in m)))
                     :close (fn [ws code reason]
                              (println "closing" ws code reason)
                              (async/close! in)
                              (async/close! out))
                     :error (fn [ws err] (println "ws-error" err)
                              (.printStackTrace err)))
      (catch Exception e
        (println "client-connect error:" e)))))


(defn start-server!
  [ip port]
  (let [channel-hub (atom {})
        conns (chan)
        log (atom {})
        handler (fn [request]
                  (let [in (debug/chan log [(str ip ":" port) :in])
                        out (debug/chan log [(str ip ":" port) :out])]
                    (async/put! conns [in out])
                    (with-channel request channel
                      (swap! channel-hub assoc channel request)
                      (go-loop [m (<! out)]
                               (when m
                                 (println "server sending msg:" ip port m)
                                 (send! channel (str m))
                                 (recur (<! out))))
                      (on-close channel (fn [status]
                                          (println "channel closed:" status)
                                          (swap! channel-hub dissoc channel)
                                          (async/close! in)
                                          (async/close! out)))
                      (on-receive channel (fn [data]
                                            (println "server received data:" ip port data)
                                            (async/put! in (read-string data)))))))]
    {:new-conns conns
     :channel-hub channel-hub
     :log log
     :server (run-server handler {:port port})}))


#_(def server (start-server2! "127.0.0.1" 19090))
#_((:server server))
