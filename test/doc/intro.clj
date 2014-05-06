(ns doc.intro
  (:require [clojure.core.incubator :refer [dissoc-in]]
            [midje.sweet :refer :all]
            [geschichte.repo :as repo]
            [geschichte.meta :as meta]
            [geschichte.stage :as s]))

[[:chapter {:tag "motivation" :title "Motivation for geschichte"}]]

"The web is still a bag of data silos (*places* in Rich Hickey's terms). Despite existing cooperation on source code, data rarely is shared cooperatively, because it is accessed through a single (mostly proprietary) service, which also is fed with inputs to 'update' the data (read: it has an *API*). This creates a single point of perception to decide upon writes, which at the same time has to be economically viable and hence lock in the data.

While sophisticated new functional databases like [Datomic](http://www.datomic.com/) promise scalable relational programming and access to all data for the service provider, they still do not fit for distributed data. A single writer with a singular notion of time is still required. *geschichte* tries to apply some lessons learned from these efforts, building foremost on immutablity, but applies them to a different spot in the spectrum of storage management. The goal of geschichte is to build a distributed web and edit data collectively, while still allowing the right to fork and dissent for anybody. In general distributed 'serverless' applications should be possible. The tradeoff is application-specific/user-guided conflict resolution through three-way merges.

Experience with immutable bit-torrent sharing, distributed systems like git and mercurial, [Votorola](http://zelea.com/project/votorola/home.html) as well as [HistoDB](https://github.com/mirkokiefer/HistoDB) and its [description](https://github.com/mirkokiefer/syncing-thesis) have been inspirations. [CRDTs](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf) have been introduced to allow carefree synching of metadata values without 'places'.

Github already resembles a community using tools to produce source code, but it is still a central site (service) and does not apply to the web itself. geschichte will use *p2p* web-technologies, like *websockets* and *webrtc*, to globally exchange values. It will also use `IndexedDB` in the browser. It is supposed to be *functionally pure* (besides synching io) and runs on `Clojure/ClojureScript`(/ClojureX?). Different io-techniques can be used to exchange *pub-sub* like update-notifications and allow immutable value fetches (*kv-store*). On the JVM it could hook into existing distributed systems beyond the web.

In the following we will explain how *geschichte* works by building a small repository containing tagged bookmarks as an example."

[[:chapter {:tag "usage" :title "Usage"}]]

[[:section {:title "Repository format"}]]

"Metadata (without id binding) looks like:"

{:causal-order
 {#uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7" #{}},
 :last-update #inst "2013-11-11T05:10:59.495-00:00",
 :head "master",
 :public false,
 :branches {"master" {:heads #{#uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7"}
                      :indexes {:economy [2]}}},
 :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
 :pull-requests {"somebody@mail.com" {3 {:returnpaths-b {3 #{2}
                                                         2 #{1}}
                                         :cut #{1}}}},
 :id #uuid "b1732275-a7e7-4401-9485-c7249e4a13e7",
 :description "Bookmark collection."},

"We need to rebind the *id-generating* function and *time-function* to have fixed values here for testing. Otherwise ids are random `UUID`s by default, so they cannot and may not conflict. UUID-4 is the only option if you want to conform to the standard, so **don't** rebind these functions. They represent unchangable and global values. While time is very helpful to track, it is not critical for synching metadata. We will zero it out for testing here. All function calls are in fact unit tests, you can run this documentation with *[midje-doc](http://docs.caudate.me/lein-midje-doc/)*."



(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [repo/*id-fn* (let [counter (atom 0)]
                                      (fn ([] (swap! counter inc))
                                        ([val] (swap! counter inc))))
            repo/*date-fn* zero-date-fn]
    (f)))

"First we need to create the repository. The new-repositroy function returns a map containing both the metadata and value of the new repository."



(fact
 (test-env
  #(repo/new-repository "author@mail.com"
                        "Bookmark collection."
                        false
                        {:economy #{"http://opensourceecology.org/"}}))
 =>
 {:author "author@mail.com",
  :meta
  {:causal-order {3 []},
   :last-update #inst "1970-01-01T00:00:00.000-00:00",
   :head "master",
   :public false,
   :branches {"master" {:heads #{3}}},
   :schema {:type "http://github.com/ghubber/geschichte", :version 1},
   :pull-requests {},
   :id 4,
   :description "Bookmark collection."},
  :new-values
  {1 {:economy #{"http://opensourceecology.org/"}},
   2 '(fn replace [old params] params),
   3 {:author "author@mail.com",
      :parents [],
      :transactions [[1 2]],
      :ts #inst "1970-01-01T00:00:00.000-00:00"}},
  :transactions [],
  :type :meta-sub})


[[:subsection {:title "Metadata"}]]

"First we have a look at the metadata structure: "

{:causal-order {1 []},
 :last-update #inst "1970-01-01T00:00:00.000-00:00",
 :head "master",
 :public false,
 :branches {"master" {:heads #{1}
                      :indexes {:economy [1]}}},
 :schema {:type "http://github.com/ghubber/geschichte"
          :version 1,},
 :pull-requests {},
 :id 2,
 :description "Bookmark collection."}

"* `:causal-order` contains the whole dependency graph for revisions and is the core data we use to resolve conflicts. It points reverse from head to the root commit of the repository, which is the only commit with an empty parent set.
* `:branches` tracks all heads of branches in the causal order, while
* `:heads` marks the currently selected branch (head) and
* `:indixes` can track different subsets of the history
* `:id` (UUID) is generated on creation and is constant for all forks.
* `:pull-requests` is the only part where other users can append data, to be pulled. Pull-requests are immutable.
* `:public` marks whether access is restricted to the user him/herself.
* `:schema` is necessary to allow updates to the server-side software."

[[:subsection {:title "Converging and Commutative Replicated Data Type (CRDT)"}]]

"It is noteworthy that the metadata is a [CRDT](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf). Since it needs to be synched globally (in a key value store), it needs to converge to be eventual consistent. When it is, synching new versions of metadata from remote sources can happen gradually and consistently converging to the global state and values of the repository. "

"For each key the CRDT update function for value and new-value is described:"

(fact
 (meta/update
  { ;; only new keys (commits) can be added => (merge new-value value)
   :causal-order {1 [] ;; keys: G-SET
                  2 [1]}, ;; parental values don't change

   ;; (if (> new-value value) new-value value) non-critical for
   ;; merging (monotone)
   :last-update #inst "1970-01-01T00:00:00.000-00:00",

   ;; update to newer selection (for convenience to resolve the repo to a
   ;; default value)
   :head "master",

   ;; (or value new-value) might only turn true (monotone)
   :public false,

   ;; keys: G-SET
   ;; values: OR-SET,
   ;; (heads) are merged with lca which is commutative and idempotent,
   ;; heads cannot become empty
   :branches {"master" {:heads #{2}}},

   :schema {:type "http://github.com/ghubber/geschichte" ;; immutable
            :version 1,}, ;; might only increase

   ;; G-SET, several pull-requests to the same tip are identical
   :pull-requests {"somebody@mail.com" {3 {:returnpaths-b {3 #{2}}
                                           :cut #{2}}}},

   ;; might never change (global id), immutable
   :id 2,

   ;; set on initialisation, bound to id, immutable
   :description "Bookmark collection."}

  ;; new metadata information:
  {:causal-order {1 []
                  2 [1]
                  3 [2]
                  1000 [1]},
   :last-update #inst "2000-01-01T00:00:00.000-00:00",
   :head "future",
   :public true,
   :branches {"master" {:heads #{3}
                        :indexes {:economy [1 3]}}
              "future" {:heads #{1000}}},
   :schema {:type "http://github.com/ghubber/geschichte" :version 42},
   :pull-requests {"somebody@mail.com" {4 {:returnpaths-b {4 #{2}}
                                           :cut #{2}}}},
   :id 2,
   :description "Bookmark collection."})
 => {:causal-order {2 [1], 1 [], 3 [2], 1000 [1]},
     :last-update #inst "2000-01-01T00:00:00.000-00:00",
     :head "future",
     :public true,
     :branches {"master" {:heads #{3}
                          :indexes {:economy [1 3]}},
                "future" {:heads #{1000}}},
     :schema {:version 42, :type "http://github.com/ghubber/geschichte"},
     :pull-requests
     {"somebody@mail.com"
      {3 {:cut #{2}, :returnpaths-b {3 #{2}}},
       4 {:cut #{2}, :returnpaths-b {4 #{2}}}}},
     :id 2,
     :description "Bookmark collection."})

"The most sophisticated operation is merging branch heads through lca,
which is necessary to resolve stale branch heads. This operation has
currently square complexity on number of heads per branch. Having many
branches is not a problem, having branches with many heads is."

"The operation is commutative: "

(fact
 (meta/update
  ;; new metadata information:
  {:causal-order {1 []
                  2 [1]
                  3 [2]
                  1000 [1]},
   :last-update #inst "2000-01-01T00:00:00.000-00:00",
   :head "future",
   :public true,
   :branches {"master" {:heads #{3}
                        :indexes {:economy [1 3]}}
              "future" {:heads #{1000}}},
   :schema {:type "http://github.com/ghubber/geschichte" :version 42},
   :pull-requests {"somebody@mail.com" {4 {:returnpaths-b {4 #{2}}
                                           :cut #{2}}}},
   :id 2,
   :description "Bookmark collection."}
  {:causal-order {1 []
                  2 [1]},
   :last-update #inst "1970-01-01T00:00:00.000-00:00",
   :head "master",
   :public false,
   :branches {"master" #{2}},
   :schema {:type "http://github.com/ghubber/geschichte"
            :version 1,},
   :pull-requests {"somebody@mail.com" {3 {:returnpaths-b {3 #{2}}
                                           :cut #{2}}}},
   :id 2,
   :description "Bookmark collection."})
 => {:causal-order {2 [1], 1 [], 3 [2], 1000 [1]},
     :last-update #inst "2000-01-01T00:00:00.000-00:00",
     :head "future",
     :public true,
     :branches {"master" {:heads #{3}
                          :indexes {:economy [1 3]}},
                "future" {:heads #{1000}}},
     :schema {:version 42, :type "http://github.com/ghubber/geschichte"},
     :pull-requests
     {"somebody@mail.com"
      {3 {:cut #{2}, :returnpaths-b {3 #{2}}},
       4 {:cut #{2}, :returnpaths-b {4 #{2}}}}},
     :id 2,
     :description "Bookmark collection."})

"And idempotent: "

(fact
 (meta/update
  {:causal-order {1 []
                  2 []},
   :last-update #inst "1970-01-01T00:00:00.000-00:00",
   :head "master",
   :public false,
   :branches {"master" {:heads #{2}}},
   :schema {:type "http://github.com/ghubber/geschichte"
            :version 1,},
   :pull-requests {"somebody@mail.com" {3 {:returnpaths-b {3 #{2}}
                                           :cut #{2}}}},
   :id 2,
   :description "Bookmark collection."}
  {:causal-order {1 []
                  2 []},
   :last-update #inst "1970-01-01T00:00:00.000-00:00",
   :head "master",
   :public false,
   :branches {"master" {:heads #{2}
                        :indexes {:economy [1 3]}}},
   :schema {:type "http://github.com/ghubber/geschichte"
            :version 1,},
   :pull-requests {"somebody@mail.com" {3 {:returnpaths-b {3 #{2}}
                                           :cut #{2}}}},
   :id 2,
   :description "Bookmark collection."})
 => {:causal-order {1 []
                    2 []},
     :last-update #inst "1970-01-01T00:00:00.000-00:00",
     :head "master",
     :public false,
     :branches {"master" {:heads #{2}
                          :indexes {:economy [1 3]}}},
     :schema {:type "http://github.com/ghubber/geschichte"
              :version 1,},
     :pull-requests {"somebody@mail.com" {3 {:returnpaths-b {3 #{2}}
                                             :cut #{2}}}},
     :id 2,
     :description "Bookmark collection."})

"Which we have (hopefully) shown for each field of the metadata map individually above."

[[:subsection {:title "Value"}]]

{#uuid "04eb5b1b-4d10-5036-b235-fa173253089a"
 {:transactions [[{:economy #{"http://opensourceecology.org/"}}
                  '(fn add-links [old params] (merge-with set/union old params))]],
  :ts #inst "1970-01-01T00:00:00.000-00:00",
  :author "author@mail.com",
  :parents [2 3], ;; normally singular, with merge sequence of parent commits applied in ascending order.
  :schema {:type "http://some.bookmarksite.info/schema-file",
           :version 1}}}

"The value consists of one or more transactions, each a pair of a parameter map (data) and a freely chosen data (code) to describe the transaction. The code needn't be freely evaled, but can be mapped to a limit set of application specific operations. That way it can be safely resolved via a hardcoded hash-map and will still be invariant to version changes in code. Read: You should use a code description instead of symbols where possible, even if this induces a small overhead."

[[:section {:title "Forking and Pulling"}]]

[[:subsection {:title "Forking (Cloning)"}]]

"You always fork from a desired branch. More branches can be pulled separately. Only common causal-order (branch ancestors) is pulled. This yields a copy (clone) of the remote branch."

(fact
 (test-env
  #(repo/fork {:causal-order {1 []
                              3 [1]},
               :last-update #inst "1970-01-01T00:00:00.000-00:00",
               :head "master",
               :public false,
               :branches {"master" {:heads #{1}}
                          "politics-coll" {:heads #{3}}},
               :pull-requests {},
               :id 2,
               :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
               :description "Bookmark collection."}
              "master"
              true
              "author@mail.com"))
 =>
 {:meta {:id 2,
         :description "Bookmark collection.",
         :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
         :causal-order {1 []},
         :branches {"master" {:heads #{1}}},
         :head "master",
         :last-update #inst "1970-01-01T00:00:00.000-00:00",
         :pull-requests {}},
  :author "author@mail.com",
  :transactions []

  :type :meta-sub})

[[:subsection {:title "Pull"}]]

"Pulling happens much the same."

(fact
 (test-env
  #(repo/pull {:meta {:causal-order {1 []},
                      :last-update #inst "1970-01-01T00:00:00.000-00:00",
                      :head "master",
                      :public false,
                      :branches {"master" {:heads #{1}}},
                      :schema {:type "http://github.com/ghubber/geschichte"
                               :version 1,},
                      :pull-requests {},
                      :id 2,
                      :description "Bookmark collection."}
               :author "author@mail.com"
               :schema {}
               :transactions []}
              {:causal-order {1 []
                              3 [1]
                              4 [3]},
               :last-update #inst "1970-01-01T00:00:00.000-00:00",
               :head "master",
               :public false,
               :branches {"master" {:heads #{4}}
                          "politics-coll" {:heads #{3}}},
               :schema {:type "http://github.com/ghubber/geschichte"
                        :version 1,},
               :pull-requests {},
               :id 2,
               :description "Bookmark collection."}
              4))
 =>
 {:meta {:causal-order {4 [3],
                        3 [1],
                        1 []},
         :last-update #inst "1970-01-01T00:00:00.000-00:00",
         :head "master",
         :public false,
         :branches {"master" {:heads #{4}}},
         :schema {:version 1,
                  :type "http://github.com/ghubber/geschichte"},
         :pull-requests {},
         :id 2,
         :description "Bookmark collection."},
  :author "author@mail.com",
  :schema {},
  :transactions []

  :type :meta-pub})


[[:section {:title "Branching, Committing and Merging"}]]

[[:subsection {:title "Branch"}]]

"Branching is possible from any commit and does not create a commit:"

(fact
 (test-env
  #(repo/branch {:meta {:causal-order {10 []
                                       30 [10]
                                       40 [30]},
                        :last-update #inst "1970-01-01T00:00:00.000-00:00",
                        :head "master",
                        :public false,
                        :branches {"master" {:heads #{40}}
                                   "politics-coll" {:heads #{30}}},
                        :schema {:type "http://github.com/ghubber/geschichte"
                                 :version 1,},
                        :pull-requests {},
                        :id 2,
                        :description "Bookmark collection."}
                 :author ""
                 :schema {}
                 :transactions []}
                "environ-coll"
                30))
 =>
 {:meta {:causal-order {10 [],
                        30 [10],
                        40 [30]},
         :last-update #inst "1970-01-01T00:00:00.000-00:00",
         :head "master",
         :public false,
         :branches {"environ-coll" {:heads #{30}},
                    "master" {:heads #{40}},
                    "politics-coll" {:heads #{30}}},
         :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
         :pull-requests {},
         :id 2,
         :description "Bookmark collection."},
  :author "",
  :schema {},
  :transactions []

  :type :meta-pub})

"One can use this to merge pull-requests with old branch-heads in a dedicated branch, which otherwise cannot be pulled. Pull requests can also be merged directly."


[[:subsection {:title "Commit"}]]

"You can commit against any branch head."

(fact (test-env
       #(repo/commit {:meta {:causal-order {10 []
                                            30 [10]
                                            40 [30]},
                             :last-update #inst "1970-01-01T00:00:00.000-00:00",
                             :head "politics-coll",
                             :public false,
                             :branches {"master" {:heads #{40}}
                                        "politics-coll" {:heads #{30}}},
                             :schema {:type "http://github.com/ghubber/geschichte"
                                      :version 1},
                             :pull-requests {},
                             :id 2,
                             :description "Bookmark collection."}
                      :author "author@mail.com"
                      :transactions [[{:economy #{"http://opensourceecology.org/"}
                                       :politics #{"http://www.economist.com/"}}
                                      '(fn merge [old params] (merge-with set/union old params))]]}))
      =>
      {:author "author@mail.com",
       :meta {:branches {"master" {:heads #{40}},
                         "politics-coll" {:heads #{3}}},
              :causal-order {3 [30], 10 [], 30 [10], 40 [30]},
              :description "Bookmark collection.",
              :head "politics-coll",
              :id 2,
              :last-update #inst "1970-01-01T00:00:00.000-00:00",
              :public false,
              :pull-requests {},
              :schema {:type "http://github.com/ghubber/geschichte", :version 1}},
       :new-values {1 {:economy #{"http://opensourceecology.org/"}, :politics #{"http://www.economist.com/"}},
                    2 '(fn merge [old params] (merge-with set/union old params)),
                    3 {:author "author@mail.com",
                       :parents [30],
                       :transactions [[1 2]],
                       :ts #inst "1970-01-01T00:00:00.000-00:00"}},
       :transactions [],
       :type :meta-pub})



[[:subsection {:title "Merge"}]]

"You can check whether a merge is necessary (the head branch has multiple heads):"

(facts (test-env
       #(repo/merge-necessary? (meta/update {:causal-order {10 []
                                                            30 [10]
                                                            40 [10]},
                                             :last-update #inst "1970-01-01T00:00:00.000-00:00",
                                             :head "master",
                                             :public false,
                                             :branches {"master" {:heads #{40}}
                                                        "politics-coll" {:heads #{30}}},
                                             :schema {:type "http://github.com/ghubber/geschichte"
                                                      :version 1},
                                             :pull-requests {},
                                             :id 2,
                                             :description "Bookmark collection."}
                                            {:causal-order {10 []
                                                            20 [10]},
                                             :last-update #inst "1970-01-01T00:00:00.000-00:00",
                                             :head "master",
                                             :public false,
                                             :branches {"master" {:heads #{20}}},
                                             :schema {:type "http://github.com/ghubber/geschichte"
                                                      :version 1},
                                             :pull-requests {},
                                             :id 2,
                                             :description "Bookmark collection."})))
      => true)

"Merging is like pulling but adding a value as resolution for the new commit. You have to supply the remote-metadata and a vector of parents, which are applied to the repository value in order before the merge commit."


(fact (test-env
       #(repo/merge {:author "author@mail.com"
                     :meta {:causal-order {10 []
                                           30 [10]
                                           40 [10]},
                            :last-update #inst "1970-01-01T00:00:00.000-00:00",
                            :head "master",
                            :public false,
                            :branches {"master" {:heads #{40}}
                                       "politics-coll" {:heads #{30}}},
                            :schema {:type "http://github.com/ghubber/geschichte"
                                     :version 1},
                            :pull-requests {},
                            :id 2,
                            :description "Bookmark collection."}
                     :transactions [[{:economy #{"http://opensourceecology.org/"}
                                      :politics #{"http://www.economist.com/"}}
                                     '(fn merge [old params] (merge-with set/union old params))]]}
                    {:causal-order {10 []
                                    20 [10]},
                     :last-update #inst "1970-01-01T00:00:00.000-00:00",
                     :head "master",
                     :public false,
                     :branches {"master" {:heads #{20}}},
                     :schema {:type "http://github.com/ghubber/geschichte"
                              :version 1},
                     :pull-requests {},
                     :id 2,
                     :description "Bookmark collection."}
                    [40 20]))
      =>
      {:author "author@mail.com",
       :meta {:branches {"master" {:heads #{3}},
                         "politics-coll" {:heads #{30}}},
              :causal-order {3 [40 20], 10 [], 20 [10], 30 [10], 40 [10]},
              :description "Bookmark collection.",
              :head "master",
              :id 2,
              :last-update #inst "1970-01-01T00:00:00.000-00:00",
              :public false,
              :pull-requests {},
              :schema {:type "http://github.com/ghubber/geschichte", :version 1}},
       :new-values {1 {:economy #{"http://opensourceecology.org/"}, :politics #{"http://www.economist.com/"}},
                    2 '(fn merge [old params] (merge-with set/union old params)),
                    3 {:author "author@mail.com",
                       :parents [40 20],
                       :transactions [[1 2]],
                       :ts #inst "1970-01-01T00:00:00.000-00:00"}},
       :transactions [],
       :type :meta-pub})


"Have a look at the [synching API](synching.html) as well. Further documentation will be added, have a look at the
[test/geschichte/core_test.clj](https://github.com/ghubber/geschichte/blob/master/test/geschichte/core_test.clj) tests or
the [API docs](doc/index.html) for implementation details."
