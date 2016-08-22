(ns replikativ.stage
  "A stage allows to execute upstream operations of each CRDT and
  communicates them downstream to a peer through
  synchronous (blocking) operations."
  (:require [konserve.core :as k]
            [kabel.peer :refer [drain]]
            [replikativ.core :refer [wire]]
            [replikativ.connect :refer [connect]]
            [replikativ.protocols :refer [-downstream]]
            [replikativ.environ :refer [*id-fn* store-blob-trans-id store-blob-trans-value]]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [kabel.middleware.block-detector :refer [block-detector]]
            [kabel.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [<? <<? go-try go-loop-try alt? put?]])
            #?(:clj [full.lab :refer [go-for go-loop-super]])
            [hasch.core :refer [uuid]]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close! alt! onto-chan]]
                    :cljs [cljs.core.async :as async
                           :refer [>! timeout chan put! sub unsub pub close! onto-chan]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [alt!]]
                            [full.async :refer [<? <<? go-try go-loop-try alt? put?]]
                            [full.lab :refer [go-for go-loop-super]])))

#?(:clj
   (defmacro go-try-locked [stage & code]
     `(go-try
       (let [{{sync-token# :sync-token} :volatile} (deref ~stage)]
         (debug "acquiring stage token")
         (<? sync-token#)
         (try
           ~@code
           (finally
             (put? sync-token# :stage)
             (debug "released stage token")))))))


(defn ensure-crdt [crdt-class stage [user crdt-id]]
  (let [val (get-in @stage [user crdt-id :state])
        t (type val)]
    (when-not (= t crdt-class)
      (if val
        (throw (ex-info (str "You cannot apply operations on this type.")
                        {:user user
                         :expected-type crdt-class
                         :actual-type t
                         :crdt-id crdt-id
                         :value val}))
        (throw (ex-info "There is no CRDT here. Have you forgot to initialize one?"
                        {:user user
                         :expected-type crdt-class
                         :crdt-id crdt-id}))))))

(defn sync!
  "Synchronize (push) the results of an upstream CRDT command with
  storage and other peers. Returns go block to synchronize."
  [stage-val [user crdt-id]]
  (let [{:keys [new-values downstream]} (get-in stage-val [user crdt-id])
        {:keys [id]} (:config stage-val)
        [p out] (get-in stage-val [:volatile :chans])
        fch (chan)
        bfch (chan)
        pch (chan)
        sync-id  (*id-fn*)
        sync-ch (chan)]
    (sub p :pub/downstream-ack pch)
    (sub p :fetch/edn fch)

    (go-loop-super [to-fetch (:ids (<? fch))]
                   (when to-fetch
                     (let [selected (select-keys new-values to-fetch)]
                       (when (= (set (keys selected)) to-fetch)
                         (debug "fetching edn from stage" to-fetch)
                         (>! out {:type :fetch/edn-ack
                                  :values selected
                                  :id sync-id
                                  :sender id})))
                     (recur (:ids (<? fch)))))

    (sub p :fetch/binary bfch)
    (go-loop-super []
                   (let [to-fetch (:blob-id (<? bfch))]
                     (when to-fetch
                       (when-let [selected (get new-values to-fetch)]
                         (debug "trying to fetch blob from stage" to-fetch)
                         (>! out {:type :fetch/binary-ack
                                  :value selected
                                  :blob-id sync-id
                                  :id sync-id
                                  :sender id}))
                       (recur))))
    (put! out {:type :pub/downstream
               :user user
               :crdt-id crdt-id
               :id sync-id
               :sender id
               :host ::stage
               :downstream downstream})

    (go-loop-super [{:keys [id]} (<? pch)]
                   (when id
                     (when (= id sync-id)
                       (debug "finished syncing" sync-id)
                       (unsub p :pub/downstream-ack pch)
                       (unsub p :fetch/edn fch)
                       (unsub p :fetch/binary fch)
                       (put! sync-ch sync-id)
                       (close! sync-ch)
                       (close! fch)
                       (close! bfch)
                       (close! pch))
                     (recur (<? pch))))
    sync-ch))


(defn cleanup-ops-and-new-values! [stage upstream fetched-vals]
  (swap! stage (fn [old] (reduce (fn [old [u id]]
                                   #_(update-in old [u id :new-values] #(apply dissoc % fetched-vals))
                                   old)
                                 old
                                (for [[user crdts] upstream
                                      id crdts]
                                  [user id]))))
  nil)



(defn connect!
  "Connect stage to a remote url of another peer,
e.g. ws://remote.peer.net:1234/replikativ/ws. Returns go block to
synchronize."
  [stage url & {:keys [retries] :or {retries #?(:clj Long/MAX_VALUE
                                                :cljs js/Infinity)}}]
  (let [[p out] (get-in @stage [:volatile :chans])
        connedch (chan)
        connection-id (uuid)]
    (sub p :connect/peer-ack connedch)
    (put! out {:type :connect/peer
               :url url
               :id connection-id
               :retries retries})
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
  [user peer]
  (go-try (let [in (chan)
                out (chan)
                middleware (-> @peer :volatile :middleware)
                p (pub in :type)
                pub-ch (chan)
                stage-id (str "STAGE-" (subs (str (uuid)) 0 4))
                sync-token (chan)
                _ (put! sync-token :stage)
                {:keys [store]} (:volatile @peer)
                stage (atom {:config {:id stage-id
                                      :user user}
                             :volatile {:chans [p out]
                                        :peer peer
                                        :store store
                                        :sync-token sync-token}})]
            (-> (block-detector stage-id [peer [out in]])
                middleware
                connect
                wire
                drain)
            (sub p :pub/downstream pub-ch)
            (go-loop-super [{:keys [downstream id user crdt-id] :as mp} (<? pub-ch)]
                           (when mp
                             (try
                               (info "stage: pubing " id)
                               (swap! stage update-in [user crdt-id :state]
                                      (fn [old vanilla] (-downstream (or old vanilla) (:op downstream)))
                                      (key->crdt (:crdt downstream)))
                               (>! out {:type :pub/downstream-ack
                                        :sender stage-id
                                        :id id})
                               (catch #?(:clj Exception :cljs js/Error) e
                                   (throw (ex-info "Cannot apply downstream operation on stage value."
                                                   {:publication mp
                                                    :stage-id stage-id
                                                    :error e}))))
                             (recur (<? pub-ch))))
            stage)))


(defn subscribe-crdts!
  "Subscribe stage to crdts map, e.g. {user #{crdt-id}}.
This is not additive, but only these identities are
subscribed on the stage afterwards. Returns go block to synchronize."
  [stage crdts]
  (go-try (let [{{[p out] :chans
                  :keys [store]} :volatile} @stage
                sub-id (*id-fn*)
                subed-ch (chan)
                stage-id (get-in @stage [:config :id])]
            (sub p :sub/identities-ack subed-ch)
            (>! out
                {:type :sub/identities
                 :identities crdts
                 :id sub-id
                 :sender stage-id})
            (<? subed-ch)
            (unsub p :sub/identities-ack subed-ch)
            (let [not-avail (fn [] (->> (for [[user rs] crdts
                                             crdt-id rs]
                                         [user crdt-id])
                                       (filter #(not (get-in @stage %)))))]
              (loop [na (not-avail)]
                (when (not (empty? na))
                  (debug "waiting for CRDTs in stage: " na)
                  (<? (timeout 1000))
                  (recur (not-avail)))))
            ;; TODO [:config :subs] only managed by subscribe-crdts! => safe as singleton application only
            (swap! stage assoc-in [:config :subs] crdts)
            nil)))


(defn remove-crdts!
  "Remove crdts map from stage, e.g. {user #{crdt-id}}.
  Returns go block to synchronize."
  [stage crdts]
  (let [new-subs
        (->
         ;; can still get pubs in the mean time which undo in-memory removal, but should be safe
         (swap! stage (fn [old]
                        (reduce #(-> %1
                                     (update-in (butlast %2) disj (last %2))
                                     (update-in [:config :subs (first %2)] disj (last %)))
                                old
                                (for [[u rs] crdts
                                      id rs]
                                  [u id]))))
         (get-in [:config :subs]))]
    (subscribe-crdts! stage new-subs)))
