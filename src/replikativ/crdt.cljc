(ns replikativ.crdt)

(defrecord CDVCS [commit-graph heads cursor store version])
