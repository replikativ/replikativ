(ns ^:shared geschichte.protocols)

(defprotocol IByteCoercion
  (-coerce [this hash-fn]))

(defprotocol IAsyncKeyValueStore
  (-get-in [this key-vec])
  (-exists? [this key-vec])
  (-assoc-in [this key-vec value])
  (-update-in [this key-vec up-fn]))
