(ns geschichte.platform
  (:require [geschichte.protocols :refer [IAsyncKeyValueStore -get-in -assoc-in -update-in]]
   [goog.net.WebSocket]
            [goog.events :as events]
            [cljs.reader :refer [read-string]]
            [cljs.core.async :as async :refer (take! put! close! chan)])
  (:require-macros [cljs.core.async.macros :refer [<! >! go go-loop]]))


(defn log [& s]
  (.log js/console (apply str s)))


;; taken from https://github.com/whodidthis/cljs-uuid-utils/blob/master/src/cljs_uuid_utils.cljs
;; TODO check: might not have enough randomness (?)
(defn make-random-uuid
  "(make-random-uuid) => new-uuid
   Arguments and Values:
   new-uuid --- new type 4 (pseudo randomly generated) cljs.core/UUID instance.
   Description:
   Returns pseudo randomly generated UUID,
   like: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx as per http://www.ietf.org/rfc/rfc4122.txt.
   Examples:
   (make-random-uuid) => #uuid \"305e764d-b451-47ae-a90d-5db782ac1f2e\"
   (type (make-random-uuid)) => cljs.core/UUID"
  []
  (letfn [(f [] (.toString (rand-int 16) 16))
          (g [] (.toString (bit-or 0x8 (bit-and 0x3 (rand-int 15))) 16))]
    (UUID. (.toString (.append (goog.string.StringBuffer.)
       (f) (f) (f) (f) (f) (f) (f) (f) "-" (f) (f) (f) (f)
       "-4" (f) (f) (f) "-" (g) (f) (f) (f) "-"
       (f) (f) (f) (f) (f) (f) (f) (f) (f) (f) (f) (f))))))


(defn uuid
  ([] (make-random-uuid))
  ([val] :TODO-UUID))


(defn now []
  (js/Date.))


(defn client-connect! [ip port in out]
  (let [channel (goog.net.WebSocket.)
        opener (chan)]
    (doto channel
      (events/listen goog.net.WebSocket.EventType.MESSAGE
              (fn [evt]
                (log "receiving: " (-> evt .-message))
                (put! in (-> evt .-message read-string))))
      (events/listen goog.net.WebSocket.EventType.OPENED
              (fn [evt] (close! opener)))
      (events/listen goog.net.WebSocket.EventType.ERROR
              (fn [evt] (log "ERROR:" evt) (close! opener)))
      (.open (str "ws://" ip ":" port)))
    ((fn sender [] (take! out
                         (fn [m]
                           (log "sending: " m)
                           (.send channel (str m))
                           (sender)))))
    opener))

(defn start-server! [ip port]
  {throw "No server functionality in js yet. Node port welcome."})



(defrecord IndexedDBKeyValueStore [db store-name]
  IAsyncKeyValueStore
  (-get-in [this key-vec]
    (let [[fkey & rkey] key-vec
          res (chan)
          tx (.transaction db #js [store-name])
          obj-store (.objectStore tx store-name)
          req (.get obj-store (str fkey))]
      (set! (.-onerror req)
            (fn [e] (log e) (close! res) (throw e)))
      (set! (.-onsuccess req)
            (fn [e] (when-let [r (.-result req)]
                     (put! res(-> r
                                  .-edn_value
                                  read-string
                                  (get-in rkey))))
              ;; returns nil
              (close! res)))
      res))
  (-assoc-in [this key-vec value]
    (go (let [[fkey & rkey] key-vec
              res (chan)
              tx (.transaction db #js [store-name] "readwrite")
              obj-store (.objectStore tx store-name)
              req (.get obj-store (str fkey))]
          (set! (.-onerror req)
                (fn error-handler [e] (log e) (close! res) (throw e)))
          (set! (.-onsuccess req)
                (fn read-old [e]
                  (let [old (when-let [r (.-result req)]
                              (-> r .-edn_value read-string))
                        up-req (.put obj-store
                                     (clj->js {:key (str fkey)
                                               :edn_value
                                               (str (if-not (empty? rkey)
                                                      (assoc-in old rkey value)
                                                      value))}))]
                    (set! (.-oncomplete up-req)
                          (fn [e] (close! res))))))
          (<! res))))
  (-update-in [this key-vec up-fn]
    (go (let [[fkey & rkey] key-vec
              res (chan)
              tx (.transaction db #js [store-name] "readwrite")
              obj-store (.objectStore tx store-name)
              req (.get obj-store (str fkey))]
          (set! (.-onerror req)
                (fn error-handler [e] (log e) (close! res) (throw e)))
          (set! (.-onsuccess req)
                (fn read-old [e]
                  (let [old (when-let [r (.-result req)]
                              (-> r .-edn_value read-string))
                        up-req (.put obj-store
                                     (clj->js {:key (str fkey)
                                               :edn_value
                                               (str (if-not (empty? rkey)
                                                      (update-in old rkey up-fn)
                                                      (up-fn value)))}))]
                    (set! (.-oncomplete up-req)
                          (fn [e] (close! res))))))
          (<! res)))))


(defn new-indexeddb-store [name]
  (let [res (chan)
        req (.open js/window.indexedDB name 1)]
    (set! (.-onerror req)
          (fn error-handler [e]
            (log (js->clj (.-target e)))
            (close! res)
            (throw e)))
    (set! (.-onsuccess req)
          (fn success-handler [e]
            (log "db-opened:" (.-result req))
            (put! res (IndexedDBKeyValueStore. (.-result req) "geschichte"))))
    (set! (.-onupgradeneeded req)
          (fn upgrade-handler [e]
            (let [db (-> e .-target .-result)]
              (.createObjectStore db name #js {:keyPath "key"}))
            (log "db upgraded from version: " (.-oldVersion e))))
    res))


(comment
  (client-connect! "127.0.0.1" 9090 (chan) (chan))

  (go (def my-db (<! (new-indexeddb-store "geschichte"))))

  (def my-kv (IndexedDBKeyValueStore. my-db "geschichte"))

  (go (println "get:" (<! (-get-in my-kv ["test"]))))

  (go (println (<! (-assoc-in my-kv ["test2"] {:a 1 :b 4.2}))))

  (go (println (<! (-assoc-in my-kv ["test" :a] 43))))

  (go (println (<! (-update-in my-kv ["test" :a] inc))))
  )
