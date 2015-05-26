(ns doc.hooks
  (:require [geschichte.replicate :refer [client-peer server-peer]]
            [geschichte.environ :refer [*date-fn*]]
            [geschichte.platform :refer [create-http-kit-handler! start stop <? go<? <!?]]
            [geschichte.crdt.repo.stage :refer [create-stage! connect! create-repo! subscribe-repos!] :as s]
            [geschichte.crdt.repo.repo :as repo]
            [geschichte.crdt.repo.impl :refer [pull-repo!]]

            [geschichte.p2p.fetch :refer [fetch]]
            [geschichte.p2p.log :refer [logger]]
            [geschichte.p2p.hooks :refer [hook #_pull-repo!]]
            [konserve.store :refer [new-mem-store]]
            [konserve.protocols :refer [-assoc-in -get-in -bget]]
            [konserve.filestore :refer [new-fs-store]]
            [midje.sweet :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.core.async :as async
             :refer [<! >! >!! <!! timeout chan alt! go put!
                     filter< map< go-loop pub sub unsub close!]])
  (:import [geschichte.crdt.repo.impl Repository]))

[[:chapter {:tag "hooks" :title "Pull hook middleware of geschichte"}]]

"This chapter describes the hooking middleware of geschichte. You can use these hooks to automatically pull or merge from other repositories on peer level, e.g. to pull new user data on server side or to pull server updates to a central repository into a user writable repository on client-side."

"You can use regular expression wildcards on usernames to pull from, see example:"

(facts
 ;; hooking map
 (def hooks (atom {[#".*"
                    #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                    "master"]
                   [["a@mail.com"
                     #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                     "master"]]}))

 ;; setup two peers with stores and a single commit in a@mail.com and b@mail.com repositories
 (def store-a
   (<!? (new-mem-store (atom {["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                              {:description "some repo.",
                               :public false,
                               :crdt :geschichte.repo
                               :state {:causal-order {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" []},
                                       :branches {"master" #{#uuid "06118e59-303f-51ed-8595-64a2119bf30d"}},}},
                              ["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                              {:description "some repo.",
                               :public false,
                               :crdt :geschichte.repo
                               :state {:causal-order {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" []},
                                       :branches {"master" #{#uuid "06118e59-303f-51ed-8595-64a2119bf30d"}}}},
                              #uuid "06118e59-303f-51ed-8595-64a2119bf30d"
                              {:transactions [],
                               :parents [],
                               :ts #inst "2015-01-06T16:21:40.741-00:00",
                               :author "b@mail.com"}}))))


 (def store-b
   (<!? (new-mem-store (atom @(:state store-a)))))


 (def peer-a (server-peer (create-http-kit-handler! "ws://127.0.0.1:9090")
                          store-a
                          ;; include hooking middleware in peer-a
                          (comp (partial hook hooks store-a)
                                (partial fetch store-a))))

 (def peer-b (server-peer (create-http-kit-handler! "ws://127.0.0.1:9091")
                          store-b
                          (partial fetch store-b)))

 (go-loop []
   (println "ERROR peer-a: " (<! (get-in @peer-a [:volatile :error-ch])))
   (recur))
 (go-loop []
   (println "ERROR peer-b: " (<! (get-in @peer-b [:volatile :error-ch])))
   (recur))


 (start peer-a)
 (start peer-b)

 (def stage-a (<!? (create-stage! "a@mail.com" peer-a eval)))


 (<!? (subscribe-repos! stage-a {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                               #{"master"}}
                                 "a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                               #{"master"}}}))

 ;; TODO unit case
 (comment
   (<!? (s/branch! stage-a ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"] "plan-b"
                   #uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"))
   (<!? (s/pull! stage-a ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"] "plan-b"
                 :into-user "b@mail.com"))
   (get-in @(:state store-a) ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))

 (<!? (connect! stage-a "ws://127.0.0.1:9091"))

 (def stage-b (<!? (create-stage! "b@mail.com" peer-b eval)))

 (<!? (subscribe-repos! stage-b {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                               #{"master"}}
                                 "a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                               #{"master"}}}))

 ;; prepare commit to b@mail.com on peer-b through stage-b
 (<!? (s/transact stage-b
                  ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"]
                  '+
                  5))

 ;; ensure we can carry binary blobs
 (<!? (s/transact-binary stage-b
                         ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"]
                         (byte-array 5 (byte 42))))

 ;; commit atomically now
 (<!? (s/commit! stage-b {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}}}))


 (<!? (timeout 500)) ;; let network settle

 ;; ensure both have pulled metadata for user a@mail.com
 (-> store-a :state deref (get-in [["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                                   :state :causal-order]))
 => {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" [],
     #uuid "108d6e8e-8547-58f9-bb31-a0705800bda8" [#uuid "06118e59-303f-51ed-8595-64a2119bf30d"]}

 ;; check that byte-array is correctly stored
 (map byte (get-in @(:state store-a) [#uuid "11f72278-9b93-51b0-a646-3425554e0c51" :input-stream]))
 => '(42 42 42 42 42)

 (-> store-b :state deref (get-in [["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
                                   :state :causal-order]))
 => {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" [],
     #uuid "108d6e8e-8547-58f9-bb31-a0705800bda8" [#uuid "06118e59-303f-51ed-8595-64a2119bf30d"]}

 (stop peer-a)
 (stop peer-b))



;; merge, creates new commit, fix timestamp:
(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [*date-fn* zero-date-fn]
    (f)))

(facts
 ;; pull normally
 (let [store (<!! (new-mem-store))
       atomic-pull-store (<!! (new-mem-store))]
   (test-env
    #(<!! (pull-repo! store atomic-pull-store
                      [["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:op {:causal-order
                              {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                               #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                               [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                              :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                              :description "some repo.",
                              :branches
                              {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
                              :public false}}]
                       ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:state {:description "some repo.",
                                 :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
                                 :public false,
                                 :branches
                                 {"master" #{#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"}},
                                 :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}]
                       (fn check [store new-commit-ids]
                         (go (fact new-commit-ids => #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"})
                             true))])))
   => [["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
       {:type :geschichte.crdt.repo
        :op {:method :pull
             :version 1
             :branches {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
             :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                            #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                            [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]}},}]
   @(:state store) => {}
   @(:state atomic-pull-store) => {"b@mail.com"
                                   {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                    {:description "some repo.",
                                     :causal-order
                                     {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                      #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                      [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                                     :public false,
                                     :branches
                                     {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
                                     :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}}))


(facts
 (let [store (<!! (new-mem-store))
       atomic-pull-store (<!! (new-mem-store))]
   (test-env
    #(<!! (pull-repo! store atomic-pull-store
                      [["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:type :state
                         :op {:causal-order
                              {1 []
                               2 [1]
                               3 [2]},
                              :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                              :description "some repo.",
                              :branches
                              {"master" #{3}},
                              :public false}}]
                       ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:state {:causal-order
                                 {1 []
                                  2 [1]
                                  4 [2]},
                                 :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                                 :description "some repo.",
                                 :branches
                                 {"master" #{4}},
                                 :public false}}]
                       (fn check [store new-commit-ids]
                         (go
                           (fact new-commit-ids => #{3})
                           true))
                       true])))
   => [["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
       {:type :geschichte.crdt.repo
        :op {:method :pull
             :branches {"master" #{3}},
             :causal-order {2 [1], 3 [2]}
             :version 1}}]
   @(:state store) => {}
   @(:state atomic-pull-store) => {}))


;; do not pull from conflicting repo
(facts
 (let [store (<!! (new-mem-store))
       atomic-pull-store (<!! (new-mem-store))]
   (<!! (pull-repo! store atomic-pull-store
                    [["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                      {:type :state
                       :op {:causal-order
                            {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                             #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                             [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]
                             #uuid "24c41811-9f1a-55c6-9de7-0eea379838fb"
                             [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                            :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                            :description "some repo.",
                            :branches
                            {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                        #uuid "24c41811-9f1a-55c6-9de7-0eea379838fb"}},
                            :public false}}]
                     ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                      {:state {:description "some repo.",
                               :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
                               :public false,
                               :branches
                               {"master" #{#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"}},
                               :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}]
                     (fn check [store new-commit-ids]
                       (go
                         (fact new-commit-ids => #{})
                         true))
                     (fn order-conflicts [store heads]
                       (go heads))]))
   => :rejected
   @(:state atomic-pull-store) => {}))


"A test checking that automatic pulls happen atomically never inducing a conflict."

(facts
 (let [store (<!! (new-mem-store))
       atomic-pull-store (<!! (new-mem-store))]
   (test-env
    #(<!! (pull-repo! store atomic-pull-store
                      [["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:op {:causal-order
                              {1 []
                               2 [1]
                               3 [2]},
                              :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                              :description "some repo.",
                              :branches
                              {"master" #{3}},
                              :public false}}]
                       ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:state {:causal-order
                                 {1 []
                                  2 [1]
                                  4 [2]},
                                 :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                                 :description "some repo.",
                                 :branches
                                 {"master" #{4}},
                                 :public false}}]
                       (fn check [store new-commit-ids]
                         (go
                           (fact new-commit-ids => #{})
                           true))
                       false])))
   => :rejected
   @(:state store) => {}))
