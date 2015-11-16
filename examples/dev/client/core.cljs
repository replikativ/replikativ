(ns dev.client.core
  (:require [konserve.memory :refer [new-mem-store]]
            [konserve.protocols :refer [-get-in]]
            [replikativ.core :refer [wire client-peer]]
            [replikativ.stage :refer [create-stage! connect! subscribe-crdts!]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.p2p.log :refer [logger]]
            [replikativ.p2p.hooks :refer [hook]]
            [replikativ.crdt.cdvcs.stage :as s]
            [replikativ.p2p.block-detector :refer [block-detector]]
            [cljs.core.async :refer [>! chan timeout]]
            [full.cljs.async :refer [throw-if-throwable]])
  (:require-macros [full.cljs.async :refer [go-try <? go-loop-try]]
                   [cljs.core.async.macros :refer [go-loop]]))

#_(repl/connect "ws://localhost:9001")

(def repo-id #uuid "8e9074a1-e3b0-4c79-8765-b6537c7d0c44")

(def uri "ws://127.0.0.1:31744")

(def hooks (atom {[#".*"
                   repo-id
                    "master"]
                  [["kordano@replikativ.io"
                    repo-id
                    "master"]]}))

(enable-console-print!)

(def eval-fns
  {'(fn [old params] params) (fn [old params] params)
   '(fn [old params] (inc old)) (fn [old params] (inc old))
   '(fn [old params] (dec old)) (fn [old params] (dec old))
   '+ +})

(defn start-local []
  (go-try
   (let [local-store (<? (new-mem-store))
         err-ch (chan)
         log-atom (atom {})
         local-peer (client-peer "CLJS CLIENT" local-store err-ch
                                 (comp (partial logger log-atom :local-core)
                                       (partial fetch local-store err-ch)))
         stage (<? (create-stage! "kordano@replikativ.io" local-peer err-ch eval-fns))
         _ (<? (s/create-repo! stage :description "testing" :id repo-id))
         _ (go-loop [e (<? err-ch)]
            (when e
              (.log js/console "ERROR:" e)
              (recur (<? err-ch))))]
     {:store local-store
      :stage stage
      :log log-atom
      :error-chan err-ch
      :peer local-peer})))




(comment
  ;; jack in figwheel cljs REPL
  (require 'figwheel-sidecar.repl-api)
  (figwheel-sidecar.repl-api/cljs-repl)

  (go-try
   (def client-state (<? (start-local)))
   (<? (connect! (:stage client-state) uri))
   (<? (s/transact (:stage client-state)
                   ["kordano@replikativ.io" repo-id "master"]
                   '(fn [old params] params)
                   666))
   (<? (s/commit! (:stage client-state) {"kordano@replikativ.io" {repo-id #{"master"}}}))


   (<? (timeout 2000))
   (println "commit graph" (get @(:stage client-state) "kordano@replikativ.io")
            (-> client-state :store :state deref (get ["kordano@replikativ.io" repo-id]))))


  (go-try (def client-state (<? (start-local))))

  (go-try (<? (connect! (:stage client-state) uri)))

  (go-try (<? (subscribe-crdts! (:stage client-state) {"kordano@replikativ.io" {repo-id #{"master"}}})))

  (keys (get @(:stage client-state) "kordano@replikativ.io"))

  (println (-> client-state :stage deref :config))

  (println (-> client-state :log deref))

  (-> client-state :store :state deref (get ["kordano@replikativ.io" repo-id]) :state :commit-graph)


  (->> client-state :store :state deref keys (filter vector?))

  (-> client-state :store :state deref (get ["kordano@replikativ.io" repo-id]) :state :commit-graph count)

  (go-try
   (<? (s/transact (:stage client-state)
                   ["kordano@replikativ.io" repo-id "master"]
                   '(fn [old params] params)
                   999)))


  (-> client-state :stage deref (get-in ["kordano@replikativ.io" repo-id :prepared]) println)

  (println (:stage client-state))

  (go-try
   (<? (s/commit! (:stage client-state) {"kordano@replikativ.io" {repo-id #{"master"}}})))

  (go-try
   (<? (s/transact (:stage client-state)
                   ["kordano@replikativ.io" repo-id "master"]
                   '(fn [old params] params)
                   666)))

  (go-try
   (<? (s/commit! (:stage client-state) {"kordano@replikativ.io" {repo-id #{"master"}}})))

  )
