(ns geschichte.platform
  "Platform specific UUID generation.")


(defn uuid
  ([] (java.util.UUID/randomUUID))
  ([val] (java.util.UUID/randomUUID)))

(defn date [] (java.util.Date.))
