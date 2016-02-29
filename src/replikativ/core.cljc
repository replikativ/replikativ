(ns replikativ.core
  "Replication related pub-sub protocols."
  (:require [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.protocols :refer [-downstream]]
            [kabel.peer :refer [drain]]
            [konserve.core :as k]
            [replikativ.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            [clojure.data :refer [diff]]
            #?(:clj [full.async :refer [<? <<? go-for go-try go-try> go-loop-try go-loop-try> alt?]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [full.cljs.async :refer [<<? <? go-for go-try go-try> go-loop-try go-loop-try> alt?]])))


(defn get-error-ch [peer]
  (get-in @peer [:volatile :error-ch]))


(defn- publish-out
  "Reply to publications by sending an update value filtered to subscription."
  [store error-ch pub-ch out identities remote-pn]
  (go-try> error-ch
           (debug "initial state publication:" identities)
           (<<? (go-for [[user crdts] identities
                         id crdts
                         :let [{:keys [crdt state]} (<? (k/get-in store [[user id]]))
                               state (into {} state)]
                         :when (not (empty? state))]
                        (>! out {:user user
                                 :crdt-id id
                                 :type :pub/downstream
                                 :id (*id-fn*)
                                 :downstream {:crdt crdt
                                              :method :new-state
                                              :op state}})))

           (go-loop-try> error-ch
                         [{:keys [downstream id user crdt-id] :as p} (<? pub-ch)]
                         (if-not p
                           (info "publication-loop ended for " identities)
                           (do
                             (when (get-in identities [user crdt-id])
                               (info "publication-loop: sending " p "to" remote-pn)
                               (>! out p))
                             (recur (<? pub-ch)))))))


(defn subscribe-out [remote-pn error-ch remote-subs sub-out-ch out extend?]
  (go-loop-try> error-ch
                [{:keys [identities] :as s} (<? sub-out-ch)
                 old-subs nil]
                (when s
                  (let [new-subs (if extend? identities
                                     (let [[_ _ common-subs] (diff identities remote-subs)]
                                       common-subs))]
                    (when-not (= new-subs old-subs)
                      (debug remote-pn "subscribing to " new-subs)
                      (>! out (assoc s :identities new-subs)))
                    (recur (<? sub-out-ch) new-subs)))))

(defn subscribe-in
  "Adjust publication stream and propagate subscription requests."
  [peer store sub-ch out]
  (let [{:keys [chans log]} (-> @peer :volatile)
        [bus-in bus-out] chans
        pn (:id @peer)
        err-ch (get-error-ch peer)
        sub-out-ch (chan)]
    (go-loop-try> err-ch
                  [{:keys [identities id extend?] :as s} (<? sub-ch)
                   old-identities nil
                   old-pub-ch nil
                   old-sub-ch nil]
                  (if s
                    (if (= old-identities identities)
                      (recur (<? sub-ch) old-identities old-pub-ch old-sub-ch)
                      (let [[old-subs new-subs]
                            (<? (k/update-in store
                                             [:peer-config :sub :subscriptions]
                                             ;; TODO filter here
                                             (fn [old] (merge-with set/union old identities))))

                            remote-pn (:sender s)
                            pub-ch (chan)
                            sub-out-ch (chan)
                            extend-me? (true? (<? (k/get-in store [:peer-config :sub :extend?])))]
                        (info pn "subscribe: starting subscription " id " from " remote-pn)
                        (debug pn "subscribe: subscriptions " identities)

                        (when old-pub-ch
                          (unsub bus-out :pub/downstream old-pub-ch)
                          (close! old-pub-ch))
                        (sub bus-out :pub/downstream pub-ch)
                        (publish-out store err-ch pub-ch out identities remote-pn)

                        (when old-sub-ch
                          (unsub bus-out :sub/identities old-sub-ch)
                          (close! old-sub-ch))
                        (sub bus-out :sub/identities sub-out-ch)
                        (subscribe-out remote-pn err-ch identities sub-out-ch out extend?)

                        (let [msg {:type :sub/identities
                                   :identities new-subs
                                   :id id
                                   :extend? extend-me?}]
                          (when (= new-subs old-subs)
                            (debug "ensure back-subscription")
                            (>! sub-out-ch msg))
                          (when (not (= new-subs old-subs))
                            (debug "notify all peers of changed subscription")
                            (alt? [[bus-in msg]]
                                  :wrote

                                  (timeout 5000)
                                  ;; TODO disconnect peer
                                  (throw (ex-info "bus-in was blocked. Subscription broken."
                                                  {:type :bus-in-block
                                                   :failed-put msg
                                                   :was-blocked-by (<? bus-in)})))))

                        (>! out {:type :sub/identities-ack :id id})
                        (info pn "subscribe: finishing " id)

                        (recur (<? sub-ch) identities pub-ch sub-out-ch)))
                    (do (info "subscribe: closing old-pub-ch")
                        (unsub bus-out :pub/downstream old-pub-ch)
                        (unsub bus-out :sub/identities out)
                        (when old-pub-ch (close! old-pub-ch)))))))


(defn commit-pub [store [user crdt-id] pub]
  (k/update-in store [[user crdt-id]]
               (fn [{:keys [description public state crdt]}]
                 (let [state (or state (key->crdt (:crdt pub)))]
                   {:crdt (or crdt (:crdt pub))
                    :description (or description
                                     (:description pub))
                    :public (or (:public pub) public false)
                    :state (-downstream state (:op pub))}))))


(defn publish-in
  "Synchronize downstream publications."
  [peer store pub-ch bus-in out]
  (go-loop-try> (get-error-ch peer)
                [{:keys [downstream id crdt-id user] :as p} (<? pub-ch)]
                (when p
                  (let [pn (:id @peer)]
                    (info pn "publish: " p)
                    (let [[old-state new-state] (<? (commit-pub store [user crdt-id] downstream))]
                      (>! out {:type :pub/downstream-ack
                               :user user
                               :crdt-id crdt-id
                               :id id})
                      (when (not= old-state new-state)
                        (info pn "publish: downstream ops" p)
                        (alt? [[bus-in p]]
                              (debug pn "publish: sent new downstream ops")

                              (timeout 5000) ;; TODO make tunable
                              (throw (ex-info "bus-in was blocked. Subscription broken."
                                              {:type :bus-in-block
                                               :failed-put p
                                               :was-blocked-by (<? bus-in)}))))))
                  (recur (<? pub-ch)))))



(defn wire
  "Wire a peer to an output (response) channel and a publication by :type of the input."
  [[peer [in out]]]
  (let [new-in (chan)]
    (go-try (let [p (pub in (fn [{:keys [type]}]
                              (or ({:sub/identities :sub/identities
                                    :pub/downstream :pub/downstream} type)
                                  :unrelated)))
                  {:keys [store chans log]} (:volatile @peer)
                  name (:name @peer)
                  [bus-in bus-out] chans
                  pub-in-ch (chan)
                  sub-in-ch (chan)]

              (sub p :sub/identities sub-in-ch)
              (subscribe-in peer store sub-in-ch out)

              (sub p :pub/downstream pub-in-ch)
              (publish-in peer store pub-in-ch bus-in out)

              (sub p :unrelated new-in true)))
    [peer [new-in out]]))
