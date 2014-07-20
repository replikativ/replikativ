(ns geschichte.sync
    "Synching related pub-sub protocols."
    (:require [geschichte.meta :refer [update isolate-branch]]
              [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
              [geschichte.platform-log :refer [debug info warn error]]
              [clojure.set :as set]
              [hasch.core :refer [uuid uuid?]]
              [geschichte.platform-data :refer [diff]]
              [geschichte.platform :refer [client-connect!]]
              #+clj [clojure.core.async :as async
                     :refer [<! >! timeout chan alt! go put!
                             filter< map< go-loop pub sub unsub close!]]
              #+cljs [cljs.core.async :as async
                     :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]])
    #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


(declare wire)
(defn client-peer
  "Creates a client-side peer only."
  [name store middleware]
  (let [log (atom {})
        in (chan)
        out (pub in :topic)]
    (atom {:volatile {:log log
                      :middleware middleware
                      :chans [in out]
                      :store store}
           :name name
           :meta-sub {}})))


(defn server-peer
  "Constructs a listening peer.
You need to integrate returned :handler to run it."
  [handler store middleware]
  (let [{:keys [new-conns url]} handler
        log (atom {})
        in (chan)
        out (pub in :topic)
        peer (atom {:volatile (merge handler
                                     {:store store
                                      :middleware middleware
                                      :log log
                                      :chans [in out]})
                    :name (:url handler)
                    :meta-sub {}})]
    (go-loop [[in out] (<! new-conns)]
      (<! (wire peer [in out]))
      (recur (<! new-conns)))
    peer))


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
  (reduce (fn [res [user v]]
            (let [nv (reduce (fn [res [repo v]]
                               (let [branches (get-in sbs [user repo])
                                     branches-causal (apply set/union
                                                            (map (comp set keys (partial isolate-branch v))
                                                                 branches))]
                                 (assoc res repo
                                        (-> v
                                            (update-in [:causal-order] select-keys branches-causal)
                                            (update-in [:branches] select-keys branches)))))
                             res
                             (select-keys v (set (keys (sbs user)))))]
              (if-not (empty? nv)
                (assoc res user nv)
                res)))
          {}
          (select-keys metas (set (keys sbs)))))


(defn subscribe
  "Store and propagate subscription requests."
  [peer sub-ch pubed-ch out]
  (let [{:keys [chans log]} (-> @peer :volatile)
        [bus-in bus-out] chans
        pn (:name @peer)]
    (sub bus-out :meta-sub out)
    (go-loop [{sub-metas :metas :as s} (<! sub-ch)
              old-subs nil
              old-pub-ch nil]
      (if s
        (let [new-subs (:meta-sub (swap! peer
                                         update-in
                                         [:meta-sub]
                                         (partial deep-merge-with set/union) sub-metas))
              pub-ch (chan)]
          (info pn "starting subscription from" (:peer s))
          (debug pn "subscriptions:" sub-metas)
          ;; properly restart go-loop
          (when old-pub-ch
            (async/unsub bus-out :meta-pub old-pub-ch)
            (close! old-pub-ch))
          (sub bus-out :meta-pub pub-ch)
          (go-loop [{:keys [metas] :as p} (<! pub-ch)]
            (debug "GO-LOOP-PUB" p)
            (when p
              ;; TODO move delta compression to middleware
              (let [new-metas (filter-subs sub-metas metas)]
                (debug "NEW-METAS" metas "subs" sub-metas new-metas)
                (when-not (empty? new-metas)
                  (debug pn "publishing" new-metas "to" (:peer s))
                  (>! out (assoc p
                            :metas new-metas
                            :peer pn))
                  (<! pubed-ch)
                  (debug pn "published"))
                (recur (<! pub-ch)))))

          (when-not (= new-subs old-subs)
            (>! out {:topic :meta-sub :metas new-subs :peer pn})
            (let [[new] (diff new-subs old-subs)] ;; pull all new repos
              (debug "subscribing to new subs:" new)
              (>! out {:topic :meta-pub-req
                       :peer pn
                       :metas new})))

          (>! out {:topic :meta-subed :metas sub-metas :peer (:peer s)})
          ;; propagate that the remote has subscribed (for connect)
          (>! bus-in {:topic :meta-subed :metas sub-metas :peer (:peer s)})
          (debug pn "finishing subscription")

          (recur (<! sub-ch) new-subs pub-ch))
        (do (debug "closing old-pub-ch")
            (unsub bus-out :meta-pub old-pub-ch)
            (unsub bus-out :meta-sub out)
            (close! old-pub-ch))))))

(defn update-metas [store metas]
  (->> (for [[user repos] metas
             [repo meta] repos]
         (go [[user repo]
              (<! (-update-in store [user repo] #(if % (update % meta)
                                                     (update meta meta))))]))
       async/merge
       (async/into [])))


(defn publish
  "Synchronize metadata publications by fetching missing repository values."
  [peer pub-ch store bus-in out]
  (go-loop [{:keys [metas] :as p} (<! pub-ch)]
    (when p
      (let [pn (:name @peer)
            remote (:peer p)]
        (>! out {:topic :meta-pubed
                 :peer (:peer p)})
        ;; update all repos of all users
        (let [up-metas (<! (update-metas store metas))]
          (when (some true? (map #(let [[old-meta up-meta] (second %)]
                                    (not= old-meta up-meta)) up-metas))
            (let [new-metas (reduce #(assoc-in %1 (first %2)
                                               (second (second %2))) metas up-metas)]
              (info pn "new-metas:" new-metas)
              (>! bus-in (assoc p :peer pn :metas new-metas))
              (debug pn "sent new-metas"))))

        (recur (<! pub-ch))))))


(defn connect
  "Service connection requests."
  [peer conn-ch out]
  (go-loop [{:keys [url] :as c} (<! conn-ch)]
    (when c
      (debug (:name @peer) "connecting to:" url)
      (let [[bus-in bus-out] (:chans (:volatile @peer))
            pn (:name @peer)
            log (:log (:volatile @peer))
            [c-in c-out] (<! (client-connect! url (:tag-table (:store (:volatile @peer)))))
            subs (:meta-sub @peer)
            subed-ch (chan)]
        ;; handshake
        (sub bus-out :meta-subed subed-ch)
        (<! (wire peer [c-in c-out]))
        (>! c-out {:topic :meta-sub :metas subs :peer pn})
        ;; HACK? wait for ack on backsubscription, is there a simpler way?
        (<! (go-loop [{u :peer :as c} (<! subed-ch)]
              (when (and c (not= u url))
                (recur (<! subed-ch)))))
        (async/close! subed-ch)

        (>! out {:topic :connected
                 :url url
                 :peer (:peer c)})
        (recur (<! conn-ch))))))


(defn publish-requests
  "Handles publication requests (at connection atm.)."
  [peer pub-req-ch out]
  (let [[_ bus-out] (-> @peer :volatile :chans)]
    (sub bus-out :meta-pub-req out)
    (go-loop [{req-metas :metas :as pr} (<! pub-req-ch)]
      (when pr
        (let [metas-list (->> (for [[user repos] req-metas
                                   [repo meta] repos]
                               (go [[user repo] (<! (-get-in (-> @peer :volatile :store) [user repo]))]))
                             async/merge
                             (filter< second)
                             (async/into [])
                             <!)
              metas (reduce #(assoc-in %1 (first %2) (second %2)) nil metas-list)]
          (when metas
            (debug (:name @peer) "meta-pub-req reply:" metas)
            (>! out {:topic :meta-pub
                     :peer (:name @peer)
                     :metas (filter-subs req-metas metas nil)})))
        (recur (<! pub-req-ch))))))


(defn wire
  "Wire a peer to an output (response) channel and a publication by :topic of the input."
  [peer [in out]]
  (go (let [[in out] ((:middleware (:volatile @peer)) [in out])
            p (pub in :topic)
            {:keys [store chans log]} (:volatile @peer)
            name (:name @peer)
            [bus-in bus-out] chans
            pub-ch (chan)
            pub-req-ch (chan)
            pubed-ch (chan)
            conn-ch (chan)
            sub-ch (chan)
            fetched-ch (chan)]

        (sub p :meta-sub sub-ch)
        (sub p :meta-pubed pubed-ch)
        (subscribe peer sub-ch pubed-ch out)

        (sub p :meta-pub pub-ch)
        (publish peer pub-ch store bus-in out)

        (sub p :meta-pub-req pub-req-ch)
        (publish-requests peer pub-req-ch out)

        (sub p :connect conn-ch)
        (connect peer conn-ch out))))
