(ns geschichte.platform
  "Platform specific io operations."
  (:use [clojure.set :as set]
        [lamina.core :refer [enqueue read-channel wait-for-result
                             receive channel siphon map* channel->lazy-seq
                             receive-all]]
        [aleph.http :refer [websocket-client start-http-server]]
        [geschichte.protocols]))


(defn uuid
  ([] (java.util.UUID/randomUUID))
  ([val] (java.util.UUID/randomUUID)))

(defn now [] (java.util.Date.))

(defn put!
  "Puts msg on channel, can be non-blocking.
   Return value undefined."
  [channel msg]
  (enqueue channel msg))

(defn take-all!
  "Take all messages on channel and apply callback.
   Return value undefined."
  [channel callback]
  (receive-all channel callback))

(defn client-connect!
  "Connect a client to address and return channel."
  [address]
   @(websocket-client {:url (str "ws://" address)}))

(defn start-server!
  "Starts a listening server applying dispatch-fn
   to all messages on each connection channel.
   Returns server."
  [this dispatch-fn]
  (start-http-server (fn [ch handshake]
                       (receive-all ch (partial dispatch-fn ch)))
                     {:port (:port @(:state this))
                      :websocket true}))
