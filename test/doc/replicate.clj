(ns doc.replicate
  (:require [midje.sweet :refer :all]
            [geschichte.replicate :refer [client-peer server-peer wire]]
            [geschichte.p2p.fetch :refer [fetch]]
            [geschichte.p2p.hash :refer [ensure-hash]]
            [geschichte.p2p.log :refer [logger]]
            [geschichte.p2p.block-detector :refer [block-detector]]
            [geschichte.crdt.repo.repo :as repo]
            [geschichte.platform :refer [create-http-kit-handler! start stop]]
            [konserve.store :refer [new-mem-store]]
            [clojure.core.async :refer [<! >! <!! >!! timeout chan go go-loop pub sub]]))

[[:chapter {:tag "synching" :title "Synching protocol of geschichte"}]]

"This chapter describes the synching protocol of geschichte. The synching protocol is the stateful network layer which ensures that updates (commits) to repositories propagate quickly and without conflicts. It is out of necessity eventual consistent, but tries to keep the diverging time frames as small as possible. "


[[:section {:tag "full-message-protocol" :title "Full Message Protocol"}]]

"The messaging protocol benefits from the *CRDT* nature of the metadata and has as little state as possible. Propagation can fail at any point and the network is still in a (locally) consistent state, so clients can keep writing without any synchronization. There is no server/client distinction except for the fact that some peers cannot accept connections (e.g. web-clients, clients behind a NAT). Each operation is acknowledged. As you can see in the following test, fetching actual transaction values happens based on need, only the metadata changes are pushed. User authentication as well as a trust mechanism between servers is not yet implemented, but will limit the propagation of values in the network at some point. For privacy encryption of transactional data is planned. Metadata here contains little private information and can be obfuscated."

"This is a demonstration of the low-level message API of the protocol. This API is subject to change and not supposed to be used by applications directly. For sake of simplicity we have replaced ids and values with small integers here as they are sufficient for the actual synching procedure."


(def log-atom (atom {}))


(facts
 (try
   (let [ ;; create a platform specific handler (needed for server only)
         handler (create-http-kit-handler! "ws://127.0.0.1:9090/")
         ;; remote server to sync to
         remote-store (<!! (new-mem-store))
         _ (def remote-peer (server-peer handler remote-store (comp (partial block-detector :remote)
                                                                    #_(partial logger log-atom :remote-core)
                                                                    (partial fetch remote-store))))

         ;; start it as its own server (usually you integrate it in ring e.g.)
         _ (start remote-peer)
         ;; local peer (e.g. used by a stage)
         local-store (<!! (new-mem-store))
         _ (def local-peer (client-peer "CLIENT" local-store (comp (partial block-detector :local)
                                                                   #_(partial logger log-atom :local-core)
                                                                   (partial fetch local-store))))
         ;; hand-implement stage-like behaviour with [in out] channels
         in (chan)
         out (chan)]
     (go-loop []
       (println "ERROR remote-peer: " (<! (get-in @remote-peer [:volatile :error-ch])))
       (recur))
     (go-loop []
       (println "ERROR local-peer: " (<! (get-in @local-peer [:volatile :error-ch])))
       (recur))
     ;; to steer the local peer one needs to wire the input as our 'out' and output as our 'in'
     (<!! (wire local-peer [out in]))
     ;; subscribe to publications of repo '1' from user 'john'
     (>!! out {:topic :meta-sub
               :metas {"john" {42 #{"master"}}}
               :peer "STAGE"
               :id 43})
     ;; subscription (back-)propagation (in peer network)
     (dissoc (<!! in) :id)
     => {:topic :meta-sub,
         :metas {"john" {42 #{"master"}}}
         :peer "CLIENT"}
     ;; ack sub
     (<!! in) => {:metas {"john" {42 #{"master"}}},
                  :peer "STAGE",
                  :topic :meta-subed
                  :id 43}
     (>!! out {:metas {"john" {42 #{"master"}}},
               :peer "CLIENT",
               :topic :meta-subed
               :id :ignored})
     ;; connect to the remote-peer
     (>!! out {:topic :connect
               :url "ws://127.0.0.1:9090/"
               :peer "STAGE"
               :id 101})
     ;; ack
     (<!! in) => {:topic :connected,
                  :url "ws://127.0.0.1:9090/",
                  :peer "STAGE"
                  :id 101}
     ;; publish a new value of repo '42' of user 'john'
     (>!! out {:topic :meta-pub,
               :peer "STAGE",
               :id 1001
               :metas {"john" {42 {:crdt :geschichte.repo
                                   :op {:method :new-state
                                        :causal-order {1 []
                                                       2 [1]}
                                        :branches {"master" #{2}}}
                                   :description "Bookmark collection."
                                   :public false}}}})
     ;; the peer replies with a request for missing commit values
     (<!! in) => {:topic :fetch,
                  :id 1001
                  :ids #{1 2}}
     ;; send them...
     (>!! out {:topic :fetched,
               :id 1001
               :values {1 {:transactions [[10 11]]}
                        2 {:transactions [[20 21]]}}})
     ;; fetch trans-values
     (<!! in) => {:topic :fetch,
                  :id 1001
                  :ids #{10 11 20 21}}
     ;; send them
     (>!! out {:topic :fetched,
               :values {10 100
                        11 110
                        20 200
                        21 210}})
     ;; ack
     (<!! in) => {:topic :meta-pubed
                  :id 1001
                  :peer "STAGE"}
     ;; back propagation of update
     (<!! in) => {:topic :meta-pub,
                  :peer "CLIENT",
                  :id 1001
                  :metas {"john" {42 {:crdt :geschichte.repo,
                                      :op {:method :new-state,
                                           :causal-order {1 []
                                                          2 [1]},
                                           :branches {"master" #{2}}}
                                      :public false,
                                      :description "Bookmark collection."}}}}

     ;; ack
     (>!! out {:topic :meta-pubed
               :id 1001
               :peer "CLIENT"})
     ;; send another update
     (>!! out {:topic :meta-pub,
               :peer "STAGE",
               :id 1002
               :metas {"john" {42 {:crdt :geschichte.repo
                                   :op {:method :new-state
                                        :causal-order {1 []
                                                       2 [1]
                                                       3 [2]}
                                        :branches {"master" #{3}}},
                                   :description "Bookmark collection.",
                                   :public false}}}})
     ;; again a new commit value is needed
     (<!! in) => {:topic :fetch,
                  :id 1002
                  :ids #{3}}
     ;; send it...
     (>!! out {:topic :fetched,
               :id 1002
               :values {3 {:transactions [[30 31]]}},
               :peer "CLIENT"})
     ;; again new tranaction values are needed
     (<!! in) => {:topic :fetch,
                  :id 1002
                  :ids #{30 31}}
     ;; send it...
     (>!! out {:topic :fetched,
               :id 1002
               :values {30 300
                        31 310}
               :peer "CLIENT"})
     ;; ack
     (<!! in) => {:topic :meta-pubed,
                  :id 1002
                  :peer "STAGE"}
     ;; and back-propagation
     (<!! in) => {:metas {"john" {42 {:crdt :geschichte.repo
                                      :op {:method :new-state
                                           :branches {"master" #{3}},
                                           :causal-order {1 [], 2 [1], 3 [2]}},
                                      :description "Bookmark collection.",
                                      :public false}}},
                  :peer "CLIENT",
                  :id 1002,
                  :topic :meta-pub}
     ;; ack
     (>!! out {:topic :meta-pubed
               :id 1002
               :peer "CLIENT"})
     ;; wait for the remote peer to sync
     (<!! (timeout 5000)) ;; let network settle
     ;; check the store of our local peer
     (-> @local-peer :volatile :store :state deref)
     => {1 {:transactions [[10 11]]},
         2 {:transactions [[20 21]]},
         3 {:transactions [[30 31]]},
         10 100,
         11 110,
         ["john" 42] {:crdt :geschichte.repo,
                      :public false,
                      :description "Bookmark collection.",
                      :state {:causal-order {1 [], 2 [1], 3 [2]},
                              :branches {"master" #{3}}}}

         20 200,
         21 210,
         30 300,
         31 310}
     ;; check the store of the remote peer
     (-> @remote-peer :volatile :store :state deref)
     => {1 {:transactions [[10 11]]},
         2 {:transactions [[20 21]]},
         3 {:transactions [[30 31]]},
         10 100,
         11 110,
         ["john" 42] {:crdt :geschichte.repo,
                      :public false,
                      :description "Bookmark collection.",
                      :state {:causal-order {1 [], 2 [1], 3 [2]},
                              :branches {"master" #{3}}}}

         20 200,
         21 210,
         30 300,
         31 310}

     ;; stop peers

     (stop local-peer)
     (stop remote-peer))
   (catch Exception e
     (.printStackTrace e))))
