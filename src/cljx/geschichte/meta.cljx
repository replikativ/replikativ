(ns geschichte.meta
  "Operation on metadata and causal-order (directed acyclic graph) of a repository.

   Metadata repository-format for automatic server-side
   synching (p2p-web). Have a look at the midje-doc documentation for
   more information."
  (:require [clojure.set :as set]))

(defn consistent-causal? [causal]
  (let [parents (->> causal vals (map set) (apply set/union))
        commits (->> causal keys set)]
    (set/superset? commits parents)))

(defn- track-returnpaths [returnpaths heads meta]
  (reduce (fn [returnpaths head]
            (reduce (fn [returnpaths parent]
                      (update-in returnpaths [parent] #(conj (or %1 #{}) %2) head))
                    returnpaths
                    (meta head)))
          returnpaths
          heads))


(defn- init-returnpath [heads]
  (reduce #(assoc %1 %2 #{}) {} heads))


(defn lowest-common-ancestors
  "Naive online BFS implementation. Assumes no cycles exist."
  ([meta-a heads-a meta-b heads-b]
     (let [heads-a (set heads-a)
           heads-b (set heads-b)
           returnpaths-a (init-returnpath heads-a)
           returnpaths-b (init-returnpath heads-b)
           cut (set/intersection heads-a heads-b)]
       (if-not (empty? cut) {:cut cut
                             :returnpaths-a returnpaths-a
                             :returnpaths-b returnpaths-b}
               (lowest-common-ancestors meta-a heads-a returnpaths-a
                                        meta-b heads-b returnpaths-b))))
  ([meta-a heads-a returnpaths-a
    meta-b heads-b returnpaths-b]
     (let [new-returnpaths-a (track-returnpaths returnpaths-a heads-a meta-a)
           new-returnpaths-b (track-returnpaths returnpaths-b heads-b meta-b)
           cut (set/intersection (set (keys new-returnpaths-a)) (set (keys new-returnpaths-b)))]
       (if (or (not (empty? cut))
               (and (empty? heads-a) (empty? heads-b)))
         {:cut cut :returnpaths-a new-returnpaths-a :returnpaths-b new-returnpaths-b}
         (let [new-heads-a (set (mapcat meta-a heads-a))
               new-heads-b (set (mapcat meta-b heads-b))]
           (recur meta-a new-heads-a new-returnpaths-a
                  meta-b new-heads-b new-returnpaths-b))))))


(defn- merge-parent [missing-returnpaths meta parent]
  (reduce (fn [meta child]
            (update-in meta [child] #(conj (or %1 []) %2) parent))
          meta
          (missing-returnpaths parent)))


(defn merge-ancestors
  "Use returnpaths and cut from lowest-common-ancestors to merge alien
   ancestor paths into meta data."
  ([meta cut missing-returnpaths]
     (let [new-meta (reduce (partial merge-parent missing-returnpaths) meta cut)
           new-cut (mapcat missing-returnpaths cut)]
       (if (empty? new-cut) new-meta
         (recur new-meta new-cut missing-returnpaths)))))


;; TODO refactor to isolate-tipps
(declare isolate-branch)
(defn isolate-branch                    ; -nomemo
  "Isolate a branch's metadata causal-order."
  ([meta branch]
   (isolate-branch (:causal-order meta) (-> meta :branches (get branch)) {}))
  ([causal-order cut branch-meta]
   (if (empty? cut) branch-meta
       (recur causal-order
              (set (mapcat causal-order cut))
              (merge branch-meta (select-keys causal-order cut))))))

#_(def isolate-branch (memoize isolate-branch-nomemo))


(defn- old-heads [causal heads]
  (set (for [a heads b heads]
         (if (not= a b)                 ; => not a and b in cut
           (let [{:keys [returnpaths-a returnpaths-b]}
                 (lowest-common-ancestors causal #{a} causal #{b})
                 keys-a (set (keys returnpaths-a))
                 keys-b (set (keys returnpaths-b))]
             (cond (keys-b a) a
                   (keys-a b) b))))))


(defn remove-ancestors [causal heads-a heads-b]
  (if causal
    (let [to-remove (old-heads causal (set/union heads-a heads-b))]
      (set (filter #(not (to-remove %)) (set/union heads-a heads-b))))))

(defrecord RepoCRDTState [id description schema public causal-order branches])

(defn update
  "Updates current meta-data with other-meta metadata. Idempotent and commutative."
  [{:keys [id description schema public causal-order branches] :as meta} other-meta]
  ;; TODO move check to entry point/middleware
  (when-not (consistent-causal? (:causal-order other-meta))
    (throw (ex-info "Remote meta does not have a consistent causal oder."
                    {:type :inconsistent-causal-order
                     :meta other-meta})))
  (let [new-causal (merge (:causal-order other-meta) causal-order)
        new-meta {:id id
                  :description (or description (:description other-meta))
                  :schema {:type (:type schema)
                           :version (max (:version schema) (or (:version (:schema other-meta))
                                                               (:version schema)))}
                  :branches (merge-with (partial remove-ancestors new-causal)
                                        branches (:branches other-meta))
                  :public (or public (:public other-meta) false)}]
    (if new-causal
      (assoc new-meta :causal-order new-causal)
      new-meta)))


(comment
  (require '[clojure.tools.trace :refer [dotrace]])

  #_(dotrace [lowest-common-ancestors] (update problem-meta problem-meta))
  ;; heavily branched merge diverging sample
  (update {:causal-order
           {#uuid "353ac289-58ed-5544-ae57-425b27bc7996"
            [#uuid "2208c288-7a73-5011-a76d-7f36d9a2d9e5"
             #uuid "321c92b7-95ff-599b-a752-73729087d450"
             #uuid "2208c288-7a73-5011-a76d-7f36d9a2d9e5"
             #uuid "321c92b7-95ff-599b-a752-73729087d450"],
            #uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
            [#uuid "0c020b47-eada-568f-a0e1-4624ca4767c8"
             #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"
             #uuid "0c020b47-eada-568f-a0e1-4624ca4767c8"
             #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"],
            #uuid "22799c09-1bd7-5ce7-8e78-e493cefe17bd"
            [#uuid "03889ec4-5060-5c4a-a955-b7bececc09dc"
             #uuid "0cd0077a-4445-5b00-bc48-bff3225e5a0f"
             #uuid "03889ec4-5060-5c4a-a955-b7bececc09dc"
             #uuid "0cd0077a-4445-5b00-bc48-bff3225e5a0f"],
            #uuid "3752cf7d-7005-5480-a651-faf0a8beb6ed"
            [#uuid "2fd7db13-9d5e-51cc-9bb3-dfcaf976d2e1"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             #uuid "2fd7db13-9d5e-51cc-9bb3-dfcaf976d2e1"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             ],
            #uuid "1178bfe7-9089-572f-97dc-1de79818de4e"
            [#uuid "0dd3cba5-2dac-53a9-b260-e2329912a196"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             #uuid "001d0cae-56c9-5837-8d03-834fa4d5c6bf"
             #uuid "0dd3cba5-2dac-53a9-b260-e2329912a196"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             #uuid "001d0cae-56c9-5837-8d03-834fa4d5c6bf"],
            #uuid "0c020b47-eada-568f-a0e1-4624ca4767c8"
            [#uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             #uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"],
            #uuid "16ff4cb0-b14a-566f-b517-344314817898"
            [#uuid "28c8e600-aaea-5ff7-ad07-2e79a5e36702"
             #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             #uuid "1253e0c3-4d55-59b4-9268-80fb70d2c447"
             #uuid "28c8e600-aaea-5ff7-ad07-2e79a5e36702"
             #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             #uuid "1253e0c3-4d55-59b4-9268-80fb70d2c447"],
            #uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"
            [#uuid "0faa8ca5-b018-57cb-8577-73bb5519738e"

             #uuid "22799c09-1bd7-5ce7-8e78-e493cefe17bd"
             #uuid "0faa8ca5-b018-57cb-8577-73bb5519738e"
             #uuid "22799c09-1bd7-5ce7-8e78-e493cefe17bd"],
            #uuid "041a080d-6c39-58e8-b602-89b6fb902b17"
            [#uuid "3083686c-ae18-5c88-b67b-3d45bdf7cc2c"
             #uuid "1da324e0-4e16-5e8e-ba40-35e5e3746266"
             #uuid "3083686c-ae18-5c88-b67b-3d45bdf7cc2c"
             #uuid "1da324e0-4e16-5e8e-ba40-35e5e3746266"],
            #uuid "3ce64964-f0e5-5d74-bcf2-c3294c45099d"
            [#uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"
             #uuid "1d1d39dd-b594-5a79-8313-0148d7104d76"
             #uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"
             #uuid "1d1d39dd-b594-5a79-8313-0148d7104d76"],
            #uuid "22ff91ab-a87f-5bd3-8da3-99d81e1f9385"
            [#uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             #uuid "1d1d39dd-b594-5a79-8313-0148d7104d76"
             #uuid "1293c59f-107b-5060-a719-176b395f8e9b"
             #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             #uuid "1d1d39dd-b594-5a79-8313-0148d7104d76"
             #uuid "1293c59f-107b-5060-a719-176b395f8e9b"],
            #uuid "316d3c09-81bf-5067-ba42-1648b2274663"

            [#uuid "353ac289-58ed-5544-ae57-425b27bc7996"
             #uuid "0dac91e1-1fbe-5c8a-99a8-52604c316ddc"
             #uuid "353ac289-58ed-5544-ae57-425b27bc7996"
             #uuid "0dac91e1-1fbe-5c8a-99a8-52604c316ddc"],
            #uuid "0e68b433-1278-5836-b756-8a4441b7faa7"
            [#uuid "0f82bbb6-f9f0-5813-968b-609390c0c6f1"
             #uuid "27acd9f9-d732-5055-bdee-41a18c4b3517"
             #uuid "0f82bbb6-f9f0-5813-968b-609390c0c6f1"
             #uuid "27acd9f9-d732-5055-bdee-41a18c4b3517"],
            #uuid "0cd0077a-4445-5b00-bc48-bff3225e5a0f"
            [#uuid "39a6932d-9412-5157-ba8b-8889d0c47220"],
            #uuid "36d2002e-1e52-5e81-829f-988e7cac3947"
            [#uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"
             #uuid "1c734d9f-4a2c-52f3-8e4f-4c4aa0904eb9"
             #uuid "3fe1791f-6810-5ade-9f8d-7b35f1eaa6d9"
             #uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"
             #uuid "1c734d9f-4a2c-52f3-8e4f-4c4aa0904eb9"
             #uuid "3fe1791f-6810-5ade-9f8d-7b35f1eaa6d9"],
            #uuid "04d6cfa7-3999-58a7-8ecf-486a2205a64d"
            [#uuid "1d1d39dd-b594-5a79-8313-0148d7104d76"
             #uuid "30069192-ed08-57ec-b646-eb6852a834e3"

             #uuid "1d1d39dd-b594-5a79-8313-0148d7104d76"
             #uuid "30069192-ed08-57ec-b646-eb6852a834e3"],
            #uuid "0f82bbb6-f9f0-5813-968b-609390c0c6f1"
            [#uuid "13a887b1-910d-5d0e-bc5c-ad9fd019f44d"
             #uuid "3d08f789-a6c4-54a3-8400-bf440c51e0a4"
             #uuid "13a887b1-910d-5d0e-bc5c-ad9fd019f44d"
             #uuid "3d08f789-a6c4-54a3-8400-bf440c51e0a4"],
            #uuid "0a703ef2-60f4-5ccc-8dd7-64ccb46cc9fa"
            [#uuid "16ff4cb0-b14a-566f-b517-344314817898"
             #uuid "3d08f789-a6c4-54a3-8400-bf440c51e0a4"
             #uuid "16ff4cb0-b14a-566f-b517-344314817898"
             #uuid "3d08f789-a6c4-54a3-8400-bf440c51e0a4"],
            #uuid "33b6ed58-5765-5804-bfe8-a2a0fef5e0b4"
            [#uuid "0dd3cba5-2dac-53a9-b260-e2329912a196"
             #uuid "3236cc78-a667-5d75-ba84-6f3c865eaa27"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             #uuid "3d7f09dc-db22-5b12-ab49-349fadc30d58"
             #uuid "0dd3cba5-2dac-53a9-b260-e2329912a196"
             #uuid "3236cc78-a667-5d75-ba84-6f3c865eaa27"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             #uuid "3d7f09dc-db22-5b12-ab49-349fadc30d58"
             ],
            #uuid "0dd3cba5-2dac-53a9-b260-e2329912a196"
            [#uuid "353ac289-58ed-5544-ae57-425b27bc7996"
             #uuid "0dac91e1-1fbe-5c8a-99a8-52604c316ddc"
             #uuid "1c734d9f-4a2c-52f3-8e4f-4c4aa0904eb9"
             #uuid "353ac289-58ed-5544-ae57-425b27bc7996"
             #uuid "0dac91e1-1fbe-5c8a-99a8-52604c316ddc"
             #uuid "1c734d9f-4a2c-52f3-8e4f-4c4aa0904eb9"],
            #uuid "327f8a5e-1cab-5919-a5d0-6c2e1f1cbca1"
            [#uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             #uuid "001d0cae-56c9-5837-8d03-834fa4d5c6bf"
             #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             #uuid "001d0cae-56c9-5837-8d03-834fa4d5c6bf"],
            #uuid "001d0cae-56c9-5837-8d03-834fa4d5c6bf"
            [#uuid "3236cc78-a667-5d75-ba84-6f3c865eaa27"
             #uuid "2fd7db13-9d5e-51cc-9bb3-dfcaf976d2e1"
             #uuid "3236cc78-a667-5d75-ba84-6f3c865eaa27"
             #uuid "2fd7db13-9d5e-51cc-9bb3-dfcaf976d2e1"],
            #uuid "3d7f09dc-db22-5b12-ab49-349fadc30d58"
            [#uuid "36d2002e-1e52-5e81-829f-988e7cac3947"
             #uuid "2fd7db13-9d5e-51cc-9bb3-dfcaf976d2e1"
             #uuid "36d2002e-1e52-5e81-829f-988e7cac3947"

             #uuid "2fd7db13-9d5e-51cc-9bb3-dfcaf976d2e1"],
            #uuid "0008c24d-da09-50b5-ad12-b647471fd3b6"
            [#uuid "0cd0077a-4445-5b00-bc48-bff3225e5a0f"
             #uuid "03889ec4-5060-5c4a-a955-b7bececc09dc"
             #uuid "0cd0077a-4445-5b00-bc48-bff3225e5a0f"
             #uuid "03889ec4-5060-5c4a-a955-b7bececc09dc"],
            #uuid "34819a87-c9fa-51b1-8142-d4b8afb86007"
            [#uuid "0a703ef2-60f4-5ccc-8dd7-64ccb46cc9fa"
             #uuid "27acd9f9-d732-5055-bdee-41a18c4b3517"
             #uuid "0a703ef2-60f4-5ccc-8dd7-64ccb46cc9fa"
             #uuid "27acd9f9-d732-5055-bdee-41a18c4b3517"],
            #uuid "2208c288-7a73-5011-a76d-7f36d9a2d9e5"
            [#uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"],
            #uuid "3d08f789-a6c4-54a3-8400-bf440c51e0a4"
            [#uuid "0de99c6e-0a10-5faf-b1dd-99b6a04ab5fc"
             #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             #uuid "0de99c6e-0a10-5faf-b1dd-99b6a04ab5fc"
             #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             ],
            #uuid "3586fbdf-ec95-5966-81fd-edbfe659fa2c"
            [#uuid "34819a87-c9fa-51b1-8142-d4b8afb86007"
             #uuid "2208c288-7a73-5011-a76d-7f36d9a2d9e5"
             #uuid "2ee40183-fb17-5ce9-a136-e6c9737390fc"
             #uuid "15338ba3-edf2-511b-a960-98a609095054"
             #uuid "34819a87-c9fa-51b1-8142-d4b8afb86007"
             #uuid "2208c288-7a73-5011-a76d-7f36d9a2d9e5"
             #uuid "2ee40183-fb17-5ce9-a136-e6c9737390fc"
             #uuid "15338ba3-edf2-511b-a960-98a609095054"],
            #uuid "15338ba3-edf2-511b-a960-98a609095054"
            [#uuid "16ff4cb0-b14a-566f-b517-344314817898"
             #uuid "1a763604-2c74-502f-8540-e7c590dfb39c"
             #uuid "33dcec63-d61b-5ff9-8245-c7bb1f6c45ea"
             #uuid "16ff4cb0-b14a-566f-b517-344314817898"
             #uuid "1a763604-2c74-502f-8540-e7c590dfb39c"
             #uuid "33dcec63-d61b-5ff9-8245-c7bb1f6c45ea"],
            #uuid "00f6e37a-eed5-570c-a64a-f559645a5b85"
            [#uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "27acd9f9-d732-5055-bdee-41a18c4b3517"
             #uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"

             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "27acd9f9-d732-5055-bdee-41a18c4b3517"],
            #uuid "33dcec63-d61b-5ff9-8245-c7bb1f6c45ea"
            [#uuid "2a95a5a9-617e-56c2-a893-fca9e1def5d4"
             #uuid "1253e0c3-4d55-59b4-9268-80fb70d2c447"
             #uuid "2a95a5a9-617e-56c2-a893-fca9e1def5d4"
             #uuid "1253e0c3-4d55-59b4-9268-80fb70d2c447"],
            #uuid "1253e0c3-4d55-59b4-9268-80fb70d2c447"
            [#uuid "29923963-c513-5390-b645-d9531373857c"
             #uuid "3ce64964-f0e5-5d74-bcf2-c3294c45099d"
             #uuid "29923963-c513-5390-b645-d9531373857c"
             #uuid "3ce64964-f0e5-5d74-bcf2-c3294c45099d"],
            #uuid "123dd307-9df4-5e39-ad2e-1438c1ab134d"
            [#uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "21f13f78-5880-54eb-96ad-2d96df0e4813"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "3e866b49-0ae6-53a5-b3a4-2491d1b8f282"
             #uuid "2ec61402-8576-5d3f-bc29-f0f12965ce16"
             #uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "21f13f78-5880-54eb-96ad-2d96df0e4813"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"

             #uuid "3e866b49-0ae6-53a5-b3a4-2491d1b8f282"
             #uuid "2ec61402-8576-5d3f-bc29-f0f12965ce16"],
            #uuid "0de99c6e-0a10-5faf-b1dd-99b6a04ab5fc"
            [#uuid "3752cf7d-7005-5480-a651-faf0a8beb6ed"
             #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"
             #uuid "04d6cfa7-3999-58a7-8ecf-486a2205a64d"
             #uuid "3752cf7d-7005-5480-a651-faf0a8beb6ed"
             #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"
             #uuid "04d6cfa7-3999-58a7-8ecf-486a2205a64d"],
            #uuid "28c8e600-aaea-5ff7-ad07-2e79a5e36702"
            [#uuid "3ce64964-f0e5-5d74-bcf2-c3294c45099d"
             #uuid "04d6cfa7-3999-58a7-8ecf-486a2205a64d"
             #uuid "3ce64964-f0e5-5d74-bcf2-c3294c45099d"
             #uuid "04d6cfa7-3999-58a7-8ecf-486a2205a64d"],
            #uuid "03889ec4-5060-5c4a-a955-b7bececc09dc"
            [#uuid "39a6932d-9412-5157-ba8b-8889d0c47220"],
            #uuid "00964867-9755-5302-83f5-d23b0ea07892"
            [#uuid "04d6cfa7-3999-58a7-8ecf-486a2205a64d"
             #uuid "327f8a5e-1cab-5919-a5d0-6c2e1f1cbca1"
             #uuid "22ff91ab-a87f-5bd3-8da3-99d81e1f9385"
             #uuid "04d6cfa7-3999-58a7-8ecf-486a2205a64d"

             #uuid "327f8a5e-1cab-5919-a5d0-6c2e1f1cbca1"
             #uuid "22ff91ab-a87f-5bd3-8da3-99d81e1f9385"],
            #uuid "38e2e4a0-0771-51c6-8cb9-809db7cd5bf7"
            [#uuid "2ed4a2f6-142d-555f-bfce-213f489f4269"
             #uuid "3ce64964-f0e5-5d74-bcf2-c3294c45099d"
             #uuid "2ed4a2f6-142d-555f-bfce-213f489f4269"
             #uuid "3ce64964-f0e5-5d74-bcf2-c3294c45099d"],
            #uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"
            [#uuid "3752cf7d-7005-5480-a651-faf0a8beb6ed"
             #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"
             #uuid "327f8a5e-1cab-5919-a5d0-6c2e1f1cbca1"
             #uuid "3752cf7d-7005-5480-a651-faf0a8beb6ed"
             #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"
             #uuid "327f8a5e-1cab-5919-a5d0-6c2e1f1cbca1"],
            #uuid "12105edd-21db-5b9e-bdfd-6aa6a8d52809"
            [#uuid "1401ff5d-3be1-568e-ae97-ad765d6eb652"],
            #uuid "3990c395-f04d-56b9-927e-76556a5097f4"
            [#uuid "37f2b268-6598-50ad-abe7-29f73addb001"
             #uuid "30069192-ed08-57ec-b646-eb6852a834e3"
             #uuid "37f2b268-6598-50ad-abe7-29f73addb001"
             #uuid "30069192-ed08-57ec-b646-eb6852a834e3"
             ],
            #uuid "3083686c-ae18-5c88-b67b-3d45bdf7cc2c"
            [#uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "21f13f78-5880-54eb-96ad-2d96df0e4813"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "3e866b49-0ae6-53a5-b3a4-2491d1b8f282"
             #uuid "0e68b433-1278-5836-b756-8a4441b7faa7"
             #uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "21f13f78-5880-54eb-96ad-2d96df0e4813"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "3e866b49-0ae6-53a5-b3a4-2491d1b8f282"
             #uuid "0e68b433-1278-5836-b756-8a4441b7faa7"],
            #uuid "1da324e0-4e16-5e8e-ba40-35e5e3746266"
            [#uuid "3e866b49-0ae6-53a5-b3a4-2491d1b8f282"
             #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             #uuid "3e866b49-0ae6-53a5-b3a4-2491d1b8f282"
             #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"],
            #uuid "3fe1791f-6810-5ade-9f8d-7b35f1eaa6d9"
            [#uuid "353ac289-58ed-5544-ae57-425b27bc7996"
             #uuid "0dac91e1-1fbe-5c8a-99a8-52604c316ddc"
             #uuid "3e397695-7321-57b3-8bcf-5e163977454d"
             #uuid "353ac289-58ed-5544-ae57-425b27bc7996"

             #uuid "0dac91e1-1fbe-5c8a-99a8-52604c316ddc"
             #uuid "3e397695-7321-57b3-8bcf-5e163977454d"],
            #uuid "13a887b1-910d-5d0e-bc5c-ad9fd019f44d"
            [#uuid "28c8e600-aaea-5ff7-ad07-2e79a5e36702"
             #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             #uuid "38e2e4a0-0771-51c6-8cb9-809db7cd5bf7"
             #uuid "28c8e600-aaea-5ff7-ad07-2e79a5e36702"
             #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
             #uuid "38e2e4a0-0771-51c6-8cb9-809db7cd5bf7"],
            #uuid "321c92b7-95ff-599b-a752-73729087d450"
            [#uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"],
            #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"
            [#uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             #uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             #uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"],
            #uuid "1293c59f-107b-5060-a719-176b395f8e9b"
            [#uuid "3752cf7d-7005-5480-a651-faf0a8beb6ed"

             #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"
             #uuid "3990c395-f04d-56b9-927e-76556a5097f4"
             #uuid "3752cf7d-7005-5480-a651-faf0a8beb6ed"
             #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"
             #uuid "3990c395-f04d-56b9-927e-76556a5097f4"],
            #uuid "3f06b346-cb73-5fc8-af04-021b314d9b65"
            [#uuid "12105edd-21db-5b9e-bdfd-6aa6a8d52809"],
            #uuid "05293360-95b4-5481-a594-b9de4979308f"
            [#uuid "00f6e37a-eed5-570c-a64a-f559645a5b85"
             #uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"
             #uuid "112b8e9f-d029-542c-8919-a41b56365c9d"
             #uuid "00f6e37a-eed5-570c-a64a-f559645a5b85"
             #uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"
             #uuid "112b8e9f-d029-542c-8919-a41b56365c9d"],
            #uuid "1a763604-2c74-502f-8540-e7c590dfb39c"
            [#uuid "00f6e37a-eed5-570c-a64a-f559645a5b85"
             #uuid "321c92b7-95ff-599b-a752-73729087d450"
             #uuid "00f6e37a-eed5-570c-a64a-f559645a5b85"
             #uuid "321c92b7-95ff-599b-a752-73729087d450"],
            #uuid "39a6932d-9412-5157-ba8b-8889d0c47220"
            [#uuid "2c53f42b-6073-57ba-a9ff-d2b6a74b1ab4"
             ],
            #uuid "216d27a3-b24b-5911-89e7-d2950cf80a94"
            [#uuid "1ea1c794-4fdd-53e3-b9ec-096d79595d8c"],
            #uuid "37f2b268-6598-50ad-abe7-29f73addb001"
            [#uuid "001d0cae-56c9-5837-8d03-834fa4d5c6bf"
             #uuid "06c8a332-c006-503c-9853-c2ebb653729e"
             #uuid "33b6ed58-5765-5804-bfe8-a2a0fef5e0b4"
             #uuid "001d0cae-56c9-5837-8d03-834fa4d5c6bf"
             #uuid "06c8a332-c006-503c-9853-c2ebb653729e"
             #uuid "33b6ed58-5765-5804-bfe8-a2a0fef5e0b4"],
            #uuid "2c53f42b-6073-57ba-a9ff-d2b6a74b1ab4"
            [#uuid "3f06b346-cb73-5fc8-af04-021b314d9b65"],
            #uuid "2fd7db13-9d5e-51cc-9bb3-dfcaf976d2e1"
            [#uuid "0dac91e1-1fbe-5c8a-99a8-52604c316ddc"
             #uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"
             #uuid "0dac91e1-1fbe-5c8a-99a8-52604c316ddc"
             #uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"],
            #uuid "165b6969-c3a6-510e-b8d3-cabee7b26fec"
            [#uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "21f13f78-5880-54eb-96ad-2d96df0e4813"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "3e866b49-0ae6-53a5-b3a4-2491d1b8f282"

             #uuid "34819a87-c9fa-51b1-8142-d4b8afb86007"
             #uuid "03cfa438-ade6-5fb1-8adf-be058b915f2c"
             #uuid "21f13f78-5880-54eb-96ad-2d96df0e4813"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "3e866b49-0ae6-53a5-b3a4-2491d1b8f282"
             #uuid "34819a87-c9fa-51b1-8142-d4b8afb86007"],
            #uuid "30069192-ed08-57ec-b646-eb6852a834e3"
            [#uuid "06c8a332-c006-503c-9853-c2ebb653729e"
             #uuid "2fd7db13-9d5e-51cc-9bb3-dfcaf976d2e1"
             #uuid "06c8a332-c006-503c-9853-c2ebb653729e"
             #uuid "2fd7db13-9d5e-51cc-9bb3-dfcaf976d2e1"],
            #uuid "112b8e9f-d029-542c-8919-a41b56365c9d"
            [#uuid "123dd307-9df4-5e39-ad2e-1438c1ab134d"
             #uuid "1da324e0-4e16-5e8e-ba40-35e5e3746266"
             #uuid "123dd307-9df4-5e39-ad2e-1438c1ab134d"
             #uuid "1da324e0-4e16-5e8e-ba40-35e5e3746266"],
            #uuid "3236cc78-a667-5d75-ba84-6f3c865eaa27"
            [#uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"
             #uuid "1c734d9f-4a2c-52f3-8e4f-4c4aa0904eb9"
             #uuid "316d3c09-81bf-5067-ba42-1648b2274663"
             #uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"

             #uuid "1c734d9f-4a2c-52f3-8e4f-4c4aa0904eb9"
             #uuid "316d3c09-81bf-5067-ba42-1648b2274663"],
            #uuid "1ea1c794-4fdd-53e3-b9ec-096d79595d8c"
            [#uuid "169ecefc-a5db-5058-a5f4-bc34c719c749"],
            #uuid "1ef523df-540e-540c-a481-cea0e5772d85"
            [#uuid "00f6e37a-eed5-570c-a64a-f559645a5b85"
             #uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"
             #uuid "0f21f221-0308-55a9-bc2f-3bc81b12015f"
             #uuid "00f6e37a-eed5-570c-a64a-f559645a5b85"
             #uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"
             #uuid "0f21f221-0308-55a9-bc2f-3bc81b12015f"],
            #uuid "21f13f78-5880-54eb-96ad-2d96df0e4813"
            [#uuid "3d08f789-a6c4-54a3-8400-bf440c51e0a4"
             #uuid "327f8a5e-1cab-5919-a5d0-6c2e1f1cbca1"
             #uuid "3d08f789-a6c4-54a3-8400-bf440c51e0a4"
             #uuid "327f8a5e-1cab-5919-a5d0-6c2e1f1cbca1"],
            #uuid "2a95a5a9-617e-56c2-a893-fca9e1def5d4"
            [#uuid "22cf25df-0b95-5e86-abc8-5abec5b4af91"
             #uuid "29923963-c513-5390-b645-d9531373857c"
             #uuid "22cf25df-0b95-5e86-abc8-5abec5b4af91"
             #uuid "29923963-c513-5390-b645-d9531373857c"
             ],
            #uuid "22cf25df-0b95-5e86-abc8-5abec5b4af91"
            [#uuid "05293360-95b4-5481-a594-b9de4979308f"
             #uuid "321c92b7-95ff-599b-a752-73729087d450"
             #uuid "05293360-95b4-5481-a594-b9de4979308f"
             #uuid "321c92b7-95ff-599b-a752-73729087d450"],
            #uuid "0dac91e1-1fbe-5c8a-99a8-52604c316ddc"
            [#uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "0faa8ca5-b018-57cb-8577-73bb5519738e"
             #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
             #uuid "0faa8ca5-b018-57cb-8577-73bb5519738e"],
            #uuid "1401ff5d-3be1-568e-ae97-ad765d6eb652"
            [#uuid "216d27a3-b24b-5911-89e7-d2950cf80a94"],
            #uuid "39b8b82b-3dea-5223-90e8-6f2ca6fbafdc"
            [#uuid "03889ec4-5060-5c4a-a955-b7bececc09dc"
             #uuid "0cd0077a-4445-5b00-bc48-bff3225e5a0f"
             #uuid "03889ec4-5060-5c4a-a955-b7bececc09dc"
             #uuid "0cd0077a-4445-5b00-bc48-bff3225e5a0f"],
            #uuid "27acd9f9-d732-5055-bdee-41a18c4b3517"
            [#uuid "3752cf7d-7005-5480-a651-faf0a8beb6ed"
             #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"

             #uuid "3752cf7d-7005-5480-a651-faf0a8beb6ed"
             #uuid "20b441df-3144-5fd4-afd3-783e8dd721fb"],
            #uuid "0d118a8d-d142-538d-986b-83d09d3123ea"
            [#uuid "3236cc78-a667-5d75-ba84-6f3c865eaa27"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             #uuid "30069192-ed08-57ec-b646-eb6852a834e3"
             #uuid "3236cc78-a667-5d75-ba84-6f3c865eaa27"
             #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
             #uuid "30069192-ed08-57ec-b646-eb6852a834e3"],
            #uuid "1d1d39dd-b594-5a79-8313-0148d7104d76"
            [#uuid "1178bfe7-9089-572f-97dc-1de79818de4e"
             #uuid "06c8a332-c006-503c-9853-c2ebb653729e"
             #uuid "1178bfe7-9089-572f-97dc-1de79818de4e"
             #uuid "06c8a332-c006-503c-9853-c2ebb653729e"],
            #uuid "2afbbe4d-7031-5663-97df-fdb019011e24"
            [#uuid "0faa8ca5-b018-57cb-8577-73bb5519738e"
             #uuid "22799c09-1bd7-5ce7-8e78-e493cefe17bd"
             #uuid "0faa8ca5-b018-57cb-8577-73bb5519738e"
             #uuid "22799c09-1bd7-5ce7-8e78-e493cefe17bd"],
            #uuid "2ec61402-8576-5d3f-bc29-f0f12965ce16"
            [#uuid "2ee40183-fb17-5ce9-a136-e6c9737390fc"

             #uuid "27acd9f9-d732-5055-bdee-41a18c4b3517"
             #uuid "2ee40183-fb17-5ce9-a136-e6c9737390fc"
             #uuid "27acd9f9-d732-5055-bdee-41a18c4b3517"],
            #uuid "0faa8ca5-b018-57cb-8577-73bb5519738e"
            [#uuid "39b8b82b-3dea-5223-90e8-6f2ca6fbafdc"
             #uuid "0008c24d-da09-50b5-ad12-b647471fd3b6"
             #uuid "39b8b82b-3dea-5223-90e8-6f2ca6fbafdc"
             #uuid "0008c24d-da09-50b5-ad12-b647471fd3b6"],
            #uuid "06c8a332-c006-503c-9853-c2ebb653729e"
            [#uuid "0dd3cba5-2dac-53a9-b260-e2329912a196"
             #uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"
             #uuid "0dd3cba5-2dac-53a9-b260-e2329912a196"
             #uuid "0c2e89b6-2a4e-5249-af44-7914ba193ccf"],
            #uuid "29923963-c513-5390-b645-d9531373857c"
            [#uuid "21f13f78-5880-54eb-96ad-2d96df0e4813"
             #uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"
             #uuid "21f13f78-5880-54eb-96ad-2d96df0e4813"
             #uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"],
            #uuid "3e397695-7321-57b3-8bcf-5e163977454d"
            [#uuid "1a763604-2c74-502f-8540-e7c590dfb39c"
             #uuid "2208c288-7a73-5011-a76d-7f36d9a2d9e5"

             #uuid "1a763604-2c74-502f-8540-e7c590dfb39c"
             #uuid "2208c288-7a73-5011-a76d-7f36d9a2d9e5"],
            #uuid "2ed4a2f6-142d-555f-bfce-213f489f4269"
            [#uuid "0de99c6e-0a10-5faf-b1dd-99b6a04ab5fc"
             #uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"
             #uuid "00964867-9755-5302-83f5-d23b0ea07892"
             #uuid "0de99c6e-0a10-5faf-b1dd-99b6a04ab5fc"
             #uuid "1cb86ccf-3374-5f44-b376-a7e64f514b21"
             #uuid "00964867-9755-5302-83f5-d23b0ea07892"],
            #uuid "0f21f221-0308-55a9-bc2f-3bc81b12015f"
            [#uuid "165b6969-c3a6-510e-b8d3-cabee7b26fec"
             #uuid "1da324e0-4e16-5e8e-ba40-35e5e3746266"
             #uuid "165b6969-c3a6-510e-b8d3-cabee7b26fec"
             #uuid "1da324e0-4e16-5e8e-ba40-35e5e3746266"],
            #uuid "1c734d9f-4a2c-52f3-8e4f-4c4aa0904eb9"
            [#uuid "321c92b7-95ff-599b-a752-73729087d450"
             #uuid "2208c288-7a73-5011-a76d-7f36d9a2d9e5"
             #uuid "321c92b7-95ff-599b-a752-73729087d450"
             #uuid "2208c288-7a73-5011-a76d-7f36d9a2d9e5"],
            #uuid "39b417ec-c0f6-56e9-8acd-6ade6ad6f224"
            [#uuid "22799c09-1bd7-5ce7-8e78-e493cefe17bd"

             #uuid "0008c24d-da09-50b5-ad12-b647471fd3b6"
             #uuid "22799c09-1bd7-5ce7-8e78-e493cefe17bd"
             #uuid "0008c24d-da09-50b5-ad12-b647471fd3b6"],
            #uuid "3e866b49-0ae6-53a5-b3a4-2491d1b8f282"
            [#uuid "28c8e600-aaea-5ff7-ad07-2e79a5e36702"
             #uuid "0de99c6e-0a10-5faf-b1dd-99b6a04ab5fc"
             #uuid "28c8e600-aaea-5ff7-ad07-2e79a5e36702"
             #uuid "0de99c6e-0a10-5faf-b1dd-99b6a04ab5fc"],
            #uuid "2ee40183-fb17-5ce9-a136-e6c9737390fc"
            [#uuid "1da324e0-4e16-5e8e-ba40-35e5e3746266"
             #uuid "3d08f789-a6c4-54a3-8400-bf440c51e0a4"
             #uuid "1da324e0-4e16-5e8e-ba40-35e5e3746266"
             #uuid "3d08f789-a6c4-54a3-8400-bf440c51e0a4"]},

           :last-update #inst "2014-04-12T16:02:06.874-00:00",
           :head "master",
           :public true,
           :branches
           {"master"
            #{#uuid "041a080d-6c39-58e8-b602-89b6fb902b17"
              #uuid "36d2002e-1e52-5e81-829f-988e7cac3947"
              #uuid "3586fbdf-ec95-5966-81fd-edbfe659fa2c"
              #uuid "123dd307-9df4-5e39-ad2e-1438c1ab134d"
              #uuid "1ef523df-540e-540c-a481-cea0e5772d85"}},

           :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
           :pull-requests {},
           :id #uuid "84473475-2c87-470e-8774-9de66e665812",
           :description "A bookmark app."}
          {:causal-order
           {#uuid "169ecefc-a5db-5058-a5f4-bc34c719c749" []}
           :last-update #inst "2014-04-12T16:02:06.874-00:00",
           :head "master",
           :public true,
           :branches
           {"master"
            #{#uuid "169ecefc-a5db-5058-a5f4-bc34c719c749"}},

           :schema {:version 1, :type "http://github.com/ghubber/geschichte"},
           :pull-requests {},
           :id #uuid "84473475-2c87-470e-8774-9de66e665812",
           :description "A bookmark app."}))
