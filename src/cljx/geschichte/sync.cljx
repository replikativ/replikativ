(ns geschichte.sync
    "Synching related pub-sub protocols."
    (:require [geschichte.meta :refer [update isolate-branch]]
              [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
              [geschichte.platform-log :refer [debug info warn error]]
              [clojure.set :as set]
              [geschichte.platform-data :refer [diff]]
              [geschichte.platform :refer [client-connect! <? go-loop<? go-loop>? go<?]
               :include-macros true]
              #+clj [clojure.core.async :as async
                     :refer [>! timeout chan alt! go put!
                             filter< map< go-loop pub sub unsub close!]]
              #+cljs [cljs.core.async :as async
                     :refer [>! timeout chan put! filter< map< pub sub unsub close!]])
    #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


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

(defn- error-ch [peer]
  (get-in @peer [:volatile :error-ch]))


; by Chouser:
(defn deep-merge-with
  "Like merge-with, but merges maps recursively, applying the given fn
   only when there's a non-map at a particular level.

   (deepmerge + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
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
  [sbs metas]
  (->> (select-keys metas (set (keys sbs)))
       (reduce (fn [subed-metas [user user-repos]]
                 (let [subed-user-repos
                       (->> (select-keys user-repos (set (keys (sbs user))))
                            (reduce (fn [subed-user-repos [repo meta]]
                                      (let [branches (get-in sbs [user repo])
                                            branches-causal
                                            (apply set/union
                                                   (map (comp set keys (partial isolate-branch meta))
                                                        branches))]
                                        (assoc subed-user-repos repo
                                               (-> meta
                                                   ;; OP -> not necessary
                                                   (update-in [:causal-order]
                                                              select-keys branches-causal)
                                                   (update-in [:branches] select-keys branches)))))
                                    {}))]
                   (if-not (empty? subed-user-repos)
                     (assoc subed-metas user subed-user-repos)
                     subed-metas)))
               {})))


(defn- publication-loop [error-ch pub-ch out sub-metas pn remote-pn]
  (go-loop>? error-ch
             [{:keys [metas] :as p} (<? pub-ch)]
             (when-not p
               (info pn "publication-loop ended for " sub-metas))
             (when p
               (let [new-metas (filter-subs sub-metas metas)]
                 (info pn "publication-loop: new-metas " metas
                       "\nsubs " sub-metas new-metas "\nto " remote-pn)
                 (when-not (empty? new-metas)
                   (info pn "publication-loop: sending " new-metas "to" remote-pn)
                   (>! out (assoc p
                             :metas new-metas
                             :peer pn)))
                 (recur (<? pub-ch))))))


(defn subscribe
  "Adjust publication stream and propagate subscription requests."
  [peer sub-ch out]
  (let [{:keys [chans log]} (-> @peer :volatile)
        [bus-in bus-out] chans
        pn (:name @peer)]
    (sub bus-out :meta-sub out)
    (go-loop>? (error-ch peer)
               [{sub-metas :metas :as s} (<? sub-ch)
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
                   (info pn "subscribe: starting from " remote-pn)
                   (debug pn "subscribe: subscriptions " sub-metas)
                   ;; properly close previous publication-loop
                   (when old-pub-ch
                     (unsub bus-out :meta-pub old-pub-ch)
                     (close! old-pub-ch))
                   ;; and restart
                   (sub bus-out :meta-pub pub-ch)
                   (publication-loop error-ch pub-ch out sub-metas pn remote-pn)

                   (when (and init (= new-subs old-subs)) ;; subscribe back at least exactly once on init
                     (>! out {:topic :meta-sub :metas new-subs :peer pn}))
                   (when (not (= new-subs old-subs))
                     (let [msg {:topic :meta-sub :metas new-subs :peer pn}]
                       (alt! [[bus-in msg]]
                             :wrote

                             (timeout 5000)
                             (throw (ex-info "bus-in is blocked."
                                             {:type :bus-in-block
                                              :failed-put msg})))))

                   ;; propagate (internally) that the remote has subscribed (for connect)
                   ;; also guarantees meta-sub is sent to remote peer before meta-subed!
                   (let [msg {:topic :meta-subed :metas common-subs :peer remote-pn}]
                     (alt! [[bus-in msg]]
                           :wrote

                           (timeout 5000)
                           (throw (ex-info "bus-in is blocked."
                                           {:type :bus-in-block
                                            :failed-put msg}))))
                   (>! out {:topic :meta-subed :metas common-subs :peer remote-pn})
                   (info pn "subscribe: finishing " remote-pn)

                   (recur (<? sub-ch) false pub-ch))
                 (do (info "subscribe: closing old-pub-ch")
                     (unsub bus-out :meta-pub old-pub-ch)
                     (unsub bus-out :meta-sub out)
                     (close! old-pub-ch))))))


(defn update-metas [store metas]
  (->> (for [[user repos] metas
             [repo meta] repos]
         (go [[user repo]
              ;; OP -> if nil, other meta must be full state
              (<? (-update-in store [user repo] #(if % (update % meta)
                                                     (update meta meta))))]))
       async/merge
       (async/into [])))


(defn publish
  "Synchronize metadata publications."
  [peer pub-ch store bus-in out]
  (go-loop>? (error-ch peer)
             [{:keys [metas] :as p} (<? pub-ch)]
             (when p
               (let [pn (:name @peer)
                     remote (:peer p)]
                 (info pn "publish: " p)
                 (>! out {:topic :meta-pubed
                          :peer (:peer p)})
                 ;; update all repos of all users
                 (let [up-metas (<? (update-metas store metas))]
                   (when (some true? (map #(let [[old-meta up-meta] (second %)]
                                             (not= old-meta up-meta)) up-metas))
                     ;; OP -> sent ops, not merged meta
                     (let [new-metas (reduce #(assoc-in %1 (first %2)
                                                        (second (second %2))) metas up-metas)]
                       (info pn "publish: new-metas " new-metas)
                       (let [msg (assoc p :peer pn :metas new-metas)]
                         (alt! [[bus-in msg]]
                               (debug pn "publish: sent new-metas")

                               (timeout 5000) ;; TODO make tunable
                               (throw (ex-info "bus-in is blocked."
                                               {:type :bus-in-block
                                                :failed-put msg}))))))))
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
              subed-ch (chan)]
          ;; handshake
          (sub bus-out :meta-subed subed-ch)
          (<? (wire peer [c-in c-out]))
          (>! c-out {:topic :meta-sub :metas subs :peer pn})
          ;; HACK? wait for ack on backsubscription, is there a simpler way?
          (<? (go-loop<? [{u :peer :as c} (<? subed-ch)]
                         (when (and c (not= u url))
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
          (subscribe peer sub-ch out)

          (sub p :meta-pub pub-ch)
          (publish peer pub-ch store bus-in out)

          (sub p :connect conn-ch)
          (connect peer conn-ch out))))
