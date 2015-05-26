(ns geschichte.protocols
  "Protocols between replication and CRDT implementations.")


(defprotocol PHasIdentities
  "CRDTs which have separate internal identities implement this protocol."
  (-identities [this]
    "Lists the different identities in a CRDT, e.g. branches. ")
  (-select-identities [this identities operation]
    "Selects parts of operation which are relevant for the set of identities and discards the rest. Returns selected operation or nil if the result is empty."))


(defprotocol POpBasedCRDT
  (-downstream [this op]
    "Returns new state when operation is applied to the CRDT. This function is pure and does not affect the stored CRDT!")
  (-apply-downstream! [this op]
    "Applies the operation coming downstream to the CRDT. Returns go block to synchronize."))


(defprotocol PExternalValues
  "Routine which fetches all values external to the metadata and stores them.
  It must request the necessary values and blobs on the out channel
  and receive them through fetched and binary-fetched
  channels. Returns go block containing true if all values are stored, false
  otherwise."
  (-ensure-external [this pub-id op out fetched-ch binary-fetched-ch]))


(defprotocol PPullOp
  (-pull [this atomic-pull-store hooks]
    "Create a pull operation from the other CRDT of the same type. Returns go block to synchronize.

Valid hooks are: [[a-user a-repo a-branch a-crdt]
    [b-user b-repo b-branch b-crdt]
    integrity-fn
    allow-induced-conflict?]

Pull from user 'a' into repo of user 'b', optionally verifying integrity and optionally supplying a reordering function for merges. If induced conflicts are not allowed, only clean pulls can move a branch forward to ensure availability of the state.

   Atomicity only works inside the stores atomicity boundaries (probably peer-wide). So when different peers with different stores pull through this middleware they might still induce conflicts although each one disallows them."))
