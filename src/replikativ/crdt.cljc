(ns replikativ.crdt)

(defrecord CDVCS [commit-graph heads version])

(defrecord SimpleGSet [elements])

(defrecord SimpleORMap [adds removals])
