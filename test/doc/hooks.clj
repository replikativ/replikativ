(ns doc.hooks
  (:require [geschichte.sync :refer [client-peer server-peer]]
            [geschichte.platform :refer [create-http-kit-handler! start stop <? go<? <!?]]
            [geschichte.stage :refer [create-stage! connect! create-repo! subscribe-repos!] :as s]
            [geschichte.repo :as repo]
            [geschichte.p2p.fetch :refer [fetch]]
            [geschichte.p2p.log :refer [logger]]
            [geschichte.p2p.hooks :refer [hook pull-repo!]]
            [geschichte.p2p.publish-on-request :refer [publish-on-request]]
            [konserve.store :refer [new-mem-store]]
            [konserve.protocols :refer [-assoc-in -get-in -bget]]
            [konserve.filestore :refer [new-fs-store]]
            [midje.sweet :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.core.async :as async
             :refer [<! >! >!! <!! timeout chan alt! go put!
                     filter< map< go-loop pub sub unsub close!]]))

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
 (def store-a (<!? (new-mem-store (atom {"b@mail.com"
                                         {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                          {:description "some repo.",
                                           :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                                           :pull-requests {},
                                           :causal-order {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" []},
                                           :public false,
                                           :branches {"master" #{#uuid "06118e59-303f-51ed-8595-64a2119bf30d"}},
                                           :head "master",
                                           :last-update #inst "2015-01-06T16:21:40.741-00:00",
                                           :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}},
                                         "a@mail.com"
                                         {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                          {:description "some repo.",
                                           :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                                           :pull-requests {},
                                           :causal-order {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" []},
                                           :public false,
                                           :branches {"master" #{#uuid "06118e59-303f-51ed-8595-64a2119bf30d"}},
                                           :head "master",
                                           :last-update #inst "2015-01-06T16:21:40.741-00:00",
                                           :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}},
                                         #uuid "06118e59-303f-51ed-8595-64a2119bf30d"
                                         {:transactions [],
                                          :parents [],
                                          :ts #inst "2015-01-06T16:21:40.741-00:00",
                                          :author "b@mail.com"}}))))


 (def store-b (<!? (new-mem-store (atom {"b@mail.com"
                                         {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                          {:description "some repo.",
                                           :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                                           :pull-requests {},
                                           :causal-order {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" []},
                                           :public false,
                                           :branches {"master" #{#uuid "06118e59-303f-51ed-8595-64a2119bf30d"}},
                                           :head "master",
                                           :last-update #inst "2015-01-06T16:21:40.741-00:00",
                                           :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}},
                                         "a@mail.com"
                                         {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                          {:description "some repo.",
                                           :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                                           :pull-requests {},
                                           :causal-order {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" []},
                                           :public false,
                                           :branches {"master" #{#uuid "06118e59-303f-51ed-8595-64a2119bf30d"}},
                                           :head "master",
                                           :last-update #inst "2015-01-06T16:21:40.741-00:00",
                                           :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}},
                                         #uuid "06118e59-303f-51ed-8595-64a2119bf30d"
                                         {:transactions [],
                                          :parents [],
                                          :ts #inst "2015-01-06T16:21:40.741-00:00",
                                          :author "b@mail.com"}}))))


 (def peer-a (server-peer (create-http-kit-handler! "ws://127.0.0.1:9090")
                          store-a
                          ;; include hooking middleware in peer-a
                          (comp (partial hook hooks store-a)
                                (partial fetch store-a)
                                (partial publish-on-request store-a))))

 (def peer-b (server-peer (create-http-kit-handler! "ws://127.0.0.1:9091")
                          store-b
                          (comp (partial fetch store-b)
                                (partial publish-on-request store-b))))

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
 (-> store-a :state deref (get-in ["a@mail.com"
                                   #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                   :causal-order]))
 => {#uuid "0061c511-32ed-5bd5-9996-7d0bc7d4b7b8" [#uuid "06118e59-303f-51ed-8595-64a2119bf30d"],
     #uuid "06118e59-303f-51ed-8595-64a2119bf30d" []}

 ;; check that byte-array is correctly stored
 (map byte (get-in @(:state store-a) [#uuid "133e3d6b-7756-5365-9eae-dbf7e609d244" :input-stream]))
 => '(42 42 42 42 42)

 (-> store-b :state deref (get-in ["a@mail.com"
                                   #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                   :causal-order]))
 => {#uuid "0061c511-32ed-5bd5-9996-7d0bc7d4b7b8" [#uuid "06118e59-303f-51ed-8595-64a2119bf30d"],
     #uuid "06118e59-303f-51ed-8595-64a2119bf30d" []}

 (stop peer-a)
 (stop peer-b))


"Some lower-level tests to cover conflicts and integrity-fn functionality:"

;; merge, creates new commit, fix timestamp:
(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [repo/*date-fn* zero-date-fn]
    (f)))

(facts
 ;; pull normally
 (let [store (<!! (new-mem-store))
       atomic-pull-store (<!! (new-mem-store))]
   (test-env
    #(<!! (pull-repo! store atomic-pull-store
                      [["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:causal-order
                         {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                          #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                          [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                         :last-update #inst "2014-09-01T21:17:37.699-00:00",
                         :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                         :description "some repo.",
                         :schema
                         {:type "http://github.com/ghubber/geschichte", :version 1},
                         :head "master",
                         :branches
                         {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
                         :public false,
                         :pull-requests {}}]
                       ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:description "some repo.",
                         :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                         :pull-requests {},
                         :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
                         :public false,
                         :branches
                         {"master" #{#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"}},
                         :head "master",
                         :last-update #inst "2014-08-26T21:14:27.179-00:00",
                         :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}]
                       (fn check [store new-commit-ids]
                         (go (fact new-commit-ids => #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"})
                             true))])))
   => [["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
       {:description "some repo.",
        :schema {:type "http://github.com/ghubber/geschichte", :version 1},
        :pull-requests {},
        :causal-order
        {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
         #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
         [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
        :public false,
        :branches {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
        :head "master",
        :last-update #inst "2014-09-01T21:17:37.699-00:00",
        :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}]
   @(:state store) => {}
   @(:state atomic-pull-store) => {"b@mail.com"
                                   {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                    {:description "some repo.",
                                     :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                                     :pull-requests {},
                                     :causal-order
                                     {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                                      #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                      [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                                     :public false,
                                     :branches
                                     {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
                                     :head "master",
                                     :last-update #inst "2014-09-01T21:17:37.699-00:00",
                                     :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}}))

(facts
 (let [store (<!! (new-mem-store))
       atomic-pull-store (<!! (new-mem-store))]
   (test-env
    #(<!! (pull-repo! store atomic-pull-store
                      [["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:causal-order
                         {1 []
                          2 [1]
                          3 [2]},
                         :last-update #inst "2014-09-01T21:17:37.699-00:00",
                         :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                         :description "some repo.",
                         :schema
                         {:type "http://github.com/ghubber/geschichte", :version 1},
                         :head "master",
                         :branches
                         {"master" #{3}},
                         :public false,
                         :pull-requests {}}]
                       ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:causal-order
                         {1 []
                          2 [1]
                          4 [2]},
                         :last-update #inst "2014-09-01T21:17:37.699-00:00",
                         :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                         :description "some repo.",
                         :schema
                         {:type "http://github.com/ghubber/geschichte", :version 1},
                         :head "master",
                         :branches
                         {"master" #{4}},
                         :public false,
                         :pull-requests {}}]
                       (fn check [store new-commit-ids]
                         (go
                           (fact new-commit-ids => #{3})
                           true))
                       true])))
   => [["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]
       {:description "some repo.",
        :schema {:type "http://github.com/ghubber/geschichte", :version 1},
        :pull-requests {},
        :causal-order {1 [], 2 [1], 3 [2], 4 [2]},
        :public false,
        :branches {"master" #{3}},
        :head "master",
        :last-update #inst "2014-09-01T21:17:37.699-00:00",
        :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}]
   @(:state store) => {}
   @(:state atomic-pull-store) => {}))


;; do not pull from conflicting repo
(facts
 (let [store (<!! (new-mem-store))
       atomic-pull-store (<!! (new-mem-store))]
   (<!! (pull-repo! store atomic-pull-store
                    [["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                      {:causal-order
                       {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" [],
                        #uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                        [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]
                        #uuid "24c41811-9f1a-55c6-9de7-0eea379838fb"
                        [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]},
                       :last-update #inst "2014-09-01T21:17:37.699-00:00",
                       :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                       :description "some repo.",
                       :schema
                       {:type "http://github.com/ghubber/geschichte", :version 1},
                       :head "master",
                       :branches
                       {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
                                   #uuid "24c41811-9f1a-55c6-9de7-0eea379838fb"}},
                       :public false,
                       :pull-requests {}}]
                     ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                      {:description "some repo.",
                       :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                       :pull-requests {},
                       :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
                       :public false,
                       :branches
                       {"master" #{#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"}},
                       :head "master",
                       :last-update #inst "2014-08-26T21:14:27.179-00:00",
                       :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}]
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
       atomic-pull-store
       (<!!
        (new-mem-store
         (atom {"b@mail.com"
                {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                 {:causal-order
                  {1 []
                   2 [1]
                   4 [2]},
                  :last-update #inst "2014-09-01T21:17:37.699-00:00",
                  :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                  :description "some repo.",
                  :schema
                  {:type "http://github.com/ghubber/geschichte", :version 1},
                  :head "master",
                  :branches
                  {"master" #{4}},
                  :public false,
                  :pull-requests {}}}})))]
   (test-env
    #(<!! (pull-repo! store atomic-pull-store
                      [["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:causal-order
                         {1 []
                          2 [1]
                          3 [2]},
                         :last-update #inst "2014-09-01T21:17:37.699-00:00",
                         :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                         :description "some repo.",
                         :schema
                         {:type "http://github.com/ghubber/geschichte", :version 1},
                         :head "master",
                         :branches
                         {"master" #{3}},
                         :public false,
                         :pull-requests {}}]
                       ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"
                        {:causal-order
                         {1 []
                          2 [1]},
                         :last-update #inst "2014-09-01T21:17:37.699-00:00",
                         :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                         :description "some repo.",
                         :schema
                         {:type "http://github.com/ghubber/geschichte", :version 1},
                         :head "master",
                         :branches
                         {"master" #{2}},
                         :public false,
                         :pull-requests {}}]
                       (fn check [store new-commit-ids]
                         (go
                           (fact new-commit-ids => #{3 #uuid "0628f216-0573-55c4-9c35-69a438e4e890"})
                           true))
                       false])))
   => :rejected
   @(:state store) => {}
   @(:state atomic-pull-store) => {"b@mail.com"
                                   {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                    {:causal-order
                                     {1 []
                                      2 [1]
                                      4 [2]},
                                     :last-update #inst "2014-09-01T21:17:37.699-00:00",
                                     :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6",
                                     :description "some repo.",
                                     :schema
                                     {:type "http://github.com/ghubber/geschichte", :version 1},
                                     :head "master",
                                     :branches
                                     {"master" #{4}},
                                     :public false,
                                     :pull-requests {}} }}))





















(comment
  ;; hooking map
  (def hooks (atom {[#".*"
                     #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                     "master"]
                    [["a@mail.com"
                      #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                      "master"]]}))

  ;; setup two peers with stores and a single commit in a@mail.com and b@mail.com repositories
  (def store-a (<!! (new-fs-store "/tmp/store-a"))
    #_(<!! (new-mem-store (atom {"b@mail.com"
                                 {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                  {:description "some repo.",
                                   :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                                   :pull-requests {},
                                   :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
                                   :public false,
                                   :branches
                                   {"master" #{#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"}},
                                   :head "master",
                                   :last-update #inst "2014-08-26T21:14:27.179-00:00",
                                   :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}},
                                 "a@mail.com"
                                 {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                  {:description "some repo.",
                                   :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                                   :pull-requests {},
                                   :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
                                   :public false,
                                   :branches
                                   {"master" #{#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"}},
                                   :head "master",
                                   :last-update
                                   #inst "2014-08-26T21:14:27.179-00:00",
                                   :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}},
                                 #uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"
                                 {:transactions
                                  [[#uuid "1b6c9246-3d99-51c0-b17a-75034dff5ab1"
                                    #uuid "123ed64b-1e25-59fc-8c5b-038636ae6c3d"]],
                                  :parents [],
                                  :ts #inst "2014-08-26T21:14:27.179-00:00",
                                  :author "b@mail.com"},
                                 #uuid "123ed64b-1e25-59fc-8c5b-038636ae6c3d"
                                 '(fn replace [old params] params),
                                 #uuid "1b6c9246-3d99-51c0-b17a-75034dff5ab1" 42}))))


  (let [repo {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
              {:description "some repo.",
               :schema {:type "http://github.com/ghubber/geschichte", :version 1},
               :pull-requests {},
               :causal-order {#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
               :public false,
               :branches
               {"master" #{#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"}},
               :head "master",
               :last-update #inst "2014-08-26T21:14:27.179-00:00",
               :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}]
    (<!! (-assoc-in store-a ["b@mail.com"] repo))
    (<!! (-assoc-in store-a ["a@mail.com"] repo))
    (<!! (-assoc-in store-a [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"]
                    {:transactions
                     [[#uuid "1b6c9246-3d99-51c0-b17a-75034dff5ab1"
                       #uuid "123ed64b-1e25-59fc-8c5b-038636ae6c3d"]],
                     :parents [],
                     :ts #inst "2014-08-26T21:14:27.179-00:00",
                     :author "b@mail.com"}))
    (<!! (-assoc-in store-a [#uuid "123ed64b-1e25-59fc-8c5b-038636ae6c3d"]
                    '(fn replace [old params] params)))
    (<!! (-assoc-in store-a [#uuid "1b6c9246-3d99-51c0-b17a-75034dff5ab1"] 42)))


  (def peer-a (server-peer (create-http-kit-handler! "ws://127.0.0.1:9090")
                           store-a
                           ;; include hooking middleware in peer-a
                           (comp #_(partial hook hooks store-a)
                                 (partial fetch store-a)
                                 (partial publish-on-request store-a))))

  (start peer-a)

  (def stage-a (<!! (create-stage! "a@mail.com" peer-a eval)))

  (<!! (subscribe-repos! stage-a {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                                #{"master"}}
                                  "a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                                #{"master"}}}))



  (require '[taoensso.timbre :as timbre])
  (timbre/set-level! :warn)
  ;; commit atomically now
  (let [sm (System/currentTimeMillis)]
    (time (do (doseq [i (range 10000)]
                (when (= (mod i 100) 0)
                  (println "Commit: " i ", time spent:" (- (System/currentTimeMillis) sm) " ms"))
                (<!! (s/transact-binary stage-a
                                        ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"]
                                        (byte-array (* 1024 1024) (byte (mod i 128)))))
                (<!! (s/transact stage-a
                                 ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"]
                                 #_i
                                 [[{:db/id i
                                    :person/prename "Peter"
                                    :person/lastname "Frost"
                                    :person/phone "02348737373"
                                    :person/address "47231 Postalplace"}
                                   {:db/id i
                                    :type "json"
                                    :neuron/cm 0.2
                                    :neuron/t_ref 10
                                    :neuron/v_rest -50
                                    :neuron/v_reset -55
                                    :neuron/E_rev 0.0
                                    :neuron/I_rev -100.0}
                                   {:db/id i
                                    :type "json"
                                    :restricted/weights [[0.3 0.7 0.4]
                                                         [0.0 0.1 0.5]]
                                    :restricted/v-biases [0.3 -0.4 0.2]
                                    :restricted/h-biases [0.2 0.1]}]]
                                 #_'+
                                 '(fn [old params]
                                      (d/transact old params))))
                (<!! (s/commit! stage-a {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}}}))))))





  (reduce + (range 10000))
  (-> @stage-a :volatile :val-atom)

  (<!! (timeout 500)) ;; let network settle

  ;; ensure both have pulled metadata for user a@mail.com
  (let [meta (<!! (-get-in store-a ["b@mail.com"
                                    #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))]
    (->> (s/commit-history (:causal-order meta) (-> meta :branches (get "master") first))
         (map #(try [% (<!! (-get-in store-a [%]))]
                    (catch Exception e
                      (println "id" % e)
                      (throw e))))
         (filter #(nil? (-> % second :transactions)))))


  (let [meta (<!! (-get-in store-a ["b@mail.com"
                                    #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"]))]
    (time (->> (<!! (s/commit-history-values store-a
                                             (:causal-order meta)
                                             (-> meta :branches (get "master") first)))
               (map :transactions)
               (map ffirst)
               (reduce +))))


  (<!! (go? (<? (go (ex-info "hello" {})))))
  (-> '(go? (go (throw (ex-info "hello" {}))))
      macroexpand-1)

  (def store-b (<!! (new-fs-store "/tmp/store-b")))

  (def peer-b (server-peer (create-http-kit-handler! "ws://127.0.0.1:9091")
                           store-b
                           ;; include hooking middleware in peer-a
                           (comp #_(partial hook hooks store-b)
                                 (partial fetch store-b)
                                 (partial publish-on-request store-b))))

  (start peer-b)

  (def stage-b (<!! (create-stage! "a@mail.com" peer-b eval)))

  (<!! (connect! stage-b "ws://127.0.0.1:9090"))

  (time (<!! (subscribe-repos! stage-b {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                                      #{"master"}}
                                        "a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                                      #{"master"}}})))


  (stop peer-a)
  (stop peer-b)
  )
