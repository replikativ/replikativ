(ns replikativ.crdt.simple-gset.impl
  (:require [replikativ.protocols :refer [POpBasedCRDT -downstream
                                          PExternalValues -missing-commits -commit-value
                                          PPullOp -pull]]
            [replikativ.crdt.simple-gset.core :refer [downstream]]
            #?(:clj [full.async :refer [go-try go-loop-try <?]])
            #?(:clj [clojure.core.async :as async
                    :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [full.async :refer [go-try go-loop-try <?]]
                            [full.lab :refer [go-for]])))

(extend-type replikativ.crdt.SimpleGSet
  POpBasedCRDT
  (-handshake [this] (into {} this))
  (-downstream [this op] (downstream this op))
  PExternalValues
  (-missing-commits [this store out fetched-ch op] (go-try #{})))
