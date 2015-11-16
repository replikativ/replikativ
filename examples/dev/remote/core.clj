(ns dev.remote.core
  (:require [konserve.memory :refer [new-mem-store]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.platform-log :refer [warn info debug]]
            [replikativ.crdt.cdvcs.realize :refer :all]
            [replikativ.p2p.block-detector :refer [block-detector]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.p2p.log :refer [logger]]
            [replikativ.p2p.hooks :refer [hook]]
            [replikativ.platform :refer [create-http-kit-handler! start stop]]
            [replikativ.crdt.cdvcs.stage :as s]
            [replikativ.stage :refer [create-stage! connect! subscribe-crdts!]]
            [replikativ.crdt.cdvcs.repo :as repo]
            [full.async :refer [<?? <? go-try go-loop-try] :include-macros true]
            [clojure.core.async :refer [chan go-loop go]]
            [replikativ.core :refer [client-peer server-peer wire]]))

(def uri "ws://127.0.0.1:31744")

(def repo-id #uuid "8e9074a1-e3b0-4c79-8765-b6537c7d0c44")

(def eval-fns
  {'(fn [old params] params) (fn [old params] params)
   '(fn [old params] (inc old)) (fn [old params] (inc old))
   '(fn [old params] (dec old)) (fn [old params] (dec old))
   '+ +})

(defn init []
  (let [err-ch (chan)
        handler (create-http-kit-handler! uri err-ch)
        remote-store (<?? (new-mem-store))
        _ (go-loop [e (<? err-ch)]
            (when e
              (warn "ERROR:" e)
              (recur (<? err-ch))))
        remote-peer (server-peer handler "REMOTE"
                                 remote-store err-ch
                                 (comp (partial block-detector :remote)
                                       (partial fetch remote-store err-ch)))
        stage (<?? (create-stage! "kordano@replikativ.io" remote-peer err-ch eval-fns))
        rp (<?? (s/create-repo! stage :description "testing" :id repo-id))
        state {:store remote-store
               :stage stage
               :repo rp
               :peer remote-peer}]
    (start remote-peer)
    state))

(def log-atom (atom {}))

(def hooks (atom {[#".*"
                   repo-id
                    "master"]
                  [["kordano@replikativ.io"
                    repo-id
                    "master"]]}))

(defn start-local []
  (go-try
   (let [local-store (<? (new-mem-store))
         err-ch (chan)
         local-peer (client-peer "CLJ CLIENT" local-store err-ch
                                 (comp (partial logger log-atom :local-core)
                                       (partial fetch local-store err-ch)))
         stage (<? (create-stage! "kordano@replikativ.io" local-peer err-ch eval-fns))
         _ (go-loop [e (<? err-ch)]
            (when e
              (info "ERROR:" e)
              (recur (<? err-ch))))]
     {:store local-store
      :stage stage
      :error-chan err-ch
      :peer local-peer})))


(comment
  (def remote-state (init))

  (<?? (subscribe-crdts! (:stage remote-state) {"kordano@replikativ.io" {repo-id #{"master"}}}))

  (<?? (s/transact (:stage remote-state)
                   ["kordano@replikativ.io" repo-id "master"]
                   '(fn [old params] params)
                   42))

  (<?? (s/commit! (:stage remote-state) {"kordano@replikativ.io" {repo-id #{"master"}}}))

  (<?? (s/transact (:stage remote-state)
                   ["kordano@replikativ.io" repo-id "master"]
                   43))

  (<?? (s/commit! (:stage remote-state) {"kordano@replikativ.io" {repo-id #{"master"}}}))

   (<?? (s/transact (:stage remote-state)
                   ["kordano@replikativ.io" repo-id "master"]
                   '(fn [old params] (inc old))
                   44))

   (<?? (s/commit! (:stage remote-state) {"kordano@replikativ.io" {repo-id #{"master"}}}))

    (<?? (s/transact (:stage remote-state)
                   ["kordano@replikativ.io" repo-id "master"]
                   '(fn [old params] (inc old))
                   45))

  (<?? (s/commit! (:stage remote-state) {"kordano@replikativ.io" {repo-id #{"master"}}}))


  (-> remote-state :store :state deref clojure.pprint/pprint)

  (-> remote-state :store :state deref (get ["kordano@replikativ.io" repo-id]) :state :commit-graph count)


  (def client-state (<?? (start-local)))

  (<?? (connect! (:stage client-state) uri))

  (<?? (subscribe-crdts! (:stage client-state) {"kordano@replikativ.io" {repo-id #{"master"}}}))

  (-> client-state :store :state deref (get ["kordano@replikativ.io" repo-id]) :state :commit-graph count)

  (<?? (s/transact (:stage client-state)
                  ["kordano@replikativ.io" repo-id "master"]
                  '(fn [old params] params)
                  777))

  (<?? (s/commit! (:stage client-state) {"kordano@replikativ.io" {repo-id #{"master"}}}))

  )
