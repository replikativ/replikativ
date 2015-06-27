(ns doc.stage
  (:require [clojure.core.async :refer [go]]
            [midje.sweet :refer :all]
            [konserve.filestore :refer [new-fs-store]]
            [replikativ.stage :refer [create-stage! connect! subscribe-repos!]]
            [replikativ.crdt.repo.stage :as s]
            [replikativ.crdt.repo.repo :as repo]
            [replikativ.core :refer [server-peer]]
            [replikativ.platform :refer [<!? create-http-kit-handler! start stop]]
            [replikativ.p2p.block-detector :refer [block-detector]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.p2p.fetch :refer [fetch]]
            [taoensso.timbre :as timber]))

(timber/refer-timbre)

(defn init-repo [config]
  (let [{:keys [user repo branches store remote peer]} config
        store (<!? (new-fs-store store))
        peer-server (server-peer (create-http-kit-handler! peer) ;; TODO client-peer?
                                 store
                                 (comp (partial block-detector :peer-core)
                                       (partial fetch store)
                                       ensure-hash
                                       (partial block-detector :p2p-surface)))
        stage (<!? (create-stage! user peer-server eval))
        res {:store store
             :peer peer-server
             :stage stage
             :id repo}]

    (when-not (= peer :client)
      (start peer-server))

    (when remote
      (<!? (connect! stage remote)))

    #_(<!? (s/create-repo! stage "Profiling experiments." :id repo))

    #_(when repo
      (<!? (subscribe-repos! stage {user {repo branches}})))
    res))


(comment
  (def state (init-repo {:store "repo/store"
                         :peer "ws://127.0.0.1:41744"
                         :user "profiler@topiq.es"
                         :repo #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487"
                         :branches #{"master"}}))

  (stop (:peer state))

  (def stage (:stage state))

  (<!? (s/create-repo! stage "Profiling experiments." :id #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487"))

  (stop (:peer state))
  (timber/set-level! :warn)

  (doseq [n (range 3e5)]
    (println "Iteration:" n)
    (<!? (s/transact stage ["profiler@topiq.es" (:id state) "master"] 'conj
                     {:id n
                      :val (range 1024)}))
    (time (<!? (s/commit! stage {"profiler@topiq.es" {(:id state) #{"master"}}}))))
  )
