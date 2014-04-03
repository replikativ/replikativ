(ns ^:shared geschichte.sync
    "Synching related pub-sub protocols."
    (:require [geschichte.meta :refer [update]]
              [geschichte.stage :as s]
              [konserve.protocols :refer [IAsyncKeyValueStore -assoc-in -get-in -update-in]]
              [geschichte.debug-channels :as debug]
              [clojure.set :as set]
              [clojure.data :refer [Diff diff] :as data]
              [geschichte.platform :refer [client-connect!]]
              #+clj [clojure.core.async :as async
                     :refer [<! >! timeout chan alt! go put! filter< map< go-loop]]
              #+cljs [cljs.core.async :as async
                     :refer [<! >! timeout chan put! filter< map<]])
    #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


;; HACK from clojure.data, might break
(def ^:dynamic *dumb-diff* false)

(defn- vectorize
  "Convert an associative-by-numeric-index collection into
   an equivalent vector, with nil for any missing keys"
  [m]
  (when (seq m)
    (reduce
     (fn [result [k v]] (assoc result k v))
     (vec (repeat (apply max (keys m))  nil))
     m)))

(defn- diff-associative-key
  "Diff associative things a and b, comparing only the key k."
  [a b k]
  (let [va (get a k)
        vb (get b k)
        [a* b* ab] (diff va vb)
        in-a (contains? a k)
        in-b (contains? b k)
        same (and in-a in-b
                  (or (not (nil? ab))
                      (and (nil? va) (nil? vb))))]
    [(when (and in-a (or (not (nil? a*)) (not same))) {k a*})
     (when (and in-b (or (not (nil? b*)) (not same))) {k b*})
     (when same {k ab})
     ]))

(defn- diff-associative
  "Diff associative things a and b, comparing only keys in ks."
  [a b ks]
  (reduce
   (fn [diff1 diff2]
     (doall (map merge diff1 diff2)))
   [nil nil nil]
   (map
    (partial diff-associative-key a b)
    ks)))

(defn- diff-sequential
  [a b]
  (vec (map vectorize (diff-associative
                       (if (vector? a) a (vec a))
                       (if (vector? b) b (vec b))
                       (range (max (count a) (count b)))))))


(extend-protocol Diff
  java.util.List
  (diff-similar [a b]
    (if *dumb-diff*
      (if (= a b)
        [nil nil a]
        [a b nil])
      (diff-sequential a b))))


(comment
  (diff [1 2] [1 2 3])
  (diff {:a 1 :b 2}
        {:a 1})


(declare wire)
(defn client-peer
  "Creates a client-side peer only."
  [name store]
  (let [log (atom {})
        in (debug/chan log [name :in])
        out (async/pub in :topic)]
    (atom {:volatile {:log log
                      :chans [in out]
                      :store store}
           :name name
           :meta-sub {}})))


(defn server-peer
  "Constructs a listening peer.
You need to integrate returned :handler to run it."
  [handler store]
  (let [{:keys [new-conns url]} handler
        log (atom {})
        in (debug/chan log [url :in])
        out (async/pub in :topic)
        peer (atom {:volatile (merge handler
                                     {:store store
                                      :log log
                                      :chans [in out]})
                    :name (:url handler)
                    :meta-sub {}})]
    (go-loop [[in out] (<! new-conns)]
             (wire peer [out (async/pub in :topic)])
             (recur (<! new-conns)))
    peer))


;; TODO remove
(defn- new-commits [meta-sub old-meta]
  (set/difference (set (keys (:causal-order meta-sub)))
                  (set (keys (:causal-order old-meta)))))


(defn possible-commits [meta]
  (reduce set/union
          (set (keys (:causal-order meta)))
          (->> meta :branches vals (mapcat (comp vals :indexes))
               (map set))))


(defn- new-commits! [store meta-sub old-meta]
  (->> (map #(go [(not (<!(-get-in store [%]))) %])
            (set/difference (possible-commits meta-sub)
                            (possible-commits old-meta)))
       async/merge
       (filter< first)
       (map< second)
       (async/into #{})))


(defn subscribe
  "Store and propagate subscription requests."
  [peer sub-ch out]
  (let [[bus-in bus-out] (-> @peer :volatile :chans)
        pubs-ch (chan)
        _ (async/sub bus-out :meta-pub pubs-ch)
        p (async/pub pubs-ch (fn [{{r :id} :meta u :user}] [u r]))]
    (async/sub bus-out :meta-sub out)
    (go-loop [{:keys [metas] :as s} (<! sub-ch)]
             (when s
               (doseq [user (keys metas)
                       repo (get metas user)]
                 (async/sub p [user repo] out))

               ;; incrementally propagate subscription to other peers
               (let [new-subs (->> (:meta-sub @peer)
                                   (merge-with set/difference metas)
                                   (filter (fn [[k v]] (not (empty? v))))
                                   (into {}))]
                 (when-not (empty? new-subs)
                   (>! bus-in {:topic :meta-sub :metas new-subs})))

               (swap! peer update-in [:meta-sub] (partial merge-with set/union) metas)

               (>! out {:topic :meta-subed :metas metas})

               (recur (<! sub-ch))))))


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


(defn- filter-subs [subs new old]
  (let [diff (first (binding [*dumb-diff* true]
                      (diff new old)))
        {:keys [branches]} diff
        full-branch-keys (->> subs
                              (filter (fn [[k v]] (empty? v)))
                              first
                              (into #{}))
        partial-branches (reduce (fn [acc k]
                                   (-> acc
                                       (update-in [k :indexes] select-keys (get subs k))
                                       (update-in [k] dissoc :heads)))
                                 (:branches diff)
                                 (set/difference (set (keys branches)) full-branch-keys))
        new-meta (assoc diff
                   :branches (merge partial-branches (select-keys branches full-branch-keys))
                   :id (:id new)
                   :last-update (:last-update new))]
    (if (empty? full-branch-keys)
      (dissoc new-meta :causal-order)
      new-meta)))



#_(filter-subs {"master" #{:politics}}
             {:branches {"master" {:indexes {:economy [1]
                                             :ecology [2]
                                             :politics [2 4]}}}}
             {:branches {"master" {:indexes {:economy [1 4]
                                             :politics [2]}}}})


(defn new-subscribe
  "Store and propagate subscription requests."
  [peer sub-ch out]
  (let [[bus-in bus-out] (-> @peer :volatile :chans)]
    (async/sub bus-out :meta-sub out)
    (go-loop [{:keys [metas] :as s} (<! sub-ch)
              prev-ch nil]
             (when s
               (let [pubs-ch (chan)
                     pub-pub (async/pub pubs-ch (fn [{{r :id} :meta u :user}] [u r]))]
                 (async/sub bus-out :meta-pub pubs-ch)
                 (when prev-ch (async/close! prev-ch))
                 (doseq [user (keys metas)
                         repo (keys (get metas user))]
                   (let [pub-ch (chan)]
                     (async/sub pub-pub [user repo] pub-ch)
                     (go-loop [{:keys [meta] :as p} (<! pub-ch)
                               old nil]
                              (when p
                                (>! out (assoc p
                                          :meta (filter-subs (get-in metas [user repo])
                                                             meta old)
                                          :user user))
                                (recur (<! pub-ch) meta)))))

                 ;; incrementally propagate subscription to other peers
                 (let [[new-subs] (diff metas (:meta-sub @peer))]
                   (when new-subs
                     (>! bus-in {:topic :meta-sub :metas new-subs})))

                 (swap! peer update-in [:meta-sub] (partial deep-merge-with set/union) metas)

                 (>! out {:topic :meta-subed :metas metas})

                 (recur (<! sub-ch) pubs-ch))))))



(defn publish
  "Synchronize metadata publications by fetching missing repository values."
  [peer pub-ch fetched-ch store bus-in out]
  (go-loop [{:keys [user meta] :as p} (<! pub-ch)]
           (when p
             (let [repo (:id meta)
                   nc (<! (new-commits! store meta (<! (-get-in store [user repo]))))]
               (when-not (empty? nc)
                 (>! out {:topic :fetch
                          :ids nc})
                 ;; TODO check hash
                 (doseq [[trans-id val] (:values (<! fetched-ch))] ;; TODO timeout
                   (<! (-assoc-in store [trans-id] val))))

               (>! out {:topic :meta-pubed})
               (let [[old-meta up-meta]
                     (<! (-update-in store [user repo] #(if % (update % meta)
                                                            (update meta meta))))]
                 (when (not= old-meta up-meta)
                   (>! bus-in (assoc p :meta up-meta))))

               (recur (<! pub-ch))))))


#_(->> {:id 1
      :causal-order {1 #{}
                     2 #{1}}
      :last-update (java.util.Date. 0)
      :description "Bookmark collection."
      :head "master"
      :branches {"master" {:indexes {:economy #{2}
                                     :politics #{1}}}}
      :schema {:type :geschichte
               :version 1}}
     :branches
     vals
     (mapcat (comp vals :indexes)))


(defn connect
  "Service connection requests."
  [peer conn-ch out]
  (go-loop [{:keys [url] :as c} (<! conn-ch)]
           (when c
             (let [[bus-in bus-out] (:chans (:volatile @peer))
                   log (:log (:volatile @peer))
                   [c-in c-out] (<! (client-connect! url))
                   subs (:meta-sub @peer)
                   subed-ch (chan)]
               ;; handshake
               (>! c-out {:topic :meta-sub :metas subs})
               (put! subed-ch (<! c-in))
               (new-subscribe peer subed-ch c-out)

               (<! (wire peer [c-out (async/pub c-in :topic)]))
               (>! out {:topic :connected
                        :url url})
               (recur (<! conn-ch))))))


(defn fetch
  "Service (remote) fetch requests."
  [peer fetch-ch out]
  (go-loop [{:keys [user repo ids] :as m} (<! fetch-ch)]
           (when m
             (let [store (:store (:volatile @peer))
                   fetched (->> ids
                                (map (fn [id] (go [id (<! (-get-in store [id]))])))
                                async/merge
                                (async/into {})
                                <!)]
               (>! out {:topic :fetched
                        :values fetched})
               (recur (<! fetch-ch))))))


(defn wire
  "Wire a peer to an output (response) channel and a publication by :topic of the input."
  [peer [out p]]
  (go (let [{:keys [store chans log]} (:volatile @peer)
            name (:name @peer)
            [bus-in bus-out] chans
            ;; unblock on fast publications, newest always superset
            pub-ch (debug/chan log [name :pub] (async/sliding-buffer 1))
            conn-ch (debug/chan log [name :conn])
            sub-ch (debug/chan log [name :sub])
            fetch-ch (debug/chan log [name :fetch])
            fetched-ch (debug/chan log [name :fetched])]
        ;; HACK drop those for cljs core.async pub
        (async/sub bus-out :meta-subed (chan (async/sliding-buffer 1)))
        (async/sub bus-out :meta-pubed (chan (async/sliding-buffer 1)))
        (async/sub p :meta-subed (chan (async/sliding-buffer 1)))
        (async/sub p :meta-pubed (chan (async/sliding-buffer 1)))

        (async/sub p :meta-sub sub-ch)
        (new-subscribe peer sub-ch out)

        (async/sub p :meta-pub pub-ch)
        (async/sub p :fetched fetched-ch)
        (publish peer pub-ch fetched-ch store bus-in out)

        (async/sub p :fetch fetch-ch)
        (fetch peer fetch-ch out)

        (async/sub p :connect conn-ch)
        (connect peer conn-ch out)

        (async/sub p nil (debug/chan log [name :unsupported]) false))))


(defn load-stage!
  ([peer author repo schema]
     (load-stage! peer author repo schema (chan) (chan)))
  ([peer author repo schema [in out]]
     (go (<! (wire peer [in (async/pub out :topic)]))
         {:author author
          :schema schema
          :meta (<! (-get-in (:store (:volatile @peer)) [author repo]))
          :chans [in out]
          :transactions []})))


(defn wire-stage
  "Wire a stage to a peer."
  [peer {:keys [chans] :as stage}]
  (go (if chans stage
          (let [log (atom {})
                in (debug/chan log ["STAGE" :in] 10)
                out (debug/chan log ["STAGE" :out])]
            (<! (wire peer [in (async/pub out :topic)]))
            (assoc stage :chans [(async/pub in :topic) out])))))


(defn sync!
  "Synchronize the results of a geschichte.repo command with storage and other peers."
  [{:keys [type author chans new-values meta] :as stage}]
  (go (let [[p out] chans
            fch (chan)
            pch (chan)
            repo (:id meta)]

        (async/sub p :fetch fch)
        (async/sub p :meta-pubed pch)
        (case type
          :meta-pub  (>! out {:topic :meta-pub :meta meta :user author})
          :meta-sub (do
                      (>! out {:topic :meta-sub :metas {author #{repo}}})
                      (>! out {:topic :meta-pub :meta meta :user author})))

        (go (let [to-fetch (select-keys new-values (:ids (<! fch)))]
              (>! out {:topic :fetched
                       :user author :repo repo
                       :values to-fetch})))

        (let [m (alt! pch (timeout 10000))]
          (when-not m
            (throw #+clj (IllegalStateException. (str "No meta-pubed ack received for" meta))
                   #+cljs (str "No meta-pubed ack received for" meta))))
        (async/unsub p :meta-pubed fch)
        (async/unsub p :fetch fch)

        (dissoc stage :type :new-values))))


(defn connect! [url stage]
  (let [[p _] (:chans stage)
        connedch (chan)]
    (async/sub p :connected connedch)
    (go-loop [{u :url} (<! connedch)]
             (if-not (= u url)
                 (recur (<! connedch))))))



(comment
  (require '[geschichte.repo :as repo])
  (require '[konserve.store :refer [new-mem-store]])
  (def peer (client-peer "CLIENT" (new-mem-store)))
  (require '[clojure.core.incubator :refer [dissoc-in]])
  (dissoc-in @peer [:volatile :log])
  @(get-in @peer-a [:volatile :store :state])
  (clojure.pprint/pprint (get-in @peer [:volatile :log]))
  (-> @peer-a :volatile :store)

  (go (let [printfn (partial println "STAGE:")
            stage (repo/new-repository "me@mail.com"
                                       {:type "s" :version 1}
                                       "Testing."
                                       false
                                       {:some 42})
            stage (<! (wire-stage peer stage))
            [in out]  (:chans stage)
            connedch (chan)
            _ (async/sub in :connected connedch)
            _ (go-loop [conned (<! connedch)]
                       (println "CONNECTED-TO:" conned)
                       (recur (<! connedch)))
            _ (<! (timeout 100))
            _ (>! out {:topic :meta-sub
                       :metas {"me@mail.com" #{(:id (:meta stage))}}})
            _ (<! (timeout 100))
            _ (>! out {:topic :connect
                       :ip4 "127.0.0.1"
                       :port 9090})
            _ (<! (timeout 1000))
             new-stage (<! (sync! stage))
            ]
        (-> new-stage
            (s/transact {:helo :world} '(fn more [old params] old))
            repo/commit
            sync!
            <!
            s/realize-value
            printfn))))





(comment
  (def stage (atom nil))

  (go (println (<! (s/realize-value @stage (-> @peer :volatile :store) eval))))
  (go (println
       (let [new-stage (->> (repo/new-repository "me@mail.com"
                                                 {:type "s" :version 1}
                                                 "Testing."
                                                 false
                                                 {:some 43})
                            (wire-stage peer)
                            <!
                            sync!
                            <!)]
         (reset! stage new-stage)
         #_(swap! stage (fn [old stage] stage)
                  (->> (s/transact new-stage
                                   {:other 43}
                                   '(fn merger [old params] (merge old params)))
                       repo/commit
                       sync!
                       <!))))))
