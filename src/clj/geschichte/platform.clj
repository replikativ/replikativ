(ns geschichte.platform
  "Platform specific io operations."
  (:require [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [geschichte.platform-log :refer [debug info warn error]]
            [konserve.platform :refer [read-string-safe]]
            [hasch.benc :refer [IHashCoercion -coerce]]
            [clojure.core.async :as async
             :refer [<!! <! >! timeout chan alt! go go-loop]]
            [org.httpkit.server :refer :all]
            [http.async.client :as cli])
  (:import [java.io ByteArrayOutputStream]))


(defn throwable? [x]
  (instance? Throwable x))

(defn throw-err [e]
  (when (throwable? e) (throw e)) e)

(defmacro <? [ch]
  `(throw-err (async/<! ~ch)))

(defmacro <!? [ch]
  `(throw-err (async/<!! ~ch)))

(defmacro go<? [& body]
  `(go (try
         ~@body
         (catch Exception e#
           e#))))

(defmacro go>? [err-chan & body]
  `(go (try
         ~@body
         (catch Exception e#
           (>! ~err-chan e#)))))

(defmacro go-loop>? [err-chan bindings & body]
  `(go (try
         (loop ~bindings
           ~@body)
         (catch Exception e#
           (>! ~err-chan e#)))))

(defmacro go-loop<? [bindings & body]
  `(go<? (loop ~bindings ~@body) ))

(defn now [] (java.util.Date.))


(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
Only supports websocket at the moment, but is supposed to dispatch on
protocol of url. tag-table is an atom"
  [url tag-table]
  (let [host (.getHost (java.net.URL. (str/replace url #"^ws" "http"))) ; HACK
        http-client (cli/create-client) ;; TODO use as singleton var?
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
                                 (if (= (:topic m) :binary-fetched)
                                   (cli/send ws :byte (let [out (ByteArrayOutputStream.)]
                                                        (.write out (byte 0))
                                                        (io/copy (:value m) out)
                                                        (.toByteArray out)))
                                   (cli/send ws :text (str " " (pr-str m))))
                                 (recur (<! out))))
                             (async/put! opener [in out])
                             (async/close! opener))
                     :text (fn [ws ms]
                             (let [m (read-string-safe @tag-table ms)]
                               (debug "client received msg from:" url m)
                               (async/put! in (with-meta m {:host host}))))
                     :byte (fn [ws ^bytes ms]
                             (when-not (= (aget ms 0) (byte 32))
                               (let [blob (java.util.Arrays/copyOfRange ms 1 (count ms))
                                     m {:topic :binary-fetched
                                        :value blob}]
                                 (debug "client received binary blob from:"
                                        url (take 5 (map byte blob)))
                                 (async/put! in (with-meta m {:host host})))))
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
                           (if (@channel-hub channel)
                             (do
                               (debug "server sending msg:" url (pr-str m))
                               (if (= (:topic m) :binary-fetched)
                                 (send! channel ^bytes (let [out (ByteArrayOutputStream.)]
                                                         (.write out (byte 0))
                                                         (io/copy (:value m) out)
                                                         (.toByteArray out)))
                                 (send! channel (str " " (pr-str m)))))
                             (debug "dropping msg because of closed channel: " url (pr-str m)))
                           (recur (<! out))))
                       (on-close channel (fn [status]
                                           (info "channel closed:" status)
                                           (swap! channel-hub dissoc channel)
                                           (async/close! in)))
                       (on-receive channel (fn [data]
                                             (if (string? data)
                                               (do
                                                 (debug "server received data:" url data)
                                                 (async/put! in
                                                             (with-meta
                                                               (read-string-safe @tag-table data)
                                                               {:host (:remote-addr request)})))
                                               (let [blob (java.util.Arrays/copyOfRange data 1 (count data))]
                                                 (debug "received blob data:" url (take 5 (map byte blob)))
                                                 (async/put! (with-meta {:topic :binary-fetched
                                                                         :value blob}
                                                               {:host (:remote-addr request)})))))))))]
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
  (<!! (timeout 200))
  (when-let [hub (get-in @peer [:volatile :channel-hub])]
    (reset! hub {}))
  (when-let [in (-> @peer :volatile :chans first)]
    (async/close! in)))
