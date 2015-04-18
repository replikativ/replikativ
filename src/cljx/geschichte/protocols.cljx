(ns geschichte.protocols
  "Protocols between replication and CRDT implementations.")

(defprotocol IOpBasedCRDT
  (-filter-identities [this ids])
  (-downstream [this op]))

(defprotocol IExternalValues
  (-new-values [this op])
  (-new-blobs [this op]))
