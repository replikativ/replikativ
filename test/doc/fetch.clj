(ns doc.fetch
  (:require [doc.fetch :refer :all]
            [replikativ.protocols :refer [-ensure-external]]
            [midje.sweet :refer :all]))



(comment
  (def out-a (chan 100))
  (def fetched-a (chan 100))
  (def binary-fetched-a (chan 100))

  ;; no new values
  (<!?
   (-ensure-external (get-in @(:state store-a) ["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"])
                     1123
                     {:method :commit
                      :version 1
                      :causal-order {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" [],
                                     #uuid "108d6e8e-8547-58f9-bb31-a0705800bda8" [#uuid "06118e59-303f-51ed-8595-64a2119bf30d"]}
                      :branches {"master" #{#uuid "108d6e8e-8547-58f9-bb31-a0705800bda8"}}}
                     store-a
                     out-a
                     fetched-a
                     binary-fetched-a))


  ;; a new commit
  (put! fetched-a {:topic :fetched
                   :id 1123
                   :values {#uuid "108d6e8e-8547-58f9-bb31-a0705800bda9" :blub}})

  (<!?
   (-ensure-external (get-in @(:state store-a) ["a@mail.com" #uuid "790f85e2-b48a-47be-b2df-6ad9ccbc73d6"])
                     1123
                     {:method :commit
                      :version 1
                      :causal-order {#uuid "06118e59-303f-51ed-8595-64a2119bf30d" [],
                                     #uuid "108d6e8e-8547-58f9-bb31-a0705800bda9" [#uuid "06118e59-303f-51ed-8595-64a2119bf30d"]}
                      :branches {"master" #{#uuid "108d6e8e-8547-58f9-bb31-a0705800bda9"}}}
                     store-a
                     out-a
                     fetched-a
                     binary-fetched-a)))
