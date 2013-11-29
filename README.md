# geschichte

For the documentation look at [introduction](http://ghubber.github.io/geschichte/).

There are also [API docs](http://ghubber.github.io/geschichte/doc/index.html).

## Usage

An example [pedestal](http://pedestal.io)-application can be found
[here](http://github.com/ghubber/ped-geschichte).

Use this to store your application state, e.g. with `pedestal`, to
easily compose applications and data. It is supposed to eventually work
from JavaScript as well, ping me and I will have a look what is
necessary to make interop painfree. Patches welcome :-)

## TODO for a stable release

- Implement a demo application covering some of the following points.
- Add (general) commit graph plotting and a basic web toolbar.
- Make usage from JavaScript straightforward (including JSON merging).
- Offer some three-way conflict resolution.

# Longer term

- Provide undo and redo for applications.
- Make synching mobile friendly, by only transmitting novelty (patches),
  structurally share persistent value(parts) and not transmit complete
  values all the time.
- Evaluate lowest-common-ancestor algorithms if merging becomes too expansive.
  See also [lca in haskell (including repository monad)](http://slideshare.net/ekmett/skewbinary-online-lowest-common-ancestor-search#btnNext)

## License

Copyright © 2013 Christian Weilbach

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
