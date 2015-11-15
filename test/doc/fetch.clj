(ns doc.fetch
  (:require [replikativ.p2p.fetch :refer :all]
            [replikativ.protocols :refer [-missing-commits]]
            [replikativ.environ :refer [store-blob-trans-id]]
            [replikativ.crdt.cdvcs.impl :refer :all]
            [konserve.memory :refer [new-mem-store]]
            [clojure.core.async :refer [chan put!]]
            [full.async :refer [<?? <? go-loop-try go-try]]
            [midje.sweet :refer :all]))



(fact
 (let [out (chan)
       fetched-ch (chan)
       binary-fetched-ch (chan)
       store (<?? (new-mem-store))]
   (go-loop-try [o (<? out)]
                (println "OUT" o)
                (recur (<? out)))
   (put! fetched-ch {:type :fetch/edn-ack
                     :values {1 {:transactions [[11 12]]
                                 :crdt-refs #{#replikativ.crdt.CDVCS{:commit-graph {2 []}
                                                                          :branches {"master" #{2}}}}}}})
   (put! fetched-ch {:type :fetch/edn-ack
                     :values {2 {:transactions [[21 22]]
                                 :crdt-refs #{#replikativ.crdt.CDVCS{:commit-graph {3 []}
                                                                          :branches {"master" #{2}}}}}}})
   (put! fetched-ch {:type :fetch/edn-ack
                     :values {3 {:transactions [[store-blob-trans-id #uuid "3dfeb3c9-e6cf-53b2-97df-bb4e77a2dda8"]]
                                 :crdt-refs #{#replikativ.crdt.CDVCS{:commit-graph {1 []}
                                                                          :branches {"master" #{2}}}}}}})
   (let [pub {:crdt :repo
              :op {:method :new-state
                   :commit-graph {1 []}
                   :branches {"master" #{1}}}}
         cvs (<?? (fetch-commit-values! out fetched-ch store ["a" 1] pub 42))
         txs (mapcat :transactions (vals cvs))]
     (put! fetched-ch {:type :fetch/edn-ack
                       :values {11 11
                                12 12
                                21 21
                                22 22
                                31 31
                                32 32}})
     (<?? (fetch-and-store-txs-values! out fetched-ch store txs 42))
     (put! binary-fetched-ch {:value 1123})
     (<?? (fetch-and-store-txs-blobs! out binary-fetched-ch store txs 42))
     (<?? (store-commits! store cvs)) => [])))
