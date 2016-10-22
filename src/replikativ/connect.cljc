(ns replikativ.connect
  "Connection management middleware."
  (:require [replikativ.environ :refer [*id-fn*]]
            [replikativ.core :refer [wire]]
            [kabel.peer :refer [drain]]
            [konserve.core :as k]
            [kabel.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            #?(:clj [superv.async :refer [<? <<? go-try go-loop-try alt?]])
            #?(:clj [superv.lab :refer [go-for go-loop-super go-super
                                        restarting-supervisor]]
               :cljs [superv.lab :refer [restarting-supervisor]])
            [kabel.client :refer [client-connect!]]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [superv.async :refer [<<? <? go-for go-try go-loop-try alt?]]
                            [superv.lab :refer [go-for go-loop-super go-super]])))


(defn handle-connection-request
  "Service connection requests. Waits for ack on initial subscription,
  also ensuring you have the remote state of your subscriptions
  locally replicated."
  [peer conn-ch out]
  (let [{{S :supervisor} :volatile} @peer]
    (go-loop-super S [{:keys [url id retries] :as c} (<? S conn-ch)]
                   ;; keep connection scope for reconnects
                   (when c
                     (restarting-supervisor
                      (fn [S]
                        (go-super S
                                  (info {:event :connecting-to :peer (:id @peer) :url url})
                                  (let [{{:keys [log middleware]
                                          {:keys [read-handlers write-handlers] :as store} :cold-store} :volatile
                                         pn :id} @peer
                                        subs (<? S (k/get-in store [:peer-config :sub :subscriptions]))
                                        [c-in c-out] (<? S (client-connect! S url id
                                                                            read-handlers
                                                                            write-handlers))
                                        subed-ch (chan)
                                        sub-id (*id-fn*)

                                        new-out (chan)
                                        p (pub new-out (fn [{:keys [type]}]
                                                         (or ({:sub/identities-ack :sub/identities-ack} type)
                                                             :unrelated)))]
                                    ;; handshake
                                    (sub p :sub/identities-ack subed-ch)
                                    (sub p :sub/identities-ack c-out)
                                    (sub p :unrelated c-out)
                                    ((comp drain wire middleware) [peer [c-in new-out]])
                                    (>! c-out {:type :sub/identities
                                               :identities subs
                                               :id sub-id
                                               :extend? (<? S (k/get-in store [:peer-config :sub :extend?]))})
                                    ;; wait for ack on backsubscription
                                    (<? S (go-loop-try S [{id :id :as c} (<? S subed-ch)]
                                                       (debug {:event :connect-backsubscription
                                                               :sub-id sub-id :ack-msg c})
                                                       (when (and c (not= id sub-id))
                                                         (recur (<? S subed-ch)))))
                                    (async/close! subed-ch)

                                    (>! out {:type :connect/peer-ack
                                             :url url
                                             :id id
                                             :peer-id (:sender c)}))))
                         :delay (* 60 1000)
                         :retries retries
                         :log-fn (fn [level msg]
                                   (case level
                                     :error (error msg)
                                     :warn (warn msg)
                                     :debug (debug msg)
                                     :info (info msg)
                                     (debug msg)))))
                   (recur (<? S conn-ch)))))

(defn connect
  [[peer [in out]]]
  (let [new-in (chan)
        {{S :supervisor} :volatile} @peer]
    (go-try S (let [p (pub in (fn [{:keys [type]}]
                                (or ({:connect/peer :connect/peer} type)
                                    :unrelated)))
                    conn-ch (chan)]

                (sub p :connect/peer conn-ch)
                (handle-connection-request peer conn-ch out)

                (sub p :unrelated new-in true)))
    [peer [new-in out]]))
