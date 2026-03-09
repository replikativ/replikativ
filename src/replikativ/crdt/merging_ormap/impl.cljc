(ns replikativ.crdt.merging-ormap.impl
  (:require [replikativ.protocols :refer [POpBasedCRDT -downstream
                                          PExternalValues -missing-commits
                                          PPullOp -pull]]
            [replikativ.crdt.merging-ormap.core :refer [downstream]]
            [konserve.core :as k]
            #?(:clj [superv.async :refer [go-try go-loop-try <?]])
            #?(:cljs [superv.async :refer [go-try go-loop-try <?] :include-macros true])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [clojure.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!] :include-macros true])
            [clojure.set :as set]))

(extend-type replikativ.crdt.MergingORMap
  PExternalValues
  (-missing-commits [this S store out fetched-ch op]
    (go-try S #{}))
  POpBasedCRDT
  (-handshake [this S] (into {} this))
  (-downstream [this op] (downstream this op)))



