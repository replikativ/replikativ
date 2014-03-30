(ns geschichte.platform
  (:require [goog.net.WebSocket]
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


(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
Only supports websocket at the moment, but is supposed to dispatch on protocol of url."
  [url]
  (let [channel (goog.net.WebSocket.)
        in (chan)
        out (chan)
        opener (chan)]
    (doto channel
      (events/listen goog.net.WebSocket.EventType.MESSAGE
              (fn [evt]
                (log "receiving: " (-> evt .-message))
                (put! in (-> evt .-message read-string))))
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
