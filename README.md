# geschichte

`geschichte` (meaning history in German) is a distributed eventual consistent database for web applications. Instead of programming thin web-clients around a central server/cloud, you operate on your local data like a native application both on client- and (if you wish to) server-side. We make heavy use of `core.async` to model both sides platform and network agnostic just as peers having a pair of messaging channels for `edn` messages. We build on platform-neutral durable key-value storage through [konserve](https://github.com/ghubber/konserve).
Commit whenever you want and access values whenever you want no matter if the remote peer (server) is *available* or not. You can imagine it as a `git` for `edn` database + automatic eventual consistent synching. The motivation is to share data openly and develop applications on shared well defined data carrying over the immutable value semantics of `Clojure`. The tradeoff is that your application has to support conflict resolution, which can be achieved fairly easily with strict data-models like [datascript](https://github.com/tonsky/datascript).

For detailed documentation look at the [introduction](http://ghubber.github.io/geschichte/). Or to understand the [pub-sub message protocol for synching](http://ghubber.github.io/geschichte/synching.html). 

A prototype application, mostly working, can be found here: [link-collective](https://github.com/kordano/topiq).

## Usage

Use this to store your application state, e.g. with `om`, to
easily compose applications and data. It is supposed to eventually work
from JavaScript as well, ping me and I will have a look what is
necessary to make interop painfree. 

*Any help or patches are very welcome :-)*

## TODO for a first release

- Factor out more p2p protocol functionality in ring-like middleware layer and clearify pub-sub core.
- Clean up and document stage API
- Passwordless authentication (and authorisation) based on email verification and inter-peer trust network as p2p middleware.
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
