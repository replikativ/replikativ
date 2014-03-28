(ns ^:shared geschichte.protocols)


(defprotocol IAsyncKeyValueStore
  (-get-in [this key-vec] "Returns the value stored described by key-vec or nil if the path is not resolvable.")
  (-assoc-in [this key-vec value] "Associates the key-vec to the value, any missing collections for the key-vec (nested maps and vectors) are newly created.")
  (-update-in [this key-vec up-fn] "Updates a position described by key-vec by applying up-fn and storing the result atomically. Returns a vector [old new] of the previous value and the result of applying up-fn (the newly stored value)." ))
