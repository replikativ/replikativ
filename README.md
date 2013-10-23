# geschichte

A revision control DB modelled after git and HistoDB. (1) It is
separated from the underlying storage which is modelled as a key-value
store. All it assumes is to be the only writer of the repo metadata (one
mutable value of the repo key) on the key-value store. Different repos
and branches can be stored in the same key-value store and are supposed
to structurally share (nested) immutable values in the store. Values may
never by nobody be updated in places except for metadata for this to
work. See also Datomic for the benefits of immutability in DB design.

Current design decisions:

- Merging happens only through the head value of both branches
  (e.g. 3-way merge). No history is consulted for merging of content.
- Keep metadata simple to just allow a causal ordering of commits for
  merges. This dependency graph is stored in a single value. All other
  metadata is stored under the :meta key in the commit value.
- Separation from transactor, all operations return a map describing
  operations to be performed atomically on an underlying kv-store.

(1) https://github.com/mirkokiefer/syncing-thesis

## Usage

Use this to store your application state, e.g. with pedestal. It should
work from JavaScript as well, ping me and I will have a look what is
necessary to make interop painfree. Patches welcome :-)

## TODO for a stable release

- Find a better hash-function than 32 bit standard in
  Clojure(Script). Probably use SHA-1 like git.
- Evaluate lowest-common-ancestor algorithms if merging becomes too expansive.
- Make usage from JavaScript straightforward.

## License

Copyright © 2013 Christian Weilbach

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
