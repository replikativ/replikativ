(ns geschichte.protocols)

(defprotocol IActivity
  (-start [this])
  (-stop [this]))

(defprotocol IPeer
  (-publish [this user repo new-meta]
    "Publish repo for user with new metadata value.")
  (-subscribe
    [this address subs chan]
    "Subscribe peer with address and subscriptions
     and backchannel chan."))
