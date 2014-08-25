(ns geschichte.auth
  "Authentication middleware for geschichte."
  (:require [geschichte.platform-log :refer [debug info warn error]]
            [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
            [clojure.set :as set]
            [postal.core :refer [send-message]]
            #+clj [clojure.core.async :as async
                   :refer [<! >! >!! <!! timeout chan alt! go put!
                           filter< map< go-loop pub sub unsub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]])
  #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))

(defn possible-commits
  [meta]
  (set (keys (:causal-order meta))))

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

;; https://medium.com/@ninjudd/passwords-are-obsolete-9ed56d483eb
;; Passwordless Authentication

;; Here’s how passwordless authentication works in more detail:

;;     Instead of asking users for a password when they try to log in to your app or website, just ask them for their username (or email or mobile phone number).
;;     Create a temporary authorization code on the backend server and store it in your database.
;;     Send the user an email or SMS with a link that contains the code.
;;     The user clicks the link which opens your app or website and sends the authorization code to your server.
;;     On your backend server, verify that the code is valid and exchange it for a long-lived token, which is stored in your database and sent back to be stored on the client device as well.
;;     The user is now logged in, and doesn’t have to repeat this process again until their token expires or they want to authenticate on a new device.

(defn meta-published [pub-ch store trusted-hosts credential-fn [new-in out]]
  (let [sessions (atom {})]
    (go-loop [{:keys [metas] :as p} (<! pub-ch)]
      (when p
        (let [nc (<! (new-commits! store metas))]
          (<! (go-loop [auth-count 0]
                (let [not-auth (filter #(not (@sessions (:user %))) nc)]
                  (if (or (@trusted-hosts (:host (meta p)))
                          (empty? not-auth))
                    (do
                      (debug "AUTH" p)
                      (>! new-in (assoc p ::authed true))) ;; annotate authenticated user(s)
                    (do
                      (debug "NOT-AUTH" not-auth)
                      (>! out {:topic ::auth-required :users (set (map :user not-auth))})
                      (swap! sessions
                             (fn [old creds] (reduce #(assoc %1 (:username %2) %2) old creds))
                             (->> (<! (go-loop [p (<! pub-ch)] ;; pass through, maybe use pub?
                                        (if (= (:topic p) ::auth)
                                          p
                                          (do (>! new-in p)
                                              (recur (<! pub-ch))))))
                                  :users
                                  (map (fn [[k v]] (credential-fn {:username k :password v})))
                                  (filter (comp not nil?))))
                      (debug "NEW-SESSIONS" sessions)
                      (when (< auth-count 3)
                        (recur (inc auth-count)))))))))
        (recur (<! pub-ch))))))

(defn auth-required [auth-req-ch auth-fn out]
  (go-loop [{:keys [users] :as a} (<! auth-req-ch)]
    (when a
      (debug "AUTH-REQUIRED:" a)
      (>! out {:topic ::auth
               :users (<! (auth-fn users))})
      (recur (<! auth-req-ch)))))


(defn in-dispatch [{:keys [topic]}]
  (case topic
    :meta-pub :meta-pub
    ::auth-required ::auth-required
    :unrelated))

(defn auth
  "Authorize publications containing new data and TODO subscriptions against private repositories
 against friend-like credential-fn. Supply an auth-fn taking a set of usernames,
returning a go-channel with a user->password map."
  [store auth-fn credential-fn trusted-hosts [in out]]
  (let [new-in (chan)
        sessions (atom {})
        p (pub in in-dispatch)
        pub-ch (chan)
        auth-req-ch (chan)
        register (chan)] ;; TODO
    (sub p :meta-pub)
    (meta-published pub-ch store trusted-hosts credential-fn [new-in out])

    (sub p ::auth-required)
    (auth-required auth-req-ch auth-fn out)

    (sub p :unrelated new-in)
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
