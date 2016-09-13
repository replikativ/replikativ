(ns replikativ.crdt.ormap.impl
  (:require [replikativ.protocols :refer [POpBasedCRDT -downstream
                                          PExternalValues -missing-commits -commit-value
                                          PPullOp -pull]]
            [replikativ.crdt.ormap.core :refer [downstream]]
            [konserve.core :as k]
            #?(:clj [full.async :refer [go-try go-loop-try <?]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]])
            [clojure.set :as set])
  #?(:cljs (:require-macros [full.async :refer [go-try go-loop-try <?]]
                            [full.lab :refer [go-for]])))

(defn- all-commits
  [ormap]
  (set (for [[_ uid->cid] (concat (:adds ormap) (:removals ormap))
             [_ cid] uid->cid]
        cid)))

(comment
  (all-commits
   #replikativ.crdt.ORMap{:adds {12 {#uuid "18bf18b5-bfb9-4e99-9e7b-e1f5214c4e82" #uuid "27c47346-c92f-5662-b772-535239f5fb28"}
                                       44 {#uuid "18bf18b5-bfb9-4e99-9e7b-e1f5214c4e82" #uuid "27c47346-c92f-5662-b772-535239f5fb28"}}, :removals {12 {#uuid "02b706f4-a52c-4501-b598-fe8f362a0d04" #uuid "122398db-f04a-553e-b215-a0a359070fcc"}}}))


;; similar to CDVCS
(defn missing-commits [store ormap op]
  (let [missing (set/difference (all-commits op)
                                (all-commits ormap))]
    (go-loop-try [not-in-store #{}
                  [f & r] (seq missing)]
                 (if f
                   (recur (if (not (<? (k/exists? store f)))
                            (conj not-in-store f) not-in-store) r)
                            not-in-store))))

(extend-type replikativ.crdt.ORMap
  PExternalValues
  (-missing-commits [this store out fetched-ch op]
    (missing-commits store this op))
  (-commit-value [this commit]
    (select-keys commit #{:transactions}))
  POpBasedCRDT
  (-handshake [this] (into {} this))
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
