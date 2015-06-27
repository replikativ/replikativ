# replikativ

`replikativ` is a replication system for convergent replicative data types ([CRDTs](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf)). It is primarily designed to work as a decentralized database for web applications, but can be used to distribute any state durably between different peers with different runtimes (JVM, js; CLR planned). Instead of programming thin web-clients around a central server/cloud, you operate on your local data like a native application both on client- and (if you wish to) server-side. You can also view it in reverse as a cloud being expanded to all end-points.
Commit whenever you want and access values whenever you want no matter if the remote peer (server) is *available* or not. You can imagine it as a `git` for data (expressed e.g. in [edn](https://github.com/edn-format/edn)) + automatic eventual consistent synchronization. The motivation is to share data openly and develop applications on shared well-defined data carrying over the immutable value semantics of [Clojure](http://clojure.org/). This allows not only to fork code, but much more importantly to fork the data of applications and extend it in unplanned ways.
The tradeoff is that your application maybe has to support after-the-fact conflict resolution, which can be achieved fairly easily with strict relational data-models like [datascript](https://github.com/tonsky/datascript), but in some cases users will have to decide conflicts.

A prototype application, with an example deployment, can be found here: [topiq](https://github.com/ghubber/topiq).

## Usage

Use this to store your application state, e.g. with `datascript` and `om`, to easily compose applications and data. You need to create a `peer` and potentially a `stage` or `pull-hooks`.

## Design

`replikativ` consists of two parts, a core of CRDTs, especially a newly crafted one for a repository in the `replikativ.crdt.repo` namespaces, and a generic replication protocol for CRDTs in `replikativ.core` and some middlewares. The replication can be extended for any CRDT and we will try to provide as many implementations as possible by default. Together the CRDTs and the replication provides conflict-free convergent synchronization. The datatypes decouple resolution of application level state changes (writes) from synchronization over a network.

The replication protocol partitions the global state space into user specific places for CRDTs, `[email crdt-id]`, possibly further dividing this inside the CRDT into identities (e.g. branches). All replication happens between these places. All peers automatically synchronize CRDTs of each user they subscribe to and push changes as soon as they have all data.

We make heavy use of [core.async](https://github.com/clojure/core.async) to model peers platform- and network-agnostic just as peers having a pair of messaging channels for `edn` messages. We build on platform-neutral durable storage through [konserve](https://github.com/ghubber/konserve). At the core is a `pub-sub` scheme between peers, but most functionality is factored into `middlewares` filtering and tweaking the in/out channel pair of each peers pub-sub core. This allows decoupled extension of the network protocol.

For detailed documentation of the repository CRDT look at the [introduction](http://ghubber.github.io/replikativ/). Or to understand the [pub-sub message protocol for synching](http://ghubber.github.io/replikativ/synching.html).

# Repository CRDT

For the special repository CRDT implicit conflicts might occur (if a device is offline for instance). But committing to the same CRDT on different peers in general is not supposed to be the best way to organize distributed writes. Instead, explicit pulling or merging between branches or users allows for supervised pulls or merges under each user's control, e.g. through pull-hooks or supervised on stage.

## Twisting CAP

Having distributed writes in any system puts it under the restrictions of the [CAP theorem](https://en.wikipedia.org/wiki/CAP_theorem). Similar to `git` there is no design-level decision of how to deal with breaks of consistency and how to modulate convergence. So feel free to organize different write schemes, from a singular writer like Datomic to a totally distributed landscape of non-consistent always available repositories (like git) are possible. Ideally some preconfigurable schemes should emerge.

### Scheme 0: Personal applications

For "high-level", low write-throughput data (like a port of a classical desktop application for personal data management) which is not immediately synchronized in a global state between several users by your application, everything is fine and you don't need to worry about CAP. An example would be a personal address book manager, a personal todo app, ... All you need to do is cover conflict resolution.

### Scheme 1: Tree of coordinators

The currently supposed coordination around writes is a Linux kernel-development inspired tree like structure of synching groups consisting of one central user (e.g. user corresponding to the state of the application/database on the server) we call the `coordinator`.

The **coordinator**:

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

- Define CRDT Algebra for synching and repo. Use downstream ops of INRIA techreport [DONE]
- Allow dual op-based vs. state-based representation of a CRDT for constant time synching [DONE]
- Give message exchanges unique id to track pub-sub exchanges without network topology. [DONE]
- Visualize repo state. [DONE]
- Refactor core replication to break apart from repository CRDT [DONE]
- Rename all messaging: remove ambiguous "meta" terminology, suggestions:
  - conflict-free rdt -> convergent rdt (because the repo models internal conflicts, this could be confusing)
  - :meta-sub -> :sub/crdts or :sub/identities? (allow other subscription topics)
  - :meta-pub -> :pub/crdts (allow other publication topics)
  - :metas (pub) -> :crdts or :identities?
  - :metas (sub) -> :crdts
  - :causal-order (of repo) -> :commit-graph (because that is what it is for this datatype, it corresponds to the causal-history for the crdt, but this is confusing and not specific enough)
  - :op (in publication) -> :downstream (because the operation is actually always a downstream operation)
  - :transactions -> :prepared (transaction is confusing and might be misunderstood as already applied, while :prepared makes clear that the operation is not yet applied.)
- Reactivate cljs port
- Handle tag-table for messaging of records (transit?).


# Roadmap

- Implement some useful CRDTs (OR-set, vector-clock, ...) from techreview and other papers and ship by default.
- Passwordless authentication (and authorisation) based on email verification or password and inter-peer trust network as p2p middleware.
- Restructure stage and its CRDT state representation.
- Atomic cross-CRDT updates. Partially propagate updates and allow them to be delayed and reassembled again to stay atomic?
- Make usage from JavaScript straightforward (including JSON values). Browser and nodejs.
- Allow management of subscriptions of peers.
- Limit inline value size, avoid pulling huge fetched values in memory. Distribute bandwidth between CRDTs.
- Negotiate middlewares with versioning.
- Build extendable command and control interface for peers (middleware?).
- Encryption of transaction with repo key encrypted by userkeys, public key schema, explore pub/private key solutions. Maybe metadata signing can work (slowly) on a DHT?
- Add a basic web toolbar for applications to communicate their synching state to the user in a uniform way.
- Provide example for durable undo and redo for `react`-like applications.
- Make peers and stage records(?).
- Implement diverse prototypes, from real-time to "big-data".
- Evaluate lowest-common-ancestor algorithms if merging becomes too expansive.
  See also [lca in haskell (including repository monad)](http://slideshare.net/ekmett/skewbinary-online-lowest-common-ancestor-search#btnNext)

## License

Copyright © 2013-2015 Christian Weilbach & Konrad Kühne

Distributed under the Eclipse Public License, the same as Clojure.
