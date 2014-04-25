(ns geschichte.platform-log
  "Logging, might move to own project."
  (:require [clojure.tools.logging :refer [log* *logger-factory*]]
            [clojure.tools.logging.impl :refer [get-logger]]))


;; aliases for tools.logging for now
(defn debug [& args]
  (log* (get-logger *logger-factory* *ns*) :debug nil (apply pr-str args)))

(defn info [& args]
  (log* (get-logger *logger-factory* *ns*) :info nil (apply pr-str args)))

(defn warn [& args]
  (log* (get-logger *logger-factory* *ns*) :warn nil (apply pr-str args)))

(defn error [& args]
  (log* (get-logger *logger-factory* *ns*) :error nil (apply pr-str args)))
