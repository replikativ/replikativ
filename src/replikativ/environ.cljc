(ns replikativ.environ
  "Environment values and functions which track time and ids."
  (:require [hasch.core :refer [uuid]]
            [replikativ.platform :refer [now]]))

(def ^:dynamic *id-fn*
  "DO NOT REBIND EXCEPT FOR TESTING OR YOU MIGHT CORRUPT DATA.
   Determines unique ids, possibly from a value.
   Hashed UUID (from hasch) is defined as public format."
  uuid)

(def ^:dynamic *date-fn*
  "DO NOT REBIND EXCEPT FOR TESTING."
  now)


;; standardisation for blob commits (used by stage)
(def ^:dynamic *custom-store-fn* nil)

(def store-blob-trans-value
  "Transparent transaction function value to just store (binary) blobs.
  Rebind *custom-store-fn* to track when this happens."
  '(fn store-blob-trans [old params]
     (if *custom-store-fn*
       (*custom-store-fn* old params)
       old)))

(def store-blob-trans-id (*id-fn* store-blob-trans-value))

(defn store-blob-trans [old params]
  "Transparent transaction function value to just store (binary) blobs.
  Rebind *custom-store-fn* to track when this happens."
  (if *custom-store-fn*
    (*custom-store-fn* old params)
    old))
