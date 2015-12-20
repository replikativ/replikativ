(ns replikativ.platform
  (:require [replikativ.platform-log :refer [debug info warn error]]
            [cognitect.transit :as transit]
            [incognito.transit :refer [incognito-read-handler incognito-write-handler]]
            [goog.net.WebSocket]
            [goog.events :as events]
            [cljs.core.async :as async :refer (take! put! close! chan)])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))


(defn now []
  (js/Date.))


(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
Only supports websocket at the moment, but is supposed to dispatch on
  protocol of url. read-opts is ignored on cljs for now, use the
  platform-wide reader setup."
  [url err-ch read-handlers write-handlers]
  (let [host (.getDomain (goog.Uri. url))
        channel (goog.net.WebSocket. false)
        in (chan)
        out (chan)
        opener (chan)]
    (info "CLIENT-CONNECT" url)
    (doto channel
      (events/listen goog.net.WebSocket.EventType.MESSAGE
                     (fn [evt]
                       (try
                         (let [reader (transit/reader :json {:handlers ;; remove if uuid problem is gone
                                                             {"u" (fn [v] (cljs.core/uuid v))
                                                              "incognito" (incognito-read-handler read-handlers)}})
                               fr (js/FileReader.)]
                           (set! (.-onload fr) #(put! in
                                                      (with-meta
                                                        (transit/read
                                                         reader
                                                         (js/String. (.. % -target -result)))
                                                        {:host host})))
                           (.readAsText fr (.-message evt)))
                         (catch js/Error e
                           (error "Cannot read transit msg:" e)
                           (put! err-ch e)))))
      (events/listen goog.net.WebSocket.EventType.CLOSED
                     (fn [evt] (close! in) (.close channel) (close! opener)))
      (events/listen goog.net.WebSocket.EventType.OPENED
                     (fn [evt] (put! opener [in out]) (close! opener)))
      (events/listen goog.net.WebSocket.EventType.ERROR
                     (fn [evt]
                       (error "WebSocket error:" evt)
                       (put! err-ch evt) (close! opener)))
      (.open url))
    ((fn sender []
       (take! out
              (fn [m]
                (when m
                  (try
                    (let [i-write-handler (incognito-write-handler write-handlers)
                          writer (transit/writer
                                  :json
                                  {:handlers {"default" i-write-handler}})]
                      (.send channel (js/Blob. #js [(transit/write writer m)])))
                    (catch js/Error e
                      (error "Cannot send transit msg: " e)
                      (put! err-ch e)))

                  (sender))))))
    opener))


(comment
  (client-connect! "ws://127.0.0.1:9090"))


;; fire up repl
#_(do
    (ns dev)
    (def repl-env (reset! cemerick.austin.repls/browser-repl-env
                         (cemerick.austin/repl-env)))
    (cemerick.austin.repls/cljs-repl repl-env))
