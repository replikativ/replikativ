(ns replikativ.stage
  "A stage allows to execute upstream operations of each CRDT and
  communicates them downstream to a peer through
  synchronous (blocking) operations."
  (:require [konserve.core :as k]
            [replikativ.core :refer [wire]]
            [replikativ.protocols :refer [PHasIdentities -identities -downstream]]
            [replikativ.environ :refer [*id-fn* store-blob-trans-id store-blob-trans-value]]
            [replikativ.crdt.materialize :refer [pub->crdt]]
            [kabel.middleware.block-detector :refer [block-detector]]
            [replikativ.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [<? <<? go-for go-try go-loop-try go-loop-try> alt?]])
            [hasch.core :refer [uuid]]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close! alt!]]
                    :cljs [cljs.core.async :as async
                           :refer [>! timeout chan put! sub unsub pub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [alt!]]
                            [full.cljs.async :refer [<? <<? go-for go-try go-loop-try go-loop-try> alt?]])))


(defn sync!
  "Synchronize (push) the results of an upstream CRDT command with storage and other peers.
This the update of the stage is not executed synchronously. Returns go
  block to synchronize."
  [stage-val upstream]
  (go-try (let [{:keys [id]} (:config stage-val)
                [p out] (get-in stage-val [:volatile :chans])
                fch (chan)
                bfch (chan)
                pch (chan)
                sync-id (*id-fn*)
                new-values (reduce merge {} (for [[u crdts] upstream
                                                  [r branches] crdts
                                                  b branches]
                                              (get-in stage-val [u r :new-values b])))

                pubs (reduce #(assoc-in %1 %2 (get-in stage-val (concat %2 [:downstream])))
                             {}
                             (for [[u crdts] upstream
                                   [id crdt] crdts
                                   :when (or (= (get-in stage-val [u id :stage/op]) :pub)
                                             (= (get-in stage-val [u id :stage/op]) :sub))]
                               [u id]))
                ferr-ch (chan)]
            (sub p :pub/downstream-ack pch)
            (sub p :fetch/edn fch)
            (go-loop-try> ferr-ch [to-fetch (:ids (<? fch))]
                          (when to-fetch
                            (debug "fetching edn from stage" to-fetch)
                            (>! out {:type :fetch/edn-ack
                                     :values (select-keys new-values to-fetch)
                                     :id sync-id
                                     :peer id})
                            (recur (:ids (<? fch)))))

            (sub p :fetch/binary bfch)
            (go-loop-try> ferr-ch []
                          (let [to-fetch (:blob-id (<? bfch))]
                            (when to-fetch
                              (debug "fetching blob from stage" to-fetch)
                              (>! out {:type :fetch/binary-ack
                                       :value (get new-values to-fetch)
                                       :blob-id sync-id
                                       :id sync-id
                                       :peer id})
                              (recur))))
            (when-not (empty? pubs)
              (>! out (with-meta {:type :pub/downstream :downstream pubs :id sync-id :peer id}
                        {:host ::stage})))

            (loop []
              (alt! pch
                    ([_])
                    ferr-ch
                    ([e] (throw e))
                    (timeout 60000)
                    ([_]
                     (warn "No pub/downstream-ack received after 60 secs. Continue waiting..." upstream)
                     (recur))))


            (unsub p :pub/downstream-ack pch)
            (unsub p :fetch/edn fch)
            (unsub p :fetch/binary fch)
            (close! ferr-ch)
            (close! fch)
            (close! bfch))))


(defn cleanup-ops-and-new-values! [stage upstream]
  (swap! stage (fn [old] (reduce #(-> %1
                                     (update-in (butlast %2) dissoc :stage/op)
                                     (assoc-in (concat (butlast %2) [:new-values (last %2)]) {}))
                                old
                                (for [[user crdts] upstream
                                      [id branches] crdts
                                      b branches]
                                  [user id b]))))
  nil)



(defn connect!
  "Connect stage to a remote url of another peer,
e.g. ws://remote.peer.net:1234/replikativ/ws. Returns go block to
synchronize."
  [stage url]
  (let [[p out] (get-in @stage [:volatile :chans])
        connedch (chan)
        connection-id (uuid)]
    (sub p :connect/peer-ack connedch)
    (put! out {:type :connect/peer
               :url url
               :id connection-id})
    (go-loop-try [{id :id e :error} (<? connedch)]
                 (when id
                   (if-not (= id connection-id)
                     (recur (<? connedch))
                     (do (unsub p :connect/peer-ack connedch)
                         (when e (throw e))
                         (info "connect!: connected " url)))))))


(defn create-stage!
  "Create a stage for user, given peer and a safe evaluation function
for the transaction functions.  Returns go block to synchronize."
  [user peer err-ch eval-fn]
  (go-try (let [in (chan)
                out (chan)
                middleware (-> @peer :volatile :middleware)
                p (pub in :type)
                pub-ch (chan)
                stage-id (str "STAGE-" user (subs (str (uuid)) 0 4))
                {:keys [store]} (:volatile @peer)
                stage (atom {:config {:id stage-id
                                      :user user}
                             :volatile {:chans [p out]
                                        :peer peer
                                        :eval-fn eval-fn
                                        :err-ch err-ch}})]
            (<? (k/assoc-in store [store-blob-trans-id] store-blob-trans-value))
            (wire (middleware (block-detector stage-id [peer [out in]])))
            (sub p :pub/downstream pub-ch)
            (go-loop-try> err-ch [{:keys [downstream id] :as mp} (<? pub-ch)]
                          (when mp
                            (info "stage: pubing " id " : " downstream)
                            ;; TODO swap! once per update
                            (doseq [[u crdts] downstream
                                    [crdt-id op] crdts]
                              (swap! stage update-in [u crdt-id :state]
                                     (fn [old stored] (if old (-downstream old op) stored))
                                     (<? (pub->crdt store [u crdt-id] (:crdt op)))))
                            (>! out {:type :pub/downstream-ack
                                     :peer stage-id
                                     :id id})
                            (recur (<? pub-ch))))
            stage)))


(defn subscribe-crdts!
  "Subscribe stage to crdts map, e.g. {user {crdt-id #{identity1 identity2}}}.
This is not additive, but only these identities are
subscribed on the stage afterwards. Returns go block to synchronize."
  [stage crdts]
  (go-try (let [[p out] (get-in @stage [:volatile :chans])
                sub-id (*id-fn*)
                subed-ch (chan)
                pub-ch (chan)
                peer-id (get-in @stage [:config :id])]
            (sub p :sub/identities-ack subed-ch)
            (>! out
                {:type :sub/identities
                 :identities crdts
                 :id sub-id
                 :peer peer-id})
            (<? subed-ch)
            (unsub p :sub/identities-ack subed-ch)
            (sub p :pub/downstream pub-ch)
            (<? pub-ch)
            (unsub p :pub/downstream pub-ch)
            (let [not-avail (fn [] (->> (for [[user rs] crdts
                                             [crdt-id identities] rs]
                                         [[user crdt-id] identities])
                                       (filter #(when-let [crdt (get-in @stage (first %))]
                                                  (if (satisfies? PHasIdentities crdt)
                                                    (let [loaded (-identities crdt)]
                                                      (set/difference (second %) loaded)))))))]
              (loop [na (not-avail)]
                (when (not (empty? na))
                  (debug "waiting for CRDTs in stage: " na)
                  (<? (timeout 1000))
                  (recur (not-avail)))))
            ;; TODO [:config :subs] only managed by subscribe-crdts! => safe as singleton application only
            (swap! stage assoc-in [:config :subs] crdts)
            nil)))
