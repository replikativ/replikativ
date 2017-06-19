(ns replikativ.protocols
  "Protocols between replication and CRDT implementations.")


(defprotocol POpBasedCRDT
  (-handshake [this S] "Initial message send to other replica to catch up.")
  (-downstream [this op]
    "Returns new state when operation is applied to the CRDT. This function is pure and does not affect the stored CRDT!"))


(defprotocol PExternalValues
  "Returns go channel with set of missing commit ids."
  (-missing-commits [this S store out fetched-ch op]))


(defprotocol PPullOp
  (-pull [this S store atomic-pull-store hooks]
    "Create a pull operation from the other CRDT of the same type. Returns go block to synchronize.

Valid hooks are: [[a-user a-crdt-id a-crdt]
    [b-user b-crdt-id b-crdt]
    integrity-fn
    allow-induced-conflict?]

Pull from user 'a' into crdt of user 'b', optionally verifying integrity and optionally supplying a reordering function for merges. If induced conflicts are not allowed, only clean pulls can move a branch forward to ensure availability of the state.

   Atomicity only works inside the stores atomicity boundaries (probably peer-wide). So when different peers with different stores wrongly pull through this middleware they might still induce conflicts although each one disallows them."))
