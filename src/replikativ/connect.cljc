(ns replikativ.connect
  "Connection management middleware."
  (:require [replikativ.environ :refer [*id-fn*]]
            [replikativ.core :refer [wire]]
            [kabel.peer :refer [drain]]
            [konserve.core :as k]
            #?(:clj [kabel.platform-log :refer [debug info warn error]])
            [clojure.set :as set]
            #?(:clj [superv.async :refer [<? <<? go-try go-loop-try alt? >?
                                          go-for go-loop-super go-super
                                          restarting-supervisor]]
               :cljs [superv.async :refer [restarting-supervisor]])
            [kabel.client :refer [client-connect!]]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [superv.async :refer [<<? <? go-for go-try go-loop-try alt? >?
                                                  go-for go-loop-super go-super]]
                            [kabel.platform-log :refer [debug info warn error]])))


(defn handshake-middleware [url id subs extend? out [S peer [c-in c-out]]]
  (let [new-out (chan)
        subed-ch (chan)
        sub-id (*id-fn*)
        ;; we intercept and track the subscription process
        p (pub new-out (fn [{:keys [type] :as m}]
                         #_(prn "SENDING NEW_OUT" m)
                         (or ({:sub/identities-ack :sub/identities-ack} type)
                             :unrelated)))]
    (sub p :sub/identities-ack subed-ch)
    ;; pass through
    (sub p :sub/identities-ack c-out)
    (sub p :unrelated c-out)

    ;; start handshake
    (go-try S
      (try
        (>? S c-out {:type :sub/identities
                     :identities subs
                     :id sub-id
                     :extend? extend?})
        (debug {:event :connect-started-handshake :sub-id sub-id})


        ;; wait for ack on backsubscription
        (<? S (go-loop-try S [{id :id :as c} (<? S subed-ch)]
                (debug {:event :connect-backsubscription
                        :sub-id sub-id :ack-msg c})
                (when (and c (not= id sub-id))
                  (recur (<? S subed-ch)))))
        (async/close! subed-ch)

        ;; notify initiator of the connection
        (>? S out {:type :connect/peer-ack
                   :url url
                   :close-ch c-in
                   :id id})
        (catch #?(:clj Exception :cljs js/Error) e
            (>? S out {:type :connect/peer-ack
                       :url url
                       :id id}))))
    [S peer [c-in new-out]]))

(defn handle-connection-request
  "Service connection requests. Waits for ack on initial subscription,
  also ensuring you have the remote state of your subscriptions
  locally replicated."
  [S peer conn-ch out]
  (go-loop-super S [{:keys [url id retries] :as c} (<? S conn-ch)]
    ;; keep connection scope for reconnects
    (when c
      (restarting-supervisor
       (fn [S]
         (go-super S
           (info {:event :connecting-to :peer (:id @peer) :url url})
           (let [{{:keys [log middleware serialization-middleware]
                   {:keys [read-handlers write-handlers] :as store} :cold-store} :volatile
                  pn :id} @peer
                 subs (<? S (k/get-in store [:peer-config :sub :subscriptions]))
                 extend? (<? S (k/get-in store [:peer-config :sub :extend?]))]
             (debug {:event :connection-pending :url url})

             ;; build middleware pipeline with channel pair
             ;; from client-connect
             (->> [S peer (<? S (client-connect! S url id
                                                 read-handlers
                                                 write-handlers))]
                  serialization-middleware
                  (handshake-middleware url id subs extend? out)
                  middleware
                  wire
                  drain))))
       :delay (* 10 1000)
       :retries retries
       :supervisor S
       :log-fn (fn [level msg]
                 (case level
                   :error (error msg)
                   :warn (warn msg)
                   :debug (debug msg)
                   :info (info msg)
                   (debug msg)))))
    (recur (<? S conn-ch))))

(defn connect
  [[S peer [in out]]]
  (let [new-in (chan)]
    (go-try S
      (let [p (pub in (fn [{:keys [type]}]
                        (or ({:connect/peer :connect/peer} type)
                            :unrelated)))
            conn-ch (chan)]

        (sub p :connect/peer conn-ch)
        (handle-connection-request S peer conn-ch out)

        (sub p :unrelated new-in true)))
    [S peer [new-in out]]))
