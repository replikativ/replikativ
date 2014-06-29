(ns geschichte.auth
  "Authentication middleware for geschichte."
  (:require [geschichte.sync :refer [possible-commits]]
            [geschichte.platform-log :refer [debug info warn error]]
            [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
            [clojure.set :as set]
            #+clj [clojure.core.async :as async
                   :refer [<! >! >!! <!! timeout chan alt! go put!
                           filter< map< go-loop pub sub unsub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]])
  #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


(defn- new-commits! [store metas]
  (go (->> (for [[user repos] metas
                 [repo meta] repos]
             (go {:new meta
                  :old (<! (-get-in store [user repo]))
                  :user user
                  :repo repo}))
           async/merge
           (async/into [])
           <!
           (map #(assoc % :new-commits
                        (set/difference (possible-commits (:new %))
                                        (possible-commits (:old %)))))
           (filter #(not (empty? (:new-commits %)))))))


(defn auth
  "Authorize publications containing new data and TODO subscriptions against private repositories
 against friend-like credential-fn. Supply an auth-fn taking a set of usernames,
returning a go-channel with a user->password map."
  [store auth-fn credential-fn trusted-hosts [in out]]
  (let [new-in (chan)
        sessions (atom {})]
    (go-loop [i (<! in)]
      (if i
        (do
          (case (:topic i)
            :meta-pub
            (let [nc (<! (new-commits! store (:metas i)))]
              (<! (go-loop [auth-count 0]
                    (let [not-auth (filter #(not (@sessions (:user %))) nc)]
                      (if (or (@trusted-hosts (:host (meta i)))
                              (empty? not-auth))
                        (do
                          (debug "AUTH" i)
                          (>! new-in (assoc i ::authed true)))
                        (do
                          (debug "NOT-AUTH" not-auth)
                          (>! out {:topic ::auth-required :users (set (map :user not-auth))})
                          (swap! sessions
                                 (fn [old creds] (reduce #(assoc %1 (:username %2) %2) old creds))
                                 (->> (<! (go-loop [i (<! in)] ;; pass through, maybe use pub?
                                            (if (= (:topic i) ::auth)
                                              i
                                              (do (when-not (= (:topic i) ;; TODO shield properly
                                                               :meta-pub)
                                                    (>! new-in i))
                                                  (recur (<! in))))))
                                      :users
                                      (map (fn [[k v]] (credential-fn {:username k :password v})))
                                      (filter (comp not nil?))))
                          (debug "NEW-SESSIONS" sessions)
                          (when (< auth-count 3)
                            (recur (inc auth-count)))))))))

            ::auth-required (do (debug "AUTH-REQUIRED:" i)
                                (>! out {:topic ::auth
                                         :users (<! (auth-fn (:users i)))}))

            ::register (do (warn "REGISTER:" i))
            (>! new-in i))
          (recur (<! in)))
        (close! new-in)))
    [new-in out]))


(comment
  (require '[konserve.store :refer [new-mem-store]])
  (let [in (chan)
        out (chan)
        [new-in new-out] (auth (<!! (new-mem-store (atom {"user@mail.com" {1 {:causal-order {10 []}}}})))
                               (fn [users] (go (zipmap users (repeat "P4ssw0rd"))))
                               (fn [token] (if (= (:password token) "password")
                                            (dissoc token :password)))
                               (atom #{})
                               [in out])]
    (>!! in (with-meta {:topic :meta-pub :metas {"user@mail.com" {1 {:causal-order {10 []
                                                                                    20 [10]}}}}}
              {:host "127.0.0.1"}))
    (println "OUT:" (<!! out))
    (>!! in {:topic ::auth :users {"user@mail.com" "password"}})
    (println "NEW-IN:" (<!! new-in))
    #_(>!! in {:topic ::auth-required
               :users #{"eve@mail.com"}})
    #_(println "OUT:" (<!! out))))
