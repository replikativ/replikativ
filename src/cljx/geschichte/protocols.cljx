(ns geschichte.protocols
  "Protocols between replication and CRDT implementations.")

(defprotocol POpBasedCRDT
  (-identities [this] "Lists the different identities in a CRDT, e.g. branches.")
  (-downstream [this op] "Returns new state when operation is applied to the CRDT. This function is pure and does not affect the stored CRDT!")
  (-filter-identities [this ids])
  (-apply-downstream! [this op] "Applies the operation coming downstream to the CRDT."))

(defprotocol PExternalValues
  "Routine which fetches all values external to the metadata and stores them.
  It is requesting the necessary values and blobs on the out channel
  and receiving them through fetched and binary-fetched channels."
  (-ensure-external [this pub-id op out fetched-ch binary-fetched-ch]))
