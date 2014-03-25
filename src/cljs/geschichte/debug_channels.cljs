(ns geschichte.debug-channels
  (:require [cljs.core.async :as async]))

(defn chan
  "Debugging disabled in ClojureScript."
  ([log path] (async/chan))
  ([log path buf-or-n]
     (async/chan buf-or-n)))
