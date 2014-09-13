(ns doc.auto-pull
  (:require [geschichte.sync :refer [client-peer server-peer]]
            [geschichte.platform :refer [create-http-kit-handler! start stop]]
            [geschichte.stage :refer [create-stage! connect! create-repo! subscribe-repos!] :as s]
            [geschichte.repo :as repo]
            [geschichte.p2p.fetch :refer [fetch]]
            [geschichte.p2p.log :refer [logger]]
            [geschichte.p2p.auto-pull :refer [pull pull-repo]]
            [geschichte.p2p.publish-on-request :refer [publish-on-request]]
            [konserve.store :refer [new-mem-store]]
            [konserve.protocols :refer [-assoc-in -get-in]]
            [midje.sweet :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.core.async :refer [<!!]]
            [clojure.core.async :as async
             :refer [<! >! >!! <!! timeout chan alt! go put!
                     filter< map< go-loop pub sub unsub close!]]))

;; - create two peers
;; - fork repository
;; - commit change to fork
;; - recognize on other peer
;; - cycle


;; pull normally
(facts
 (pull-repo {"a@mail.com"
             {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
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
               :pull-requests {}}}
             "b@mail.com"
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
               :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}}
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
             (fn check [new-commit-ids]
               (fact new-commit-ids => #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"})
               true)
             (fn order-conflicts [heads]
               heads)])
 => {"a@mail.com"
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
       :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}},
     "b@mail.com"
     {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
      {:description "some repo.",
       :schema {:type "http://github.com/ghubber/geschichte", :version 1},
       :pull-requests {},
       :causal-order
       {#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"
        [#uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161"],
        #uuid "05fa8703-0b72-52e8-b6da-e0b06d2f4161" []},
       :public false,
       :branches
       {"master" #{#uuid "14c41811-9f1a-55c6-9de7-0eea379838fb"}},
       :head "master",
       :last-update #inst "2014-08-26T21:14:27.179-00:00",
       :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}})


;; merge, creates new commit, fix timestamp:
(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [repo/*date-fn* zero-date-fn]
    (f)))

(facts
 (test-env
  #(pull-repo {"a@mail.com"
               {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
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
                 :pull-requests {}}}
               "b@mail.com"
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
                 :pull-requests {}}}}
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
               (fn check [new-commit-ids]
                 (fact new-commit-ids => #{3 #uuid "0628f216-0573-55c4-9c35-69a438e4e890"})
                 true)
               (fn order-conflicts [heads]
                 (fact heads => [3 4])
                 heads)]))
 => {"a@mail.com"
     {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
      {:description "some repo.",
       :schema {:type "http://github.com/ghubber/geschichte", :version 1},
       :pull-requests {},
       :causal-order {1 [], 2 [1], 3 [2]},
       :public false,
       :branches {"master" #{3}},
       :head "master",
       :last-update #inst "2014-09-01T21:17:37.699-00:00",
       :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}},
     "b@mail.com"
     {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
      {:description "some repo.",
       :schema {:type "http://github.com/ghubber/geschichte", :version 1},
       :pull-requests {},
       :causal-order
       {#uuid "0628f216-0573-55c4-9c35-69a438e4e890" [3 4],
        3 [2],
        1 [],
        2 [1],
        4 [2]},
       :public false,
       :branches
       {"master" #{#uuid "0628f216-0573-55c4-9c35-69a438e4e890"}},
       :head "master",
       :last-update #inst "1970-01-01T00:00:00.000-00:00",
       :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}})


;; do not pull from conflicting repo
(facts
 (pull-repo {"a@mail.com"
             {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
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
               :pull-requests {}}}
             "b@mail.com"
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
               :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}}
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
             (fn check [new-commit-ids]
               (fact new-commit-ids => #{})
               true)
             (fn order-conflicts [heads]
               heads)])
 => {"a@mail.com"
     {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
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
       :pull-requests {}}},
     "b@mail.com"
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
       :id #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"}}})


(comment
  (use 'aprint.core)

  (def log-atom (atom {}))

  #_(pprint (get-in @log-atom [:peer-a :out]))

  #_(aprint store-b)

  (def store-a (<!! (new-mem-store (atom {"b@mail.com"
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


  (def store-b (<!! (new-mem-store (atom {"b@mail.com"
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


  (def peer-a (server-peer (create-http-kit-handler! "ws://127.0.0.1:9090")
                           store-a
                           (comp (partial logger log-atom :peer-a)
                                 (partial pull (atom {[:*
                                                       #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                                       "master"]
                                                      [["a@mail.com"
                                                        #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                                        "master"]]}) store-a)
                                 (partial fetch store-a)
                                 (partial publish-on-request store-a))))

  (def peer-b (server-peer (create-http-kit-handler! "ws://127.0.0.1:9091")
                           store-b
                           (comp (partial logger log-atom :peer-b)
                                        ;                              pull
                                 (partial fetch store-b)
                                 (partial publish-on-request store-b))))

  (start peer-a)
  (start peer-b)

  (def stage-a (<!! (create-stage! "a@mail.com" peer-a eval)))

  (<!! (subscribe-repos! stage-a {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                                #{"master"}}
                                  "a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                                #{"master"}}}))

  (<!! (connect! stage-a "ws://127.0.0.1:9091"))

  (def stage-b (<!! (create-stage! "b@mail.com" peer-b eval)))

  (<!! (subscribe-repos! stage-b {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                                #{"master"}}
                                  "a@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"
                                                #{"master"}}}))
  ;; TODO
  (<!! (timeout 50))

  (<!! (s/transact stage-b
                   ["b@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" "master"]
                   5
                   '+))

  (<!! (s/commit! stage-b {"b@mail.com" {#uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6" #{"master"}}}))

  #_(<!! (create-repo! stage-b "b@mail.com" "some repo." 42 "master"))

  #_(<!! (create-repo! stage-a "a@mail.com" "some repo." 43 "master"))

  #_(let [{{repo-a #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"} "a@mail.com"
           {repo-b #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"} "b@mail.com"} @(:state store-a)]
      (pprint [repo-a repo-b (r/pull {:meta repo-a} "master"
                                     repo-b (first (get-in repo-b [:branches "master"])))]))

  (do
    (stop peer-a)
    (stop peer-b)))
