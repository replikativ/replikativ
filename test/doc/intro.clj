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
 {#uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7" #{},
  :root #uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7"},
 :last-update #inst "2013-11-11T05:10:59.495-00:00",
 :head "master",
 :public false,
 :branches {"master" #{#uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7"}},
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

" First we need to create the repository. The new-repositroy function returns a map containing both the metadata and value of the new repository."

(def stage {:meta {:causal-order {1 #{}},
                   :last-update #inst "1970-01-01T00:00:00.000-00:00",
                   :head "master",
                   :public false,
                   :branches {"master" #{1}},
                   :schema {:type "http://github.com/ghubber/geschichte"
                            :version 1,},
                   :pull-requests {},
                   :id 2,
                   :description "Bookmark collection."}
            :author "blub"
            :schema {:type "some" :version 1}
            :transactions [[{:attr :name}
                            '(fn to-uppercase [old {:keys [attr]}]
                               (update-in old [attr] #(.toUpperCase %)))]
                           [{:prename "Hans"
                             :surname "Mueller"}
                            '(fn schema-up [old {:keys [prename surname]}]
                               (-> old
                                   (dissoc :name)
                                   (assoc :prename prename
                                          :surname surname)))]]})


(s/transact stage {:attr :age}
            '(fn remove-attr [old {:keys [attr]}] (dissoc old attr)))


(fact
 (test-env
  #(repo/new-repository "author@mail.com"
                        {:type "http://some.bookmarksite.info/schema-file"
                         :version 1}
                        "Bookmark collection."
                        false
                        {:economy #{"http://opensourceecology.org/"}}))
 =>
 {:meta {:causal-order {1 #{}},
         :last-update #inst "1970-01-01T00:00:00.000-00:00",
         :head "master",
         :public false,
         :branches {"master" #{1}},
         :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
         :pull-requests {},
         :id 2,
         :description "Bookmark collection."},
  :author "author@mail.com",
  :schema {:version 1, :type "http://some.bookmarksite.info/schema-file"},
  :transactions []

  :type :new-meta
  :new-values {1 {:transactions [[{:economy #{"http://opensourceecology.org/"}}
                                  '(fn replace [old params] params)]],
                  :parents #{},
                  :author "author@mail.com",
                  :schema {:version 1, :type "http://some.bookmarksite.info/schema-file"}}}})


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

(fact
 (meta/update
  { ;; only new keys (commits) can be added => (merge new-value value)
   :causal-order {1 #{} ;; keys: G-SET
                  2 #{1}
                  :root 1}, ;; parental values don't change

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
   :branches {"master" #{2}},

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
  {:causal-order {1 #{}
                  2 #{1}
                  3 #{2}
                  1000 #{1}
                  :root 1},
   :last-update #inst "2000-01-01T00:00:00.000-00:00",
   :head "future",
   :public true,
   :branches {"master" #{3}
              "future" #{1000}},
   :schema {:type "http://github.com/ghubber/geschichte" :version 42},
   :pull-requests {"somebody@mail.com" {4 {:returnpaths-b {4 #{2}}
                                           :cut #{2}}}},
   :id 2,
   :description "Bookmark collection."})
 => {:causal-order {2 #{1}, 1 #{}, 3 #{2}, 1000 #{1}, :root 1},
     :last-update #inst "2000-01-01T00:00:00.000-00:00",
     :head "future",
     :public true,
     :branches {"master" #{3}, "future" #{1000}},
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
  {:causal-order {1 #{}
                  2 #{1}
                  3 #{2}
                  1000 #{1}
                  :root 1},
   :last-update #inst "2000-01-01T00:00:00.000-00:00",
   :head "future",
   :public true,
   :branches {"master" #{3}
              "future" #{1000}},
   :schema {:type "http://github.com/ghubber/geschichte" :version 42},
   :pull-requests {"somebody@mail.com" {4 {:returnpaths-b {4 #{2}}
                                           :cut #{2}}}},
   :id 2,
   :description "Bookmark collection."}
  {:causal-order {1 #{}
                  2 #{1}
                  :root 1},
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
 => {:causal-order {2 #{1}, 1 #{}, 3 #{2}, 1000 #{1}, :root 1},
     :last-update #inst "2000-01-01T00:00:00.000-00:00",
     :head "future",
     :public true,
     :branches {"master" #{3}, "future" #{1000}},
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
  {:causal-order {1 #{}
                  2 #{1}
                  :root 1},
   :last-update #inst "1970-01-01T00:00:00.000-00:00",
   :head "master",
   :public false,
   :branches {"master" #{2}},
   :schema {:type "http://github.com/ghubber/geschichte"
            :version 1,},
   :pull-requests {"somebody@mail.com" {3 {:returnpaths-b {3 #{2}}
                                           :cut #{2}}}},
   :id 2,
   :description "Bookmark collection."}
  {:causal-order {1 #{}
                  2 #{1}
                  :root 1},
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
 =>  {:causal-order {1 #{}
                     2 #{1}
                     :root 1},
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

"Which we have (hopefully) shown for each field of the metadata map individually above."

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

[[:subsection {:title "Clone"}]]

"You always clone a desired branch. More branches can be pulled separately. Only common causal-order (branch ancestors) is pulled."

(fact
 (test-env
  #(repo/clone {:causal-order {1 #{}
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
               true
               "author@mail.com"
               {:schema "http://bookmark-app.com/"
                :version 1}))
 =>
 {:meta {:id 2,
         :description "Bookmark collection.",
         :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
         :causal-order {1 #{}},
         :branches {"master" #{1}},
         :head "master",
         :last-update #inst "1970-01-01T00:00:00.000-00:00",
         :pull-requests {}},
  :author "author@mail.com",
  :schema {:version 1, :schema "http://bookmark-app.com/"},
  :transactions []

  :type :new-meta})

[[:subsection {:title "Pull"}]]

"Pulling happens much the same."

(fact
 (test-env
  #(repo/pull {:meta {:causal-order {1 #{}
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
               :author "author@mail.com"
               :schema {}
               :transactions []}
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
 {:meta {:causal-order {4 #{3},
                        3 #{1},
                        1 #{},
                        :root 1},
         :last-update #inst "1970-01-01T00:00:00.000-00:00",
         :head "master",
         :public false,
         :branches {"master" #{4}},
         :schema {:version 1,
                  :type "http://github.com/ghubber/geschichte"},
         :pull-requests {},
         :id 2,
         :description "Bookmark collection."},
  :author "author@mail.com",
  :schema {},
  :transactions []

  :type :meta-up})


[[:section {:title "Branching, Committing and Merging"}]]

[[:subsection {:title "Branch"}]]

"Branching is possible from any commit and does not create a commit:"

(fact
 (test-env
  #(repo/branch {:meta {:causal-order {10 #{}
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
                 :author ""
                 :schema {}
                 :transactions []}
                "environ-coll"
                30))
 =>
 {:meta {:causal-order {10 #{},
                        30 #{10},
                        40 #{30},
                        :root 10},
         :last-update #inst "1970-01-01T00:00:00.000-00:00",
         :head "master",
         :public false,
         :branches {"environ-coll" #{30},
                    "master" #{40},
                    "politics-coll" #{30}},
         :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
         :pull-requests {},
         :id 2,
         :description "Bookmark collection."},
  :author "",
  :schema {},
  :transactions []

  :type :meta-up})

"One can use this to merge pull-requests with old branch-heads in a dedicated branch, which otherwise cannot be pulled. Pull requests can also be merged directly."


[[:subsection {:title "Commit"}]]

"You can commit against any branch head."

(fact (test-env
       #(repo/commit {:meta {:causal-order {10 #{}
                                            30 #{10}
                                            40 #{30}
                                            :root 10},
                             :last-update #inst "1970-01-01T00:00:00.000-00:00",
                             :head "politics-coll",
                             :public false,
                             :branches {"master" #{40}
                                        "politics-coll" #{30}},
                             :schema {:type "http://github.com/ghubber/geschichte"
                                      :version 1},
                             :pull-requests {},
                             :id 2,
                             :description "Bookmark collection."}
                      :author "author@mail.com"
                      :schema {:type "schema"
                               :version 1}
                      :transactions [{:economy #{"http://opensourceecology.org/"}
                                      :politics #{"http://www.economist.com/"}}
                                     '(fn merge [old params] (merge-with set/union old params))]}))
      =>
      {:meta {:causal-order {1 #{30},
                             10 #{},
                             30 #{10},
                             40 #{30},
                             :root 10},
              :last-update #inst "1970-01-01T00:00:00.000-00:00",
              :head "politics-coll",
              :public false,
              :branches {"master" #{40}, "politics-coll" #{1}},
              :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
              :pull-requests {},
              :id 2,
              :description "Bookmark collection."},
       :author "author@mail.com",
       :schema {:version 1, :type "schema"},
       :transactions []

       :type :meta-up
       :new-values {1 {:transactions [{:politics #{"http://www.economist.com/"},
                                       :economy #{"http://opensourceecology.org/"}}
                                      '(fn merge [old params] (merge-with set/union old params))],
                       :parents #{30},
                       :author "author@mail.com",
                       :schema {:version 1, :type "schema"}}}})



[[:subsection {:title "Merge"}]]

"Merging is like pulling but adding a value as resolution for the new commit. You have to supply the remote-metadata."

(fact (test-env
       #(repo/merge {:author "author@mail.com"
                     :schema {:type "schema"
                              :version 1}
                     :meta {:causal-order {10 #{}
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
                     :transactions [{:economy #{"http://opensourceecology.org/"}
                                     :politics #{"http://www.economist.com/"}}
                                    '(fn merge [old params] (merge-with set/union old params))]}
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
                    #{20}))
      =>
      {:author "author@mail.com",
       :schema {:version 1, :type "schema"},
       :meta {:causal-order {1 #{40 20},
                             20 #{10},
                             10 #{},
                             30 #{10},
                             40 #{10},
                             :root 10},
              :last-update #inst "1970-01-01T00:00:00.000-00:00",
              :head "master",
              :public false,
              :branches {"master" #{1},
                         "politics-coll" #{30}},
              :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
              :pull-requests {},
              :id 2,
              :description "Bookmark collection."},
       :transactions []

       :type :meta-up
       :new-values {1 {:transactions [{:politics #{"http://www.economist.com/"},
                                       :economy #{"http://opensourceecology.org/"}}
                                      '(fn merge [old params] (merge-with set/union old params))],
                       :parents #{40 20},
                       :author "author@mail.com",
                       :schema {:version 1, :type "schema"}}}})


"Further documentation will be added, have a look at the
[test/geschichte/core_test.clj](https://github.com/ghubber/geschichte/blob/master/test/geschichte/core_test.clj) tests or
the [API docs](doc/index.html) for now."
