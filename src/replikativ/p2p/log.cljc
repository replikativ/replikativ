(ns replikativ.p2p.log
  "Logging middleware for replikativ."
  (:require [replikativ.platform-log :refer [debug info warn error]]
            [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                      :refer [<! >! chan go put! go-loop close!]]
               :cljs [cljs.core.async :as async :refer [<! >! chan put! close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])))


(defn logger
  "Appends messages of in and out to log-atom under [topic :in/:out] to a vector."
  [log-atom topic [in out]]
  (let [new-in (chan)
        new-out (chan)]
    (go-loop [i (<! in)]
      (if i
        (do
          (swap! log-atom update-in [topic :in] (fnil conj []) i)
          (>! new-in i)
          (recur (<! in)))
        (close! new-in)))
    (go-loop [o (<! new-out)]
      (if o
        (do
          (swap! log-atom update-in [topic :out] (fnil conj []) o)
          (>! out o)
          (recur (<! new-out)))
        (close! new-out)))
    [new-in new-out]))
