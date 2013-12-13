(ns geschichte.client
  (:require goog.net.WebSocket
            [cljs.reader :refer [read-string]]
            [clojure.browser.repl]))

(defn log [s]
  (.log js/console (str s)))

;; fire up repl, remove later
#_(do
    (def repl-env (reset! cemerick.austin.repls/browser-repl-env
                          (cemerick.austin/repl-env)))
    (cemerick.austin.repls/cljs-repl repl-env))


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

(defn start-server! [address]
  (log (str "address: " address)))
