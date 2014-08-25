(ns geschichte.p2p.hash
  "Hash checksumming middleware for geschichte."
  (:require [geschichte.platform-log :refer [debug info warn error]]
            [hasch.core :refer [uuid]]
            [clojure.set :as set]
            #+clj [clojure.core.async :as async
                   :refer [<! >! >!! <!! timeout chan alt! go put!
                           filter< map< go-loop pub sub unsub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]])
  #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


(defn- check-hash [fetched-ch new-in]
  (go-loop [{:keys [values peer] :as f} (<! fetched-ch)]
    (when f
      (doseq [[id val] values]
        ;; HACK to cover commits, TODO introduce distinct fetch types/topics?
        (when (not= id (uuid (if (and (map? val) (:ts val) (:author val) (:transactions val))
                               (dissoc val :ts :author) val)))
          (let [msg (str "CRITICAL: Fetched ID: "  id
                         " does not match HASH "  (uuid val)
                         " for value " (pr-str val)
                         " from " peer)]
            (error msg)
            #+clj (throw (IllegalStateException. msg))
            #+cljs (throw msg))))
      (>! new-in f)
      (recur (<! fetched-ch)))))


(defn- hash-dispatch [{:keys [topic]}]
  (case topic
    :fetched :fetched
    :unrelated))


(defn ensure-hash
  "Ensures correct uuid hashes of incoming data (commits and transactions)."
  [[in out]]
  (let [new-in (chan)
        p (pub in hash-dispatch)
        fetched-ch (chan)]
    (sub p :fetched fetched-ch)
    (check-hash fetched-ch new-in)

    (sub p :unrelated new-in)
    [new-in out]))
