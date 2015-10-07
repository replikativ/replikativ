(ns replikativ.crdt)

(defrecord Repository [commit-graph branches cursor store version])
