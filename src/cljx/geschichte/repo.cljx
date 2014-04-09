(ns ^:shared geschichte.repo
  "Implementing core repository functions.
   Use this namespace to manage your repositories.

   Metadata is designed as a commutative replicative data type, so it
   can be synched between different servers without coordination. Don't
   add fields as this is part of the network specification."
  (:refer-clojure :exclude [merge])
  (:require [clojure.set :as set]
            [hasch.core :refer [uuid]]
            [geschichte.platform :refer [now]]
            [geschichte.meta :refer [lowest-common-ancestors
                                     merge-ancestors isolate-branch]]))


(def ^:dynamic *id-fn*
  "DO NOT REBIND EXCEPT FOR TESTING OR YOU MIGHT CORRUPT DATA.
   Determines unique ids, possibly from a value.
   UUID is defined as public format."
  uuid)



(def ^:dynamic *date-fn*
  "DO NOT REBIND EXCEPT FOR TESTING OR YOU MIGHT CORRUPT DATA."
  now)


(defn new-repository
  "Create a (unique) repository for an initial value. Returns a map with
   new metadata and commit value and transaction values."
  [author schema description is-public init-value]
  (let [now (*date-fn*)
        trans-val {:transactions [[init-value
                                   '(fn replace [old params] params)]]
                   :parents []
                   :ts now
                   :author author
                   :schema schema}
        trans-id (*id-fn* trans-val)
        repo-id (*id-fn*)
        new-meta  {:id repo-id
                   :description description
                   :schema {:type "http://github.com/ghubber/geschichte"
                            :version 1}
                   :public is-public
                   :causal-order {trans-id []}
                   :branches {"master" {:heads #{trans-id}}}
                   :head "master"
                   :last-update now
                   :pull-requests {}}]
    {:meta new-meta
     :author author
     :schema schema
     :transactions []

     :type :meta-sub
     :new-values {trans-id trans-val}}))


(defn fork
  "Fork (clone) a remote branch as your working copy.
   Pull in more branches as needed separately."
  [remote-meta branch is-public author schema]
  (let [branch-meta (-> remote-meta :branches (get branch))
        meta {:id (:id remote-meta)
              :description (:description remote-meta)
              :schema (:schema remote-meta)
              :causal-order (isolate-branch remote-meta branch)
              :branches {branch branch-meta}
              :head branch
              :last-update (*date-fn*)
              :pull-requests {}}]
    {:meta meta
     :author author
     :schema schema
     :transactions []

     :type :meta-sub}))


(defn- branch-heads [{:keys [head branches]}]
  (get-in branches [head :heads]))


(defn- raw-commit
  "Commits to meta in branch with a value for an ordered set of parents.
   Returns a map with metadata and value+inlined metadata."
  [{:keys [meta author schema transactions] :as stage} parents]
  (let [branch (:head meta)
        branch-heads (branch-heads meta)
        ts (*date-fn*)
        trans-value {:transactions transactions
                     :ts ts
                     :parents parents
                     :author author
                     :schema schema}
        id (*id-fn* trans-value)
        new-meta (-> meta
                     (assoc-in [:causal-order id] parents)
                     (update-in [:branches branch :heads] set/difference (set parents))
                     (update-in [:branches branch :heads] conj id)
                     (assoc-in [:last-update] ts))]
    (assoc stage
      :meta new-meta
      :transactions []

      :type :meta-pub
      :new-values (assoc (:new-values stage)
                    id trans-value)))) ;; TODO check for more collisions


(defn commit
  "Commits to meta in branch with a value for a set of parents.
   Returns a map with metadata and value+inlined metadata."
  [stage]
  (let [heads (branch-heads (:meta stage))]
    (if (= (count heads) 1)
      (raw-commit stage (vec heads))
      #+clj (throw (IllegalArgumentException. (str "Branch has multiple heads:" heads)))
      #+cljs (throw (str "Branch has multiple heads:" heads)))))


(defn branch
  "Create a new branch with parent."
  [{:keys [meta] :as stage} name parent]
  (let [new-meta (-> meta
                     (assoc-in [:branches name :heads] #{parent})
                     (assoc-in [:last-update] (*date-fn*)))]
    (assoc stage
      :meta new-meta
      :type :meta-pub)))


(defn checkout
  "Checkout a branch."
  [{:keys [meta] :as stage} branch]
  (let [new-meta (assoc (:meta stage)
                   :head branch
                   :last-update (*date-fn*))]
    (assoc stage
      :meta new-meta
      :type :meta-pub)))


(defn- multiple-branch-heads?
  "Checks whether branch has multiple heads."
  [meta branch]
  (> (count (get-in meta [:branches branch :heads])) 1))


(defn merge-necessary?
  "Determines whether branch-head is ancestor."
  [meta]
  (> (count (branch-heads meta)) 1))


;; TODO error handling for conflicts
(defn pull
  "Pull all commits into branch from remote-tip (only its ancestors)."
  [{:keys [meta] :as stage} remote-meta remote-tip]
  (let [branch-heads (branch-heads meta)
        branch (:head meta)
        {:keys [cut returnpaths-b]} (lowest-common-ancestors (:causal-order meta) branch-heads
                                                             (:causal-order remote-meta) #{remote-tip})
        new-meta (-> meta
                     (update-in [:causal-order]
                                merge-ancestors cut returnpaths-b)
                     (update-in [:branches branch :heads] set/difference branch-heads)
                     (update-in [:branches branch :heads] conj remote-tip))]
    (assoc stage
      :meta new-meta
      :type :meta-pub)))


(defn merge-heads
  "Constructs a head vector starting with lowest-common-ancestor,
then "
  [meta-a meta-b]
  (let [heads-a (branch-heads meta-a)
        heads-b (branch-heads meta-b)]
    (vec (concat heads-a heads-b))))


(defn merge
  "Merge a stage either with itself, or with remote metadata
and optionally supply the order in which parent commits should be supplied. Otherwise
See merge-heads how to get and manipulate them."
  ([{:keys [meta] :as stage}]
     (merge stage meta))
  ([{:keys [meta] :as stage} remote-meta]
     (merge stage remote-meta (merge-heads meta remote-meta)))
  ([{:keys [meta] :as stage} remote-meta heads]
     (let [source-heads (branch-heads meta)
           lcas (lowest-common-ancestors (:causal-order meta)
                                         source-heads
                                         (:causal-order remote-meta)
                                         heads)
           new-causal (merge-ancestors (:causal-order meta) (:cut lcas) (:returnpaths-b lcas))]
       (raw-commit (assoc-in stage [:meta :causal-order] new-causal)
                   heads))))


(comment
  (require '[clojure.pprint :refer [pprint]])

  (pprint (new-repository "john"
                          {:type "test"
                           :version 1}
                          "Test repo."
                          false
                          42))

  (def init {:meta
             {:causal-order {#uuid "2632f95d-e904-5d46-b280-353394f0db9a" []},
              :last-update #inst "2014-04-08T13:36:22.648-00:00",
              :head "master",
              :public false,
              :branches
              {"master" {:heads #{#uuid "2632f95d-e904-5d46-b280-353394f0db9a"}}},
              :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
              :pull-requests {},
              :id #uuid "9dbadda8-deef-4980-b3fe-8c55759b9222",
              :description "Test repo."},
             :author "john",
             :schema {:version 1, :type "test"},
             :transactions [],
             :type :meta-sub,
             :new-values
             {#uuid "2632f95d-e904-5d46-b280-353394f0db9a"
              {:transactions [[42 '(fn replace [old params] params)]],
               :parents [],
               :ts #inst "2014-04-08T13:36:22.648-00:00",
               :author "john",
               :schema {:version 1, :type "test"}}}})

  (pprint (commit init))


  (def commit1 {:meta
                {:causal-order
                 {#uuid "3161534b-00a6-5d1d-a407-91e3ebe1b2ec"
                  [#uuid "2632f95d-e904-5d46-b280-353394f0db9a"],
                  #uuid "2632f95d-e904-5d46-b280-353394f0db9a" []},
                 :last-update #inst "2014-04-08T13:37:34.769-00:00",
                 :head "master",
                 :public false,
                 :branches
                 {"master" {:heads #{#uuid "3161534b-00a6-5d1d-a407-91e3ebe1b2ec"}}},
                 :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
                 :pull-requests {},
                 :id #uuid "9dbadda8-deef-4980-b3fe-8c55759b9222",
                 :description "Test repo."},
                :author "john",
                :schema {:version 1, :type "test"},
                :transactions [],
                :type :meta-pub,
                :new-values
                {#uuid "3161534b-00a6-5d1d-a407-91e3ebe1b2ec"
                 {:transactions [],
                  :ts #inst "2014-04-08T13:37:34.769-00:00",
                  :parents [#uuid "2632f95d-e904-5d46-b280-353394f0db9a"],
                  :author "john",
                  :schema {:version 1, :type "test"}}}})

  (def commit2 {:meta
                {:causal-order
                 {#uuid "2531b342-94e0-5150-a6d5-17f8c80e615a"
                  [#uuid "2632f95d-e904-5d46-b280-353394f0db9a"],
                  #uuid "2632f95d-e904-5d46-b280-353394f0db9a" []},
                 :last-update #inst "2014-04-08T13:38:05.981-00:00",
                 :head "master",
                 :public false,
                 :branches
                 {"master" {:heads #{#uuid "2531b342-94e0-5150-a6d5-17f8c80e615a"}}},
                 :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
                 :pull-requests {},
                 :id #uuid "9dbadda8-deef-4980-b3fe-8c55759b9222",
                 :description "Test repo."},
                :author "john",
                :schema {:version 1, :type "test"},
                :transactions [],
                :type :meta-pub,
                :new-values
                {#uuid "2531b342-94e0-5150-a6d5-17f8c80e615a"
                 {:transactions [],
                  :ts #inst "2014-04-08T13:38:05.981-00:00",
                  :parents [#uuid "2632f95d-e904-5d46-b280-353394f0db9a"],
                  :author "john",
                  :schema {:version 1, :type "test"}}}})

  (merge-necessary? (geschichte.meta/update (:meta commit1)
                                            (:meta commit2)))

  (pprint (merge (assoc commit1 :transactions [['first nil]])
                 (:meta commit2)
                 (vec (concat (branch-heads (:meta commit1))
                              (branch-heads (:meta commit2)))))))
