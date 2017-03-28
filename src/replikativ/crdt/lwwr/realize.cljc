(ns replikativ.crdt.lwwr.realize
  (:require [clojure.set :as set]
            [konserve.core :as k]
            [konserve.memory :refer [new-mem-store]]
            [replikativ.environ :refer [store-blob-trans-id store-blob-trans-value store-blob-trans]]
            [replikativ.protocols :refer [-downstream -handshake]]
            [replikativ.realize :as real]
            [replikativ.crdt.materialize :refer [ensure-crdt]]
            [replikativ.crdt.lwwr.core :as core]
            [replikativ.crdt.lwwr.stage :as ors]
            #?(:clj [kabel.platform-log :refer [debug]])
            #?(:clj [superv.async :refer [<? go-try go-loop-super >?]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! put! sub unsub pub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [superv.async :refer [<? go-try go-loop-super >?]]
                            [kabel.platform-log :refer [debug]])))

(defn stream-into-atom! [stage [u id] val-atom]
  (let [{{[p _] :chans
          :keys [store err-ch]
          S :supervisor} :volatile} @stage
        pub-ch (chan 10000)
        applied-ch (chan 10000)]
    (async/sub p :pub/downstream pub-ch)
    ;; stage is set up, now lets kick the update loop
    (go-try S
     (let [lwwr (<? S (ensure-crdt S store (<? S (new-mem-store)) [u id] :lwwr))]
       (when lwwr
         (put! pub-ch {:downstream {:method :handshake
                                    :crdt :lwwr
                                    :op (-handshake lwwr S)}
                       :user u :crdt-id id}))
       (go-loop-super S [{{{register :register :as op} :op
                           method :method}
                          :downstream :as pub
                          :keys [user crdt-id]} (<? S pub-ch)
                         lwwr lwwr]
         (when pub
           (debug {:event :streaming-lwwr :id (:id pub)})
           (cond (not (and (= user u)
                           (= crdt-id id)))
                 (recur (<? S pub-ch) lwwr)

                 :else
                 (let [lwwr (-downstream lwwr op)]
                   (reset! val-atom (get-in lwwr [:register]))
                   (>? S applied-ch pub)
                   (recur (<? S pub-ch)
                          lwwr)))))))
    {:close-ch pub-ch
     :applied-ch applied-ch}))
