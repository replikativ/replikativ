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

(defn- take! [message]
  (log (str "data received:" (read-string message))))

(defn client-connect! [address]
  (let [channel (js/WebSocket. (str "ws://" address))]
    (log (str "establishing websocket connection with " address))
    (doall
     (map #(aset channel (first %) (second %))
          [["onopen" (fn [] (log "channel opened"))]
           ["onclose" (fn [] (log "channel closed"))]
           ["onerror" (fn [e] (log (str "ERROR:" e)))]
           ["onmessage" (fn [m] (let [data (.-data m)] (take! data)))]]))
    (log "websocket loaded.")
    channel))

#_(def the-channel (client-connect! "localhost:9090"))
#_(put! the-channel {:type :publish
         :user "user@mail.com"
         :repo #uuid"22aa0537-6e66-43e4-bda2-2b4211e0e4ec"
         :meta {:causal-order {#uuid "befcadd8-eb77-4565-b9fe-a77bac179aec" #{#uuid "b189b9f4-0901-4a39-a1c9-a0266254fbd3"}, #uuid "b189b9f4-0901-4a39-a1c9-a0266254fbd3" #{}, :root #uuid "b189b9f4-0901-4a39-a1c9-a0266254fbd3"}, :last-update #inst "2013-12-05T23:36:43.320-00:00", :head "master", :public true, :branches {"master" #{#uuid "befcadd8-eb77-4565-b9fe-a77bac179aec"}}, :schema {:version 1, :type "http://github.com/ghubber/geschichte"}, :pull-requests {}, :id #uuid "22aa0537-6e66-43e4-bda2-2b4211e0e4ec", :description "test repo"}})
