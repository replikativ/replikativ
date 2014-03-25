(ns geschichte.store
  "Address globally aggregated immutable key-value store(s)."
  (:require [geschichte.protocols :refer [IAsyncKeyValueStore]]
            #+clj [clojure.core.async :refer [go]])
  #+cljs (:require-macros [cljs.core.async.macros :refer [go]]))

(defrecord MemAsyncKeyValueStore [state]
  IAsyncKeyValueStore
  (-get-in [this key-vec] (go (get-in @state key-vec)))
  (-exists? [this key-vec] (go (not (not (get-in @state key-vec)))))
  (-assoc-in [this key-vec value] (go (swap! state assoc-in key-vec value)
                                      nil))
  (-update-in [this key-vec up-fn] (go (get-in (swap! state update-in key-vec up-fn)
                                               key-vec))))

(defn new-mem-store []
  (MemAsyncKeyValueStore. (atom {})))
