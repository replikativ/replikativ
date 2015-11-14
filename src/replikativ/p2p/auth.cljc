(ns replikativ.p2p.auth
  "Authentication middleware for replikativ. WIP"
  (:require [replikativ.platform-log :refer [debug info warn error]]
            [konserve.core :as k]
            [hasch.core :refer [uuid]]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [<! >! >!! <!! timeout chan alt! go put!
                             filter< map< go-loop pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])))


;; TODO WIP code which is not working!

(defn possible-commits
  [meta]
  (set (keys (:commit-graph meta))))

(defn- new-commits!
  "Computes difference between local and remote repo"
  [store metas]
  (go (->> (for [[user repos] metas
                 [repo meta] repos]
             (go {:new meta
                  :old (<! (k/get-in store [user repo]))
                  :user user
                  :repo repo}))
           async/merge
           (async/into [])
           <!
           (map #(assoc % :new-commits
                        (set/difference (possible-commits (:new %))
                                        (possible-commits (:old %)))))
           (filter #(not (empty? (:new-commits %)))))))


(defn- verify-users
  "Verify given users with the credential function"
  [users credential-fn]
  (->> users
       (map (fn [[k v]]
              (vec [k
                    (if (credential-fn {:username k :password v})
                      (-> {(uuid) (java.util.Date.)})
                      nil)])))
       flatten))

;; https://medium.com/@ninjudd/passwords-are-obsolete-9ed56d483eb
;; Passwordless Authentication

;; Here’s how passwordless authentication works in more detail:

;;     Instead of asking users for a password when they try to log in to your app or website, just ask them for their username (or email or mobile phone number).
;;     Create a temporary authorization code on the backend server and store it in your database.
;;     Send the user an email or SMS with a link that contains the code.
;;     The user clicks the link which opens your app or website and sends the authorization code to your server.
;;     On your backend server, verify that the code is valid and exchange it for a long-lived token, which is stored in your database and sent back to be stored on the client device as well.
;;     The user is now logged in, and doesn’t have to repeat this process again until their token expires or they want to authenticate on a new device.

(defn- pub-auth
  "Checks wether the user has a running session, the host is trusted und verifies credentials if the user ist not authenticated"
  [pub-ch store trusted-hosts sessions credential-fn [new-in out]]
  (go-loop [{:keys [metas type] :as p} (<! pub-ch)]
    (when (= type :pub/downstream)
     (let [nc (<! (new-commits! store metas))]
       (<! (go-loop [counter 0]
             (if-let [host (@trusted-hosts (-> p meta :host))]
               (do
                 (debug "AUTHED" host)
                 (>! new-in (assoc p
                              ::authed true
                              :host host))
                 (>! out {:type ::authed
                          :host host}))
               (let [not-auth (filter #(not (@sessions (:user %))) nc)]
                 (if (empty? not-auth)
                   (do
                     (debug "AUTHED" (set (keys @sessions)))
                     (>! new-in (assoc p
                                  ::authed true
                                  :users (set (keys @sessions))))
                     (>! out {:type ::authed
                              :users (set (keys @sessions))}))
                   (if (> counter 4)
                     (do
                       (debug "AUTH-FAILED" (set (map :user not-auth)))
                       (>! out {:type ::auth-failed
                                :users (set (map :user not-auth))}))
                     (do
                       (debug "AUTH-REQ" (set (map :user not-auth)))
                       (>! out {:type ::auth-required
                                :users (set (map :user not-auth))
                                :tries-left (- 5 counter)})
                       (<! (go-loop [p (<! pub-ch)]
                             (if (= (:type p) ::auth)
                               (swap! sessions
                                      (fn [old new] (apply assoc old new))
                                      (verify-users (:users p) credential-fn))
                               (recur (<! pub-ch)))))
                       (recur (inc counter)))))))))))
    (recur (<! pub-ch))))


(defn- auth-required
  "Sends the credntials of given users back using a given authentication function"
  [auth-req-ch auth-fn out]
  (go-loop [{:keys [users] :as a} (<! auth-req-ch)]
    (when a
      (debug "AUTH-REQ" users)
      (>! out
        {:type ::auth
         :users (<! (auth-fn users))})
      (recur (<! auth-req-ch)))))


(defn- in-dispatch
  "Dispatches incoming requests"
  [{:keys [type]}]
  (case type
    :pub/downstream :pub/downstream
    ::auth-required ::auth-required
    ::auth :pub/downstream
    :unrelated))


;; TODO: registration
(defn auth
  "Authorize publications containing new data and TODO subscriptions against private repositories
 against friend-like credential-fn. Supply an auth-fn taking a set of usernames,
returning a go-channel with a user->password map."
  [store auth-fn credential-fn trusted-hosts [in out]]
  (let [new-in (chan)
        pub-ch (chan)
        auth-ch (chan)
        auth-req-ch (chan)
        sessions (atom {})
        p-in (pub in in-dispatch)]
    (sub p-in :pub/downstream pub-ch)
    (pub-auth pub-ch store trusted-hosts sessions credential-fn [new-in out])

    (sub p-in ::auth-required auth-req-ch)
    (auth-required auth-req-ch auth-fn out)

    (sub p-in :unrelated new-in)

    [new-in out]))



(comment
  (require '[konserve.memory :refer [new-mem-store]])

  (let [in (chan)
        out (chan)
        local-users {"mail:user@mail.com" "P4ssw0rd"
                     "mail:eve@mail.com" "lisp"}
        input-users {"mail:user@mail.com" "P4ssw0rd"
                     "mail:eve@mail.com" "lispo"}
        [new-in new-out] (auth (<!! (new-mem-store (atom
                                                    {"john" {42 {:id 42
                                                                 :commit-graph {1 []}
                                                                 :last-update (java.util.Date. 0)
                                                                 :description "Bookmark collection."
                                                                 :head "master"
                                                                 :branches {"master" #{2}}
                                                                 :schema {:type :replikativ
                                                                          :version 1}}}})))
                               (fn [users] (go
                                            (into {} (filter #(users (key %)) input-users ))))
                               (fn [token] (if (= (:password token) (get local-users (:username token)))
                                            true
                                            nil))
                               (atom #{"127.0.0.1"})
                               [in out])]
    (go-loop [i (<! new-in)]
      (println "NEW-IN" i)
      (recur (<! new-in)))
    (go
      (>! in {:type :pub/downstream,
              :peer "STAGE",
              :metas {"john" {42 {:id 42
                                  :commit-graph {1 []
                                                 2 [1]}
                                  :last-update (java.util.Date. 0)
                                  :description "Bookmark collection."
                                  :head "master"
                                  :branches {"master" #{2}}
                                  :schema {:type :replikativ
                                           :version 1}}}}})
      (println "OUT" (<! out))
      (>! in {:type ::auth :users {"john" "häskell"}})
      (println "OUT" (<! out))
      (>! in {:type ::auth :users {"john" "häskell"}})
      (println "OUT" (<! out))
      (>! in {:type ::auth :users {"john" "häskell"}})
      (println "OUT" (<! out))
      (>! in {:type ::auth :users {"john" "häskell"}})
      (println "OUT" (<! out))
      (>! in {:type ::auth :users {"john" "häskell"}})
      (println "OUT" (<! out))
      (>! in (with-meta {:type :pub/downstream,
                :peer "STAGE",
                :metas {"john" {42 {:id 42
                                    :commit-graph {1 []
                                                   2 [1]}
                                    :last-update (java.util.Date. 0)
                                    :description "Bookmark collection."
                                    :head "master"
                                    :branches {"master" #{2}}
                                    :schema {:type :replikativ
                                             :version 1}}}}}
               {:host "127.0.0.1"}))
      (println "OUT" (<! out))
      (>! in {:type :pub/downstream,
              :peer "STAGE",
              :metas {"john" {42 {:id 42
                                  :commit-graph {1 []
                                                 2 [1]}
                                  :last-update (java.util.Date. 0)
                                  :description "Bookmark collection."
                                  :head "master"
                                  :branches {"master" #{2}}
                                  :schema {:type :replikativ
                                           :version 1}}}}})
      (println "OUT" (<! out))
      (>! in {:type ::auth :users {"john" "haskell"}})
      (println "OUT" (<! out))
      ))

  (println "\n")



  )
