(ns replikativ.crdt.simple-gset.impl
  (:require [replikativ.protocols :refer [POpBasedCRDT -downstream
                                          PExternalValues -missing-commits
                                          PPullOp -pull]]
            [replikativ.crdt.simple-gset.core :refer [downstream]]
            #?(:clj [superv.async :refer [go-try go-loop-try <?]])
            #?(:cljs [superv.async :refer [go-try go-loop-try <?] :include-macros true])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [clojure.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!] :include-macros true])))

(defn- missing-commits [S store gset op]
  (go-try S #{}))

(extend-type replikativ.crdt.SimpleGSet
  POpBasedCRDT
  (-handshake [this S] (into {} this))
  (-downstream [this op] (downstream this op))
  PExternalValues
  (-missing-commits [this S store out fetched-ch op]
    (missing-commits S store this op)))
