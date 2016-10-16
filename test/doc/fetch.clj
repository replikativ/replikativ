(ns doc.fetch
  (:require [replikativ.p2p.fetch :refer :all]
            [replikativ.protocols :refer [-missing-commits]]
            [replikativ.environ :refer [store-blob-trans-id]]
            [replikativ.crdt.cdvcs.impl :refer :all]
            [konserve.memory :refer [new-mem-store]]
            [clojure.core.async :refer [chan put!]]
            [superv.async :refer [<?? <? go-loop-try go-try S]]
            [midje.sweet :refer :all]))



(fact
 (let [out (chan)
       fetched-ch (chan)
       binary-fetched-ch (chan)
       store (<?? S (new-mem-store))]
   (go-loop-try S [o (<? S out)]
                (recur (<? S out)))
   (put! fetched-ch {:type :fetch/edn-ack
                     :values {1 {:transactions [[11 12]]}
                              2 {:transactions [[21 22]]}
                              3 {:transactions [[store-blob-trans-id
                                                 #uuid "3dfeb3c9-e6cf-53b2-97df-bb4e77a2dda8"]]
                                 }}
                     :final true})
   (let [pub {:crdt :cdvcs
              :op {:method :new-state
                   :commit-graph {1 []
                                  2 [1]
                                  3 [2]}
                   :heads #{3}}}
         cvs (<?? S (fetch-commit-values! S out fetched-ch store (<?? S (new-mem-store)) ["a" 1] pub 42
                                          #{1 2 3}))
         txs (mapcat :transactions (vals cvs))]
     cvs => {1 {:transactions [[11 12]]},
             2 {:transactions [[21 22]]},
             3 {:transactions [[#uuid "3b0197ff-84da-57ca-adb8-94d2428c6227"
                                #uuid "3dfeb3c9-e6cf-53b2-97df-bb4e77a2dda8"]]}}
     (put! fetched-ch {:type :fetch/edn-ack
                       :values {11 11
                                12 12
                                21 21
                                22 22}
                       :final true})
     (<?? S (fetch-and-store-txs-values! S out fetched-ch store txs 42))
     (put! binary-fetched-ch {:value 1123})
     (<?? S (fetch-and-store-txs-blobs! S out binary-fetched-ch store txs 42))
     (<?? S (store-commits! S store cvs)) => nil
     @(:state store) =>
     {1 {:transactions [[11 12]]},
      2 {:transactions [[21 22]]},
      3 {:transactions [[#uuid "3b0197ff-84da-57ca-adb8-94d2428c6227"
                         #uuid "3dfeb3c9-e6cf-53b2-97df-bb4e77a2dda8"]]},
      11 11,
      12 12,
      21 21,
      22 22,
      #uuid "3dfeb3c9-e6cf-53b2-97df-bb4e77a2dda8" {:input-stream 1123, :size :unknown}})))
