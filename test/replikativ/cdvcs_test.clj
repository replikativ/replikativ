(ns replikativ.cdvcs-test
  (:require [clojure.test :refer :all]
            [replikativ.environ :refer [*id-fn* *date-fn*]]
            [replikativ.crdt.cdvcs.core :as cdvcs]
            [replikativ.crdt.cdvcs.meta :as meta]
            [replikativ.crdt.cdvcs.stage :as s]))

[[:chapter {:tag "metadata" :title "CDVCS metadata operations"}]]

"In the following we will explain how *replikativ* works by building a small CDVCS containing tagged bookmarks as an example."

[[:chapter {:tag "usage" :title "Usage"}]]

[[:section {:title "CDVCS format"}]]

"Metadata (without id binding) looks like:"

{:commit-graph
 {#uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7" []},
 :heads #{#uuid "214bd0cd-c737-4c7e-a0f5-778aca769cb7"}},

"We need to rebind the *id-generating* function and *time-function* to have fixed values here for testing. Otherwise ids are cryptographic `UUID`s over their referenced values by default, so they cannot and may not conflict. UUID-5 is the only option if you want to conform to the standard, so **don't** rebind these functions. The UUIDs represent unchangable and global values. While time can be helpful to track in commits, it is not critical for synching metadata. We will zero it out for testing here. All function calls are in fact unit tests, you can run this documentation with *[midje-doc](http://docs.caudate.me/lein-midje-doc/)*."



(defn zero-date-fn [] (java.util.Date. 0))

(defn test-env [f]
  (binding [*id-fn* (let [counter (atom 0)]
                      (fn ([] (swap! counter inc))
                        ([val] (swap! counter inc))))
            *date-fn* zero-date-fn]
    (f)))

"First we need to create the CDVCS. The new-cdvcs function returns a map containing both the metadata and value of the new CDVCS."


(deftest test-new-cdvcs
  (is (=
       (test-env
        #(cdvcs/new-cdvcs "mail:author@host.org"))
       {:state
        #replikativ.crdt.CDVCS{:commit-graph {1 []},
                               :heads #{1}
                               :version 1},
        :prepared [],
        :downstream
        {:crdt :cdvcs
         :op {:method :handshake
              :commit-graph {1 []},
              :heads #{1}
              :version 1}},
        :new-values
        {1
         {:transactions [],
          :parents [],
          :ts #inst "1970-01-01T00:00:00.000-00:00",
          :author "mail:author@host.org"
          :crdt :cdvcs
          :version 1}}})))


[[:subsection {:title "Metadata"}]]

"First we have a look at the metadata structure: "

{:commit-graph {1 []},
 :heads #{1}}

"* `:commit-graph` contains the whole dependency graph for revisions and is the core data we use to resolve conflicts. It points reverse from head to the root commit of the CDVCS, which is the only commit with an empty parent vector.
   * `:heads` tracks all heads in the graph order "

[[:subsection {:title "Convergent Replicated Data Type (CRDT)"}]]

"It is noteworthy that the metadata is a [CRDT](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf). Since it needs to be synched globally (in a key value store), it needs to converge to be eventual consistent. When it is, synching new versions of metadata from remote sources can happen gradually and consistently converging to the global state and values of the CDVCS. "

"For each key the CRDT update function for value and new-value is described:"

(deftest test-downstream
  (is (= (meta/downstream
          { ;; only new keys (commits) can be added => (merge new-value value)
           :commit-graph {1 []    ;; keys: G-SET
                          2 [1]}, ;; parental values don't change

           ;; keys: G-SET
           ;; values: similar to OR-SET,
           ;; (heads) are merged with lca which is commutative and idempotent,
           ;; heads cannot become empty
           :heads #{2}}

          ;; new metadata information:
          {:commit-graph {1 []
                          2 [1]
                          3 [2]
                          1000 [1]},
           :heads #{3}})
         {:commit-graph {1 []
                         2 [1]
                         3 [2]
                         1000 [1]},
          :heads #{3}})))

"The most sophisticated operation is merging heads through lca,
   which is necessary to resolve stale heads. This operation has
   currently square complexity on the number of heads."

"The operation is commutative: "

(deftest test-downstream-commutativ
  (is (=
       (meta/downstream
        ;; new metadata information:
        {:commit-graph {1 []
                        2 [1]
                        3 [2]
                        1000 [1]},
         :heads #{3}}
        {:commit-graph {1 []
                        2 [1]},
         :heads #{2}})
       (meta/downstream
        ;; new metadata information:
        {:commit-graph {1 []
                        2 [1]},
         :heads #{2}}
        {:commit-graph {1 []
                        2 [1]
                        3 [2]
                        1000 [1]},
         :heads  #{3}}))))

"And idempotent: "

   (deftest test-downstream-idempotent
     (is (=
          (meta/downstream
           {:commit-graph {1 []
                           2 []},
            :heads #{2}}
           {:commit-graph {1 []
                           2 []},
            :heads #{2}})
          {:commit-graph {1 []
                          2 []},
           :heads #{2}})))

"Which we have shown for each field of the metadata map individually above."

[[:subsection {:title "Value"}]]

{#uuid "04eb5b1b-4d10-5036-b235-fa173253089a"
 {:transactions [['(fn add-links [old params] (merge-with set/union old params)) ;; actually uuids pointing to fn and params
                  {:economy #{"http://opensourceecology.org/"}}]],
  :ts #inst "1970-01-01T00:00:00.000-00:00",
  :author "mail:author@host.org",
  :parents [2 3], ;; normally singular, with merge sequence of parent commits applied in ascending order.
  }}

"The value consists of one or more transactions, each a pair of a parameter map (data) and a freely chosen data (code) to describe the transaction. The code needn't be freely evaled, but can be mapped to a limit set of application specific operations. That way it can be safely resolved via a hardcoded hash-map and will still be invariant to version changes in code. Read: You should use a literal code description instead of symbols where possible, even if this induces a small overhead."

 "The conflict case is also converging:"
(deftest test-convergence
  (is (=
       (meta/downstream
        ;; new metadata information:
        {:commit-graph {1 []
                        2 [1]
                        3 [2]
                        1000 [1]},
         :heads #{1000 2}}
        {:commit-graph {1 []
                        2 [1]},
         :heads #{2}})
       (meta/downstream
        ;; new metadata information:
        {:commit-graph {1 []
                        2 [1]},
         :heads #{2}}
        {:commit-graph {1 []
                        2 [1]
                        3 [2]
                        1000 [1]},
         :heads  #{1000 2}}))))

[[:section {:title "Forking and Pulling"}]]

[[:subsection {:title "Forking (Cloning)"}]]

"Forking yields a copy (clone)."

(deftest test-forking
  (is (=
       (test-env
        #(cdvcs/fork {:commit-graph {1 []
                                     3 [1]},
                      :heads #{3}}))
       {:state
        #replikativ.crdt.CDVCS{:commit-graph {1 []
                                              3 [1]},
                               :heads #{3}},
        :prepared [],
        :downstream
        {:crdt :cdvcs
         :op {:method :handshake
              :commit-graph {1 []
                             3 [1]},
              :heads #{3}
              :version 1}}})))

[[:subsection {:title "Pull"}]]

"Pulling happens much the same."

(deftest test-pulling
  (is (=
       (test-env
        #(cdvcs/pull {:state {:commit-graph {1 []},
                              :heads #{1}}
                      :prepared []}
                     {:commit-graph {1 []
                                     3 [1]
                                     4 [3]},
                      :heads #{4}}
                     4))
       {:downstream
        {:crdt :cdvcs
         :op {:commit-graph {4 [3], 3 [1], 1 []},
              :method :pull
              :heads #{4}
              :version 1}},
        :state
        {:commit-graph {1 [], 3 [1], 4 [3]},
         :heads #{4}},
        :prepared []})))


[[:section {:title "Committing and Merging"}]]

[[:subsection {:title "Commit"}]]

"Commit to apply changes to a CDVCS."

(deftest test-commit
  (is (=
       (test-env
        #(cdvcs/commit {:state {:commit-graph {10 []
                                               30 [10]
                                               40 [30]},
                                :heads #{40}}
                        :prepared [[{:economy
                                     #{"http://opensourceecology.org/"}
                                     :politics #{"http://www.economist.com/"}}
                                    '(fn merge [old params] (merge-with set/union old params))]]}
                       "mail:author@host.org"))
       {:new-values
        {3
         {:transactions [[1 2]],
          :ts #inst "1970-01-01T00:00:00.000-00:00",
          :parents [40],
          :crdt :cdvcs
          :version 1
          :author "mail:author@host.org"},
         2 '(fn merge [old params] (merge-with set/union old params)),
         1
         {:politics #{"http://www.economist.com/"},
          :economy #{"http://opensourceecology.org/"}}},
        :downstream
        {:crdt :cdvcs
         :op {:method :commit
              :commit-graph {3 [40]},
              :heads #{3}
              :version 1}},
        :state
        {:commit-graph {3 [40], 10 [], 30 [10], 40 [30]},
         :heads #{3}},
        :prepared []})))



[[:subsection {:title "Merge"}]]

"You can check whether a merge is necessary (there are multiple heads):"

(deftest test-multiple-heads
  (is (test-env
       #(cdvcs/multiple-heads?  {:commit-graph {10 []
                                                30 [10]
                                                40 [10]},
                                 :heads #{40 30}}))))

"Merging is like pulling but resolving the commit-graph of the conflicting head commits with a new commit, which can apply further corrections atomically. You have to supply the remote-metadata and a vector of parents, which are applied to the CDVCS value in order before the merge commit."

(deftest test-merge
  (is (=
       (test-env
        #(cdvcs/merge {:state {:commit-graph {10 []
                                              30 [10]
                                              40 [10]},
                               :heads  #{40}}
                       :prepared []}
                      "mail:author@host.org"
                      {:commit-graph {10 []
                                      20 [10]},
                       :heads #{20}}
                      [40 20]
                      []))
       {:new-values
        {1
         {:transactions [],
          :ts #inst "1970-01-01T00:00:00.000-00:00",
          :parents [40 20],
          :crdt :cdvcs
          :version 1
          :author "mail:author@host.org"}},
        :downstream {:crdt :cdvcs
                     :op {:method :merge
                          :commit-graph {1 [40 20]},
                          :heads #{1}
                          :version 1}},
        :state
        {:commit-graph {1 [40 20], 20 [10], 10 [], 30 [10], 40 [10]},
         :heads #{1}},
        :prepared []})))

"When there are pending commits, you need to resolve them first as well."

(deftest test-merge-resolution
  (is
   (test-env
    #(try
       (cdvcs/merge {:state {:commit-graph {10 []
                                            30 [10]
                                            40 [10]},
                             :heads #{40}}
                     :prepared  [[{:economy #{"http://opensourceecology.org/"}
                                   :politics #{"http://www.economist.com/"}}
                                  '(fn merge-bookmarks [old params]
                                     (merge-with set/union old params))]]}
                    "mail:author@host.org"
                    {:commit-graph {10 []
                                    20 [10]},
                     :heads #{20}}
                    [40 20]
                    [])
       (catch clojure.lang.ExceptionInfo e
         (= (-> e ex-data :type) :transactions-pending-might-conflict))))))


"Have a look at the [replication API](replication.html), the [stage
API](http://whilo.github.io/replikativ/stage.html) and the [pull
hooks](http://whilo.github.io/replikativ/hooks.html) as well. Further
documentation will be added, have a look at the
[test/replikativ/core_test.clj](https://github.com/ghubber/replikativ/blob/master/test/replikativ/core_test.clj) tests or
the [API docs](doc/index.html) for implementation details."
