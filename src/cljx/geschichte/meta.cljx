(ns ^:shared geschichte.meta
  "Operation on metadata and causal-order (directed acyclic graph) of a repository.

   Metadata repository-format for automatic server-side
   synching (p2p-web). Have a look at the midje-doc documentation for
   more information."
  (:require [clojure.set :as set]))


(defn track-returnpaths [returnpaths heads meta]
  (reduce (fn [returnpaths head]
            (reduce (fn [returnpaths parent] (update-in returnpaths [parent] #(conj (or %1 #{}) %2) head))
                    returnpaths
                    (meta head)))
          returnpaths
          heads))


(defn init-returnpath [heads]
  (reduce #(assoc %1 %2 #{}) {} heads))


(defn lowest-common-ancestors
  "Naive online BFS implementation. Assumes no cycles exist."
  ([meta-a heads-a meta-b heads-b]
     (let [returnpaths-a (init-returnpath heads-a)
           returnpaths-b (init-returnpath heads-b)
           cut (set/intersection heads-a heads-b)]
       ; cover initial cut, TODO move case in actual function
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


(defn isolate-branch
  "Isolate a branch's metadata causal-order."
  ([meta branch]
     (isolate-branch (:causal-order meta) (-> meta :branches (get branch) :heads) {}))
  ([causal-order cut branch-meta]
     (if (empty? cut) branch-meta
         (recur causal-order
                (set (mapcat causal-order cut))
                (merge branch-meta (select-keys causal-order cut))))))




(defn old-heads [causal heads]
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




(defn update [{:keys [id description schema public causal-order branches
                      head last-update pull-requests] :as meta} other-meta]
  (let [newer (> (.getTime (:last-update other-meta)) (.getTime last-update))
        new-causal (merge (:causal-order other-meta) causal-order)
        new-meta {:last-update (if newer (:last-update other-meta) last-update)
                  :id id
                  :description description
                  :schema {:type (:type schema)
                           :version (max (:version schema) (or (:version (:schema other-meta))
                                                               (:version schema)))}
                  :head (if newer (or (:head other-meta) head) head)
                  :branches (merge-with (fn [{heads-a :heads indexes-a :indexes}
                                            {heads-b :heads indexes-b :indexes}]
                                          (let [ind {:indexes (merge-with
                                                               #(if (set/superset? (set %1) (set %2))
                                                                  %1 %2) indexes-b indexes-a)}]
                                            (if new-causal
                                              (assoc (if-not (empty? (:indexes ind)) ind {})
                                                :heads (remove-ancestors new-causal
                                                                         (or heads-a #{})
                                                                         (or heads-b #{})))
                                              ind)))
                                        branches (:branches other-meta))
                  :public (or public (:public other-meta) false)
                  :pull-requests (merge-with merge {} (:pull-requests other-meta) pull-requests)}]
    (if new-causal
      (assoc new-meta :causal-order new-causal)
      new-meta)))

(comment
  (require '[clojure.tools.trace :refer [dotrace]])
  (def problem-meta
    {:causal-order {#uuid "2c24a78d-e400-5368-b610-ab8b06115fce"
                    [#uuid "059630dd-7a03-5ea6-9ca5-348620c8e29f" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218" #uuid "059630dd-7a03-5ea6-9ca5-348620c8e29f" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218"],
                    #uuid "3931512d-9956-55bf-9a32-2a052f26a177"
                    [#uuid "1ccd2e84-60fb-587d-9593-236a49240c0c"],
                    #uuid "2a86f198-7dfe-51c7-b6b8-bf90cddd83ee"
                    [#uuid "0b577171-086e-53f6-9262-7e851ee8fd7e"],
                    #uuid "21673d4d-338b-5d8a-bf8f-551e8fc67518"
                    [#uuid "2e48f297-de26-52e3-9a8a-5e423ef1a492" #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34" #uuid "2e48f297-de26-52e3-9a8a-5e423ef1a492" #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34"],
                    #uuid "11387177-601c-5ded-98b5-99691cc5b512"
                    [#uuid "1d5eadfb-050f-5674-850e-eeee9c9e88fa" #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34" #uuid "1d5eadfb-050f-5674-850e-eeee9c9e88fa" #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34"],
                    #uuid "35299639-cd40-5c9a-936e-9965ffc991c7"
                    [#uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a" #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a"],
                    #uuid "045e8240-6df3-531a-b390-c9238dc6cc7b"
                    [#uuid "3b12846b-48a3-5169-b369-61ac96b9425e"],
                    #uuid "059630dd-7a03-5ea6-9ca5-348620c8e29f"
                    [#uuid "3d5e0ad3-8f90-592a-ade8-b2a8352511a6" #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f" #uuid "3d5e0ad3-8f90-592a-ade8-b2a8352511a6" #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f"],
                    #uuid "0ae0e29e-ad26-5b2a-abc1-92d0cd3eb7c6"
                    [#uuid "38064367-fa19-50e9-998d-a81b358ca086"],
                    #uuid "04e824f2-d7f6-5399-89f1-8ff6438c0d3f"
                    [#uuid "3bdf5912-0b1f-5b75-87d5-fe4e2a98a81b"],
                    #uuid "27ddb3dd-b768-5c49-878a-09979c4e9d61"
                    [#uuid "02f0b98b-cc8d-5f33-98f9-3875666bb445"],
                    #uuid "03721b99-e947-5789-98e2-0e6302941df1"
                    [#uuid "02f6a2bb-cfd3-55a6-b00a-799dd395bd42"],
                    #uuid "02f6a2bb-cfd3-55a6-b00a-799dd395bd42"
                    [#uuid "280ae09e-999d-5265-95d9-8537fb4a60c9" #uuid "29d3bd89-4410-5ce5-9648-e493e8f38e39" #uuid "280ae09e-999d-5265-95d9-8537fb4a60c9" #uuid "29d3bd89-4410-5ce5-9648-e493e8f38e39"],
                    #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2"
                    [#uuid "23f3a206-5035-5d07-9b35-a083afe9ec34" #uuid "0f800e2d-b036-520a-8657-eccfaaacc9ef" #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34" #uuid "0f800e2d-b036-520a-8657-eccfaaacc9ef"],
                    #uuid "2e48f297-de26-52e3-9a8a-5e423ef1a492"
                    [#uuid "01677820-90f5-5e0b-acbc-100932a22f05" #uuid "1d5eadfb-050f-5674-850e-eeee9c9e88fa" #uuid "01677820-90f5-5e0b-acbc-100932a22f05" #uuid "1d5eadfb-050f-5674-850e-eeee9c9e88fa"],
                    #uuid "1510e9fd-aacd-55c7-941d-4d9130e0214e"
                    [#uuid "2127466a-503f-5a30-97f0-d094111b73cb" #uuid "33547f1b-2480-508d-be34-63da3e1a6704" #uuid "2127466a-503f-5a30-97f0-d094111b73cb" #uuid "33547f1b-2480-508d-be34-63da3e1a6704"],
                    #uuid "2127466a-503f-5a30-97f0-d094111b73cb"
                    [#uuid "25f58a38-aa9d-5199-997b-b1c045af7059" #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3" #uuid "25f58a38-aa9d-5199-997b-b1c045af7059" #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3"],
                    #uuid "280ae09e-999d-5265-95d9-8537fb4a60c9" [#uuid "3931512d-9956-55bf-9a32-2a052f26a177"],
                    #uuid "0b01f05c-b032-564c-ae61-662fbda0f419" [#uuid "090d9643-a944-5572-88f3-0eb7e3b1a314"],
                    #uuid "29d3bd89-4410-5ce5-9648-e493e8f38e39" [#uuid "3931512d-9956-55bf-9a32-2a052f26a177"],
                    #uuid "0fe1df74-facf-5759-8725-a7109b199b9a" [#uuid "2d9a21e3-6044-57da-87f7-b2826b17ba16"],
                    #uuid "0f800e2d-b036-520a-8657-eccfaaacc9ef" [#uuid "0b577171-086e-53f6-9262-7e851ee8fd7e"],
                    #uuid "01677820-90f5-5e0b-acbc-100932a22f05"
                    [#uuid "163ba81e-eb0e-5903-a244-da11d2230020" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c" #uuid "163ba81e-eb0e-5903-a244-da11d2230020" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c"],
                    #uuid "1e424294-bb25-5901-9782-8cf39b81bf4e"
                    [#uuid "19dc948d-f78d-5e15-b389-4909ee20e3c1" #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f" #uuid "19dc948d-f78d-5e15-b389-4909ee20e3c1" #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f"],
                    #uuid "27c6f1f5-f4de-5656-985e-1015baf59dbe" [#uuid "03a411e2-e097-516e-8c57-bba3b5b18851"],
                    #uuid "02f0b98b-cc8d-5f33-98f9-3875666bb445" [#uuid "18aa9745-a39c-5e90-8ead-7cedb716c3eb"],
                    #uuid "33547f1b-2480-508d-be34-63da3e1a6704"
                    [#uuid "2aa5bf30-49c7-5157-81e8-c3158fe7410f" #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3" #uuid "2aa5bf30-49c7-5157-81e8-c3158fe7410f" #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3"],
                    #uuid "3415ac38-98c7-5348-ba8d-427ec4461de7" [#uuid "14c9da43-a2cb-5112-8d21-d777da2189f4"],
                    #uuid "071d505d-b198-5ed2-9417-9901737e4167" [#uuid "21b919d6-0e42-5eca-b42e-89cafe1764eb"],
                    #uuid "3c65cb46-a053-56c8-a4b2-54c13e0389e5"
                    [#uuid "20ebcafe-8544-5ed5-a58f-e8c5815b6fb9" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7" #uuid "20ebcafe-8544-5ed5-a58f-e8c5815b6fb9" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7"],
                    #uuid "13534528-4d77-559e-98b7-ecfa442f5d86"
                    [#uuid "07aa256c-bf28-58ff-84de-0c7a0ba98557" #uuid "1ff4c5fc-7f82-5f6b-8db1-9811650cb6bd" #uuid "07aa256c-bf28-58ff-84de-0c7a0ba98557" #uuid "1ff4c5fc-7f82-5f6b-8db1-9811650cb6bd"],
                    #uuid "1e498232-d77c-534e-988a-b766ede1e236"
                    [#uuid "267044b2-7efa-50e0-bab6-f667ce5e5058" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7" #uuid "267044b2-7efa-50e0-bab6-f667ce5e5058" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7"],
                    #uuid "2941cf92-3650-54a2-bb63-07186dc61844" [#uuid "0b01f05c-b032-564c-ae61-662fbda0f419"],
                    #uuid "3bc4c33a-6df4-54cf-a12b-38c8a950d571"
                    [#uuid "1a1261d9-64ac-5ee8-b91d-c6d6f96c19bc" #uuid "01677820-90f5-5e0b-acbc-100932a22f05" #uuid "1a1261d9-64ac-5ee8-b91d-c6d6f96c19bc" #uuid "01677820-90f5-5e0b-acbc-100932a22f05"],
                    #uuid "163ba81e-eb0e-5903-a244-da11d2230020"
                    [#uuid "2c24a78d-e400-5368-b610-ab8b06115fce" #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5" #uuid "2c24a78d-e400-5368-b610-ab8b06115fce" #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5"],
                    #uuid "267044b2-7efa-50e0-bab6-f667ce5e5058"
                    [#uuid "33547f1b-2480-508d-be34-63da3e1a6704" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a" #uuid "33547f1b-2480-508d-be34-63da3e1a6704" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a"],
                    #uuid "2835a623-4e30-5668-860b-3f001783d5c6" [#uuid "2a86f198-7dfe-51c7-b6b8-bf90cddd83ee"],
                    #uuid "3e61855b-5b71-5956-88da-9cb86cc06218" [#uuid "2941cf92-3650-54a2-bb63-07186dc61844"],
                    #uuid "2d9a21e3-6044-57da-87f7-b2826b17ba16" [#uuid "2835a623-4e30-5668-860b-3f001783d5c6"],
                    #uuid "013d990f-8a4b-560b-b396-2ac970834ac0"
                    [#uuid "142d78d3-e2b7-5926-8b53-c26919ab2466" #uuid "163ba81e-eb0e-5903-a244-da11d2230020" #uuid "142d78d3-e2b7-5926-8b53-c26919ab2466" #uuid "163ba81e-eb0e-5903-a244-da11d2230020"],
                    #uuid "3b60573a-9a49-52a6-9176-6bc7bc1246f5" [#uuid "0ae0e29e-ad26-5b2a-abc1-92d0cd3eb7c6"],
                    #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5"
                    [#uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218" #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218"],
                    #uuid "2adc74af-0537-5ba6-ab82-d50f519e7869" [#uuid "0a7b6be1-6aa5-519e-8001-1a5c1b92c717"],
                    #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3"
                    [#uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2" #uuid "2fdbb1a8-dc89-59aa-8466-e93ccdf9dae0" #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2" #uuid "2fdbb1a8-dc89-59aa-8466-e93ccdf9dae0"],
                    #uuid "19dc948d-f78d-5e15-b389-4909ee20e3c1"
                    [#uuid "21d61fa0-7343-5103-ba28-df2c74d8521f" #uuid "3d5e0ad3-8f90-592a-ade8-b2a8352511a6" #uuid "21d61fa0-7343-5103-ba28-df2c74d8521f" #uuid "3d5e0ad3-8f90-592a-ade8-b2a8352511a6"],
                    #uuid "3b12846b-48a3-5169-b369-61ac96b9425e" [#uuid "03ee12e5-52c2-5f92-bf10-eb9744c5f8d4"],
                    #uuid "21d61fa0-7343-5103-ba28-df2c74d8521f"
                    [#uuid "08238078-0dbf-5391-aa50-d7f5435d96e7" #uuid "2941cf92-3650-54a2-bb63-07186dc61844" #uuid "08238078-0dbf-5391-aa50-d7f5435d96e7" #uuid "2941cf92-3650-54a2-bb63-07186dc61844"],
                    #uuid "3bdf5912-0b1f-5b75-87d5-fe4e2a98a81b" [#uuid "3fb9529d-6142-5bd5-a915-af91d6748f24"],
                    #uuid "14c9da43-a2cb-5112-8d21-d777da2189f4" [#uuid "2adc74af-0537-5ba6-ab82-d50f519e7869"],
                    #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314" [#uuid "0fe1df74-facf-5759-8725-a7109b199b9a"],
                    #uuid "18aa9745-a39c-5e90-8ead-7cedb716c3eb" [#uuid "27c6f1f5-f4de-5656-985e-1015baf59dbe"],
                    #uuid "38064367-fa19-50e9-998d-a81b358ca086" [#uuid "22cc5272-aa31-595c-b6c9-aa03a37b1bd4"],
                    #uuid "03ee12e5-52c2-5f92-bf10-eb9744c5f8d4" [#uuid "3890cfed-b083-52a7-960d-6cd02d74a26f"],
                    #uuid "0a7b6be1-6aa5-519e-8001-1a5c1b92c717" [#uuid "144f2ee9-010d-5c7d-9a72-03d893900dfb"],
                    #uuid "16ae7995-f3b7-529b-a8a7-b0a7bc51c8fd"
                    [#uuid "13534528-4d77-559e-98b7-ecfa442f5d86" #uuid "067d5fe8-0089-5813-95db-14e0c2035424" #uuid "13534528-4d77-559e-98b7-ecfa442f5d86" #uuid "067d5fe8-0089-5813-95db-14e0c2035424"],
                    #uuid "261b4031-5bb9-53ba-b1cf-a87d45bcaa83"
                    [#uuid "1510e9fd-aacd-55c7-941d-4d9130e0214e" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a" #uuid "1510e9fd-aacd-55c7-941d-4d9130e0214e" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a"],
                    #uuid "2dea9164-4057-542e-80ea-4159eab94b26"
                    [#uuid "21673d4d-338b-5d8a-bf8f-551e8fc67518" #uuid "11387177-601c-5ded-98b5-99691cc5b512" #uuid "21673d4d-338b-5d8a-bf8f-551e8fc67518" #uuid "11387177-601c-5ded-98b5-99691cc5b512"],
                    #uuid "3890cfed-b083-52a7-960d-6cd02d74a26f" [#uuid "169ecefc-a5db-5058-a5f4-bc34c719c749"],
                    #uuid "2307205d-51d2-59b3-a90a-5867b950d9ff" [#uuid "314edcd9-4db8-5cbb-8247-ad96c495fac9"],
                    #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34" [#uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c"],
                    #uuid "144f2ee9-010d-5c7d-9a72-03d893900dfb" [#uuid "03721b99-e947-5789-98e2-0e6302941df1"],
                    #uuid "3d5e0ad3-8f90-592a-ade8-b2a8352511a6"
                    [#uuid "3c76478b-f003-5cf9-ad19-6136ee099759" #uuid "2941cf92-3650-54a2-bb63-07186dc61844" #uuid "3c76478b-f003-5cf9-ad19-6136ee099759" #uuid "2941cf92-3650-54a2-bb63-07186dc61844"],
                    #uuid "0946b409-1e9d-5367-b3ea-9606af6bf7df"
                    [#uuid "2dea9164-4057-542e-80ea-4159eab94b26" #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2" #uuid "2dea9164-4057-542e-80ea-4159eab94b26" #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2"],
                    #uuid "20ebcafe-8544-5ed5-a58f-e8c5815b6fb9"
                    [#uuid "261b4031-5bb9-53ba-b1cf-a87d45bcaa83" #uuid "267044b2-7efa-50e0-bab6-f667ce5e5058" #uuid "261b4031-5bb9-53ba-b1cf-a87d45bcaa83" #uuid "267044b2-7efa-50e0-bab6-f667ce5e5058"],
                    #uuid "1af5f37a-1170-526e-b6aa-73406718c72c"
                    [#uuid "1e424294-bb25-5901-9782-8cf39b81bf4e" #uuid "059630dd-7a03-5ea6-9ca5-348620c8e29f" #uuid "1e424294-bb25-5901-9782-8cf39b81bf4e" #uuid "059630dd-7a03-5ea6-9ca5-348620c8e29f"],
                    #uuid "25f58a38-aa9d-5199-997b-b1c045af7059"
                    [#uuid "0946b409-1e9d-5367-b3ea-9606af6bf7df" #uuid "2aa5bf30-49c7-5157-81e8-c3158fe7410f" #uuid "0946b409-1e9d-5367-b3ea-9606af6bf7df" #uuid "2aa5bf30-49c7-5157-81e8-c3158fe7410f"],
                    #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c" [#uuid "3e61855b-5b71-5956-88da-9cb86cc06218"],
                    #uuid "2fc13656-c13e-5388-87fb-d59b54b4d4fc"
                    [#uuid "3c65cb46-a053-56c8-a4b2-54c13e0389e5" #uuid "1e498232-d77c-534e-988a-b766ede1e236" #uuid "3c65cb46-a053-56c8-a4b2-54c13e0389e5" #uuid "1e498232-d77c-534e-988a-b766ede1e236"],
                    #uuid "169ecefc-a5db-5058-a5f4-bc34c719c749" [],
                    #uuid "22cc5272-aa31-595c-b6c9-aa03a37b1bd4" [#uuid "1363f3ff-a092-5936-a7f3-edea64b960b9"],
                    #uuid "1363f3ff-a092-5936-a7f3-edea64b960b9" [#uuid "27ddb3dd-b768-5c49-878a-09979c4e9d61"],
                    #uuid "142d78d3-e2b7-5926-8b53-c26919ab2466"
                    [#uuid "02ee492f-32cb-5c74-abff-028fa8a4f129" #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5" #uuid "02ee492f-32cb-5c74-abff-028fa8a4f129" #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5"],
                    #uuid "1ff4c5fc-7f82-5f6b-8db1-9811650cb6bd"
                    [#uuid "1e498232-d77c-534e-988a-b766ede1e236" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314" #uuid "1e498232-d77c-534e-988a-b766ede1e236" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314"],
                    #uuid "1a1261d9-64ac-5ee8-b91d-c6d6f96c19bc"
                    [#uuid "013d990f-8a4b-560b-b396-2ac970834ac0" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c" #uuid "013d990f-8a4b-560b-b396-2ac970834ac0" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c"],
                    #uuid "1d5eadfb-050f-5674-850e-eeee9c9e88fa"
                    [#uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c" #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c"],
                    #uuid "08238078-0dbf-5391-aa50-d7f5435d96e7"
                    [#uuid "16ae7995-f3b7-529b-a8a7-b0a7bc51c8fd" #uuid "3c76478b-f003-5cf9-ad19-6136ee099759" #uuid "16ae7995-f3b7-529b-a8a7-b0a7bc51c8fd" #uuid "3c76478b-f003-5cf9-ad19-6136ee099759"],
                    #uuid "0b577171-086e-53f6-9262-7e851ee8fd7e"
                    [#uuid "3b60573a-9a49-52a6-9176-6bc7bc1246f5"],
                    #uuid "2aa5bf30-49c7-5157-81e8-c3158fe7410f"
                    [#uuid "11387177-601c-5ded-98b5-99691cc5b512" #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2" #uuid "11387177-601c-5ded-98b5-99691cc5b512" #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2"],
                    #uuid "3c76478b-f003-5cf9-ad19-6136ee099759"
                    [#uuid "1ff4c5fc-7f82-5f6b-8db1-9811650cb6bd" #uuid "067d5fe8-0089-5813-95db-14e0c2035424" #uuid "1ff4c5fc-7f82-5f6b-8db1-9811650cb6bd" #uuid "067d5fe8-0089-5813-95db-14e0c2035424"],
                    #uuid "3fb9529d-6142-5bd5-a915-af91d6748f24" [#uuid "045e8240-6df3-531a-b390-c9238dc6cc7b"],
                    #uuid "21b919d6-0e42-5eca-b42e-89cafe1764eb" [#uuid "2fdbb1a8-dc89-59aa-8466-e93ccdf9dae0"],
                    #uuid "02ee492f-32cb-5c74-abff-028fa8a4f129"
                    [#uuid "2f3c2b3b-1377-54af-b7ab-ab511223e8fb" #uuid "2c24a78d-e400-5368-b610-ab8b06115fce" #uuid "2f3c2b3b-1377-54af-b7ab-ab511223e8fb" #uuid "2c24a78d-e400-5368-b610-ab8b06115fce"],
                    #uuid "314edcd9-4db8-5cbb-8247-ad96c495fac9" [#uuid "3415ac38-98c7-5348-ba8d-427ec4461de7"],
                    #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f"
                    [#uuid "067d5fe8-0089-5813-95db-14e0c2035424" #uuid "2941cf92-3650-54a2-bb63-07186dc61844" #uuid "067d5fe8-0089-5813-95db-14e0c2035424" #uuid "2941cf92-3650-54a2-bb63-07186dc61844"],
                    #uuid "2f3c2b3b-1377-54af-b7ab-ab511223e8fb"
                    [#uuid "1af5f37a-1170-526e-b6aa-73406718c72c" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218" #uuid "1af5f37a-1170-526e-b6aa-73406718c72c" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218"],
                    #uuid "07aa256c-bf28-58ff-84de-0c7a0ba98557"
                    [#uuid "2fc13656-c13e-5388-87fb-d59b54b4d4fc" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314" #uuid "2fc13656-c13e-5388-87fb-d59b54b4d4fc" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314"],
                    #uuid "03a411e2-e097-516e-8c57-bba3b5b18851" [#uuid "344cadf4-366d-5115-8cdd-be8b854b48b5"],
                    #uuid "2fdbb1a8-dc89-59aa-8466-e93ccdf9dae0" [#uuid "0f800e2d-b036-520a-8657-eccfaaacc9ef"],
                    #uuid "1ccd2e84-60fb-587d-9593-236a49240c0c" [#uuid "04e824f2-d7f6-5399-89f1-8ff6438c0d3f"],
                    #uuid "344cadf4-366d-5115-8cdd-be8b854b48b5" [#uuid "2307205d-51d2-59b3-a90a-5867b950d9ff"],
                    #uuid "067d5fe8-0089-5813-95db-14e0c2035424"
                    [#uuid "071d505d-b198-5ed2-9417-9901737e4167" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7" #uuid "071d505d-b198-5ed2-9417-9901737e4167" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7"]},
     :last-update #inst "2014-04-09T22:23:23.179-00:00",
     :id #uuid "84473475-2c87-470e-8774-9de66e665812",
     :description "A bookmark app.",
     :schema {:type "http://github.com/ghubber/geschichte", :version 1},
     :head "master",
     :branches {"master" {:heads #{#uuid "1510e9fd-aacd-55c7-941d-4d9130e0214e" #uuid "3bc4c33a-6df4-54cf-a12b-38c8a950d571"}}},
     :public true,
     :pull-requests {}})
  #_(dotrace [lowest-common-ancestors] (update problem-meta problem-meta))
  #_(= (update problem-meta {:causal-order {#uuid "2c24a78d-e400-5368-b610-ab8b06115fce"
                                            [#uuid "059630dd-7a03-5ea6-9ca5-348620c8e29f" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218" #uuid "059630dd-7a03-5ea6-9ca5-348620c8e29f" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218"],
                                            #uuid "3931512d-9956-55bf-9a32-2a052f26a177"
                                            [#uuid "1ccd2e84-60fb-587d-9593-236a49240c0c"],
                                            #uuid "2a86f198-7dfe-51c7-b6b8-bf90cddd83ee"
                                            [#uuid "0b577171-086e-53f6-9262-7e851ee8fd7e"],
                                            #uuid "21673d4d-338b-5d8a-bf8f-551e8fc67518"
                                            [#uuid "2e48f297-de26-52e3-9a8a-5e423ef1a492" #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34" #uuid "2e48f297-de26-52e3-9a8a-5e423ef1a492" #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34"],
                                            #uuid "11387177-601c-5ded-98b5-99691cc5b512"
                                            [#uuid "1d5eadfb-050f-5674-850e-eeee9c9e88fa" #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34" #uuid "1d5eadfb-050f-5674-850e-eeee9c9e88fa" #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34"],
                                            #uuid "35299639-cd40-5c9a-936e-9965ffc991c7"
                                            [#uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a" #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a"],
                                            #uuid "045e8240-6df3-531a-b390-c9238dc6cc7b"
                                            [#uuid "3b12846b-48a3-5169-b369-61ac96b9425e"],
                                            #uuid "059630dd-7a03-5ea6-9ca5-348620c8e29f"
                                            [#uuid "3d5e0ad3-8f90-592a-ade8-b2a8352511a6" #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f" #uuid "3d5e0ad3-8f90-592a-ade8-b2a8352511a6" #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f"],
                                            #uuid "0ae0e29e-ad26-5b2a-abc1-92d0cd3eb7c6"
                                            [#uuid "38064367-fa19-50e9-998d-a81b358ca086"],
                                            #uuid "04e824f2-d7f6-5399-89f1-8ff6438c0d3f"
                                            [#uuid "3bdf5912-0b1f-5b75-87d5-fe4e2a98a81b"],
                                            #uuid "27ddb3dd-b768-5c49-878a-09979c4e9d61"
                                            [#uuid "02f0b98b-cc8d-5f33-98f9-3875666bb445"],
                                            #uuid "03721b99-e947-5789-98e2-0e6302941df1"
                                            [#uuid "02f6a2bb-cfd3-55a6-b00a-799dd395bd42"],
                                            #uuid "02f6a2bb-cfd3-55a6-b00a-799dd395bd42"
                                            [#uuid "280ae09e-999d-5265-95d9-8537fb4a60c9" #uuid "29d3bd89-4410-5ce5-9648-e493e8f38e39" #uuid "280ae09e-999d-5265-95d9-8537fb4a60c9" #uuid "29d3bd89-4410-5ce5-9648-e493e8f38e39"],
                                            #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2"
                                            [#uuid "23f3a206-5035-5d07-9b35-a083afe9ec34" #uuid "0f800e2d-b036-520a-8657-eccfaaacc9ef" #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34" #uuid "0f800e2d-b036-520a-8657-eccfaaacc9ef"],
                                            #uuid "2e48f297-de26-52e3-9a8a-5e423ef1a492"
                                            [#uuid "01677820-90f5-5e0b-acbc-100932a22f05" #uuid "1d5eadfb-050f-5674-850e-eeee9c9e88fa" #uuid "01677820-90f5-5e0b-acbc-100932a22f05" #uuid "1d5eadfb-050f-5674-850e-eeee9c9e88fa"],
                                            #uuid "1510e9fd-aacd-55c7-941d-4d9130e0214e"
                                            [#uuid "2127466a-503f-5a30-97f0-d094111b73cb" #uuid "33547f1b-2480-508d-be34-63da3e1a6704" #uuid "2127466a-503f-5a30-97f0-d094111b73cb" #uuid "33547f1b-2480-508d-be34-63da3e1a6704"],
                                            #uuid "2127466a-503f-5a30-97f0-d094111b73cb"
                                            [#uuid "25f58a38-aa9d-5199-997b-b1c045af7059" #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3" #uuid "25f58a38-aa9d-5199-997b-b1c045af7059" #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3"],
                                            #uuid "280ae09e-999d-5265-95d9-8537fb4a60c9" [#uuid "3931512d-9956-55bf-9a32-2a052f26a177"],
                                            #uuid "0b01f05c-b032-564c-ae61-662fbda0f419" [#uuid "090d9643-a944-5572-88f3-0eb7e3b1a314"],
                                            #uuid "29d3bd89-4410-5ce5-9648-e493e8f38e39" [#uuid "3931512d-9956-55bf-9a32-2a052f26a177"],
                                            #uuid "0fe1df74-facf-5759-8725-a7109b199b9a" [#uuid "2d9a21e3-6044-57da-87f7-b2826b17ba16"],
                                            #uuid "0f800e2d-b036-520a-8657-eccfaaacc9ef" [#uuid "0b577171-086e-53f6-9262-7e851ee8fd7e"],
                                            #uuid "01677820-90f5-5e0b-acbc-100932a22f05"
                                            [#uuid "163ba81e-eb0e-5903-a244-da11d2230020" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c" #uuid "163ba81e-eb0e-5903-a244-da11d2230020" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c"],
                                            #uuid "1e424294-bb25-5901-9782-8cf39b81bf4e"
                                            [#uuid "19dc948d-f78d-5e15-b389-4909ee20e3c1" #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f" #uuid "19dc948d-f78d-5e15-b389-4909ee20e3c1" #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f"],
                                            #uuid "27c6f1f5-f4de-5656-985e-1015baf59dbe" [#uuid "03a411e2-e097-516e-8c57-bba3b5b18851"],
                                            #uuid "02f0b98b-cc8d-5f33-98f9-3875666bb445" [#uuid "18aa9745-a39c-5e90-8ead-7cedb716c3eb"],
                                            #uuid "33547f1b-2480-508d-be34-63da3e1a6704"
                                            [#uuid "2aa5bf30-49c7-5157-81e8-c3158fe7410f" #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3" #uuid "2aa5bf30-49c7-5157-81e8-c3158fe7410f" #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3"],
                                            #uuid "3415ac38-98c7-5348-ba8d-427ec4461de7" [#uuid "14c9da43-a2cb-5112-8d21-d777da2189f4"],
                                            #uuid "071d505d-b198-5ed2-9417-9901737e4167" [#uuid "21b919d6-0e42-5eca-b42e-89cafe1764eb"],
                                            #uuid "3c65cb46-a053-56c8-a4b2-54c13e0389e5"
                                            [#uuid "20ebcafe-8544-5ed5-a58f-e8c5815b6fb9" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7" #uuid "20ebcafe-8544-5ed5-a58f-e8c5815b6fb9" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7"],
                                            #uuid "13534528-4d77-559e-98b7-ecfa442f5d86"
                                            [#uuid "07aa256c-bf28-58ff-84de-0c7a0ba98557" #uuid "1ff4c5fc-7f82-5f6b-8db1-9811650cb6bd" #uuid "07aa256c-bf28-58ff-84de-0c7a0ba98557" #uuid "1ff4c5fc-7f82-5f6b-8db1-9811650cb6bd"],
                                            #uuid "1e498232-d77c-534e-988a-b766ede1e236"
                                            [#uuid "267044b2-7efa-50e0-bab6-f667ce5e5058" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7" #uuid "267044b2-7efa-50e0-bab6-f667ce5e5058" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7"],
                                            #uuid "2941cf92-3650-54a2-bb63-07186dc61844" [#uuid "0b01f05c-b032-564c-ae61-662fbda0f419"],
                                            #uuid "3bc4c33a-6df4-54cf-a12b-38c8a950d571"
                                            [#uuid "1a1261d9-64ac-5ee8-b91d-c6d6f96c19bc" #uuid "01677820-90f5-5e0b-acbc-100932a22f05" #uuid "1a1261d9-64ac-5ee8-b91d-c6d6f96c19bc" #uuid "01677820-90f5-5e0b-acbc-100932a22f05"],
                                            #uuid "163ba81e-eb0e-5903-a244-da11d2230020"
                                            [#uuid "2c24a78d-e400-5368-b610-ab8b06115fce" #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5" #uuid "2c24a78d-e400-5368-b610-ab8b06115fce" #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5"],
                                            #uuid "267044b2-7efa-50e0-bab6-f667ce5e5058"
                                            [#uuid "33547f1b-2480-508d-be34-63da3e1a6704" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a" #uuid "33547f1b-2480-508d-be34-63da3e1a6704" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a"],
                                            #uuid "2835a623-4e30-5668-860b-3f001783d5c6" [#uuid "2a86f198-7dfe-51c7-b6b8-bf90cddd83ee"],
                                            #uuid "3e61855b-5b71-5956-88da-9cb86cc06218" [#uuid "2941cf92-3650-54a2-bb63-07186dc61844"],
                                            #uuid "2d9a21e3-6044-57da-87f7-b2826b17ba16" [#uuid "2835a623-4e30-5668-860b-3f001783d5c6"],
                                            #uuid "013d990f-8a4b-560b-b396-2ac970834ac0"
                                            [#uuid "142d78d3-e2b7-5926-8b53-c26919ab2466" #uuid "163ba81e-eb0e-5903-a244-da11d2230020" #uuid "142d78d3-e2b7-5926-8b53-c26919ab2466" #uuid "163ba81e-eb0e-5903-a244-da11d2230020"],
                                            #uuid "3b60573a-9a49-52a6-9176-6bc7bc1246f5" [#uuid "0ae0e29e-ad26-5b2a-abc1-92d0cd3eb7c6"],
                                            #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5"
                                            [#uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218" #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218"],
                                            #uuid "2adc74af-0537-5ba6-ab82-d50f519e7869" [#uuid "0a7b6be1-6aa5-519e-8001-1a5c1b92c717"],
                                            #uuid "2ed6f194-f62d-5393-8a09-c41b96a53df3"
                                            [#uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2" #uuid "2fdbb1a8-dc89-59aa-8466-e93ccdf9dae0" #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2" #uuid "2fdbb1a8-dc89-59aa-8466-e93ccdf9dae0"],
                                            #uuid "19dc948d-f78d-5e15-b389-4909ee20e3c1"
                                            [#uuid "21d61fa0-7343-5103-ba28-df2c74d8521f" #uuid "3d5e0ad3-8f90-592a-ade8-b2a8352511a6" #uuid "21d61fa0-7343-5103-ba28-df2c74d8521f" #uuid "3d5e0ad3-8f90-592a-ade8-b2a8352511a6"],
                                            #uuid "3b12846b-48a3-5169-b369-61ac96b9425e" [#uuid "03ee12e5-52c2-5f92-bf10-eb9744c5f8d4"],
                                            #uuid "21d61fa0-7343-5103-ba28-df2c74d8521f"
                                            [#uuid "08238078-0dbf-5391-aa50-d7f5435d96e7" #uuid "2941cf92-3650-54a2-bb63-07186dc61844" #uuid "08238078-0dbf-5391-aa50-d7f5435d96e7" #uuid "2941cf92-3650-54a2-bb63-07186dc61844"],
                                            #uuid "3bdf5912-0b1f-5b75-87d5-fe4e2a98a81b" [#uuid "3fb9529d-6142-5bd5-a915-af91d6748f24"],
                                            #uuid "14c9da43-a2cb-5112-8d21-d777da2189f4" [#uuid "2adc74af-0537-5ba6-ab82-d50f519e7869"],
                                            #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314" [#uuid "0fe1df74-facf-5759-8725-a7109b199b9a"],
                                            #uuid "18aa9745-a39c-5e90-8ead-7cedb716c3eb" [#uuid "27c6f1f5-f4de-5656-985e-1015baf59dbe"],
                                            #uuid "38064367-fa19-50e9-998d-a81b358ca086" [#uuid "22cc5272-aa31-595c-b6c9-aa03a37b1bd4"],
                                            #uuid "03ee12e5-52c2-5f92-bf10-eb9744c5f8d4" [#uuid "3890cfed-b083-52a7-960d-6cd02d74a26f"],
                                            #uuid "0a7b6be1-6aa5-519e-8001-1a5c1b92c717" [#uuid "144f2ee9-010d-5c7d-9a72-03d893900dfb"],
                                            #uuid "16ae7995-f3b7-529b-a8a7-b0a7bc51c8fd"
                                            [#uuid "13534528-4d77-559e-98b7-ecfa442f5d86" #uuid "067d5fe8-0089-5813-95db-14e0c2035424" #uuid "13534528-4d77-559e-98b7-ecfa442f5d86" #uuid "067d5fe8-0089-5813-95db-14e0c2035424"],
                                            #uuid "261b4031-5bb9-53ba-b1cf-a87d45bcaa83"
                                            [#uuid "1510e9fd-aacd-55c7-941d-4d9130e0214e" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a" #uuid "1510e9fd-aacd-55c7-941d-4d9130e0214e" #uuid "0fe1df74-facf-5759-8725-a7109b199b9a"],
                                            #uuid "2dea9164-4057-542e-80ea-4159eab94b26"
                                            [#uuid "21673d4d-338b-5d8a-bf8f-551e8fc67518" #uuid "11387177-601c-5ded-98b5-99691cc5b512" #uuid "21673d4d-338b-5d8a-bf8f-551e8fc67518" #uuid "11387177-601c-5ded-98b5-99691cc5b512"],
                                            #uuid "3890cfed-b083-52a7-960d-6cd02d74a26f" [#uuid "169ecefc-a5db-5058-a5f4-bc34c719c749"],
                                            #uuid "2307205d-51d2-59b3-a90a-5867b950d9ff" [#uuid "314edcd9-4db8-5cbb-8247-ad96c495fac9"],
                                            #uuid "23f3a206-5035-5d07-9b35-a083afe9ec34" [#uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c"],
                                            #uuid "144f2ee9-010d-5c7d-9a72-03d893900dfb" [#uuid "03721b99-e947-5789-98e2-0e6302941df1"],
                                            #uuid "3d5e0ad3-8f90-592a-ade8-b2a8352511a6"
                                            [#uuid "3c76478b-f003-5cf9-ad19-6136ee099759" #uuid "2941cf92-3650-54a2-bb63-07186dc61844" #uuid "3c76478b-f003-5cf9-ad19-6136ee099759" #uuid "2941cf92-3650-54a2-bb63-07186dc61844"],
                                            #uuid "0946b409-1e9d-5367-b3ea-9606af6bf7df"
                                            [#uuid "2dea9164-4057-542e-80ea-4159eab94b26" #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2" #uuid "2dea9164-4057-542e-80ea-4159eab94b26" #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2"],
                                            #uuid "20ebcafe-8544-5ed5-a58f-e8c5815b6fb9"
                                            [#uuid "261b4031-5bb9-53ba-b1cf-a87d45bcaa83" #uuid "267044b2-7efa-50e0-bab6-f667ce5e5058" #uuid "261b4031-5bb9-53ba-b1cf-a87d45bcaa83" #uuid "267044b2-7efa-50e0-bab6-f667ce5e5058"],
                                            #uuid "1af5f37a-1170-526e-b6aa-73406718c72c"
                                            [#uuid "1e424294-bb25-5901-9782-8cf39b81bf4e" #uuid "059630dd-7a03-5ea6-9ca5-348620c8e29f" #uuid "1e424294-bb25-5901-9782-8cf39b81bf4e" #uuid "059630dd-7a03-5ea6-9ca5-348620c8e29f"],
                                            #uuid "25f58a38-aa9d-5199-997b-b1c045af7059"
                                            [#uuid "0946b409-1e9d-5367-b3ea-9606af6bf7df" #uuid "2aa5bf30-49c7-5157-81e8-c3158fe7410f" #uuid "0946b409-1e9d-5367-b3ea-9606af6bf7df" #uuid "2aa5bf30-49c7-5157-81e8-c3158fe7410f"],
                                            #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c" [#uuid "3e61855b-5b71-5956-88da-9cb86cc06218"],
                                            #uuid "2fc13656-c13e-5388-87fb-d59b54b4d4fc"
                                            [#uuid "3c65cb46-a053-56c8-a4b2-54c13e0389e5" #uuid "1e498232-d77c-534e-988a-b766ede1e236" #uuid "3c65cb46-a053-56c8-a4b2-54c13e0389e5" #uuid "1e498232-d77c-534e-988a-b766ede1e236"],
                                            #uuid "169ecefc-a5db-5058-a5f4-bc34c719c749" [],
                                            #uuid "22cc5272-aa31-595c-b6c9-aa03a37b1bd4" [#uuid "1363f3ff-a092-5936-a7f3-edea64b960b9"],
                                            #uuid "1363f3ff-a092-5936-a7f3-edea64b960b9" [#uuid "27ddb3dd-b768-5c49-878a-09979c4e9d61"],
                                            #uuid "142d78d3-e2b7-5926-8b53-c26919ab2466"
                                            [#uuid "02ee492f-32cb-5c74-abff-028fa8a4f129" #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5" #uuid "02ee492f-32cb-5c74-abff-028fa8a4f129" #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5"],
                                            #uuid "1ff4c5fc-7f82-5f6b-8db1-9811650cb6bd"
                                            [#uuid "1e498232-d77c-534e-988a-b766ede1e236" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314" #uuid "1e498232-d77c-534e-988a-b766ede1e236" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314"],
                                            #uuid "1a1261d9-64ac-5ee8-b91d-c6d6f96c19bc"
                                            [#uuid "013d990f-8a4b-560b-b396-2ac970834ac0" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c" #uuid "013d990f-8a4b-560b-b396-2ac970834ac0" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c"],
                                            #uuid "1d5eadfb-050f-5674-850e-eeee9c9e88fa"
                                            [#uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c" #uuid "10418a82-fec8-57bb-b57f-65a26a8e0ff5" #uuid "11dff09b-b3e2-5a44-ba13-30faf10e6a3c"],
                                            #uuid "08238078-0dbf-5391-aa50-d7f5435d96e7"
                                            [#uuid "16ae7995-f3b7-529b-a8a7-b0a7bc51c8fd" #uuid "3c76478b-f003-5cf9-ad19-6136ee099759" #uuid "16ae7995-f3b7-529b-a8a7-b0a7bc51c8fd" #uuid "3c76478b-f003-5cf9-ad19-6136ee099759"],
                                            #uuid "0b577171-086e-53f6-9262-7e851ee8fd7e"
                                            [#uuid "3b60573a-9a49-52a6-9176-6bc7bc1246f5"],
                                            #uuid "2aa5bf30-49c7-5157-81e8-c3158fe7410f"
                                            [#uuid "11387177-601c-5ded-98b5-99691cc5b512" #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2" #uuid "11387177-601c-5ded-98b5-99691cc5b512" #uuid "16ee1c8e-f8f3-503a-b12b-1f551f391de2"],
                                            #uuid "3c76478b-f003-5cf9-ad19-6136ee099759"
                                            [#uuid "1ff4c5fc-7f82-5f6b-8db1-9811650cb6bd" #uuid "067d5fe8-0089-5813-95db-14e0c2035424" #uuid "1ff4c5fc-7f82-5f6b-8db1-9811650cb6bd" #uuid "067d5fe8-0089-5813-95db-14e0c2035424"],
                                            #uuid "3fb9529d-6142-5bd5-a915-af91d6748f24" [#uuid "045e8240-6df3-531a-b390-c9238dc6cc7b"],
                                            #uuid "21b919d6-0e42-5eca-b42e-89cafe1764eb" [#uuid "2fdbb1a8-dc89-59aa-8466-e93ccdf9dae0"],
                                            #uuid "02ee492f-32cb-5c74-abff-028fa8a4f129"
                                            [#uuid "2f3c2b3b-1377-54af-b7ab-ab511223e8fb" #uuid "2c24a78d-e400-5368-b610-ab8b06115fce" #uuid "2f3c2b3b-1377-54af-b7ab-ab511223e8fb" #uuid "2c24a78d-e400-5368-b610-ab8b06115fce"],
                                            #uuid "314edcd9-4db8-5cbb-8247-ad96c495fac9" [#uuid "3415ac38-98c7-5348-ba8d-427ec4461de7"],
                                            #uuid "1a98e86d-98c9-572e-a6e3-ea32e8b0f94f"
                                            [#uuid "067d5fe8-0089-5813-95db-14e0c2035424" #uuid "2941cf92-3650-54a2-bb63-07186dc61844" #uuid "067d5fe8-0089-5813-95db-14e0c2035424" #uuid "2941cf92-3650-54a2-bb63-07186dc61844"],
                                            #uuid "2f3c2b3b-1377-54af-b7ab-ab511223e8fb"
                                            [#uuid "1af5f37a-1170-526e-b6aa-73406718c72c" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218" #uuid "1af5f37a-1170-526e-b6aa-73406718c72c" #uuid "3e61855b-5b71-5956-88da-9cb86cc06218"],
                                            #uuid "07aa256c-bf28-58ff-84de-0c7a0ba98557"
                                            [#uuid "2fc13656-c13e-5388-87fb-d59b54b4d4fc" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314" #uuid "2fc13656-c13e-5388-87fb-d59b54b4d4fc" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314"],
                                            #uuid "03a411e2-e097-516e-8c57-bba3b5b18851" [#uuid "344cadf4-366d-5115-8cdd-be8b854b48b5"],
                                            #uuid "2fdbb1a8-dc89-59aa-8466-e93ccdf9dae0" [#uuid "0f800e2d-b036-520a-8657-eccfaaacc9ef"],
                                            #uuid "1ccd2e84-60fb-587d-9593-236a49240c0c" [#uuid "04e824f2-d7f6-5399-89f1-8ff6438c0d3f"],
                                            #uuid "344cadf4-366d-5115-8cdd-be8b854b48b5" [#uuid "2307205d-51d2-59b3-a90a-5867b950d9ff"],
                                            #uuid "067d5fe8-0089-5813-95db-14e0c2035424"
                                            [#uuid "071d505d-b198-5ed2-9417-9901737e4167" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7" #uuid "071d505d-b198-5ed2-9417-9901737e4167" #uuid "090d9643-a944-5572-88f3-0eb7e3b1a314" #uuid "35299639-cd40-5c9a-936e-9965ffc991c7"]},
                             :last-update #inst "2014-04-09T22:23:23.179-00:00",
                             :id #uuid "84473475-2c87-470e-8774-9de66e665812",
                             :description "A bookmark app.",
                             :schema {:type "http://github.com/ghubber/geschichte", :version 1},
                             :head "master",
                             :branches {"master" {:heads #{#uuid "169ecefc-a5db-5058-a5f4-bc34c719c749"}}},
                             :public true,
                             :pull-requests {}}) problem-meta))
