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
            [replikativ.platform-log :refer [debug info warn]]
            #?(:clj [full.async :refer [<? <<? go-for go-try go-loop-try go-loop-try> alt?]])
            [hasch.core :refer [uuid]]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! sub unsub pub close! alt! onto-chan]]
                    :cljs [cljs.core.async :as async
                           :refer [>! timeout chan put! sub unsub pub close! onto-chan]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [alt!]]
                            [full.cljs.async :refer [<? <<? go-for go-try go-loop-try go-loop-try> alt?]])))


(defn sync!
  "Synchronize (push) the results of an upstream CRDT command with
  storage and other peers. Returns go block to synchronize."
  [stage-val upstream]
  (go-try (let [{:keys [id]} (:config stage-val)
                [p out] (get-in stage-val [:volatile :chans])
                fch (chan)
                bfch (chan)
                pch (chan)
                sync-id (*id-fn*)
                new-values (reduce merge {} (for [[u crdts] upstream
                                                  r crdts]
                                              (get-in stage-val [u r :new-values])))

                #_pubs #_(reduce #(assoc-in %1 %2 (get-in stage-val (concat %2 [:downstream])))
                                 {}
                                 (for [[u crdts] upstream
                                       id crdts
                                       :when (or (= (get-in stage-val [u id :stage/op]) :pub)
                                                 (= (get-in stage-val [u id :stage/op]) :sub))]
                                   [u id]))
                pubs (<<? (go-for [[u crdts] upstream
                                   crdt-id crdts
                                   :when (or (= (get-in stage-val [u crdt-id :stage/op]) :pub)
                                             (= (get-in stage-val [u crdt-id :stage/op]) :sub))]
                                  (with-meta {:type :pub/downstream
                                              :user u
                                              :crdt-id crdt-id
                                              :id sync-id
                                              :peer id
                                              :downstream (get-in stage-val [u crdt-id :downstream])}
                                    {:host ::stage})))
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
            (<? (onto-chan out pubs false))
            #_(when-not (empty? pubs)
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
                                     (update-in %2 dissoc :stage/op)
                                     (assoc-in (concat %2 [:new-values]) {}))
                                old
                                (for [[user crdts] upstream
                                      id crdts]
                                  [user id]))))
  nil)



(defn connect!
  "Connect stage to a remote url of another peer,
e.g. ws://remote.peer.net:1234/replikativ/ws. Returns go block to
synchronize."
  [stage url & {:keys [reconnect?] :or {reconnect? true}}]
  (let [[p out] (get-in @stage [:volatile :chans])
        connedch (chan)
        connection-id (uuid)]
    (sub p :connect/peer-ack connedch)
    (put! out {:type :connect/peer
               :url url
               :id connection-id
               :reconnect? reconnect?})
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
  [user peer err-ch]
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
                                        :err-ch err-ch}})]
            (<? (k/assoc-in store [store-blob-trans-id] store-blob-trans-value))
            (-> (block-detector stage-id [peer [out in]])
                middleware
                connect
                wire
                drain)
            (sub p :pub/downstream pub-ch)
            (go-loop-try> err-ch [{:keys [downstream id user crdt-id] :as mp} (<? pub-ch)]
                          (when mp
                            (info "stage: pubing " id " : " downstream)
                            ;; TODO swap! once per update
                            #_(doseq [[u crdts] downstream
                                      [crdt-id {:keys [op] :as pub}] crdts])
                            (swap! stage update-in [user crdt-id :state]
                                   (fn [old vanilla] (-downstream (or old vanilla) (:op downstream)))
                                   (key->crdt (:crdt downstream)))
                            (>! out {:type :pub/downstream-ack
                                     :peer stage-id
                                     :id id})
                            (recur (<? pub-ch))))
            stage)))


(defn subscribe-crdts!
  "Subscribe stage to crdts map, e.g. {user #{crdt-id}}.
This is not additive, but only these identities are
subscribed on the stage afterwards. Returns go block to synchronize."
  [stage crdts]
  (println "CRDTS" crdts)
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
            ;; TODO
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
                                     ;; TODO
                                     (update-in (butlast %2) disj (last %2))
                                     (update-in [:config :subs (first %2)] disj (last %)))
                                old
                                (for [[u rs] crdts
                                      id rs]
                                  [u id]))))
         (get-in [:config :subs]))]
    (subscribe-crdts! stage new-subs)))
