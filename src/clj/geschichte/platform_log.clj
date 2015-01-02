(ns geschichte.platform-log
  "Logging for Clojure."
  (:require #_[clojure.tools.logging :refer [log* *logger-factory*]]
            #_[clojure.tools.logging.impl :refer [get-logger]]
            [taoensso.timbre :as timbre]))


;; aliases for tools.logging for now
(defn debug [& args]
  (timbre/debug args)
  #_(log* (get-logger *logger-factory* *ns*) :debug nil (apply pr-str args)))

(defn info [& args]
  (timbre/info args)
  #_(log* (get-logger *logger-factory* *ns*) :info nil (apply pr-str args)))

(defn warn [& args]
  (timbre/warn args)
  #_(log* (get-logger *logger-factory* *ns*) :warn nil (apply pr-str args)))

(defn error [& args]
  (timbre/error args)
  #_(log* (get-logger *logger-factory* *ns*) :error nil (apply pr-str args)))
