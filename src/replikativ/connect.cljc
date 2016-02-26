(ns replikativ.connect
  "Connection management middleware."
  (:require [replikativ.environ :refer [*id-fn*]]
            [replikativ.core :refer [wire get-error-ch]]
            [kabel.peer :refer [drain]]
            [konserve.core :as k]
            [replikativ.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            #?(:clj [full.async :refer [<? <<? go-for go-try go-try> go-loop-try go-loop-try> alt?]])
            [kabel.platform :refer [client-connect!] :include-macros true]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [full.cljs.async :refer [<<? <? go-for go-try go-try> go-loop-try go-loop-try> alt?]])))


;; TODO simplify with new error management
(defn handle-connection-request
  "Service connection requests."
  [peer conn-ch out]
  (go-loop-try> (get-error-ch peer)
                [{:keys [url id reconnect?] :as c} (<? conn-ch)]
                ;; keep connection scope for reconnects
                (when c
                  ((fn connection []
                     (go-try
                      (try
                        (info (:name @peer) "connecting to:" url)
                        (let [{{:keys [log middleware]
                                {:keys [read-handlers write-handlers]} :store
                                [bus-in bus-out] :chans} :volatile
                               pn :name
                               subs :subscriptions} @peer
                              conn-err-ch (chan)
                              _ (async/take! conn-err-ch (fn [e]
                                                           (go-try
                                                            (warn "connection failed:" e)
                                                            (<? (timeout (* 60 1000)))
                                                            (when reconnect?
                                                              (debug "retrying to connect")
                                                              (connection)))))
                              [c-in c-out] (<? (client-connect! url
                                                                conn-err-ch
                                                                (:id @peer)
                                                                read-handlers
                                                                write-handlers))
                              subed-ch (chan)
                              sub-id (*id-fn*)]
                          ;; handshake
                          (sub bus-out :sub/identities-ack subed-ch)
                          ((comp drain wire middleware) [peer [c-in c-out]])
                          (>! c-out {:type :sub/identities :identities subs :peer pn :id sub-id})
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
                                   :error e}))))))
                  (recur (<? conn-ch)))))

(defn connect
  [[peer [in out]]]
  (let [new-in (chan)]
    (go-try (let [p (pub in (fn [{:keys [type]}]
                              (or ({:connect/peer :connect/peer} type)
                                  :unrelated)))
                  {:keys [chans]} (:volatile @peer)
                  [bus-in bus-out] chans
                  conn-ch (chan)]

              (sub p :connect/peer conn-ch)
              (handle-connection-request peer conn-ch out)

              (sub p :unrelated new-in true)))
    [peer [new-in out]]))
