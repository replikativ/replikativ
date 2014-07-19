# geschichte

For the documentation look at [introduction](http://ghubber.github.io/geschichte/)
and [synching](http://ghubber.github.io/geschichte/synching.html).

A prototype application, mostly working, can be found here: [link-collective](https://github.com/kordano/link-collective).

## Usage

Use this to store your application state, e.g. with `om`, to
easily compose applications and data. It is supposed to eventually work
from JavaScript as well, ping me and I will have a look what is
necessary to make interop painfree. 

*Any help or patches very welcome :-)*

## TODO for a first release

- Authentication and authorisation based on mail registration and inter-peer trust network.
- Automatic load balancing to shield network (each peer). Any recommendations?

# long-term Roadmap

- Encryption of transaction with repo key encrypted by userkeys, public key schema, explore pub/private key solutions. Maybe metadata signing can work (slowly) on a DHT?
- Partially propagate updates and allow them to be reassembled again.
- Add (general) commit graph plotting and a basic web toolbar for applications to communicate their synching state to the user. Including:
- Provide undo and redo for applications.
- Make usage from JavaScript straightforward (including JSON merging).
- Offer some default (three-way) user-supported (ui) conflict resolution.
- Implement diverse prototypes, from real-time to "big-data".
- Evaluate lowest-common-ancestor algorithms if merging becomes too expansive.
  See also [lca in haskell (including repository monad)](http://slideshare.net/ekmett/skewbinary-online-lowest-common-ancestor-search#btnNext)

## License

Copyright © 2013-2014 Christian Weilbach

Distributed under the Eclipse Public License, the same as Clojure.
