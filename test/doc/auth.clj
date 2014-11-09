(ns doc.auth
  (:require [clojure.core.incubator :refer [dissoc-in]]
            [midje.sweet :refer :all]
            [geschichte.sync :refer [client-peer server-peer wire]]
            [geschichte.p2p.log :refer [logger]]
            [geschichte.p2p.auth :refer [auth]]
            [geschichte.repo :as repo]
            [geschichte.platform :refer [create-http-kit-handler! start stop]]
            [konserve.store :refer [new-mem-store]]
            [geschichte.stage :as s]
            [clojure.core.async :refer [<! >! <!! >!! timeout chan go go-loop pub sub]]))

[[:chapter {:tag "authentication" :title "Authentication of geschichte"}]]
""

[[:section {:tag "authentication-protocol" :title "Full Message Protocol"}]]
""

(def log-atom (atom {}))

(comment
  (facts
   (try
     (let [ ;; create a platform specific handler (needed for server only)
           handler (create-http-kit-handler! "ws://127.0.0.1:9090/")
           ;; remote server to sync to
           remote-store (<!! (new-mem-store))
           local-users {"john" "haskell" "jane" "lisp"}
           input-users {"john" "haskell" "jane" "lisp"}
           auth-fn (fn [users] (go (into {} (filter #(users (key %)) input-users ))))
           cred-fn (fn [token] (if (= (:password token) (get local-users (:username token))) true nil))
           _ (def remote-peer (server-peer handler remote-store (comp (partial logger log-atom :remote-core))))

           ;; start it as its own server (usually you integrate it in ring e.g.)
           _ (start remote-peer)
           ;; local peer (e.g. used by a stage)
           local-store (<!! (new-mem-store))
           _ (def local-peer (client-peer "CLIENT" local-store (comp (partial logger log-atom :local-core)
                                                                     (partial auth local-store auth-fn cred-fn (atom #{"127.0.0.1"})))))
           ;; hand-implement stage-like behaviour with [in out] channels
           in (chan)
           out (chan)]
       ;; to steer the local peer one needs to wire the input as our 'out' and output as our 'in'
       (<!! (wire local-peer [out in]))
       ;; subscribe to publications of repo '1' from user 'john'
       (>!! out {:topic :meta-sub
                 :metas {"john" {42 #{"master"}}}
                 :peer "STAGE"})
       ;; subscription (back-)propagation (in peer network)
       (<!! in) => {:topic :meta-sub,
                    :metas {"john" {42 #{"master"}}}
                    :peer "CLIENT"}
       ;; connect to the remote-peer
       (>!! out {:topic :connect
                 :url "ws://127.0.0.1:9090/"
                 :peer "STAGE"})
       ;; ack sub
       (<!! in) => {:metas {"john" {42 #{"master"}}},
                    :peer "STAGE",
                    :topic :meta-subed}
       ;; ack
       (<!! in) => {:topic :connected,
                    :url "ws://127.0.0.1:9090/",
                    :peer "STAGE"}
       ;; publish a new value of repo '42' of user 'john'
       (>!! out {:topic :meta-pub,
                 :peer "STAGE",
                 :metas {"john" {42 {:id 42
                                     :causal-order {1 []
                                                    2 [1]}
                                     :last-update (java.util.Date. 0)
                                     :description "Bookmark collection."
                                     :head "master"
                                     :branches {"master" #{2}}
                                     :schema {:type :geschichte
                                              :version 1}}}}})
       ;; auth required
       (<!! in) => {:topic :geschichte.p2p.auth/auth-required, :users #{"john"},:tries-left 5}
       ;; send some credentials
       (>!! out {:topic :geschichte.p2p.auth/auth :users {"john" "häßkl"}})
       ;; ups
       (<!! in) => {:topic :geschichte.p2p.auth/auth-required, :users #{"john"},:tries-left 4}
       ;; let's try again
       (>!! out {:topic :geschichte.p2p.auth/auth :users {"john" "häskl"}})
       ;; wrong, how could that be?
       (<!! in) => {:topic :geschichte.p2p.auth/auth-required, :users #{"john"},:tries-left 3}
       ;; oh my, next try
       (>!! out {:topic :geschichte.p2p.auth/auth :users {"john" "häskel"}})
       ;; wrong again
       (<!! in) => {:topic :geschichte.p2p.auth/auth-required, :users #{"john"},:tries-left 2}
       ;; it's getting close
       (>!! out {:topic :geschichte.p2p.auth/auth :users {"john" "häsckl"}})
       ;; doh, wrong again
       (<!! in) => {:topic :geschichte.p2p.auth/auth-required, :users #{"john"},:tries-left 1}
       ;; last try
       (>!! out {:topic :geschichte.p2p.auth/auth :users {"john" "häskil"}})
       ;; oh no, epic fail
       (<!! in) => {:topic :geschichte.p2p.auth/auth-failed, :users #{"john"}}
       ;; let's try as trusted host
       (>!! out (with-meta {:topic :meta-pub,
                            :peer "STAGE",
                            :metas {"john" {42 {:id 42
                                                :causal-order {1 []
                                                               2 [1]}
                                                :last-update (java.util.Date. 0)
                                                :description "Bookmark collection."
                                                :head "master"
                                                :branches {"master" #{2}}
                                                :schema {:type :geschichte
                                                         :version 1}}}}}
                  {:host "127.0.0.1"}))
       ;; I'm in, sweet
       (<!! in) => {:topic :geschichte.p2p.auth/authed, :host "127.0.0.1"}
       (<!! in) => {:peer "STAGE", :topic :meta-pubed}
       ;; let's try again with token
       (>!! out {:topic :meta-pub,
                 :peer "STAGE",
                 :metas {"john" {42 {:id 42
                                     :causal-order {1 []
                                                    2 [1]}
                                     :last-update (java.util.Date. 0)
                                     :description "Bookmark collection."
                                     :head "master"
                                     :branches {"master" #{2}}
                                     :schema {:type :geschichte
                                              :version 1}}}}})
       ;; auth required again
       (<!! in) => {:topic :geschichte.p2p.auth/auth-required, :users #{"john"},:tries-left 5}
       ;; this time with correct password
       (>!! out {:topic :geschichte.p2p.auth/auth :users {"john" "haskell"}})
       ;; authed
       (<!! in) => {:topic :geschichte.p2p.auth/authed, :users #{"john"}}
       ;; the peer replies with a request for missing commit values

       ;; stop peers
       (stop local-peer)
       (stop remote-peer))
     (catch Exception e
       (.printStackTrace e)))))
