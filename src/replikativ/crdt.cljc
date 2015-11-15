(ns replikativ.crdt)

(defrecord CDVCS [commit-graph branches cursor store version])
