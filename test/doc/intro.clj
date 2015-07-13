(ns doc.intro
  (:require [midje.sweet :refer :all]
            [replikativ.environ :refer [*id-fn* *date-fn*]]
            [replikativ.crdt.repo.repo :as repo]
            [replikativ.crdt.repo.meta :as meta]
            [replikativ.crdt.repo.stage :as s]))

[[:chapter {:tag "motivation" :title "Motivation for replikativ"}]]

"The web is still a bag of data silos (*places* in Rich Hickey's terms). Despite existing cooperation on source code, data rarely is shared cooperatively, because it is accessed through a single (mostly proprietary) service, which also is fed with inputs to 'update' the data (read: it has an *API*). This creates a single point of perception to decide upon writes, which at the same time has to be economically viable and hence locks the data in.

While sophisticated new functional databases like [Datomic](http://www.datomic.com/) promise scalable relational programming and access to all data for the service provider, they still do not fit for distributed data. A single writer with a singular notion of time is still required. *replikativ* tries to apply some lessons learned from these efforts, building foremost on immutablity, but applies them to a different spot in the spectrum of storage management. The goal of replikativ is to build a distributed web and edit data collectively, while still allowing the right to fork and dissent for anybody. In general distributed 'serverless' applications should be possible. The tradeoff is application-specific/user-guided conflict resolution through three-way merges.

Experience with immutable bit-torrent sharing, distributed systems like git and mercurial, [Votorola](http://zelea.com/project/votorola/home.html) as well as [HistoDB](https://github.com/mirkokiefer/HistoDB) and its [description](https://github.com/mirkokiefer/syncing-thesis) have been inspirations. [CRDTs](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf) have been introduced to allow carefree synching of metadata values without 'places'.

Github already resembles a community using tools to produce source code, but it is still a central site (service) and does not apply to the web itself. replikativ will use *p2p* web-technologies, like *websockets* and *webrtc*, to globally exchange values. It will also use `IndexedDB` in the browser. It is supposed to be *functionally pure* (besides synching io) and runs on `Clojure/ClojureScript`(/ClojureX?). Different io-techniques can be used to exchange *pub-sub* like update-notifications and allow immutable value fetches (*kv-store*). On the JVM it could hook into existing distributed systems beyond the web.

In the following we will explain how *replikativ* works by building a small repository containing tagged bookmarks as an example."

[[:chapter {:tag "usage" :title "Usage"}]]

[[:section {:title "Repository format"}]]

"Metadata (without id binding) looks like:"

{:commit-graph
 {#uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7" []},
 :branches {"master" #{#uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7"}}},

"We need to rebind the *id-generating* function and *time-function* to have fixed values here for testing. Otherwise ids are cryptographic `UUID`s over their referenced values by default, so they cannot and may not conflict. UUID-5 is the only option if you want to conform to the standard, so **don't** rebind these functions. The UUIDs represent unchangable and global values. While time can be helpful to track in commits, it is not critical for synching metadata. We will zero it out for testing here. All function calls are in fact unit tests, you can run this documentation with *[midje-doc](http://docs.caudate.me/lein-midje-doc/)*."



(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [*id-fn* (let [counter (atom 0)]
                      (fn ([] (swap! counter inc))
                        ([val] (swap! counter inc))))
            *date-fn* zero-date-fn]
    (f)))

"First we need to create the repository. The new-repositroy function returns a map containing both the metadata and value of the new repository."



(fact
 (test-env
  #(repo/new-repository "author@mail.com"))
 =>
 {:state
  {:commit-graph {1 []},
   :branches {"master" #{1}}},
  :prepared {"master" []},
  :downstream
  {:crdt :repo
   :op {:method :new-state
        :commit-graph {1 []},
        :branches {"master" #{1}}
        :version 1}},
  :new-values
  {"master"
   {1
    {:transactions [],
     :parents [],
     :branch "master"
     :ts #inst "1970-01-01T00:00:00.000-00:00",
     :author "author@mail.com"
     :crdt :repo
     :version 1
     :crdt-refs #{}}}}})


   [[:subsection {:title "Metadata"}]]

   "First we have a look at the metadata structure: "

   {:commit-graph {1 []},
    :branches {"master" #{1}}}

   "* `:commit-graph` contains the whole dependency graph for revisions and is the core data we use to resolve conflicts. It points reverse from head to the root commit of the repository, which is the only commit with an empty parent vector.
   * `:branches` tracks all heads of branches in the graph order "

   [[:subsection {:title "Convergent Replicated Data Type (CRDT)"}]]

   "It is noteworthy that the metadata is a [CRDT](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf). Since it needs to be synched globally (in a key value store), it needs to converge to be eventual consistent. When it is, synching new versions of metadata from remote sources can happen gradually and consistently converging to the global state and values of the repository. "

   "For each key the CRDT update function for value and new-value is described:"

   (fact
    (meta/downstream
     { ;; only new keys (commits) can be added => (merge new-value value)
      :commit-graph {1 []    ;; keys: G-SET
                     2 [1]}, ;; parental values don't change

      ;; keys: G-SET
      ;; values: similar to OR-SET,
      ;; (heads) are merged with lca which is commutative and idempotent,
      ;; heads cannot become empty
      :branches {"master" #{2}}}

     ;; new metadata information:
     {:commit-graph {1 []
                     2 [1]
                     3 [2]
                     1000 [1]},
      :branches {"master" #{3},
                 "future" #{1000}}})
    =>  {:commit-graph {1 []
                        2 [1]
                        3 [2]
                        1000 [1]},
         :branches {"master" #{3},
                    "future" #{1000}}})

   "The most sophisticated operation is merging branch heads through lca,
   which is necessary to resolve stale branch heads. This operation has
   currently square complexity on number of heads per branch. Having many
   branches is not a problem, having branches with many heads is."

   "The operation is commutative: "

   (fact
    (meta/downstream
     ;; new metadata information:
     {:commit-graph {1 []
                     2 [1]
                     3 [2]
                     1000 [1]},
      :branches {"master" #{3}
                 "future" #{1000}}}
     {:commit-graph {1 []
                     2 [1]},
      :branches {"master" #{2}}})
    =>  (meta/downstream
         ;; new metadata information:
         {:commit-graph {1 []
                         2 [1]},
          :branches {"master" #{2}}}
         {:commit-graph {1 []
                         2 [1]
                         3 [2]
                         1000 [1]},
          :branches {"master" #{3}
                     "future" #{1000}}}))

   "And idempotent: "

   (fact
    (meta/downstream
     {:commit-graph {1 []
                     2 []},
      :branches {"master" #{2}}}
     {:commit-graph {1 []
                     2 []},
      :branches {"master" #{2}}})
    => {:commit-graph {1 []
                       2 []},
        :branches {"master" #{2}}})

   "Which we have (hopefully) shown for each field of the metadata map individually above."

   [[:subsection {:title "Value"}]]

   {#uuid "04eb5b1b-4d10-5036-b235-fa173253089a"
    {:transactions [['(fn add-links [old params] (merge-with set/union old params)) ;; actually uuids pointing to fn and params
                     {:economy #{"http://opensourceecology.org/"}}]],
     :ts #inst "1970-01-01T00:00:00.000-00:00",
     :author "author@mail.com",
     :parents [2 3], ;; normally singular, with merge sequence of parent commits applied in ascending order.
     :crdt-refs #{}
     }}

   "The value consists of one or more transactions, each a pair of a parameter map (data) and a freely chosen data (code) to describe the transaction. The code needn't be freely evaled, but can be mapped to a limit set of application specific operations. That way it can be safely resolved via a hardcoded hash-map and will still be invariant to version changes in code. Read: You should use a literal code description instead of symbols where possible, even if this induces a small overhead."

   [[:section {:title "Forking and Pulling"}]]

   [[:subsection {:title "Forking (Cloning)"}]]

   "You always fork from a desired branch. More branches can be pulled separately. Only common commit-graph (branch ancestors) is pulled. This yields a copy (clone) of the remote branch."

   (fact
    (test-env
     #(repo/fork {:commit-graph {1 []
                                 3 [1]},
                  :branches {"master" #{1}
                             "politics-coll" #{3}},}
                 "master"))
    =>
    {:state
     {:commit-graph {1 []},
      :branches {"master" #{1}}},
     :prepared {"master" []},
     :downstream
     {:crdt :repo
      :op {:method :new-state
           :commit-graph {1 []},
           :branches {"master" #{1}}
           :version 1}}})

[[:subsection {:title "Pull"}]]

"Pulling happens much the same."

(fact
 (test-env
  #(repo/pull {:state {:commit-graph {1 []},
                       :branches {"master" #{1}}}
               :prepared {"master" []}}
              "master"
              {:commit-graph {1 []
                              3 [1]
                              4 [3]},
               :branches {"master" #{4}
                          "politics-coll" #{3}}}
              4))
 =>
 {:downstream
  {:crdt :repo
   :op {:commit-graph {4 [3], 3 [1], 1 []},
        :method :pull
        :branches {"master" #{4}}
        :version 1}},
  :state
  {:commit-graph {1 [], 3 [1], 4 [3]},
   :branches {"master" #{4}}},
  :prepared {"master" []}})


[[:section {:title "Branching, Committing and Merging"}]]

[[:subsection {:title "Branch"}]]

"Branching is possible from any commit and does not create a commit:"

(fact
 (test-env
  #(repo/branch {:state {:commit-graph {10 []
                                        30 [10]
                                        40 [30]},
                         :branches {"master" #{40}
                                    "politics-coll" #{30}}}
                 :prepared {"master" []}}
                "environ-coll"
                30))
 =>
 {:downstream {:crdt :repo
               :op {:branches {"environ-coll" #{30}}
                    :method :branch
                    :version 1}},
  :state
  {:commit-graph {10 [], 30 [10], 40 [30]},
   :branches
   {"environ-coll" #{30}, "politics-coll" #{30}, "master" #{40}}},
  :prepared {"environ-coll" [], "master" []}})


[[:subsection {:title "Commit"}]]

"You can commit against any branch head."

(fact (test-env
       #(repo/commit {:state {:commit-graph {10 []
                                             30 [10]
                                             40 [30]},
                              :branches {"master" #{40}
                                         "politics-coll" #{30}}}
                      :prepared {"politics-coll" [[{:economy
                                                        #{"http://opensourceecology.org/"}
                                                        :politics #{"http://www.economist.com/"}}
                                                       '(fn merge [old params] (merge-with set/union old params))]]
                                     "master" []}}
                     "author@mail.com"
                     "politics-coll"))
      =>
      {:new-values
       {"politics-coll"
        {3
         {:transactions [[1 2]],
          :ts #inst "1970-01-01T00:00:00.000-00:00",
          :branch "politics-coll"
          :parents [30],
          :crdt-refs #{}
          :crdt :repo
          :version 1
          :author "author@mail.com"},
         2 '(fn merge [old params] (merge-with set/union old params)),
         1
         {:politics #{"http://www.economist.com/"},
          :economy #{"http://opensourceecology.org/"}}}},
       :downstream
       {:crdt :repo
        :op {:method :commit
             :commit-graph {3 [30]},
             :branches {"politics-coll" #{3}}
             :version 1}},
       :state
       {:commit-graph {3 [30], 10 [], 30 [10], 40 [30]},
        :branches {"politics-coll" #{3}, "master" #{40}}},
       :prepared {"politics-coll" [], "master" []}})



[[:subsection {:title "Merge"}]]

"You can check whether a merge is necessary (the head branch has multiple heads):"

(facts (test-env
        #(repo/multiple-branch-heads?  {:commit-graph {10 []
                                                       30 [10]
                                                       40 [10]},
                                        :branches {"master" #{40 30}
                                                   "politics-coll" #{30}}}
                                       "master"))
       => true)

"Merging is like pulling but resolving the commit-graph of the conflicting head commits with a new commit, which can apply further corrections atomically. You have to supply the remote-metadata and a vector of parents, which are applied to the repository value in order before the merge commit."

(fact (test-env
       #(repo/merge {:state {:commit-graph {10 []
                                            30 [10]
                                            40 [10]},
                             :branches {"master" #{40}
                                        "politics-coll" #{30}}}
                     :prepared {"master" []}}
                    "author@mail.com"
                    "master"
                    {:commit-graph {10 []
                                    20 [10]},
                     :branches {"master" #{20}}}
                    [40 20]
                    []))
      =>
      {:new-values
       {"master"
        {1
         {:transactions [],
          :ts #inst "1970-01-01T00:00:00.000-00:00",
          :branch "master"
          :parents [40 20],
          :crdt-refs #{}
          :crdt :repo
          :version 1
          :author "author@mail.com"}}},
       :downstream {:crdt :repo
                    :op {:method :merge
                         :commit-graph {1 [40 20]},
                         :branches {"master" #{1}}
                         :version 1}},
       :state
       {:commit-graph {1 [40 20], 20 [10], 10 [], 30 [10], 40 [10]},
        :branches {"politics-coll" #{30}, "master" #{1}}},
       :prepared {"master" []}})

"When there are pending commits, you need to resolve them first as well."
(fact (test-env
       #(try
          (repo/merge {:state {:commit-graph {10 []
                                              30 [10]
                                              40 [10]},
                               :branches {"master" #{40}
                                          "politics-coll" #{30}}}
                       :prepared {"master" [[{:economy #{"http://opensourceecology.org/"}
                                              :politics #{"http://www.economist.com/"}}
                                             '(fn merge-bookmarks [old params]
                                                (merge-with set/union old params))]]}}
                      "author@mail.com"
                      "master"
                      {:commit-graph {10 []
                                      20 [10]},
                       :branches {"master" #{20}}}
                      [40 20]
                      [])
          (catch clojure.lang.ExceptionInfo e
            (= (-> e ex-data :type) :transactions-pending-might-conflict))))
      => true)


"Have a look at the [replication API](replication.html), the [stage
API](http://whilo.github.io/replikativ/stage.html) and the [pull
hooks](http://whilo.github.io/replikativ/hooks.html) as well. Further
documentation will be added, have a look at the
[test/replikativ/core_test.clj](https://github.com/ghubber/replikativ/blob/master/test/replikativ/core_test.clj) tests or
the [API docs](doc/index.html) for implementation details."
