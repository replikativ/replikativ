(ns replikativ.stage
  "A stage allows to execute upstream operations of each CRDT and
  communicates them downstream to a peer through
  synchronous (blocking) operations."
    (:require [konserve.protocols :refer [-get-in -assoc-in -bget -bassoc]]
              [replikativ.core :refer [wire]]
              [replikativ.protocols :refer [PHasIdentities -identities]]
              [replikativ.environ :refer [*id-fn* store-blob-trans-id store-blob-trans-value]]
              [replikativ.crdt.materialize :refer [pub->crdt]]
              [replikativ.p2p.block-detector :refer [block-detector]]
              [replikativ.platform-log :refer [debug info warn]]
              [replikativ.platform :refer [<? go<? go>? go-loop>? go-loop<?]]
              [hasch.core :refer [uuid]]
              [clojure.set :as set]
              #?(:clj [clojure.core.async :as async
                        :refer [<! <!! >! timeout chan alt! go put! filter< map< go-loop sub unsub pub close!]]
                 :cljs [cljs.core.async :as async
                        :refer [<! >! timeout chan put! filter< map< sub unsub pub close!]]))
    #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]])))


(defn sync!
  "Synchronize (push) the results of an upstream CRDT command with storage and other peers.
This the update of the stage is not executed synchronously. Returns go
  block to synchronize."
  [stage-val metas]
  (go<? (let [{:keys [id]} (:config stage-val)
              [p out] (get-in stage-val [:volatile :chans])
              fch (chan)
              bfch (chan)
              pch (chan)
              sync-id (*id-fn*)
              new-values (reduce merge {} (for [[u repos] metas
                                                [r branches] repos
                                                b branches]
                                            (get-in stage-val [u r :new-values b])))

              pubs (reduce #(assoc-in %1 %2 (get-in stage-val (concat %2 [:downstream])))
                           {}
                           (for [[u repos] metas
                                 [id repo] repos
                                 :when (or (= (get-in stage-val [u id :stage/op]) :pub)
                                           (= (get-in stage-val [u id :stage/op]) :sub))]
                             [u id]))
              ferr-ch (chan)]
          (sub p :meta-pubed pch)
          (sub p :fetch fch)
          (go-loop>? ferr-ch [to-fetch (:ids (<? fch))]
                     (when to-fetch
                       (>! out {:topic :fetched
                                :values (select-keys new-values to-fetch)
                                :id sync-id
                                :peer id})
                       (recur (:ids (<? fch)))))

          (sub p :binary-fetch bfch)
          (go-loop>? ferr-ch []
                     (let [to-fetch (:blob-id (<? bfch))]
                       (when to-fetch
                         (>! out {:topic :binary-fetched
                                  :value (get new-values to-fetch)
                                  :blob-id sync-id
                                  :id sync-id
                                  :peer id})
                         (recur))))
          (when-not (empty? pubs)
            (>! out (with-meta {:topic :meta-pub :metas pubs :id sync-id :peer id}
                      {:host ::stage})))

          (loop []
            (alt! pch
                  ([_])
                  ferr-ch
                  ([e] (throw e))
                  (timeout 60000)
                  ([_]
                   (warn "No meta-pubed ack received after 60 secs. Continue waiting..." metas)
                   (recur))))


          (unsub p :meta-pubed pch)
          (unsub p :fetch fch)
          (unsub p :binary-fetch fch)
          (close! ferr-ch)
          (close! fch)
          (close! bfch))))


(defn cleanup-ops-and-new-values! [stage metas]
  (swap! stage (fn [old] (reduce #(-> %1
                                     (update-in (butlast %2) dissoc :stage/op)
                                     (assoc-in (concat (butlast %2) [:new-values (last %2)]) {}))
                                old
                                (for [[user repos] metas
                                      [id branches] repos
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
    (sub p :connected connedch)
    (put! out {:topic :connect
               :url url
               :id connection-id})
    (go-loop<? [{id :id e :error} (<? connedch)]
               (when id
                 (if-not (= id connection-id)
                   (recur (<? connedch))
                   (do (unsub p :connected connedch)
                       (when e (throw e))
                       (info "connect!: connected " url)))))))


(defn create-stage!
  "Create a stage for user, given peer and a safe evaluation function
for the transaction functions.  Returns go block to synchronize."
  [user peer eval-fn]
  (go<? (let [in (chan)
              out (chan)
              p (pub in :topic)
              pub-ch (chan)
              val-ch (chan (async/sliding-buffer 1))
              val-atom (atom {})
              stage-id (str "STAGE-" (uuid))
              {:keys [store]} (:volatile @peer)
              stage (atom {:config {:id stage-id
                                    :user user}
                           :volatile {:chans [p out]
                                      :peer peer
                                      :eval-fn eval-fn
                                      :val-ch val-ch
                                      :val-atom val-atom
                                      :val-mult (async/mult val-ch)}})
              err-ch (chan (async/sliding-buffer 10))] ;; TODO
          (<? (-assoc-in store [store-blob-trans-id] store-blob-trans-value))
          (<? (wire peer (block-detector stage-id [out in])))
          (sub p :meta-pub pub-ch)
          (go-loop>? err-ch [{:keys [metas id] :as mp} (<? pub-ch)]
                     (when mp
                       (info "stage: pubing " id " : " metas)
                       ;; TODO swap! once per update
                       (doseq [[u repos] metas
                               [repo-id op] repos]
                         (swap! stage assoc-in [u repo-id :state]
                                (<? (pub->crdt store [u repo-id] (:crdt op)))))
                       (>! out {:topic :meta-pubed
                                :peer stage-id
                                :id id})
                       (recur (<? pub-ch))))
          stage)))


(defn subscribe-repos!
  "Subscribe stage to repos map, e.g. {user {crdt-id #{identity1 identity2}}}.
This is not additive, but only these identities are
subscribed on the stage afterwards. Returns go block to synchronize."
  [stage repos]
  (go<? (let [[p out] (get-in @stage [:volatile :chans])
              sub-id (*id-fn*)
              subed-ch (chan)
              pub-ch (chan)
              peer-id (get-in @stage [:config :id])]
          (sub p :meta-subed subed-ch)
          (>! out
              {:topic :meta-sub
               :metas repos
               :id sub-id
               :peer peer-id})
          (<? subed-ch)
          (unsub p :meta-subed subed-ch)
          (sub p :meta-pub pub-ch)
          (<? pub-ch)
          (unsub p :meta-pub pub-ch)
          (let [not-avail (fn [] (->> (for [[user rs] repos
                                           [repo-id identities] rs]
                                       [[user repo-id] identities])
                                     (filter #(when-let [crdt (get-in @stage (first %))]
                                                (if (extends? PHasIdentities (class crdt))
                                                  (let [loaded (-identities crdt)]
                                                    (set/difference (second %) loaded)))))))]
            (loop [na (not-avail)]
              (when (not (empty? na))
                (debug "waiting for CRDTs in stage: " na)
                (<! (timeout 1000))
                (recur (not-avail)))))
          ;; [:config :subs] only managed by subscribe-repos! => safe as singleton application only
          (swap! stage assoc-in [:config :subs] repos)
          nil)))
