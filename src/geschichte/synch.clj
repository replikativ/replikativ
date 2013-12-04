(ns ^:shared geschichte.synch
    (:require [clojure.set :as set]))

;; for testing; idempotent, commutative
(def publish merge)

(defprotocol IPeer
  (-publish [this user repo new-meta]
    "Publish repo for user with new metadata value.")
  (-subscribe
    [this address subs chan]
    "Subscribe peer with address and subscriptions
     and backchannel chan."))

(defn subscribe
  "Add subscriptions for new-subs (user/repo map of peer)
   to orig-subs map for address of peer."
  [orig-subs new-subs address]
  (reduce (fn [s user]
            (assoc s user
                   (reduce (fn [repos nrepo]
                             (update-in repos [nrepo]
                                        #(conj (or %1 #{}) %2) address))
                           (orig-subs user)
                           (keys (new-subs user)))))
          orig-subs
          (keys new-subs)))

(declare network)
(defrecord Peer [state]
  IPeer
  (-publish [this user repo new-meta]
    (let [old @state ;; eventual consistent, race condition ignorable
          new (swap! state update-in [user repo] publish new-meta)
          new-meta* (get-in new [user repo])]
      (when (not= new old) ;; notify peers
        (doseq [peer (get-in old [:peers user repo])]
          (-publish (network peer) user repo new-meta*)))
      {:new new-meta*
       :new-revs (set/difference (set (keys new-meta))
                                 (set (keys (get-in old [user repo]))))}))
  (-subscribe [this address subs chan]
    (let [new (swap! state update-in [:peers] subscribe subs address)]
      (doseq [user (keys subs)
              repo (keys (subs user))]
        (-publish this user repo (get-in subs [user repo])))
      (select-keys @state (keys subs)))))


#_(def network {"1.1.1.1" (Peer. (atom {"user@mail.com" {1 {1 42}
                                                         2 {1 314}}
                                        "other@mail.com" {1 {1 42
                                                             2 43}
                                                          3 {1 628}}
                                      :peers {}}))
                "1.2.3.4" (Peer. (atom {"user@mail.com" {1 {1 42
                                                            2 43}}
                                      :peers {}}))})






#_(-subscribe (network "1.1.1.1")
            "1.2.3.4"
            (dissoc @(.-state (network "1.2.3.4")) :peers)
            nil)

#_(-publish (network "1.1.1.1") "user@mail.com" 1 {1 42
                                                  2 43
                                                  3 44
                                                  4 45})

#_(subscribe {"user@mail.com"{1 #{"1.1.1.1"}}}
           {"user@mail.com" {1 {:a 1} 2 {:a 2}}
            "other@mail.com" {1 {:a 2}
                              3 {:b 4}}}
           "1.2.3.4")
