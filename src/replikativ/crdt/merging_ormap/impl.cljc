(ns replikativ.crdt.merging-ormap.impl
  (:require [replikativ.protocols :refer [POpBasedCRDT -downstream
                                          PExternalValues -missing-commits
                                          PPullOp -pull]]
            [replikativ.crdt.merging-ormap.core :refer [downstream]]
            [konserve.core :as k]
            #?(:clj [superv.async :refer [go-try go-loop-try <?]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]])
            [clojure.set :as set])
  #?(:cljs (:require-macros [superv.async :refer [go-try go-loop-try <?]])))


(extend-type replikativ.crdt.MergingORMap
  PExternalValues
  (-missing-commits [this S store out fetched-ch op]
    (go-try S #{}))
  POpBasedCRDT
  (-handshake [this S] (into {} this))
  (-downstream [this op] (downstream this op)))



