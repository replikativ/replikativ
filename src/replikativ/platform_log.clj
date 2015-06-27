(ns replikativ.platform-log
  "Logging for Clojure."
  (:require [taoensso.timbre :as timbre]))


(defn debug [& args]
  ;; wrapping macros ...
  (timbre/debug args))

(defn info [& args]
  (timbre/info args))

(defn warn [& args]
  (timbre/warn args))

(defn error [& args]
  (timbre/error args))
