(ns doc.intro
  (:require [clojure.core.incubator :refer [dissoc-in]]
            [midje.sweet :refer :all]
            [geschichte.repo :refer :all]))

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
 {#uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7" #{},
  :root #uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7"},
 :last-update #inst "2013-11-11T05:10:59.495-00:00",
 :head "master",
 :public false,
 :branches {"master" #{#uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7"}},
 :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
 :pull-requests {"somebody@mail.com" {:returnpaths-b {3 #{2}
                                                      2 #{1}}
                                      :cut #{1}}},
 :id #uuid "b1732275-a7e7-4401-9485-c7249e4a13e7",
 :description "Bookmark collection."},

"We need to rebind the *id-generating* function and *time-function* to have fixed values here for testing. Otherwise ids are random `UUID`s by default, so they cannot and may not conflict. UUID-3 is the only option if you want to conform to the standard, so **don't** rebind these functions. They represent unchangable and global values. While time is very helpful to track, it is not critical for synching metadata. We will zero it out for testing here. All function calls are in fact unit tests, you can run this documentation with *midje-doc*."



(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [geschichte.repo/*id-fn* (let [counter (atom 0)]
                                      (fn ([] (swap! counter inc))
                                        ([val] (swap! counter inc))))
            geschichte.repo/*date-fn* zero-date-fn]
    (f)))

" First we need to create the repository. The new-repositroy function returns a map containing both the metadata and value of the new repository."


(fact (test-env
       #(new-repository "Bookmark collection." "author@mail.com"
                        {:type "http://some.bookmarksite.info/schema-file"
                         :version 1}
                        false
                        {:economy #{"http://opensourceecology.org/"}}))
      =>
      {:meta {:causal-order {1 #{}
                             :root 1},
              :last-update #inst "1970-01-01T00:00:00.000-00:00",
              :head "master",
              :public false,
              :branches {"master" #{1}},
              :schema {:type "http://github.com/ghubber/geschichte"
                       :version 1,},
              :pull-requests {},
              :id 2,
              :description "Bookmark collection."},
       :value {:geschichte.meta/meta
               {:ts #inst "1970-01-01T00:00:00.000-00:00",
                :author "author@mail.com",
                :schema {:type "http://some.bookmarksite.info/schema-file"
                         :version 1,},
                :branch "master",
                :id 1},
               :economy #{"http://opensourceecology.org/"}}})


[[:subsection {:title "Metadata"}]]

"First we have a look at the metadata structure: "

{:causal-order {1 #{}
                :root 1},
 :last-update #inst "1970-01-01T00:00:00.000-00:00",
 :head "master",
 :public false,
 :branches {"master" #{1}},
 :schema {:type "http://github.com/ghubber/geschichte"
          :version 1,},
 :pull-requests {},
 :id 2,
 :description "Bookmark collection."}

"* `:causal-order` contains the whole dependency graph for revisions and is core data we use to resolve conflicts. It points reverse from head to the root commit of the repository, which is the only commit with an empty parent set.
* `:branches` tracks all heads of branches in the causal order, while
* `:head` marks the currently selected branch (head).
* `:id` (UUID) is generated on creation and is constant for all forks.
* `:pull-requests` is the only part where other users can append data, to be pulled. Pull-requests are immutable.
* `:public` marks whether access is restricted to the user him/herself.
* `:schema` is necessary to allow updates to the server-side software."

[[:subsection {:title "Converging and Commutative Replicated Data Type (CRDT)"}]]

"It is noteworthy that the metadata is a [CRDT](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf). Since it needs to be synched globally (in a key value store), it needs to converge to be eventual consistent. When it is, synching new versions of metadata from remote sources can happen gradually and consistently converging to the global state and values of the repository. "

"For each key the CRDT update function for value and new-value is described:"

{:causal-order {1 #{} ;; parental values don't change => (merge new-value value)
                :root 1}, ;; (G-SET)
 ;; (if (> new-value value) new-value value) non-critical for merging (monotone)
 :last-update #inst "1970-01-01T00:00:00.000-00:00",
 ;; update to newer selection (for convenience to resolve the repo to a value)
 :head "master",
 ;; (or value new-value) might only turn true (monotone)
 :public false,
 ;; keys are G-SET
 ;; heads (values) are merged with lca which
 ;; is commutative and idempotent, OR-SET
 ;; heads cannot become empty
 :branches {"master" #{1}},
 :schema {:type "http://github.com/ghubber/geschichte" ;; immutable
          :version 1,}, ;; might only increase
 ;; keys G-SET
 ;; values OR-SET
 ;; if tip (key) in (keys causal-order) remove. (already pulled)
 :pull-requests {"somebody@mail.com" {:returnpaths-b {3 #{2}
                                                      2 #{1}}
                                      :cut #{1}}},
 ;; might never change (global id), immutable
 :id 2,
 ;; set on initialisation, bound to id, immutable
 :description "Bookmark collection."}

"The most sophisticated operation is merging branch heads through lca,
which is necessary to resolve conflicts between branch heads."

[[:subsection {:title "Value"}]]

{:geschichte.meta/meta
 {:ts #inst "1970-01-01T00:00:00.000-00:00",
  :author "author@mail.com",
  :schema {:type "http://some.bookmarksite.info/schema-file"
           :version 1,},
  :branch "master",
  :id 1},
 :economy #{"http://opensourceecology.org/"}}

"The value has to be associative (a map). It contains inlined metadata under the namespaced key `:geschichte.meta/meta`. Additional metadata can be assigned, if extending geschichte is needed. This inlined metadata is supposed to be used for deeper operations than merging, which should be determinable by global metadata alone."

[[:section {:title "Cloning and Pulling"}]]

"You always clone a desired branch. More branches can be pulled separately. Only common causal-order (branch ancestors) is pulled."

(fact (test-env
       #(clone {:causal-order {1 #{}
                               3 #{1}
                               :root 1},
                :last-update #inst "1970-01-01T00:00:00.000-00:00",
                :head "master",
                :public false,
                :branches {"master" #{1}
                           "politics-coll" #{3}},
                :schema {:type "http://github.com/ghubber/geschichte"
                         :version 1,},
                :pull-requests {},
                :id 2,
                :description "Bookmark collection."}
               "master"
               true))
      => {:id 2,
          :description "Bookmark collection.",
          :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
          :causal-order {1 #{}},
          :branches {"master" #{1}},
          :head "master",
          :ts #inst "1970-01-01T00:00:00.000-00:00",
          :pull-requests {}})

"Pulling happens much the same."
(fact (test-env
       #(pull {:causal-order {1 #{}
                              :root 1},
               :last-update #inst "1970-01-01T00:00:00.000-00:00",
               :head "master",
               :public false,
               :branches {"master" #{1}},
               :schema {:type "http://github.com/ghubber/geschichte"
                        :version 1,},
               :pull-requests {},
               :id 2,
               :description "Bookmark collection."}
              "master"
              {:causal-order {1 #{}
                              3 #{1}
                              4 #{3}
                              :root 1},
               :last-update #inst "1970-01-01T00:00:00.000-00:00",
               :head "master",
               :public false,
               :branches {"master" #{4}
                          "politics-coll" #{3}},
               :schema {:type "http://github.com/ghubber/geschichte"
                        :version 1,},
               :pull-requests {},
               :id 2,
               :description "Bookmark collection."}
              4))
      => {:meta {:causal-order {4 #{3}, 3 #{1}, 1 #{}, :root 1},
                 :last-update #inst "1970-01-01T00:00:00.000-00:00",
                 :head "master",
                 :public false,
                 :branches {"master" #{4}},
                 :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
                 :pull-requests {},
                 :id 2,
                 :description "Bookmark collection."},
          :branch-update "master",
          :new-revisions #{3 4}})

"If you try to pull and lca returns lowest-common-ancestors without branch's head, you get an error."

(fact (test-env
       #(pull {:causal-order {1 #{}
                              5 #{1}
                              :root 1},
               :last-update #inst "1970-01-01T00:00:00.000-00:00",
               :head "master",
               :public false,
               :branches {"master" #{5}},
               :schema {:type "http://github.com/ghubber/geschichte"
                        :version 1,},
               :pull-requests {},
               :id 2,
               :description "Bookmark collection."}
              "master"
              {:causal-order {1 #{}
                              3 #{1}
                              4 #{3}
                              :root 1},
               :last-update #inst "1970-01-01T00:00:00.000-00:00",
               :head "master",
               :public false,
               :branches {"master" #{4}
                          "politics-coll" #{3}},
               :schema {:type "http://github.com/ghubber/geschichte"
                        :version 1,},
               :pull-requests {},
               :id 2,
               :description "Bookmark collection."}
              4))
      =>
      {:error "Remote-tip is not descendant of local branch head. Merge is necessary."
       :meta
       {:causal-order {1 #{}, 5 #{1}, :root 1},
        :last-update #inst "1970-01-01T00:00:00.000-00:00",
        :head "master",
        :public false,
        :branches {"master" #{5}},
        :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
        :pull-requests {},
        :id 2,
        :description "Bookmark collection."},
       :branch "master",
       :lcas
       {:cut #{1},
        :returnpaths-a {1 #{5}, 5 #{}},
        :returnpaths-b {1 #{3}, 3 #{4}, 4 #{}}}})


"Similarly you cannot pull when you have multiple branch heads
 (e.g. through server synch from your different work places).  This is
not necessary from a technical side, but otherwise branches will diverge
uncontrollably and server side synching through lca on branch heads will
become expensive."

[[:section {:title "Branching, Committing and Merging"}]]

[[:subsection {:title "Branching"}]]

"Branching is possible from any commit and does not create a commit:"

(fact
 (branch {:causal-order {10 #{}
                         30 #{10}
                         40 #{30}
                         :root 10},
          :last-update #inst "1970-01-01T00:00:00.000-00:00",
          :head "master",
          :public false,
          :branches {"master" #{40}
                     "politics-coll" #{30}},
          :schema {:type "http://github.com/ghubber/geschichte"
                   :version 1,},
          :pull-requests {},
          :id 2,
          :description "Bookmark collection."}
         "environ-coll"
         30)
 => {:meta
     {:causal-order {10 #{}, 30 #{10}, 40 #{30}, :root 10},
      :last-update #inst "1970-01-01T00:00:00.000-00:00",
      :head "master",
      :public false,
      :branches
      {"master" #{40}, "environ-coll" #{30}, "politics-coll" #{30}},
      :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
      :pull-requests {},
      :id 2,
      :description "Bookmark collection."}})


[[:subsection {:title "Commit"}]]

"You can commit against any branch head."

(fact (test-env
       #(commit {:causal-order {10 #{}
                                30 #{10}
                                40 #{30}
                                :root 10},
                 :last-update #inst "1970-01-01T00:00:00.000-00:00",
                 :head "master",
                 :public false,
                 :branches {"master" #{40}
                            "politics-coll" #{30}},
                 :schema {:type "http://github.com/ghubber/geschichte"
                          :version 1},
                 :pull-requests {},
                 :id 2,
                 :description "Bookmark collection."}
                "author@mail.com"
                {:type "schema"
                 :version 1}
                "politics-coll"
                #{30}
                {:economy #{"http://opensourceecology.org/" ""}
                 :politics #{"http://www.economist.com/"}}))
      => {:meta
          {:causal-order {1 #{30}, 10 #{}, 30 #{10}, 40 #{30}, :root 10},
           :last-update #inst "1970-01-01T00:00:00.000-00:00",
           :head "master",
           :public false,
           :branches {"master" #{40}, "politics-coll" #{1}},
           :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
           :pull-requests {},
           :id 2,
           :description "Bookmark collection."},
          :value
          {:geschichte.meta/meta
           {:ts #inst "1970-01-01T00:00:00.000-00:00",
            :author "author@mail.com",
            :schema {:version 1, :type "schema"},
            :branch "politics-coll",
            :id 1},
           :politics #{"http://www.economist.com/"},
           :economy #{"" "http://opensourceecology.org/"}}})

"But not against non-branch heads:"

(fact (test-env
       #(commit {:causal-order {10 #{}
                                30 #{10}
                                40 #{30}
                                :root 10},
                 :last-update #inst "1970-01-01T00:00:00.000-00:00",
                 :head "master",
                 :public false,
                 :branches {"master" #{40}
                            "politics-coll" #{30}},
                 :schema {:type "http://github.com/ghubber/geschichte"
                          :version 1},
                 :pull-requests {},
                 :id 2,
                 :description "Bookmark collection."}
                "author@mail.com"
                {:type "schema"
                 :version 1}
                "politics-coll"
                #{10}
                {:economy #{"http://opensourceecology.org/"}
                 :politics #{"http://www.economist.com/"}}))
      => {:error "No parent is in branch heads.",
          :parents #{10},
          :branch "politics-coll",
          :meta
          {:causal-order {10 #{}, 30 #{10}, 40 #{30}, :root 10},
           :last-update #inst "1970-01-01T00:00:00.000-00:00",
           :head "master",
           :public false,
           :branches {"master" #{40}, "politics-coll" #{30}},
           :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
           :pull-requests {},
           :id 2,
           :description "Bookmark collection."},
          :branch-heads #{30}})

[[:subsection {:title "Merge"}]]

"Merging is like pulling but adding a value as resolution. You have to supply the remote-metadata "

(fact (test-env
       #(merge-heads "author@mail.com"
                     {:type "schema"
                      :version 1}
                     "master"
                     {:causal-order {10 #{}
                                     30 #{10}
                                     40 #{10}
                                     :root 10},
                      :last-update #inst "1970-01-01T00:00:00.000-00:00",
                      :head "master",
                      :public false,
                      :branches {"master" #{40}
                                 "politics-coll" #{30}},
                      :schema {:type "http://github.com/ghubber/geschichte"
                               :version 1},
                      :pull-requests {},
                      :id 2,
                      :description "Bookmark collection."}
                     #{40}
                     {:causal-order {10 #{}
                                     20 #{10}
                                     :root 10},
                      :last-update #inst "1970-01-01T00:00:00.000-00:00",
                      :head "master",
                      :public false,
                      :branches {"master" #{20}},
                      :schema {:type "http://github.com/ghubber/geschichte"
                               :version 1},
                      :pull-requests {},
                      :id 2,
                      :description "Bookmark collection."}
                     #{20}
                     {:economy #{"http://opensourceecology.org/"}
                      :politics #{"http://www.economist.com/"}}))
      => {:meta
          {:causal-order
           {1 #{40 20}, 20 #{10}, 10 #{}, 30 #{10}, 40 #{10}, :root 10},
           :last-update #inst "1970-01-01T00:00:00.000-00:00",
           :head "master",
           :public false,
           :branches {"master" #{1}, "politics-coll" #{30}},
           :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
           :pull-requests {},
           :id 2,
           :description "Bookmark collection."},
          :value
          {:geschichte.meta/meta
           {:ts #inst "1970-01-01T00:00:00.000-00:00",
            :author "author@mail.com",
            :schema {:version 1, :type "schema"},
            :branch "master",
            :id 1},
           :politics #{"http://www.economist.com/"},
           :economy #{"http://opensourceecology.org/"}}})


"You can also merge with twice the same metadata but multiple branch heads."

"Further documentation will be added, have a look at the
[test/geschichte/core_test.clj](http://github.com/ghubber/geschichte/test/geschichte/core_test.clj) tests or
the [API docs](http://github.com/ghubber/geschichte/doc/index.html) for now."
