(ns geschichte.p2p.fetch
  "Fetching middleware for geschichte. This middleware covers the exchange of the actual content (commits and transactions, not metadata) of repositories."
  (:require [geschichte.environ :refer [*id-fn* store-blob-trans-id]]
            [geschichte.protocols :refer [-ensure-external]]
            [geschichte.platform-log :refer [debug info warn error]]
            [geschichte.crdt.materialize :refer [pub->crdt]]
            [geschichte.platform :refer [<? go<?]]
            [konserve.protocols :refer [-assoc-in -exists? -get-in -update-in
                                        -bget -bassoc]]
            [clojure.set :as set]
            #+clj [clojure.java.io :as io]
            #+clj [clojure.core.async :as async
                   :refer [<! >! >!! <!! timeout chan alt! go put!
                           filter< map< go-loop pub sub unsub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]])
  #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])
  #+clj (:import [java.io ByteArrayOutputStream]))

(defn- fetch-new-pub
  "Delegate fetching process to each CRDT to ensure integrity of
  external references."
  [store p pub-ch [in out]]
  (let [fetched-ch (chan)
        binary-fetched-ch (chan)
        all-true? (fn [x] (if (seq? x) (reduce #(and %1 %2)) x))]
    (sub p :fetched fetched-ch)
    (sub p :binary-fetched binary-fetched-ch)
    ;; TODO err-channel
    (go-loop [{:keys [topic metas values peer] :as m} (<? pub-ch)]
      (when m
        (if (->> (for [[user repos] metas
                       [repo pub] repos]
                   (go<? (let [crdt (<? (pub->crdt store [user repo] (:crdt pub)))] ;; protocol vs. core.async incompatibility if inline... TODO
                           (<? (-ensure-external crdt (:id m) (:op pub)
                                                 out fetched-ch binary-fetched-ch)))))
                 async/merge
                 <?
                 all-true?)
          (>! in m)
          (error "Could not ensure external integrity: " m))
        (recur (<? pub-ch))))))

(defn- fetched [store fetch-ch out]
  (go-loop [{:keys [ids peer id] :as m} (<! fetch-ch)]
      (when m
        (info "fetch:" ids)
        (let [fetched (->> ids
                           (map (fn [id] (go [id (<! (-get-in store [id]))])))
                           async/merge
                           (async/into {})
                           <!)]
          (>! out {:topic :fetched
                   :values fetched
                   :id id
                   :peer peer})
          (debug "sent fetched:" fetched)
          (recur (<! fetch-ch))))))

(defn- binary-fetched [store binary-fetch-ch out]
  (go-loop [{:keys [id peer blob-id] :as m} (<! binary-fetch-ch)]
    (when m
      (info "binary-fetch:" id)
      (>! out {:topic :binary-fetched
               :value (<! (-bget store blob-id
                                 #+clj #(with-open [baos (ByteArrayOutputStream.)]
                                          (io/copy (:input-stream %) baos)
                                          (.toByteArray baos))
                                 #+cljs identity))
               :blob-id blob-id
               :id id
               :peer peer})
      (debug "sent blob " id ": " blob-id)
      (recur (<! binary-fetch-ch)))))


(defn- fetch-dispatch [{:keys [topic] :as m}]
  (case topic
    :meta-pub :meta-pub
    :fetch :fetch
    :fetched :fetched
    :binary-fetch :binary-fetch
    :binary-fetched :binary-fetched
    :unrelated))

(defn fetch [store [in out]]
  (let [new-in (chan)
        p (pub in fetch-dispatch)
        pub-ch (chan 100) ;; TODO disconnect on overflow?
        fetch-ch (chan)
        binary-fetch-ch (chan)]
    (sub p :meta-pub pub-ch)
    (fetch-new-pub store p pub-ch [new-in out])

    (sub p :fetch fetch-ch)
    (fetched store fetch-ch out)

    (sub p :binary-fetch binary-fetch-ch)
    (binary-fetched store binary-fetch-ch out)

    (sub p :unrelated new-in)
    [new-in out]))
