(ns replikativ.platform-log
  "Logging, might move to own project.")


;; aliases for console for now
(defn debug [& args]
  (.debug js/console (apply pr-str args)))

(defn info [& args]
  (.info js/console (apply pr-str args)))

(defn warn [& args]
  (.warn js/console (apply pr-str args)))

(defn error [& args]
  (.error js/console (apply pr-str args)))
