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

- Merging happens only through the values of commits (e.g. 3-way
  merge). No history (of transactions) is consulted for merging of
  content.
- Keep metadata simple to just allow a causal ordering of commits for
  merges. This dependency graph is stored in a single value. At the
  moment this is also a branch. All other metadata is stored under the
  :geschichte.meta/meta key in each commit value.
- Separation from transactor, all operations return a map describing
  operations to be performed atomically on an underlying
  kv-store. Functions are supposed to be pure data flow. A glue layer to
  underlying key-value stores will be provided with core.async
  separately.

(1) https://github.com/mirkokiefer/syncing-thesis

## Usage

Use this to store your application state, e.g. with pedestal. It should
work from JavaScript as well, ping me and I will have a look what is
necessary to make interop painfree. Patches welcome :-)

## TODO for a stable release

- Add (general) commit graph plotting
- Offer three-way conflict resolution.
- Find a better hash-function than 32 bit standard in
  Clojure(Script). Probably use SHA-1 like git.
- Evaluate lowest-common-ancestor algorithms if merging becomes too expansive.
  See also lca in haskell (including repository monad): http://slideshare.net/ekmett/skewbinary-online-lowest-common-ancestor-search#btnNext
- Make usage from JavaScript straightforward (including JSON merging).

## License

Copyright © 2013 Christian Weilbach

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
