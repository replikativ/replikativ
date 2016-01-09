# replikativ

[Quote](http://tonsky.me/blog/the-web-after-tomorrow/):
>These are the things we are interested (for the modern web) in:
>
>    Consistent view of the data. What we’re looking at should be coherent at some point-in-time moment. We don’t want patchwork of static data at one place, slightly stale at another and fresh rare pieces all over the place. People percieve page as a whole, all at once. Consistency removes any possibility for contradictions in what people see, consistent app looks sane and builds trust.

>    Always fresh data. All data we see on the client should be relevant right now. Everything up to the tiniest detail. Ideally including all resources and even code that runs the app. If I upload a new userpic, I want it to be reloaded on all the screens where people might be seeing it at the moment. Even if it’s displayed in a one-second-long, self-disposing notification popup.
>
>    Instant response. UI should not wait until server confirms user’s actions. Effect of the action should be displayed immediately.
>
>    Handle network failures. Networks are not a reliable communication device, yet reliable protocols can be built on top of them. Network failures, lost packets, dropped connections, duplicates should not undermine our consistency guarantees.

>    Offline. Obviously data will not be up-to-date, but at least I should be able to do local modifications, then merge changes when I get back online.
>
>    No low-level connection management, retries, deduplication. These are tedious, error-prone details with subtle nuances. App developers should not handle these manually: they will always choose what’s easier or faster to implement, sacrificing user experience. Underlying library should take care of the details.



`replikativ` is a replication system for convergent replicated data types ([CRDTs](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf)). It is primarily designed to work as a decentralized database for web applications, but can be used to distribute any state durably between different peers with different runtimes (JVM, js; CLR planned). Instead of programming thin web-clients around a central server/cloud, you operate on your local data like a native application both on client- and (if you wish to) server-side. You can also view it in reverse as a cloud being expanded to all end-points.
Commit whenever you want and access values whenever you want no matter if the remote peer (server) is *available* or not. You can imagine it as a `git` for data (expressed e.g. in [edn](https://github.com/edn-format/edn)) + automatic eventual consistent . The motivation is to share data openly and develop applications on shared well-defined data carrying over the immutable value semantics of [Clojure](http://clojure.org/). This allows not only to fork code, but much more importantly to fork the data of applications and extend it in unplanned ways.
The tradeoff is that your application maybe has to support after-the-fact conflict resolution, which can be achieved fairly easily with strict relational data-models like [datascript](https://github.com/tonsky/datascript), but in some cases users will have to decide conflicts.

A prototype application, with an example deployment, can be found here: [topiq](https://github.com/kordano/topiq).

## Usage <a href="https://gitter.im/replikativ/replikativ?utm_source=badge&amp;utm_medium=badge&amp;utm_campaign=pr-badge&amp;utm_content=badge"><img src="https://camo.githubusercontent.com/da2edb525cde1455a622c58c0effc3a90b9a181c/68747470733a2f2f6261646765732e6769747465722e696d2f4a6f696e253230436861742e737667" alt="Gitter" data-canonical-src="https://badges.gitter.im/Join%20Chat.svg" style="max-width:100%;"></a>

Add this to your project dependencies:
[![Clojars Project](http://clojars.org/io.replikativ/replikativ/latest-version.svg)](http://clojars.org/io.replikativ/replikativ)

Use this to store your application state, e.g. with `datascript` and `om`, to easily compose applications and data. You need to create a `peer` and potentially a `stage` or `pull-hooks`.

## Design

`replikativ` consists of two parts, a core of CRDTs, especially a newly crafted one for a git-like repository in the `replikativ.crdt.cdvcs` namespaces, and a generic replication protocol for CRDTs in `replikativ.core` and some middlewares. The replication can be extended for any CRDT and we will try to provide as many implementations as possible by default. Together the CRDTs and the replication provides conflict-free convergent replication. The datatypes decouple resolution of application level state changes (writes) from replication over a network.

The replication protocol partitions the global state space into user specific places for CRDTs, `[email crdt-id]`, possibly further dividing this inside the CRDT into identities (e.g. branches). All replication happens between these places. All peers automatically replicate CRDTs of each user they subscribe to and push changes as soon as they have all data.

We make heavy use of [core.async](https://github.com/clojure/core.async) to model peers platform- and network-agnostic just as peers having a pair of messaging channels from [kabel](https://github.com/replikativ/kabel) for `edn` messages. We build on platform-neutral durable storage through [konserve](https://github.com/replikativ/konserve). At the core is a `pub-sub` scheme between peers, but most functionality is factored into `middlewares` filtering and tweaking the in/out channel pair of each peers pub-sub core. This allows decoupled extension of the network protocol.

For a detailed documentation of CDVCS look at the [introduction](http://replikativ.github.io/replikativ/). Or to understand the [pub-sub message protocol for replication](http://replikativ.github.io/replikativ/replication.html). You can also find test cases for the [stage API](http://replikativ.github.io/replikativ/stage.html) and the [pull hooks](http://replikativ.github.io/replikativ/hooks.html) there.

# Repository CRDT

For the special repository CRDT called CDVCS (confluent distributed version control system) implicit conflicts might occur (if a device is offline for instance). But committing to the same CDVCS on different peers in general is not supposed to be the best way to organize distributed writes. Instead, explicit pulling or merging between branches or users allows for supervised pulls or merges under each user's control, e.g. through pull-hooks or supervised on stage.

## Twisting CAP

Having distributed writes in any system puts it under the restrictions of the [CAP theorem](https://en.wikipedia.org/wiki/CAP_theorem). Similar to `git` there is no design-level decision of how to deal with breaks of consistency and how to modulate convergence. So feel free to organize different write schemes, from a singular writer like Datomic to a totally distributed landscape of non-consistent always available repositories (like git) are possible. Ideally some preconfigurable schemes should emerge.

### Scheme 0: Personal applications

For "high-level", low write-throughput data (like a port of a classical desktop application for personal data management) which is not immediately replicated in a global state between several users by your application, everything is fine and you don't need to worry about CAP. An example would be a personal address book manager, a personal todo app, ... All you need to do is cover conflict resolution.

### Scheme 1: Tree of coordinators

The currently supposed coordination around writes is a Linux kernel-development inspired tree like structure of synching groups consisting of one central user (e.g. user corresponding to the state of the application/database on the server) we call the `coordinator`.

The **coordinator**:

1. tracks changes in all authenticated users clones
2. checks for integrity
3. pulls and hence decides on a new upstream value
4. and propagates it back to all peers
5. users pull *and* merge any changes

Latency differences between users together with a high volume of writes can cause the network to diverge, because high latency peers have no chance anymore to be pulled from and diverge from the coordinator. To avoid a runaway effect in this scenario we introduce a delay cost to merge gradually rising with the ratio of merges vs. normal commits in the CDVCS on user-side. In CAP terms we reduce availability, but do not directly gain consistency, only limit divergence.
In this scenario the application is supposed to notify each user about the diverged state and if this persists, recommend joining a lower write-intensive coordinator. Coordinators then form a tree around the central coordinator themselves being users. This reassignment can be done automatically on server side (but has not been worked out yet) corresponding to load-balancing, a P2P load-balancer is also conceivable.

## JavaScript

It is supposed to work from JavaScript as well, ping us and we will have a look what is necessary to make interop more painfree if you have problems.

*Any help or patches are very welcome :-)*

## TODO for a first release
- rename repository type to CDVCS for consistency with paper [WIP]
- add state diagram for replication protocol with middlewares

# Roadmap

- Implement useful CRDTs (LWW-register, OR-set, counter, vector-clock, ...) from techreview and other papers and ship by default.
- Improve error-handling and handle reconnections gracefully. [WIP, already in full.monty]
- Drop publication with missing values and unsubscribe form CRDT in fetch middleware, allows peers to opt-out to partial replication.
- Introduce strong typing with `core.typed`.
- Make usage from JavaScript straightforward (including JSON values). Browser and nodejs.
- Limit inline value size, avoid pulling huge fetched values in memory.
- Distribute bandwidth between CRDTs.
- Negotiate middlewares with versioning.
- Encryption of transaction with repo key encrypted by userkeys, public key schema, explore pub/private key solutions. Maybe metadata signing can work (slowly) on a DHT?
- Add a basic web toolbar for applications to communicate their synching state to the user in a uniform way.
- Provide example for durable undo and redo for `react`-like applications.
- Implement diverse prototypes, from real-time to "big-data".

## License

Copyright © 2013-2015 Christian Weilbach & Konrad Kühne

Distributed under the Eclipse Public License, the same as Clojure.
