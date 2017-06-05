(ns replikativ.crdt.ormap.impl
  (:require [replikativ.protocols :refer [POpBasedCRDT -downstream
                                          PExternalValues -missing-commits
                                          PPullOp -pull]]
            [replikativ.crdt.ormap.core :refer [downstream]]
            [konserve.core :as k]
            #?(:clj [superv.async :refer [go-try go-loop-try <?]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]])
            [clojure.set :as set])
  #?(:cljs (:require-macros [superv.async :refer [go-try go-loop-try <?]])))

(defn all-commits [ormap]
  (for [[_ uid->cid] (concat (:adds ormap) (:removals ormap))
        [_ cid] uid->cid]
    cid))


;; similar to CDVCS
(defn missing-commits [S store ormap op]
  (let [missing (all-commits op)]
    (go-loop-try S [not-in-store #{}
                    [f & r] (seq missing)]
                 (if f
                   (recur (if (not (<? S (k/exists? store f)))
                            (conj not-in-store f) not-in-store) r)
                   not-in-store))))

(extend-type replikativ.crdt.ORMap
  PExternalValues
  (-missing-commits [this S store out fetched-ch op]
    (missing-commits S store this op))
  POpBasedCRDT
  (-handshake [this S] (into {} this))
  (-downstream [this op] (downstream this op)))


(comment

  (require '[konserve.memory :refer [new-mem-store]]
           '[full.async :refer :all]
           '[clojure.core.async :refer [chan]]
           '[replikativ.crdt.simple-ormap.core :refer [new-simple-ormap or-assoc or-dissoc]])

  (def ormap (new-simple-ormap))

  (def store (<?? (new-mem-store)))

  (def foo
    (-downstream
           (-downstream (:state ormap)
                        (get-in (-> ormap (or-assoc 12 [['+ 42]] "john")) [:downstream :op]))
           (get-in (-> ormap (or-assoc 12 [['+ 42]] "john")
                       (or-dissoc 12 [['- 42]] "john")) [:downstream :op])))

  (<??(-missing-commits #_foo (:state ormap) store (chan) (chan)
                        (get-in (-> ormap (or-assoc 12 [['+ 42]] "john")) [:downstream :op])))
  )
