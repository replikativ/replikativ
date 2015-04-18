(ns geschichte.environ
  (:require [hasch.core :refer [uuid]]
            [geschichte.platform :refer [now]]))

(def ^:dynamic *id-fn*
  "DO NOT REBIND EXCEPT FOR TESTING OR YOU MIGHT CORRUPT DATA.
   Determines unique ids, possibly from a value.
   Hashed UUID (from hasch) is defined as public format."
  uuid)

(def ^:dynamic *date-fn*
  "DO NOT REBIND EXCEPT FOR TESTING."
  now)
