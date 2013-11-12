# geschichte

For the documentation look at [introduction](http://ghubber.github.io/geschichte/).

## TODO
- navigation of dependency graph, e.g. undo/redo. (detached head mode?)
  take Undo-tree as example, but does not work directly for causal-order graphs
- implement prototype including synchronisation with `core.async`

(1) https://github.com/mirkokiefer/syncing-thesis

## Usage

Use this to store your application state, e.g. with `pedestal` to easily
compose applications and data. It should work from JavaScript as well,
ping me and I will have a look what is necessary to make interop
painfree. Patches welcome :-)

## TODO for a stable release

- Add (general) commit graph plotting
- Offer three-way conflict resolution.
- Evaluate lowest-common-ancestor algorithms if merging becomes too expansive.
  See also lca in haskell (including repository monad): http://slideshare.net/ekmett/skewbinary-online-lowest-common-ancestor-search#btnNext
- Make usage from JavaScript straightforward (including JSON merging).

## License

Copyright © 2013 Christian Weilbach

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
