# geschichte

`geschichte` (meaning history in German) is a distributed eventual consistent database for web applications. Instead of programming thin web-clients around a central server/cloud, you operate on your local data like a native application both on client- and (if you wish to) server-side. You can also view it in reverse as a cloud being expanded to all end-points.
Commit whenever you want and access values whenever you want no matter if the remote peer (server) is *available* or not. You can imagine it as a `git` for `edn` database + automatic eventual consistent synching. The motivation is to share data openly and develop applications on shared well-defined data carrying over the immutable value semantics of `Clojure`. This allows not only to fork code, but much more importantly to fork the data of applications and extend it in unplanned ways.
The tradeoff is that your application has to support after-the-fact conflict resolution, which can be achieved fairly easily with strict data-models like [datascript](https://github.com/tonsky/datascript), but in many applications users will have to decide some conflict cases.

A prototype application, with an example deployment, can be found here: [topiq](https://github.com/ghubber/topiq).

## Usage

Use this to store your application state, e.g. with `datascript` and `om`, to easily compose applications and data. You need to create a `peer` and (if you want to interact with the repository) a `stage`.

## Design

geschichte consists of two parts, the pure core of a CRDT data-structure for the repository in the `geschichte.repo` namespace and a synching protocol updating this CRDT in `geschichte.sync` and some middlewares.

The synching protocol partitions the global state space into user specific places for repositories, `[user repo-id]` further dividing this inside the CRDT into branches. All synchronization happens between these places. All peers automatically synchronize repositories of each user they subscribe to (is registered) potentially introducing conflicts if the user writes to the same branch of the same repository, e.g. on different devices. While this might happen (if a device is offline for instance), it is not the general way to organize distributed writes. Instead, explicit pulling or merging between branches or users allows for supervised pulls or merges under each user's control, e.g. through pull-hooks or on stage.

We make heavy use of `core.async` to model peers platform- and network-agnostic just as peers having a pair of messaging channels for `edn` messages. We build on platform-neutral durable key-value storage through [konserve](https://github.com/ghubber/konserve). At the core is a pub-sub scheme between peers, but most functionality is factored into `middlewares` filtering and tweaking the in/out channel pair of each peers pub-sub core. This allows decoupled extension.

For detailed documentation look at the [introduction](http://ghubber.github.io/geschichte/). Or to understand the [pub-sub message protocol for synching](http://ghubber.github.io/geschichte/synching.html).

## Twisting CAP

Having distributed writes in any system puts it under the restrictions of the [CAP theorem](https://en.wikipedia.org/wiki/CAP_theorem). Similar to `git` there is no design-level decision of how to deal with breaks of consistency and how to modulate convergence. So feel free to organize different write schemes, from a singular writer like Datomic to a totally distributed landscape of non-consistent always available repositories (like git) are possible. Ideally some preconfigurable schemes should emerge.

### Scheme 0: Personal applications

For "high-level", low write-throughput data (like a port of a classical desktop application for personal data management) which is not immediately synchronized in a global state between several users by your application, everything is fine and you don't need to worry about CAP. An example would be a personal address book manager, a personal todo app, ... All you need to do is cover conflict resolution.

### Scheme 1: Tree of coordinators

The currently supposed coordination around writes is a Linux kernel-development inspired tree like structure of synching groups consisting of one central user (e.g. user corresponding to the state of the application/database on the server) we call the `coordinator`.
The coordinator:
1. tracks changes in all authenticated users clones
2. checks for integrity
3. pulls and hence decides on a new upstream value
4. and propagates it back to all peers
5. users pull *and* merge any changes

Latency differences between users together with a high volume of writes can cause the network to diverge, because high latency peers have no chance anymore to be pulled from and diverge from the coordinator. To avoid a runaway effect in this scenario we introduce a delay cost to merge gradually rising with the ratio of merges vs. normal commits in the repository on user-side. In CAP terms we reduce availability, but do not directly gain consistency, only limit divergence.
In this scenario the application is supposed to notify each user about the diverged state and if this persists, recommend joining a lower write-intensive coordinator. Coordinators then form a tree around the central coordinator themselves being users. This reassignment can be done automatically on server side (but has not been worked out yet) corresponding to load-balancing, a P2P load-balancer is also conceivable.

## JavaScript

It is supposed to work from JavaScript as well, ping me and I will have a look what is necessary to make interop more painfree if you have problems.

*Any help or patches are very welcome :-)*

## TODO for a first release

- Clean up and document stage API
- Refactor core synching API against CRDT protocol to break apart from repository CRDT.

# long-term Roadmap

- Add delta compression to only transmit new parts of metadata.
- Passwordless authentication (and authorisation) based on email verification and inter-peer trust network as p2p middleware.
- Port to transit for faster (de)serialisation.
- Build extendable command and control interface for peers (middleware?).
- Encryption of transaction with repo key encrypted by userkeys, public key schema, explore pub/private key solutions. Maybe metadata signing can work (slowly) on a DHT?
- Partially propagate updates and allow them to be reassembled again.
- Add (general) commit graph plotting and a basic web toolbar for applications to communicate their synching state to the user. Including:
- Provide durable undo and redo for `om` applications out of the box.
- Make usage from JavaScript straightforward (including JSON merging).
- Offer some default (three-way) user-supported (ui) conflict resolution.
- Implement diverse prototypes, from real-time to "big-data".
- Evaluate lowest-common-ancestor algorithms if merging becomes too expansive.
  See also [lca in haskell (including repository monad)](http://slideshare.net/ekmett/skewbinary-online-lowest-common-ancestor-search#btnNext)

## License

Copyright © 2013-2014 Christian Weilbach & Konrad Kühne

Distributed under the Eclipse Public License, the same as Clojure.
