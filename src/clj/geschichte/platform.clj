(ns geschichte.platform
  "Platform specific io operations."
  (:use [clojure.set :as set]
        [geschichte.protocols :refer [IAsyncKeyValueStore -get-in -assoc-in -update-in]])
  (:require [geschichte.debug-channels :as debug]
            [hasch.core :refer [edn-hash]]
            [hasch.platform :refer [uuid5]]
            [clojure.core.async :as async
             :refer [<! >! timeout chan alt! go go-loop]]
            [com.ashafa.clutch :refer [couch create!] :as cl]
            [org.httpkit.server :refer :all]
            [http.async.client :as cli]))


(defn- uuid4 []
  (java.util.UUID/randomUUID))

(def log println)

(defn uuid
  ([] (uuid4))
  ([val] (-> val
             edn-hash
             uuid5)))

(defn now [] (java.util.Date.))


(defn client-connect!
  "Connect a client to address and return channel."
  [ip port in out]
  (let [http-client (cli/create-client)
        opener (chan)] ;; TODO use as singleton var?
    (try
      (cli/websocket http-client (str "ws://" ip ":" port)
                     :open (fn [ws]
                             (log "ws-opened" ws)
                             (go-loop [m (<! out)]
                                      (when m
                                        (log "client sending msg to:" ip port m)
                                        (cli/send ws :text (str m))
                                        (recur (<! out))))
                             (async/close! opener))
                     :text (fn [ws ms]
                             (let [m (read-string ms)]
                               (log "client received msg from:" ip port m)
                               (async/put! in m)))
                     :close (fn [ws code reason]
                              (log "closing" ws code reason)
                              (async/close! in)
                              (async/close! out))
                     :error (fn [ws err] (log "ws-error" err)
                              (.printStackTrace err)
                              (async/close! opener)))
      (catch Exception e
        (log "client-connect error:" e)))
    opener))


(defn start-server!
  [ip port]
  (let [channel-hub (atom {})
        conns (chan)
        ch-log (atom {})
        handler (fn [request]
                  (let [in (debug/chan ch-log [(str ip ":" port) :in])
                        out (debug/chan ch-log [(str ip ":" port) :out])]
                    (async/put! conns [in out])
                    (with-channel request channel
                      (swap! channel-hub assoc channel request)
                      (go-loop [m (<! out)]
                               (when m
                                 (log "server sending msg:" ip port m)
                                 (send! channel (str m))
                                 (recur (<! out))))
                      (on-close channel (fn [status]
                                          (log "channel closed:" status)
                                          (swap! channel-hub dissoc channel)
                                          (async/close! in)
                                          (async/close! out)))
                      (on-receive channel (fn [data]
                                            (log "server received data:" ip port data)
                                            (async/put! in (read-string data)))))))]
    {:new-conns conns
     :channel-hub channel-hub
     :log log
     :server (run-server handler {:port port})}))



(defrecord CouchKeyValueStore [db]
  IAsyncKeyValueStore
  (-get-in [this key-vec]
    (let [[fkey & rkey] key-vec]
      (go (get-in (->> fkey
                       (cl/get-document db)
                       :edn-value
                       read-string)
                  rkey) )))
  ;; TODO, cleanup an unify with update-in
  (-assoc-in [this key-vec value] ;; TODO add timeouts
    (go (let [[fkey & rkey] key-vec
              doc (cl/get-document db fkey)]
          (if-not doc
            (cl/put-document db {:_id (str fkey)
                                 :edn-value (str (if-not (empty? rkey)
                                                   (assoc-in nil rkey value)
                                                   value))})
            ((fn trans [doc]
               (try (cl/update-document db
                                        doc
                                        (fn [{v :edn-value :as old}]
                                          (assoc old
                                            :edn-value (str (if-not (empty? rkey)
                                                              (assoc-in (read-string v) rkey value)
                                                              value)))))
                    (catch clojure.lang.ExceptionInfo e
                      (log e)
                      (.printStackTrace e)
                      (trans (cl/get-document db fkey))))) doc))
          nil)))
  (-update-in [this key-vec up-fn]
    (go (let [[fkey & rkey] key-vec
              doc (cl/get-document db fkey)]
          (if-not doc
            [nil (-> (cl/put-document db {:_id (str fkey)
                                          :edn-value (str (if-not (empty? rkey)
                                                            (update-in nil rkey up-fn)
                                                            (up-fn nil)))})
                     :edn-value
                     read-string
                     (get-in rkey))]
            ((fn trans [doc]
               (let [old (-> doc :edn-value read-string (get-in rkey))
                     new* (try (cl/update-document db
                                                   doc
                                                   (fn [{v :edn-value :as old}]
                                                     (assoc old
                                                       :edn-value (str (if-not (empty? rkey)
                                                                         (update-in (read-string v) rkey up-fn)
                                                                         (up-fn (read-string v)))))))
                               (catch clojure.lang.ExceptionInfo e
                                 (log e)
                                 (.printStackTrace e)
                                 (trans (cl/get-document db fkey))))
                     new (-> new* :edn-value read-string (get-in rkey))]
                 [old new])) doc))))))

(defn new-couch-store [name]
  (let [db (couch name)]
    (create! db)
    (CouchKeyValueStore. db)))


(comment

  (def couch-store (new-couch-store "geschichte"))

  (go (println (<! (-get-in couch-store ["hans"]))))
  (get-in (:db couch-store) ["john"] )

  (go (println (<! (-assoc-in couch-store ["peter" 12383] [3 1 4 5]))))


  (go (println (<! (-update-in couch-store ["hans" :a] (fnil inc 0)))))



  (def server (start-server2! "127.0.0.1" 19090))
  ((:server server)))
