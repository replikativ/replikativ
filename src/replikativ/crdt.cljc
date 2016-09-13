(ns replikativ.crdt)

(defrecord CDVCS [commit-graph heads version])

(defrecord SimpleGSet [elements])

(defrecord ORMap [adds removals])

(defrecord LWWR [register timestamp])
