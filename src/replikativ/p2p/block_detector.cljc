(ns replikativ.p2p.block-detector
  "Block detection middleware for replikativ."
  (:require [replikativ.platform-log :refer [debug info warn error]]
            [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [<! >! >!! <!! timeout chan alt! go put!
                             filter< map< go-loop pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                     :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])))



(defn block-detector [type [in out]]
  "Warns when either in or out is blocked for longer than 5 seconds and retries."
  (let [new-in (chan)
        new-out (chan)]
    (go-loop [i (<! in)]
      (if i
        (alt! [[new-in i]]
              (recur (<! in))

              (timeout 5000)
              (do (warn type "Input channel blocked for msg: " i)
                  (recur i)))
        (close! new-in)))
    (go-loop [o (<! new-out)]
      (if o
        (alt! [[out o]]
              (recur (<! new-out))

              (timeout 5000)
              (do (warn type "Output channel blocked for msg: " o)
                  (recur o)))
        (close! new-out)))
    [new-in new-out]))
