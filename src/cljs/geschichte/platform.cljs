(ns geschichte.platform
  (:require goog.net.WebSocket
            [cljs.reader :refer [read-string]]
            [clojure.browser.repl]))

(defn log [s]
  (.log js/console (str s)))

(defn uuid
  ([] :TODO-UUID)
  ([val] :TODO-UUID))

(defn now []
  (js/Date.))

(def read-string cljs.reader/read-string)

;; --- WEBSOCKET CONNECTION ---

(defn put! [channel data]
  (.send channel (str data)))

(defn- take-all! [channel func]
  (aset channel "onmessage" (fn [m] (func (.-data m)))))

(defn client-connect! [address]
  (let [channel (js/WebSocket. (str "ws://" address))]
    (doall
     (map #(aset channel (first %) (second %))
          [["onopen" (fn [] (log "channel opened"))]
           ["onerror" (fn [e] (log (str "ERROR:" e)))]]))
    channel))

(defn start-server! [peer dispatch-fn]
  (log (str "No server functionality in js. ")))
