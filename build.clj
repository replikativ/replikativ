(ns build
  (:require [org.corfield.build :as bb]))

(def lib 'io.replikativ/replikativ)
(def version "0.2.5")

(defn jar
  "Build the JAR file for this library."
  [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/clean)
      (bb/jar)))

(defn install
  "Install the JAR locally."
  [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/install)))

(defn deploy
  "Deploy the JAR to Clojars."
  [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/deploy)))
