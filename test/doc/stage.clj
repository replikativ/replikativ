(ns doc.stage
  (:require [full.async :refer [<?? <?]]
            [clojure.core.async :refer [chan go-loop <!]]
            [midje.sweet :refer :all]
            [konserve.filestore :refer [new-fs-store]]
            [konserve.memory :refer [new-mem-store]]
            [replikativ.stage :refer [create-stage! connect! subscribe-crdts!]]
            [replikativ.crdt.cdvcs.stage :as s]
            [replikativ.crdt.cdvcs.repo :as repo]
            [replikativ.core :refer [server-peer]]
            [replikativ.platform :refer [create-http-kit-handler! start stop]]
            [replikativ.p2p.block-detector :refer [block-detector]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.p2p.fetch :refer [fetch]]
            [taoensso.timbre :as timber]))

(timber/refer-timbre)

(defn init-repo [config]
  (let [{:keys [user repo branches store remote peer]} config
        store (<?? (new-fs-store store) #_(new-mem-store))
        err-ch (chan)
        _ (go-loop [e (<! err-ch)]
            (when e
              (warn "ERROR:" e)
              (.printStackTrace e)
              (recur (<! err-ch))))
        peer-server (server-peer (create-http-kit-handler! peer err-ch) "LOCAL PEER"
                                 store err-ch
                                 (comp (partial block-detector :peer-core)
                                       (partial fetch store err-ch)
                                       ensure-hash
                                       (partial block-detector :p2p-surface)))
        stage (<?? (create-stage! user peer-server err-ch eval))
        res {:store store
             :peer peer-server
             :stage stage
             :id repo}]

    (when-not (= peer :client)
      (start peer-server))

    (when remote
      (<?? (connect! stage remote)))

    #_(<?? (s/create-repo! stage "Profiling experiments." :id repo))

    #_(when repo
      (<?? (subscribe-crdts! stage {user {repo branches}})))
    res))


(comment
  (timber/set-level! :warn)

  (def state (init-repo {:store "repo/store"
                         :peer "ws://127.0.0.1:41745"
                         :user "mail:profiler@topiq.es"
                         :repo #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487"
                         :branches #{"master"}}))

  (stop (:peer state))

  (def stage (:stage state))

  (<?? (s/create-repo! stage
                       :description "Profiling experiments."
                       :id #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487"))

  (stop (:peer state))

  ;; TODO fix description
  (require '[konserve.protocols :refer [-get-in]])
  (let [h (<?? (-get-in (:store state) [["mail:profiler@topiq.es"
                                         #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487"] :state :history]))
        hs (<?? (-get-in (:store state) [h]))]
    (for [h hs
          :let [c (count (<?? (-get-in (:store state) [h])))]
          :when (not= c 100)]
      c)) ;; '()

  (count (get-in @stage ["mail:profiler@topiq.es" #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487" :state :commit-graph])) ;; 100001

  (get-in @stage ["mail:profiler@topiq.es" #uuid "cda8bb59-6a0a-4fbd-85d9-4a7f56eb5487" :state :branches])

  (keys (get-in @stage [:volatile :peer]))

  (require '[taoensso.timbre.profiling :as profiling :refer (pspy pspy* profile defnp p p*)])
  (def commit-latency
    (future
      (doall
       (for [n (range 1e4)]
         (let [start-ts (.getTime (java.util.Date.))]
           (when (= (mod n 100) 0) (println "Iteration:" n))
           (<?? (s/transact stage ["mail:profiler@topiq.es" (:id state) "master"] 'conj
                            {:id n
                             :val (range 100)}))
           (if (= (mod n 100) 0)
             (time (<?? (s/commit! stage {"mail:profiler@topiq.es" {(:id state) #{"master"}}})))
             (<?? (s/commit! stage {"mail:profiler@topiq.es" {(:id state) #{"master"}}})))
           (- (.getTime (java.util.Date.)) start-ts))))))

  (spit "commit-latency-benchmark-1e5.edn" (vec @commit-latency)))
