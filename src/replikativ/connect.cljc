(ns replikativ.connect
  "Connection management middleware."
  (:require [replikativ.environ :refer [*id-fn*]]
            [replikativ.core :refer [wire]]
            [kabel.peer :refer [drain]]
            [konserve.core :as k]
            [kabel.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            #?(:clj [full.async :refer [<? <<? go-try go-loop-try alt?]])
            #?(:clj [full.lab :refer [go-for go-loop-super go-super
                                      restarting-supervisor with-super]])
            [kabel.http-kit :refer [client-connect!] :include-macros true]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [full.async :refer [<<? <? go-for go-try go-loop-try alt?]]
                            [full.lab :refer [go-for go-loop-super]])))


;; TODO simplify with new error management
(defn handle-connection-request
  "Service connection requests. Waits for ack on initial subscription,
  also ensuring you have the remote state of your subscriptions
  locally replicated."
  [peer conn-ch out]
  (go-loop-super [{:keys [url id retries] :as c} (<? conn-ch)]
                 ;; keep connection scope for reconnects
                 (when c
                   #?(:clj
                      (restarting-supervisor
                       (fn []
                         (go-super
                          (info (:id @peer) "connecting to:" url)
                          (let [{{:keys [log middleware]
                                  {:keys [read-handlers write-handlers] :as store} :cold-store} :volatile
                                 pn :id} @peer
                                subs (<? (k/get-in store [:peer-config :sub :subscriptions]))
                                [c-in c-out] (<? (client-connect! url (chan) id
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
                                       :extend? (<? (k/get-in store [:peer-config :sub :extend?]))})
                            ;; TODO wait for ack on backsubscription, is there a simpler way?
                            (<? (go-loop-try [{id :id :as c} (<? subed-ch)]
                                             (debug "connect: backsubscription?" sub-id c)
                                             (when (and c (not= id sub-id))
                                               (recur (<? subed-ch)))))
                            (async/close! subed-ch)

                            (>! out {:type :connect/peer-ack
                                     :url url
                                     :id id
                                     :peer-id (:sender c)}))))
                       :delay (* 60 1000)
                       :retries retries)

                      :cljs ;; kept until cljs binding issue resolved
                      ((fn connection []
                         (go-try
                          (try
                            (info (:id @peer) "connecting to:" url)
                            (let [{{:keys [log middleware]
                                    {:keys [read-handlers write-handlers] :as store} :cold-store} :volatile
                                   pn :id} @peer
                                  subs (<? (k/get-in store [:peer-config :sub :subscriptions]))
                                  reconnect-fn (fn [e]
                                                 (go-try
                                                  (warn "connection failed:" e)
                                                  (<? (timeout (* 60 1000)))
                                                  (when (pos? retries) ;; TODO always retries atm.
                                                    (debug "retrying to connect")
                                                    (connection))))
                                  conn-err-ch (chan)
                                  _ (async/take! conn-err-ch reconnect-fn)

                                  [c-in c-out] (<? (client-connect! url conn-err-ch id
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
                                         :extend? (<? (k/get-in store [:peer-config :sub :extend?]))})
                              ;; HACK? wait for ack on backsubscription, is there a simpler way?
                              (<? (go-loop-try [{id :id :as c} (<? subed-ch)]
                                               (debug "connect: backsubscription?" sub-id c)
                                               (when (and c (not= id sub-id))
                                                 (recur (<? subed-ch)))))
                              (async/close! subed-ch)

                              (>! out {:type :connect/peer-ack
                                       :url url
                                       :id id
                                       :peer-id (:sender c)}))
                            (catch #?(:clj Throwable :cljs js/Error) e
                              (>! out {:type :connect/peer-ack
                                       :url url
                                       :id id
                                       :error e})))))))
                   (recur (<? conn-ch)))))

(defn connect
  [[peer [in out]]]
  (let [new-in (chan)]
    (go-try (let [p (pub in (fn [{:keys [type]}]
                              (or ({:connect/peer :connect/peer} type)
                                  :unrelated)))
                  conn-ch (chan)]

              (sub p :connect/peer conn-ch)
              (handle-connection-request peer conn-ch out)

              (sub p :unrelated new-in true)))
    [peer [new-in out]]))
