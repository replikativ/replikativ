(ns doc.sync
  (:require [clojure.core.incubator :refer [dissoc-in]]
            [midje.sweet :refer :all]
            [geschichte.sync :refer :all]
            [geschichte.repo :as repo]
            [geschichte.store :refer [new-mem-store]]
            [geschichte.stage :as s]
            [clojure.core.async :refer [<! >! <!! >!! timeout chan go go-loop pub sub]]))

[[:chapter {:tag "synching" :title "Synching protocol of geschichte"}]]

"This chapter describes the synching protocol of geschichte. The synching protocol is the stateful network layer which ensures that updates (commits) to repositories propagate quickly and without conflicts. It is out of necessity eventual consistent, but tries to keep the diverging time frames as small as possible. "


[[:section {:tag "stage-sync" :title "Stage-based syncing"}]]

"To execute the syncing (storage) related side-effects, you create a runtime *stage* primitive, wire it to a peer and synchronize its value (unless it is loaded). To update, you transact the stage, like swapping an atom, except that you should parametrize the function to make data used in the transaction explicit for later inspection (like a serialized scope). Once you are finished you commit and sync!."

(let [store (new-mem-store)
      peer (client-peer "CLIENT" store)
      stage (atom (->> #_(repo/new-repository "me@mail.com"
                                              {:type "s" :version 1}
                                              "Testing."
                                              false
                                              {:some 43})
                       ;; => creates something like this: (other random repo UUID)
                       {:meta {:causal-order {#uuid "04eb5b1b-4d10-5036-b235-fa173253089a" #{}},
                               ;; hack stable future for meta-data update
                               :last-update #inst "2200-01-01T00:00:00.000-00:00",
                               :head "master",
                               :public false,
                               :branches {"master" #{#uuid "04eb5b1b-4d10-5036-b235-fa173253089a"}},
                               :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
                               :pull-requests {},
                               :id #uuid "7b5f3f6b-e66f-47a8-a5eb-c71ae367f956",
                               :description "Testing."},
                        :author "me@mail.com",
                        :schema {:version 1, :type "s"},
                        :transactions [],
                        ;; used by sync!
                        :type :meta-sub,
                        :new-values {#uuid "04eb5b1b-4d10-5036-b235-fa173253089a"
                                     {:transactions [[{:some 43}
                                                      '(fn replace [old params] params)]],
                                      :parents #{},
                                      :author "me@mail.com",
                                      :schema {:version 1, :type "s"}}}}
                       (wire-stage peer)
                       <!!
                       sync!
                       <!!))]
  (swap! stage #(->> (s/transact %
                                 {:other 44}
                                 '(fn merger [old params] (merge old params)))
                     repo/commit
                     sync!
                     <!!))
  (facts
   (-> store :state deref)
   =>
   {"me@mail.com"
    {#uuid "7b5f3f6b-e66f-47a8-a5eb-c71ae367f956"
     {:causal-order
      {#uuid "04eb5b1b-4d10-5036-b235-fa173253089a" #{},
       #uuid "1ee0de30-4717-5b22-a5ce-5c21aebe5a42"
       #{#uuid "04eb5b1b-4d10-5036-b235-fa173253089a"}},
      :last-update #inst "2200-01-01T00:00:00.000-00:00",
      :head "master",
      :public false,
      :branches
      {"master" #{#uuid "1ee0de30-4717-5b22-a5ce-5c21aebe5a42"}},
      :schema {:type "http://github.com/ghubber/geschichte", :version 1},
      :pull-requests {},
      :id #uuid "7b5f3f6b-e66f-47a8-a5eb-c71ae367f956",
      :description "Testing."}},
    #uuid "04eb5b1b-4d10-5036-b235-fa173253089a"
    {:author "me@mail.com",
     :parents #{},
     :schema {:type "s", :version 1},
     :transactions [[{:some 43} '(fn replace [old params] params)]]},
    #uuid "1ee0de30-4717-5b22-a5ce-5c21aebe5a42"
    {:author "me@mail.com",
     :parents #{#uuid "04eb5b1b-4d10-5036-b235-fa173253089a"},
     :schema {:type "s", :version 1},
     :transactions
     [[{:other 44} '(fn merger [old params] (merge old params))]]}}
   ;; a simple (but inefficient) way to access the value of the repo is to realize all transactions
   ;; in memory:
   (<!! (s/realize-value @stage store eval))
   => {:other 44, :some 43}))




[[:section {:tag "message-protocol" :title "Message Protocol"}]]

"The messaging protocol benefits from the *CRDT* nature of the metadata and has as little state as possible. Propagation can fail at any point and the network is still in a (locally) consistent state, so clients can keep writing without any synchronization. There is no server/client distinction except for the fact that some peers cannot accept connections (e.g. web-clients, clients behind a NAT). Each operation is acknowledged. As you can see in the following test, fetching actual transaction values happens based on need, only the metadata changes are pushed. User authentication as well as a trust mechanism between servers is not yet implemented, but will limit the propagation of values in the network at some point. For privacy encryption of transactional data is planned. Metadata here contains little private information and can be obfuscated."

"This is a demonstration of the low-level message API of the protocol. This API is subject to change and not supposed to be used by applications directly."


(facts
 (let [;; remote server to sync to
       remote-peer (server-peer "127.0.0.1"
                                9090
                                (new-mem-store))
       ;; local peer (e.g. used by a stage)
       local-peer (client-peer "CLIENT" (new-mem-store))
       ;; hand-implement stage-like behaviour with [in out] channels
       in (chan)
       out (chan)]
   ;; to steer the local peer one needs to wire 'in' and a publication of out by :topic
   (<!! (wire local-peer [in (pub out :topic)]))
   ;; subscribe to publications of repo '1' from user 'john'
   (>!! out {:topic :meta-sub :metas {"john" #{1}}})
   ;; ack
   (<!! in) => {:topic :meta-subed, :metas {"john" #{1}}}
   ;; subscription (back-)propagation (in peer network)
   (<!! in) => {:topic :meta-sub, :metas {"john" #{1}}}
   ;; connect to the remote-peer
   (>!! out {:topic :connect
             :ip4 "127.0.0.1"
             :port 9090})
   ;; ack
   (<!! in) => {:topic :connected, :port 9090, :ip4 "127.0.0.1"}
   ;; publish a new value of repo '1' of user 'john'
   (>!! out {:topic :meta-pub
             :user "john"
             :meta {:id 1
                    :causal-order {1 #{}
                                   2 #{1}}
                    :last-update (java.util.Date. 0)
                    :schema {:type :geschichte
                             :version 1}}})
   ;; the peer replies with a request for missing commit values
   (<!! in) => {:topic :fetch, :ids #{1 2}}
   ;; send them...
   (>!! out {:topic :fetched :values {1 2
                                      2 42}})
   ;; ack
   (<!! in) => {:topic :meta-pubed}
   ;; back propagation of update
   (<!! in) => {:topic :meta-pub,
                :user "john",
                :meta {:id 1,
                       :causal-order {1 #{}, 2 #{1}},
                       :last-update #inst "1970-01-01T00:00:00.000-00:00",
                       :schema {:type :geschichte, :version 1}}}
   ;; send another update
   (>!! out {:topic :meta-pub
             :user "john"
             :meta {:id 1
                    :causal-order {1 #{}
                                   2 #{1}
                                   3 #{2}}
                    :last-update (java.util.Date. 0)
                    :schema {:type :geschichte
                             :version 1}}})
   ;; again a new commit value is needed
   (<!! in) => {:topic :fetch, :ids #{3}}
   ;; send it...
   (>!! out {:topic :fetched :values {3 43}})
   ;; ack
   (<!! in) => {:topic :meta-pubed}
   ;; and back-propagation
   (<!! in) => {:topic :meta-pub,
                :user "john",
                :meta {:id 1,
                       :causal-order {1 #{}, 2 #{1}, 3 #{2}},
                       :last-update #inst "1970-01-01T00:00:00.000-00:00",
                       :schema {:type :geschichte, :version 1}}}
   ;; wait for the remote peer to sync
   (<!! (timeout 1000)) ;; let network settle
   ;; check the store of our local peer
   (-> @local-peer :volatile :store :state deref)
   => {3 43,
       "john" {1 {:causal-order {1 #{}, 2 #{1}, 3 #{2}},
                  :last-update #inst "1970-01-01T00:00:00.000-00:00",
                  :head nil, :public nil, :branches nil,
                  :schema {:type :geschichte, :version 1},
                  :pull-requests nil, :id 1, :description nil}},
       2 42,
       1 2}
   ;; check the store of the remote peer
   (-> @remote-peer :volatile :store :state deref)
   => {3 43,
       "john" {1 {:causal-order {1 #{}, 2 #{1}, 3 #{2}},
                  :last-update #inst "1970-01-01T00:00:00.000-00:00",
                  :head nil, :public nil, :branches nil,
                  :schema {:type :geschichte, :version 1},
                  :pull-requests nil, :id 1, :description nil}},
       2 42,
       1 2}
   ;; stop peers
   (stop local-peer)
   (stop remote-peer)))
