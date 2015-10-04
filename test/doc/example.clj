(ns doc.example
  (:require [replikativ.core :refer [client-peer server-peer]]
            [replikativ.environ :refer [*date-fn*]]
            [replikativ.protocols :refer [-downstream]]
            [replikativ.crdt.materialize :refer [pub->crdt]]
            [replikativ.platform :refer [create-http-kit-handler! start stop]]
            [replikativ.platform-log :refer [warn]]
            [replikativ.stage :refer [create-stage! connect! subscribe-repos!]]
            [replikativ.crdt.repo.stage :refer [create-repo!] :as s]
            [replikativ.crdt.repo.repo :as repo]
            [replikativ.crdt.repo.impl :refer [pull-repo!]]
            [replikativ.crdt.repo.realize :as real]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.p2p.log :refer [logger]]
            [replikativ.p2p.hooks :refer [hook]]
            [full.async :refer [<? <?? go-try go-loop-try]]
            [konserve.memory :refer [new-mem-store]]
            [konserve.protocols :refer [-assoc-in -get-in -bget]]
            [konserve.filestore :refer [new-fs-store]]
            [clojure.pprint :refer [pprint]]
            [clojure.core.async :as async
             :refer [>! >!! timeout chan alt! put! pub sub unsub close! go-loop]])
  (:import [replikativ.crdt Repository]))


(comment
(def store-a (<?? (new-mem-store (atom {}))))
(def store-b (<?? (new-mem-store (atom {}))))

(def err-ch (chan))

(go-loop [e (<? err-ch)]
  (when e
    (warn "ERROR occured: " e)
    (recur (<? err-ch))))


(def peer-a (server-peer (create-http-kit-handler! "ws://127.0.0.1:9090" err-ch) "PEER A"
                         store-a err-ch
                         ;; include hooking middleware in peer-a
                         (comp (partial fetch store-a err-ch)
                               ensure-hash)))


(def peer-b (server-peer (create-http-kit-handler! "ws://127.0.0.1:9091" err-ch) "PEER B"
                         store-b err-ch
                         (comp (partial fetch store-a err-ch)
                               ensure-hash)))


(start peer-a)
(start peer-b)

(stop peer-a)
(stop peer-b)



(def stage-a (<?? (create-stage! "mail:a@mail.com" peer-a err-ch eval)))

(<?? (connect! stage-a "ws://127.0.0.1:9091"))

(def repo-id (<?? (s/create-repo! stage-a)))

(<?? (s/transact stage-a ["mail:a@mail.com" repo-id "master"] [['(fn [old param] param) 5]]))

(<?? (s/commit! stage-a {"mail:a@mail.com" {repo-id #{"master"}}}))


;; inspect

;; linearization
(real/commit-history (:commit-graph (get-in @stage-a ["mail:a@mail.com" repo-id :state]))
                     #uuid "13c0ea8c-cb2e-5009-bb43-da34f57f37ff")

(<?? (real/commit-history-values store-a
                                 (:commit-graph (get-in @stage-a ["mail:a@mail.com" repo-id :state]))
                                 #uuid "13c0ea8c-cb2e-5009-bb43-da34f57f37ff"))

(<?? (real/branch-value store-a
                        eval
                        (get-in @stage-a ["mail:a@mail.com" repo-id])
                        "master"))


(<?? (s/transact stage-a ["mail:a@mail.com" repo-id "master"] [['+ 12]]))

(<?? (s/commit! stage-a {"mail:a@mail.com" {repo-id #{"master"}}}))
)
