(ns replikativ.p2p.filter-subs
  "Limit subscriptions and downstream messages to a mask of users and
  crdt-ids of a peer."
  (:require [replikativ.platform-log :refer [debug info warn error]]
            [konserve.core :as k]
            #?(:clj [full.async :refer [go-try go-loop-try <?]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [full.cljs.async :refer [<? <<? go-for go-try go-loop-try go-loop-try> alt?]])))

(defn dispatch [{:keys [type]}]
  (case type
    :sub/identities :sub/identities
    :pub/downstream :pub/downstream
    :unrelated))

(defn filter-subs [f ids]
  (if (empty? f) ids
      (->> (for [[u crdt-ids] ids
                 :when (f u)
                 id crdt-ids
                 :when (or (= (f u) :all)
                           (get-in f [u id]))]
             [u id])
           (reduce (fn [new-ids [u id]]
                     (update-in new-ids [u] #(conj (or % #{}) id)))
                   {}))))

(comment
  (filter-subs {"john" #{42}
                "eve" :all} {"john" #{42 43}
                             "eve" #{1 2 3}}))

(defn filter-subs-handler [store ch new-in]
  (go-loop-try [{:keys [identities] :as s} (<? ch)]
               (when s
                 (>! new-in (assoc s :identities
                                   (filter-subs (<? (k/get-in store [:peer-config :sub-filter]))
                                                identities)))
                 (recur (<? ch)))))


(defn filter-pubs-handler [store ch new-in]
  (go-loop-try [{:keys [user crdt-id] :as p} (<? ch)]
               (when p
                 (let [f (<? (k/get-in store [:peer-config :sub-filter]))]
                   (when (or (not f)
                             (and (f user)
                                  (or (= (f user) :all)
                                      (get-in f [user crdt-id]))))
                     (>! new-in p)))
                 (recur (<? ch)))))


(defn filtered-subscriptions [[peer [in out]]]
  (let [store (get-in @peer [:volatile :store])
        new-in (chan)
        p (pub in dispatch)
        filter-sub-ch (chan)
        filter-pub-ch (chan)]
    (sub p :sub/identities filter-sub-ch)
    (filter-subs-handler store filter-sub-ch new-in)

    (sub p :pub/downstream filter-pub-ch)
    (filter-pubs-handler store filter-pub-ch new-in)

    (sub p :unrelated new-in)

    [peer [new-in out]]))
