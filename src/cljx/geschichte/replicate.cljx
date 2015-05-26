(ns geschichte.replicate
    "Replication related pub-sub protocols."
    (:require [geschichte.crdt.materialize :refer [pub->crdt]]
              [geschichte.environ :refer [*id-fn*]]
              [geschichte.protocols :refer [-apply-downstream! PHasIdentities -select-identities]]
              [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
              [geschichte.platform-log :refer [debug info warn error]]
              [clojure.set :as set]
              [geschichte.platform-data :refer [diff]]
              [geschichte.platform :refer [client-connect! <? go-loop<? go-loop>? go<?]
               :include-macros true]
              #+clj [clojure.core.async :as async
                     :refer [>! <!! timeout chan alt! go put!
                             filter< map< go-loop pub sub unsub close!]]
              #+cljs [cljs.core.async :as async
                     :refer [>! timeout chan put! filter< map< pub sub unsub close!]])
    #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))

;; TODO
;; rename topic to sync/pub sync/sub ...

(declare wire)
(defn client-peer
  "Creates a client-side peer only."
  [name store middleware]
  (let [log (atom {})
        bus-in (chan)
        bus-out (pub bus-in :topic)]
    (atom {:volatile {:log log
                      :middleware middleware
                      :chans [bus-in bus-out]
                      :error-ch (chan (async/sliding-buffer 100))
                      :store store}
           :name name
           :meta-sub {}})))


(defn server-peer
  "Constructs a listening peer.
You need to integrate returned :handler to run it."
  [handler store middleware]
  (let [{:keys [new-conns url]} handler
        log (atom {})
        bus-in (chan)
        bus-out (pub bus-in :topic)
        error-ch (chan (async/sliding-buffer 100))
        peer (atom {:volatile (merge handler
                                     {:store store
                                      :middleware middleware
                                      :log log
                                      :error-ch error-ch
                                      :chans [bus-in bus-out]})
                    :name (:url handler)
                    :meta-sub {}})]
    (go-loop>? error-ch [[in out] (<? new-conns)]
               (<? (wire peer [in out]))
               (recur (<? new-conns)))
    peer))

(defn- get-error-ch [peer]
  (get-in @peer [:volatile :error-ch]))


; by Chouser:
(defn deep-merge-with
  "Like merge-with, but merges maps recursively, applying the given fn
   only when there's a non-map at a particular level.

   (deep-merge-with + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
                {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
   -> {:a {:b {:z 3, :c 3, :d {:z 9, :x 1, :y 2}}, :e 103}, :f 4}"
  [f & maps]
  (apply
    (fn m [& maps]
      (if (every? map? maps)
        (apply merge-with m maps)
        (apply f maps)))
    maps))

;; could be simpler/more readable ...
(defn filter-subs
  "Filters new and old metadata depending on subscriptions sbs."
  [store sbs metas]
  (go (->> (for [[user repos] metas
                 [repo-id pub] repos
                 :when (get-in sbs [user repo-id])]
             (go (let [crdt (<? (pub->crdt store [user repo-id] (:crdt pub)))
                       identities (get-in sbs [user repo-id])]
                   [[user repo-id]
                    (if (extends? PHasIdentities (class crdt))
                      (update-in pub [:op] #(-select-identities crdt identities %))
                      pub)])))
           async/merge
           (async/into [])
           <?
           (filter #(-> % second :op))
           (reduce #(assoc-in %1 (first %2) (second %2)) {})))
  #_(go (->> (select-keys metas (set (keys sbs)))
           (reduce (fn [subed-metas [user user-repos]]
                     (let [subed-user-repos
                           (->> (select-keys user-repos (set (keys (sbs user))))
                                (reduce (fn [subed-user-repos [repo {:keys [crdt op] :as pub}]]
                                          (let [ids (get-in sbs [user repo])
                                                crdt (<? (pub->crdt store [user repo] crdt))]

                                            (-> subed-user-repos
                                                (assoc-in [repo] pub)
                                                (assoc-in [repo :op]
                                                          (-select-identities crdt ids op)
                                                          op
                                                          #_(-> op
                                                                ;; OP -> not necessary
                                                                (update-in [:causal-order]
                                                                           select-keys branches-causal)
                                                                (update-in [:branches] select-keys branches))))))
                                        {}))]
                       (if-not (empty? subed-user-repos)
                         (assoc subed-metas user subed-user-repos)
                         subed-metas)))
                   {}))))


(defn- publication-loop
  "Reply to publications by sending an update value filtered to subscription."
  [store error-ch pub-ch out sub-metas pn remote-pn]
  (go (let [metas-list (->> (for [[user repos] sub-metas
                                  [id _] repos]
                              (go [[user id]
                                   (let [{:keys [crdt state]} (<? (-get-in store [[user id]]))]
                                     {:crdt crdt
                                      :method :new-state
                                      :op state})]))
                            async/merge
                            (filter< (comp :op second))
                            (async/into [])
                            <?)
            metas (reduce #(assoc-in %1 (first %2) (second %2)) nil metas-list)]
        (when metas
          (debug "initial state publication:" metas)
          (>! out {:topic :meta-pub
                   :metas (<? (filter-subs store sub-metas metas))
                   :peers pn
                   :id (*id-fn*)})))

      (go-loop>? error-ch
                 [{:keys [metas id] :as p} (<? pub-ch)]
                 (when-not p
                   (info pn "publication-loop ended for " sub-metas))
                 (when p
                   (let [new-metas (<? (filter-subs store sub-metas metas))]
                     (info pn "publication-loop: new-metas " metas
                           "\nsubs " sub-metas new-metas "\nto " remote-pn)
                     (when-not (empty? new-metas)
                       (info pn "publication-loop: sending " new-metas "to" remote-pn)
                       (>! out (assoc p
                                 :metas new-metas
                                 :peer pn
                                 :id id)))
                     (recur (<? pub-ch)))))))


(defn subscribe
  "Adjust publication stream and propagate subscription requests."
  [peer store sub-ch out]
  (let [{:keys [chans log]} (-> @peer :volatile)
        [bus-in bus-out] chans
        pn (:name @peer)]
    (sub bus-out :meta-sub out)
    (go-loop>? (get-error-ch peer)
               [{sub-metas :metas id :id :as s} (<? sub-ch)
                init true
                old-pub-ch nil]
               (if s
                 (let [old-subs (:meta-sub @peer)
                       ;; TODO make subscription configurable
                       new-subs (:meta-sub (swap! peer
                                                  update-in
                                                  [:meta-sub]
                                                  (partial deep-merge-with set/union) sub-metas))
                       remote-pn (:peer s)
                       pub-ch (chan)
                       [_ _ common-subs] (diff new-subs sub-metas)]
                   (info pn "subscribe: starting subscription " id " from " remote-pn)
                   (debug pn "subscribe: subscriptions " sub-metas)
                   ;; properly close previous publication-loop
                   (when old-pub-ch
                     (unsub bus-out :meta-pub old-pub-ch)
                     (close! old-pub-ch))
                   ;; and restart
                   (sub bus-out :meta-pub pub-ch)
                   (publication-loop store (get-error-ch peer) pub-ch out sub-metas pn remote-pn)

                   (when (and init (= new-subs old-subs)) ;; subscribe back at least exactly once on init
                     (>! out {:topic :meta-sub :metas new-subs :peer pn :id id}))
                   (when (not (= new-subs old-subs))
                     (let [msg {:topic :meta-sub :metas new-subs :peer pn :id id}]
                       (alt! [[bus-in msg]]
                             :wrote

                             (timeout 5000)
                             (throw (ex-info "bus-in is blocked."
                                             {:type :bus-in-block
                                              :failed-put msg})))))

                   ;; propagate (internally) that the remote has subscribed (for connect)
                   ;; also guarantees meta-sub is sent to remote peer before meta-subed!
                   (let [msg {:topic :meta-subed :metas common-subs :peer remote-pn :id id}]
                     (alt! [[bus-in msg]]
                           :wrote

                           (timeout 5000)
                           (throw (ex-info "bus-in is blocked."
                                           {:type :bus-in-block
                                            :failed-put msg}))))
                   (>! out {:topic :meta-subed :metas common-subs :peer remote-pn :id id})
                   (info pn "subscribe: finishing " id)

                   (recur (<? sub-ch) false pub-ch))
                 (do (info "subscribe: closing old-pub-ch")
                     (unsub bus-out :meta-pub old-pub-ch)
                     (unsub bus-out :meta-sub out)
                     (close! old-pub-ch))))))


(defn commit-pubs [store pubs]
  (->> (for [[user repos] pubs
             [repo pub] repos]
         (go<? [[user repo]
                (let [crdt (<? (pub->crdt store [user repo] (:crdt pub)))]
                  (<? (-update-in store [[user repo]] (fn [{:keys [description public state crdt]}]
                                                        {:crdt (or crdt (:crdt pub))
                                                         :description (or description
                                                                          (:description pub))
                                                         :public (or (:public pub) public false)
                                                         :state state})))
                  (<? (-apply-downstream! crdt (:op pub))))]))
       async/merge
       (async/into [])))


(defn publish
  "Synchronize metadata publications."
  [peer store pub-ch bus-in out]
  (go-loop>? (get-error-ch peer)
             [{:keys [metas id] :as p} (<? pub-ch)]
             (when p
               (let [pn (:name @peer)
                     remote (:peer p)]
                 (info pn "publish: " p)
                 (>! out {:topic :meta-pubed
                          :peer (:peer p)
                          :id id})
                 ;; update all repos of all users
                 (let [up-metas (<? (commit-pubs store metas))]
                   (when (some true? (map #(let [[old-meta up-meta] (second %)]
                                             (not= old-meta up-meta)) up-metas))
                     (info pn "publish: metas " metas)
                     (let [msg (assoc p :peer pn)]
                       (alt! [[bus-in msg]]
                             (debug pn "publish: sent new-metas")

                             (timeout 5000) ;; TODO make tunable
                             (throw (ex-info "bus-in is blocked."
                                             {:type :bus-in-block
                                              :failed-put msg})))))))
               (recur (<? pub-ch)))))


(defn connect
  "Service connection requests."
  [peer conn-ch out]
  (go-loop [{:keys [url id] :as c} (<? conn-ch)]
    (when c
      (try
        (info (:name @peer) "connecting to:" url)
        (let [[bus-in bus-out] (:chans (:volatile @peer))
              pn (:name @peer)
              log (:log (:volatile @peer))
              [c-in c-out] (<? (client-connect! url #_(:tag-table (:store (:volatile @peer))) (atom {})))
              subs (:meta-sub @peer)
              subed-ch (chan)
              sub-id (*id-fn*)]
          ;; handshake
          (sub bus-out :meta-subed subed-ch)
          (<? (wire peer [c-in c-out]))
          (>! c-out {:topic :meta-sub :metas subs :peer pn :id sub-id})
          ;; HACK? wait for ack on backsubscription, is there a simpler way?
          (<? (go-loop<? [{id :id :as c} (<? subed-ch)]
                         (debug "connect: backsubscription?" sub-id c)
                         (when (and c (not= id sub-id))
                           (recur (<? subed-ch)))))
          (async/close! subed-ch)

          (>! out {:topic :connected
                   :url url
                   :id id
                   :peer (:peer c)}))
        (catch Exception e
          (>! out {:topic :connected
                   :url url
                   :id id
                   :error e})))
      (recur (<? conn-ch)))))


(defn wire
  "Wire a peer to an output (response) channel and a publication by :topic of the input."
  [peer [in out]]
  (go<? (let [[in out] ((:middleware (:volatile @peer)) [in out])
              p (pub in :topic)
              {:keys [store chans log]} (:volatile @peer)
              name (:name @peer)
              [bus-in bus-out] chans
              pub-ch (chan)
              conn-ch (chan)
              sub-ch (chan)]

          (sub p :meta-sub sub-ch)
          (subscribe peer store sub-ch out)

          (sub p :meta-pub pub-ch)
          (publish peer store pub-ch bus-in out)

          (sub p :connect conn-ch)
          (connect peer conn-ch out))))
