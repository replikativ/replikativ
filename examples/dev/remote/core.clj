(ns dev.remote.core
  (:require [konserve.memory :refer [new-mem-store]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.platform-log :refer [warn info debug]]
            [replikativ.crdt.cdvcs.realize :refer [head-value]]
            [kabel.middleware.log :refer [logger]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [kabel.platform :refer [create-http-kit-handler! start stop]]
            [replikativ.crdt.cdvcs.stage :as s]
            [replikativ.stage :refer [create-stage! connect! subscribe-crdts!]]
            [full.async :refer [<?? <? go-try go-loop-try]]
            [clojure.core.async :refer [chan go-loop go]]
            [replikativ.peer :refer [client-peer server-peer]]))

(def uri "ws://127.0.0.1:31744")

(def cdvcs-id #uuid "8e9074a1-e3b0-4c79-8765-b6537c7d0c44")

;; we allow you to model the state efficiently as a reduction over function applications
;; for this to work you supply an "eval"-like mapping to the actual functions
(def eval-fns
  {'(fn [old params] params) (fn [old params] params) ;
   '(fn [old params] (inc old)) (fn [old params] (inc old))
   '(fn [old params] (dec old)) (fn [old params] (dec old))
   '+ +})

;; start a full-blown websocket server
(defn start-server []
  (let [err-ch (chan)
        handler (create-http-kit-handler! uri err-ch)
        remote-store (<?? (new-mem-store))
        _ (go-loop [e (<? err-ch)]
            (when e
              (warn "ERROR:" e)
              (recur (<? err-ch))))
        remote-peer (server-peer handler "REMOTE"
                                 remote-store err-ch
                                 :middleware (partial fetch remote-store (atom {}) err-ch))
        stage (<?? (create-stage! "eve@replikativ.io" remote-peer err-ch eval-fns))
        rp (<?? (s/create-cdvcs! stage :description "testing" :id cdvcs-id))
        state {:store remote-store
               :stage stage
               :cdvcs rp
               :peer remote-peer}]
    (start remote-peer)
    state))

;; for debugging purposes you can track all messages passing through a middleware
(def log-atom (atom {}))

;; start a client-side peer only, similar to javascript in the browser
(defn start-client []
  (go-try
   (let [local-store (<? (new-mem-store))
         err-ch (chan)
         local-peer (client-peer "CLJ CLIENT" local-store err-ch
                                 :middleware (comp (partial logger log-atom :local-core)
                                                   (partial fetch local-store (atom {}) err-ch)))
         stage (<? (create-stage! "eve@replikativ.io" local-peer err-ch eval-fns))
         _ (go-loop [e (<? err-ch)]
             (when e
               (info "ERROR:" e)
               (recur (<? err-ch))))]
     {:store local-store
      :stage stage
      :error-chan err-ch
      :peer local-peer})))


(comment
  (def server-state (start-server))

  (<?? (subscribe-crdts! (:stage server-state) {"eve@replikativ.io" #{cdvcs-id}}))

  (<?? (s/transact (:stage server-state)
                   ["eve@replikativ.io" cdvcs-id "master"]
                   '(fn [old params] params)
                   42))

  (<?? (s/commit! (:stage server-state) {"eve@replikativ.io" #{cdvcs-id}}))

  (-> server-state :store :state deref (get ["eve@replikativ.io" cdvcs-id]) :state :commit-graph count)
  ;; => 2 (one initial and one for 42)

  (<?? (head-value (:store server-state)
                   eval-fns
                   (-> server-state :store :state deref
                       (get ["eve@replikativ.io" cdvcs-id]) :state)))
  ;; => 42

  ;; stop server
  ((-> server-state :peer deref :volatile :server))



  ;; now lets get distributed :)
  (def client-state (<?? (start-client)))

  (<?? (connect! (:stage client-state) uri))

  (<?? (subscribe-crdts! (:stage client-state) {"eve@replikativ.io" #{cdvcs-id}}))


  (<?? (s/transact (:stage client-state)
                   ["eve@replikativ.io" cdvcs-id]
                   '+
                   5))

  (<?? (s/commit! (:stage client-state) {"eve@replikativ.io" #{cdvcs-id}}))

  ;; check value on all peers

  (<?? (head-value (:store client-state)
                   eval-fns
                   (-> server-state :store :state deref
                       (get ["eve@replikativ.io" cdvcs-id]) :state)))
  ;; => 47


  (<?? (head-value (:store server-state)
                   eval-fns
                   (-> server-state :store :state deref
                       (get ["eve@replikativ.io" cdvcs-id]) :state)))
  ;; => 47



  )
