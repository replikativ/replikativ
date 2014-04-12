(ns geschichte.platform
  (:require [goog.net.WebSocket]
            [goog.events :as events]
            [cljs.reader :refer [read-string]]
            [cljs.core.async :as async :refer (take! put! close! chan)])
  (:require-macros [cljs.core.async.macros :refer [<! >! go go-loop]]))


(defn log [& s]
  (.log js/console (apply str s)))


(defn now []
  (js/Date.))


(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
Only supports websocket at the moment, but is supposed to dispatch on protocol of url."
  [url]
  (let [channel (goog.net.WebSocket. false)
        in (chan)
        out (chan)
        opener (chan)]
    (log "CLIENT-CONNECT")
    (doto channel
      (events/listen goog.net.WebSocket.EventType.MESSAGE
              (fn [evt]
                (log "receiving: " (-> evt .-message))
                (put! in (-> evt .-message read-string))))
      (events/listen goog.net.WebSocket.EventType.CLOSED
              (fn [evt] (close! in) (.close channel) (close! opener)))
      (events/listen goog.net.WebSocket.EventType.OPENED
              (fn [evt] (put! opener [in out]) (close! opener)))
      (events/listen goog.net.WebSocket.EventType.ERROR
              (fn [evt] (log "ERROR:" evt) (close! opener)))
      (.open url))
    ((fn sender [] (take! out
                         (fn [m]
                           (log "sending: " m)
                           (.send channel (pr-str m))
                           (sender)))))
    opener))


(comment
  (client-connect! "ws://127.0.0.1:9090"))


;; fire up repl
#_(do
    (ns dev)
    (def repl-env (reset! cemerick.austin.repls/browser-repl-env
                         (cemerick.austin/repl-env)))
    (cemerick.austin.repls/cljs-repl repl-env))
