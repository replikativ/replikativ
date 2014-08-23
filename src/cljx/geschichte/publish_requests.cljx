(ns geschichte.publish-requests
  "Expose pulling of meta data (e.g. on subscription) through requests."
  (:require [geschichte.platform-log :refer [debug info warn error]]
            [konserve.protocols :refer [-get-in]]
            [geschichte.platform-data :refer [diff]]
            #+clj [clojure.core.async :as async
                   :refer [<! >! >!! <!! timeout chan alt! go put!
                           filter< map< go-loop pub sub unsub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]])
  #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


(defn request-on-subscription
  "Listens for subscriptions on sub-ch, waits for ack on subed-ch and sends publication-request."
  [sub-ch new-in subed-ch out]
  (go-loop [{new-subs :metas :as r} (<! sub-ch)
            old-subs nil]
    (when r
      (>! new-in r)
      ;; wait for subscription acknowledgement (chance to subscribe back)
      (>! out (<! subed-ch))
      (when-not (= new-subs old-subs)
        (let [[new] (diff new-subs old-subs)] ;; pull all new repos
          (debug "subscribing to new subs:" new)
          (>! out {:topic :meta-pub-req
                   :metas new})))
      (recur (<! sub-ch) new-subs))))


(defn reply-to-pub-request
  "Handles publication requests (at connection atm.)."
  [store pub-req-ch out]
  (go-loop [{req-metas :metas :as pr} (<! pub-req-ch)]
    (when pr
      (let [metas-list (->> (for [[user repos] req-metas
                                  [repo meta] repos]
                              (go [[user repo] (<! (-get-in store [user repo]))]))
                            async/merge
                            (filter< second)
                            (async/into [])
                            <!)
            metas (reduce #(assoc-in %1 (first %2) (second %2)) nil metas-list)]
        (when metas
          (debug "meta-pub-req reply:" metas)
          (>! out {:topic :meta-pub
                   :metas metas})))
      (recur (<! pub-req-ch)))))


(defn in-dispatch [{:keys [topic]}]
  (case topic
    :meta-sub :meta-sub
    :meta-pub-req :meta-pub-req
    :unrelated))


(defn out-dispatch [{:keys [topic]}]
  (case topic
    :meta-subed :meta-subed
    :unrelated))


(defn meta-pub-requester [store [in out]]
  (let [new-in (chan)
        new-out (chan)
        p-in (pub in in-dispatch)
        p-out (pub new-out out-dispatch)
        pub-req-ch (chan)
        sub-ch (chan)
        subed-ch (chan)]
    (sub p-in :meta-sub sub-ch)
    (sub p-out :meta-subed subed-ch)
    (request-on-subscription sub-ch new-in subed-ch out)

    (sub p-in :meta-pub-req pub-req-ch)
    (reply-to-pub-request store pub-req-ch out)

    (sub p-in :unrelated new-in)
    (sub p-out :unrelated out)
    [new-in new-out]))
