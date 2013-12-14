(ns geschichte.platform
  (:require goog.net.WebSocket
            [cljs.reader :as reader]
            [clojure.browser.repl]))

(defn log [& s]
  (.log js/console (apply str s)))

(defn uuid
  ([] :TODO-UUID)
  ([val] :TODO-UUID))

(defn now []
  (js/Date.))

(def read-string reader/read-string)

;; --- WEBSOCKET CONNECTION ---

(defn put! [channel data]
  (.send channel (str data)))

(defn- take-all! [channel func]
  (aset channel "onmessage" (fn [m] (func (.-data m)))))

(defn client-connect! [address]
  (let [channel (goog.net.WebSocket.)]
    (doto channel
      (aset "onopen" #(log "channel opened"))
      (aset "onerror" #(log "ERROR:" %))
      (.open (str "ws://" address)))))

(defn start-server! [peer dispatch-fn]
  (log "No server functionality in js."))
