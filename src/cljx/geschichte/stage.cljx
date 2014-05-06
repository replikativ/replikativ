(ns geschichte.stage
    (:require [konserve.protocols :refer [-get-in]]
              [geschichte.sync :refer [wire]]
              [hasch.core :refer [uuid4]]
              [clojure.set :as set]
              #+clj [clojure.core.async :as async
                     :refer [<! >! timeout chan alt! go put! filter< map< go-loop]]
              #+cljs [cljs.core.async :as async
                      :refer [<! >! timeout chan put! filter< map<]])
    #+cljs (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]))


(defn transact
  "Add a transaction to the stage."
  [stage params trans-code]
  (update-in stage [:transactions] conj [params trans-code]))


(defn commit-history
  "Returns the linear commit history for a stage."
  ([stage]
     (let [{:keys [head branches causal-order]} (:meta stage)]
       (commit-history [] [(first (get-in branches [head :heads]))] causal-order)))
  ([hist stack causal-order]
     (let [[f & r] stack
           hist-set (set hist)
           children (filter #(not (hist-set %)) (causal-order f))]
       (if f
         (if-not (empty? children)
           (recur hist (concat children stack) causal-order)
           (recur (conj hist f) r causal-order))
         hist))))



(defn realize-value
  "Realizes the value of the current stage with help of store and an
application specific eval-fn (e.g. map from source/symbols to fn.).
Does not memoize yet!"
  [stage store eval-fn]
  (go (let [commit-hist (commit-history stage)
            trans-apply (fn [val [params trans-fn]]
                          ((eval-fn trans-fn) val params))]
        (reduce trans-apply
                (loop [val nil
                       [f & r] commit-hist]
                  (if f
                    (let [txs (->> (-get-in store [f])
                                   <!
                                   :transactions
                                   (map (fn [[param-id trans-id]]
                                          (go [(<! (-get-in store [param-id]))
                                               (<! (-get-in store [trans-id]))])))
                                   async/merge
                                   (async/into [])
                                   <!)]
                      (recur (reduce trans-apply val txs) r))
                    val))
                (:transactions stage)))))


#_(defn load-stage ;; not tested yet
  "Loads a stage from peer (with its storage)."
  ([peer author repo schema]
     (load-stage peer author repo schema (chan) (chan)))
  ([peer author repo schema [in out]]
     (go (<! (wire peer [in (async/pub out :topic)]))
         {:author author
          :schema schema
          :meta (<! (-get-in (:store (:volatile @peer)) [author repo]))
          :chans [in out]
          :transactions []})))


(defn wire-stage
  "Wire a stage to a peer."
  [{:keys [chans] :as stage} peer]
  (go (if chans stage
          (let [in (chan 10)
                out (chan)]
            (<! (wire peer [in (async/pub out :topic)]))
            (assoc stage
              :chans [(async/pub in :topic) out]
              :id (uuid4))))))


(defn sync!
  "Synchronize (push) the results of a geschichte.repo command with storage and other peers.
This does not automatically update the stage."
  [{:keys [id type author chans new-values meta] :as stage}]
  (go (let [[p out] chans
            fch (chan)
            pch (chan)
            sch (chan)
            repo (:id meta)
            fetch-loop (go-loop [to-fetch  (:ids (<! fch))]
                         (when to-fetch
                           (>! out {:topic :fetched
                                    :user author :repo repo
                                    :values (select-keys new-values to-fetch)
                                    :peer (str "STAGE " id)})
                           (recur (:ids (<! fch)))))]

        (async/sub p :fetch fch)
        (async/sub p :meta-pubed pch)
        (async/sub p :meta-subed sch)
        (case type
          :meta-pub (>! out {:topic :meta-pub :meta meta :user author :peer (str "STAGE " id)})
          :meta-sub (do
                      (>! out {:topic :meta-sub
                               :metas {author {repo {(:head meta) #{}}}}
                               :peer (str "STAGE " id)})
                      (<! sch)
                      (async/unsub p :meta-subed sch)
                      (>! out {:topic :meta-pub :meta meta :user author :peer (str "STAGE " id)})))



        (let [m (alt! pch (timeout 10000))]
          (when-not m
            (throw #+clj (IllegalStateException. (str "No meta-pubed ack received for" meta))
                   #+cljs (str "No meta-pubed ack received for" meta))))
        (async/unsub p :meta-pubed pch)
        (async/unsub p :fetch fch)
        (async/close! fch)

        stage)))


(defn connect!
  "Connect stage to a remote url of another peer,
e.g. ws://remote.peer.net:1234/geschichte/ws."
  [stage url]
  (let [[p out] (:chans stage)
        connedch (chan)]
    (async/sub p :connected connedch)
    (put! out {:topic :connect
               :url url})
    (go-loop [{u :url} (<! connedch)]
      (when u
        (if-not (= u url)
          (recur (<! connedch))
          (do (println "CONNECTED:" url)
              stage))))))



(comment
  (require '[geschichte.repo :as repo])
  (require '[geschichte.sync :as sync])
  (require '[konserve.store :refer [new-mem-store]])
  (go (def store (<! (new-mem-store))))
  (go (def peer (sync/client-peer "CLIENT" store)))
  (require '[clojure.core.incubator :refer [dissoc-in]])
  (dissoc-in @peer [:volatile :log])
  @(get-in @peer-a [:volatile :store :state])
  (clojure.pprint/pprint (get-in @peer [:volatile :log]))
  (-> @peer :volatile :store)

  (go (let [printfn (partial println "STAGE:")
            stage (repo/new-repository "me@mail.com"
                                       {:type "s" :version 1}
                                       "Testing."
                                       false
                                       {:some 42})
            stage (<! (wire-stage stage peer))
            [in out]  (:chans stage)
            connedch (chan)
            _ (async/sub in :connected connedch)
            _ (go-loop [conned (<! connedch)]
                       (println "CONNECTED-TO:" conned)
                       (recur (<! connedch)))
            _ (<! (timeout 100))
            _ (>! out {:topic :meta-sub
                       :metas {"me@mail.com" {(:id (:meta stage)) {:master #{}}}}})
            _ (<! (timeout 100))
             new-stage (<! (sync! stage))
            ]
        (-> new-stage
            (transact {:helo :world} '(fn more [old params] (merge old params)))
            repo/commit
            sync!
            <!
            (realize-value store {'(fn more [old params] (merge old params))
                                    (fn more [old params] (merge old params))
                                    '(fn replace [old params] params)
                                    (fn replace [old params] params)})
            <!
            printfn))))





(comment
  (def stage (atom nil))
  (go (def store (<! (new-mem-store))))
  (go (def peer (sync/client-peer "CLIENT" store)))
  ;; remote server to sync to
  (require '[geschichte.platform :refer [create-http-kit-handler! start stop]])
  (go (def remote-peer (sync/server-peer (create-http-kit-handler! "ws://localhost:9090/")
                                    (<! (new-mem-store)))))
  (start remote-peer)
  (stop remote-peer)
  (-> @remote-peer :volatile :store)

  (go (>! (second (:chans @stage)) {:topic :meta-pub-req
                                    :user "me@mail.com"
                                    :repo #uuid "94482d4c-a4ba-4069-b017-b70c9027bb9a"
                                    :metas {"master" #{}}}))

  (first (-> @peer :volatile :chans))

  (let [pub-ch (chan)]
    (async/sub (first (:chans @stage)) :meta-pub pub-ch)
    (go-loop [p (<! pub-ch)]
             (when p
               (println  "META-PUB:" p)
               (recur (<! pub-ch)))))


  (go (println (<! (s/realize-value @stage (-> @peer :volatile :store) eval))))
  (go (println
       (let [new-stage (-> (repo/new-repository "me@mail.com"
                                                {:type "s" :version 1}
                                                "Testing."
                                                false
                                                {:some 43})
                           (wire-stage peer)
                           <!
                           (connect! "ws://localhost:9090/")
                           <!
                           sync!
                           <!)]
         (println "NEW-STAGE:" new-stage)
         (reset! stage new-stage)
         #_(swap! stage (fn [old stage] stage)
                  (->> (s/transact new-stage
                                   {:other 43}
                                   '(fn merger [old params] (merge old params)))
                       repo/commit
                       sync!
                       <!))))))
