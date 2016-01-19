(ns doc.hooks
  (:require [replikativ.peer :refer [client-peer server-peer]]
            [replikativ.environ :refer [*date-fn*]]
            [replikativ.protocols :refer [-downstream]]
            [replikativ.crdt.materialize :refer [ensure-crdt]]
            [kabel.platform :refer [create-http-kit-handler! start stop]]
            [replikativ.platform-log :refer [warn]]
            [replikativ.stage :refer [create-stage! connect! subscribe-crdts!]]
            [replikativ.crdt.cdvcs.stage :as s]
            [replikativ.crdt.cdvcs.impl :refer [pull-cdvcs!]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.p2p.hooks :refer [hook]]
            [kabel.middleware.log :refer [logger]]
            [full.async :refer [<? <?? go-try go-loop-try]]
            [konserve.memory :refer [new-mem-store]]
            [konserve.filestore :refer [new-fs-store]]
            [midje.sweet :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.core.async :as async
             :refer [>! >!! timeout chan alt! put! pub sub unsub close! go-loop]])
  (:import [replikativ.crdt CDVCS]))

[[:chapter {:tag "hooks" :title "Pull hook middleware of replikativ"}]]

"This chapter describes the hooking middleware of replikativ. You can use these hooks to automatically pull or merge from other CDVCS on peer level, e.g. to pull new user data on server side or to pull server updates to a central CDVCS into a user CDVCS on client-side."

"You can use regular expression wildcards on usernames to pull from, see example:"

 ;; hooking map
(def hooks (atom {[#".*"
                   #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                  [["mail:a@mail.com"
                    #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]]}))

 ;; setup two peers with stores and a single commit in mail:a@mail.com and mail:b@mail.com
(def store-a
  (<?? (new-mem-store (atom {["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                             {:description "some CDVCS.",
                              :public false,
                              :crdt :cdvcs
                              :state #replikativ.crdt.CDVCS{:commit-graph {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" []},
                                                            :heads #{#uuid "06118e59-303f-51ed-8595-64a2119bf30d"},}},
                             ["mail:a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                             {:description "some CDVCS.",
                              :public false,
                              :crdt :cdvcs
                              :state #replikativ.crdt.CDVCS{:commit-graph {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" []},
                                                            :heads #{#uuid "06118e59-303f-51ed-8595-64a2119bf30d"}}},
                             #uuid "06118e59-303f-51ed-8595-64a2119bf30d"
                             {:transactions [],
                              :parents [],
                              :ts #inst "2015-01-06T16:21:40.741-00:00",
                              :author "mail:b@mail.com"}}))))


(def store-b
  (<?? (new-mem-store (atom @(:state store-a)))))

(def err-ch (chan))

(go-loop [e (<? err-ch)]
  (when e
    (warn "ERROR occured: " e)
    (recur (<? err-ch))))


(def peer-a (server-peer (create-http-kit-handler! "ws://127.0.0.1:9090" err-ch) "PEER A"
                         store-a err-ch
                         ;; include hooking middleware in peer-a
                         :middleware (comp (partial hook hooks store-a)
                                           (partial fetch store-a (atom {}) err-ch)
                                           ensure-hash)))

(def peer-b (server-peer (create-http-kit-handler! "ws://127.0.0.1:9091" err-ch) "PEER B"
                         store-b err-ch
                         :middleware (partial fetch store-b (atom {}) err-ch)))


(start peer-a)
(start peer-b)

(def stage-a (<?? (create-stage! "mail:a@mail.com" peer-a err-ch)))


(<?? (subscribe-crdts! stage-a {"mail:b@mail.com" #{#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}
                                "mail:a@mail.com" #{#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}))

(<?? (connect! stage-a "ws://127.0.0.1:9091"))

(def stage-b (<?? (create-stage! "mail:b@mail.com" peer-b err-ch)))

(<?? (subscribe-crdts! stage-b {"mail:b@mail.com" #{#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}
                                "mail:a@mail.com" #{#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}))

;; prepare commit to mail:b@mail.com on peer-b through stage-b
(<?? (s/transact stage-b
                 ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                 '+
                 5))

;; ensure we can carry binary blobs
(<?? (s/transact-binary stage-b
                        ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                        (byte-array 5 (byte 42))))

;; commit atomically now
(<?? (s/commit! stage-b {"mail:b@mail.com" #{#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}))


(<?? (timeout 500)) ;; let network settle



(facts
 ;; ensure both have pulled metadata for user mail:a@mail.com
 (-> store-a :state deref (get-in [["mail:a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                                   :state :commit-graph]))
 => {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" [],
     #uuid "108d6e8e-8547-58f9-bb31-a0705800bda8" [#uuid "06118e59-303f-51ed-8595-64a2119bf30d"]}

 ;; check that byte-array is correctly stored
 (map byte (get-in @(:state store-a) [#uuid "11f72278-9b93-51b0-a646-3425554e0c51" :input-stream]))
 => '(42 42 42 42 42)

 (-> store-b :state deref (get-in [["mail:a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                                   :state :commit-graph]))
 => {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" [],
     #uuid "108d6e8e-8547-58f9-bb31-a0705800bda8" [#uuid "06118e59-303f-51ed-8595-64a2119bf30d"]}

 (stop peer-a)
 (stop peer-b))


[[:section {:title "Tests for pulling CDVCS"}]]


;; merge, creates new commit, fix timestamp:
(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [*date-fn* zero-date-fn]
    (f)))

(facts ;; pull normally
 (let [store (<?? (new-mem-store))
       atomic-pull-store (<?? (new-mem-store))]
   (test-env
    #(<?? (pull-cdvcs! store atomic-pull-store
                       [["mail:a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                         (-downstream (<?? (ensure-crdt store ["mail:a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"] {:crdt :cdvcs}))
                                      {:commit-graph
                                       {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                        #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                        [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                                       :heads #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}})]
                        ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                         (-downstream (<?? (ensure-crdt store ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"] {:crdt :cdvcs}))
                                      {:commit-graph
                                       {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
                                       :heads #{#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"}})]
                        (fn check [store new-commit-ids]
                          (go-try (fact new-commit-ids => #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"})
                                  true))])))
   => [["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
       {:crdt :cdvcs,
        :op {:method :pull,
             :version 1,
             :heads #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"},
             :commit-graph {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                            #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                            [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]}}}]
   @(:state store) => {}
   (-> @(:state atomic-pull-store)
       (get-in ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))  =>
   #replikativ.crdt.CDVCS{:commit-graph {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
                          :heads #{#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"},
                          :version 1}))


"A test checking that automatic pulls happen atomically never inducing a conflict."

(facts
 (let [store (<?? (new-mem-store))
       atomic-pull-store (<?? (new-mem-store))]
   (test-env
    #(<?? (pull-cdvcs! store atomic-pull-store
                       [["mail:a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                         (-downstream (<?? (ensure-crdt store ["mail:a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"] {:crdt :cdvcs}))
                                      {:commit-graph
                                       {1 []
                                        2 [1]
                                        3 [2]},
                                       :heads #{3}})]
                        ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                         (-downstream (<?? (ensure-crdt store ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"] {:crdt :cdvcs}))
                                      {:commit-graph
                                       {1 []
                                        2 [1]
                                        4 [2]},
                                       :heads #{4}})]
                        (fn check [store new-commit-ids]
                          (go-try (fact new-commit-ids => #{})
                                  true))])))
   => :rejected
   @(:state store) => {}
   @(:state atomic-pull-store) => {}))


;; do not pull from conflicting CDVCS
(facts
 (let [store (<?? (new-mem-store))
       atomic-pull-store (<?? (new-mem-store))]
   (test-env
    #(<?? (pull-cdvcs! store atomic-pull-store
                       [["mail:a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                         (-downstream (<?? (ensure-crdt store ["mail:a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"] {:crdt :cdvcs}))
                                      {:commit-graph
                                       {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                        #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                        [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]
                                        #uuid "24c41811-9f1a-55c6-9de7-0eea379838fb"
                                        [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                                       :heads
                                       #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                         #uuid "24c41811-9f1a-55c6-9de7-0eea379838fb"}})]
                        ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                         (-downstream (<?? (ensure-crdt store ["mail:b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"] {:crdt :cdvcs}))
                                      {:commit-graph
                                       {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
                                       :heads
                                       #{#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"}})]
                        (fn check [store new-commit-ids]
                          (go-try (fact new-commit-ids => #{})
                                  true))])))
   => :rejected
   @(:state store) => {}
   @(:state atomic-pull-store) => {}))
