# replikativ <a href="https://gitter.im/replikativ/replikativ?utm_source=badge&amp;utm_medium=badge&amp;utm_campaign=pr-badge&amp;utm_content=badge"><img src="https://camo.githubusercontent.com/da2edb525cde1455a622c58c0effc3a90b9a181c/68747470733a2f2f6261646765732e6769747465722e696d2f4a6f696e253230436861742e737667" alt="Gitter" data-canonical-src="https://badges.gitter.im/Join%20Chat.svg" style="max-width:100%;"></a>

`replikativ` is a replication system for confluent replicated data types ([CRDTs](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf)). You can think about them as durable eventual consistent persistent datastructures. replikativ is primarily designed to work as a decentralized database for web applications, but can be used to distribute any state durably between different peers with different runtimes (JVM, js atm.). Instead of programming thin web-clients around a central server/cloud, you operate on your local data like a native application both on client- and (if you wish to) server-side. You can also view it in reverse as a cloud being expanded to all end-points.
You can write to CRDTs whenever you want and also access values whenever you want no matter if the remote peer(s) is *available* or not. In combination with our [CDVCS](http://arxiv.org/abs/1508.05545) datatype you can also use it as `git` for data (expressed e.g. in [edn](https://github.com/edn-format/edn)) + automatic eventual consistent replication.

## Quickstart
Add this to your project dependencies:
[![Clojars Project](http://clojars.org/io.replikativ/replikativ/latest-version.svg)](http://clojars.org/io.replikativ/replikativ)

~~~clojure
(ns replikativ.simpleormap-demo
  (:require [full.async :refer [<??]]
            [kabel.http-kit :refer [start stop]]
            [konserve
             [filestore :refer [new-fs-store]]
             [memory :refer [new-mem-store]]]
            [replikativ
             [peer :refer [server-peer]]
             [stage :refer [connect! create-stage!]]]
            [replikativ.crdt.simple-ormap.stage :as ors]))

(def user "mail:prototype@your-domain.com") ;; will be used to authenticate you (not yet)
(def orset-id #uuid "7d274663-9396-4247-910b-409ae35fe98d") ;; application specific datatype address

(def store-a (<?? (new-fs-store "/tmp/test"))) ;; durable store
(def peer-a (<?? (server-peer store-a "ws://127.0.0.1:9090"))) ;; network and file IO
(start peer-a)
(def stage-a (<?? (create-stage! user peer-a))) ;; API for peer
(<?? (ors/create-simple-ormap! stage-a :id orset-id))

(def store-b (<?? (new-mem-store))) ;; store for testing
(def peer-b (<?? (server-peer store-b "ws://127.0.0.1:9091")))
(start peer-b)
(def stage-b (<?? (create-stage! user peer-b)))
(<?? (ors/create-simple-ormap! stage-b :id orset-id))

;; now you are set up

(<?? (ors/or-assoc! stage-b [user orset-id] :foo :bars))
(ors/or-get stage-b [user orset-id] :foo)

(<?? (connect! stage-a "ws://127.0.0.1:9091")) ;; wire the peers up

(ors/or-get stage-a [user orset-id] :foo) ;; do they converge?
(<?? (ors/or-dissoc! stage-a [user orset-id] :foo)) ;; play around :)

;; ...

(stop peer-a)
(stop peer-b)
~~~
The ClojureScript API is the same, except that you cannot have blocking IO and cannot open a websocket server in the browser (but we have already WebRTC in mind ;) ):


### CDVCS Example

If you want to have sequential semantics, e.g. to track a history of events you want to reduce over in you application (e.g. with datascript), have a look at the ([clj demo project](https://github.com/replikativ/replikativ-demo)).

Complementary there is the [cljs adder demo project](https://github.com/replikativ/replikativ-cljs-demo). This automatically connects when you have the clj demo project running. Otherwise you get a local CDVCS copy available, but this can conflict later when you connect. Addition is commutative and does not really require the sequential semantics, [topiq](https://github.com/replikativ/topiq) is a real application using CDVCS for a datascript log. In general better start with a datatype without conflict resolution.

There is also a [twitter-collector](https://github.com/replikativ/twitter-collector), which you can use with twitter API credentials.

## Performance

The initial prototypes were unoptimized and only worked well up to a few thousand write operations. Since `0.2.0-beta2` we use an extended storage protocol with append-only logs and now have a fairly fast local eventual consistent database. For the OR-Map with small values an `or-assoc` operation roughly takes ~10 ms on my Laptop, which is approximately the IO cost. We have further evaluated the performance of `replikativ` by constantly writing tweets into CDVCS and synchronizing it with a laptop from day to day for realtime analysis. The system scaled up to days of usage, more than 100,000 write operations and more than 10 GiB of managed data (a commit every 1-2s). We anticipate that the system scales further, but for now we support these performance numbers for all CRDTs with non-inlined values (CDVCS, planned OR-Set, LWWR) for now.

We are interested in performance measures of real world applications to iron `replikativ` out as a solid default storage in front of business focused databases for fast domain specific queries.

## Motivation and Vision

There is a [video presentation](https://www.youtube.com/watch?v=KV8JcVhQHxw).

The web is still a bag of data silos (often called *places*). Despite existing cooperation on source code, data rarely is shared cooperatively, because it is accessed through a single (mostly proprietary) service, which also is fed with inputs to 'update' the data (read: it has an *API*). This creates a single point of perception to decide upon writes, which at the same time has to be economically viable and hence locks the data in.

While sophisticated new functional databases like [Datomic](http://www.datomic.com/) promise scalable relational programming and access to all data for the service provider, they still do not fit for distributed data. A *single writer* with a *singular notion of time* is still required. *replikativ* tries to apply some lessons learned from these efforts, building foremost on *immutablity*, but applies them to a different spot in the spectrum of storage management. The goal of `replikativ` is to build a distributed web and edit data *collectively*, while still allowing the right to fork and dissent for anybody. In general distributed 'serverless' applications should be possible.

Experience with a [bittorrent integration for static, read-only data](http://kde-apps.org/content/show.php/kio-magnet?content=136909) in `KDE`, distributed systems like `git` and `mercurial`, [Votorola](http://zelea.com/project/votorola/home.html) as well as [HistoDB](https://github.com/mirkokiefer/syncing-thesis) have been inspirations. [CRDTs](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf) have been introduced to allow carefree synching of metadata values without coordination.

Github for example already resembles an open community using tools to produce source code, but it is still a central site (service) and does not apply to the web itself. `replikativ` uses `P2P` web-technologies, like Websockets (and eventually WebRTC), to globally exchange values. It can also make use of `IndexedDB` in the browser. Its implementation is supposed to be *functionally pure* (besides replication io) and runs on `Clojure/ClojureScript`. On the JVM and node.js it can also potentially hook into existing distributed systems.

The motivation from a programmer's perspective is to share data openly and develop applications on shared well-defined datatypes more easily by carrying over the immutable value semantics of [Clojure](http://clojure.org/). This allows not only to fork code, but much more importantly to fork the data of applications and extend it in unplanned ways. Or phrased differently, the vision is to decouple data from the infrastructure and allow an open system of collaboration.
A tradeoff is that your application may have to support *after-the-fact* conflict resolution, if you need the strong sequential semantics of CDVCS. This can be achieved either automatically, e.g. with strict relational data-models like [datascript](https://github.com/tonsky/datascript), or otherwise users have to decide how to resolve conflicts.


A more hands-on, well thought critique of status quo web development and the current architectures in general can be found [here](http://tonsky.me/blog/the-web-after-tomorrow/):
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

There is also [project quilt thinking in this direction](http://writings.quilt.org/2014/05/12/distributed-systems-and-the-end-of-the-api/).

Our vision is more ambitious by creating open data systems instead of just optimizing the privatized Internet of data silos, but CRDTs are built to solve the practical problems of distributed applications today and fit very well to the described problems even if they are run by a single party. So if you just care about developing consistent and scaling web applications this should be an attractive solution to you, if not feel free to complain :).

## References)

For more detailed examples have [a look at the tests for the
pull-hooks as
well](https://replikativ.github.io/replikativ/hooks.html).

A full-blown prototype application with
[authentication](https://github.com/replikativ/kabel-auth) built with
[datascript](https://github.com/tonsky/datascript) and
[Om](https://github.com/omcljs/om) can be found here:
[topiq](https://github.com/replikativ/topiq).

In an experimental and outdated project, we have also used replikativ
for big [hdf5 binary blob synchronisation with datomic and analysis
with gorilla](https://github.com/whilo/cnc).

The API docs are [here](https://replikativ.github.io/replikativ/doc/index.html).

## Alternatives


An interesting alternative is
[swarm.js](https://github.com/gritzko/swarm). Besides many
commonalities there are a few differences, so we could not just drop
`replikativ` and join `swarm.js`. To our understanding the `swarm.js`
authors are not sharing the vision of open data exchange and CRDTs are
not mapped in a global namespace which can be distributed without
conflicts. Additionally `replikativ` does not use a pure op-based
replication, but implements propagation of ops informally like the recently
formalized [delta-mutation
CvRDTs](http://arxiv.org/abs/1410.2803). swarm.js only uses an
efficient state CvRDT representation on the initial fetch and
otherwise replicates all operation messages. `replikativ` is also as
host agnostic as Clojure and not a pure JavaScript implementation,
which has to unconditionally accept all the weaknesses of
JavaScript. You can also use a Java runtime wherever you prefer to do
so :). We make extensive use of the strong value semantics of Clojure
and an Erlang-inspired robust concurrency version of CSP to decouple
our design.

A less CRDT oriented project with interesting git-like functionality
and mergable datatypes is [irmin](https://github.com/mirage/irmin)
including git format compatibility. There is also
[ipfs](https://ipfs.io/) which has similar goals, but does not solve
the distribution of writes with CRDTs, but so far cares mostly about
read-scaling and the build up of a p2p static content delivery
model. There is some [interest in CRDTs
though](https://github.com/ipfs/notes/issues/40).

There are many other CRDT implementations of course, e.g. in
[riak](http://basho.com/tag/crdt/), but they only losely relate to our
approach to expand the network to the endpoints.

## Design

The joint system has won a best
[poster](https://github.com/replikativ/replikativ/blob/master/doc/poster.pdf)
award at EuroSys 2016.

`replikativ` consists of two parts, a core of CRDTs, especially a
newly crafted one for the [git-like CDVCS
datatype](http://arxiv.org/abs/1508.05545) ([a bit more polished PaPoC
2016 paper](http://dl.acm.org/citation.cfm?id=2911154)) in the
`replikativ.crdt.cdvcs` namespaces, and a generic replication protocol
for CRDTs in `replikativ.core` and some middlewares. The replication
can be externally extended to any CRDT (as long as all connected peers
support it then). We will provide as many implementations as possible
by default for the open, global replication system. Together the CRDTs
and the replication provides conflict-free convergent replication. The
datatypes decouple resolution of application level state changes from
replication over a network.

The replication protocol partitions the global state space into user
specific places for CRDTs, `[user-id crdt-id]`. All replication
happens between these places. All peers are supposed to automatically
replicate CRDTs of each user they subscribe to.

We make heavy use of
[core.async](https://github.com/clojure/core.async) to model peers
platform- and network-agnostic just as peers having a pair of
messaging channels from [kabel](https://github.com/replikativ/kabel)
for `edn` messages. We build on platform-neutral durable storage
through [konserve](https://github.com/replikativ/konserve). At the
core is a `pub-sub` scheme between peers, but most functionality is
factored into `middlewares` filtering and tweaking the in/out channel
pair of each peers pub-sub core. This allows decoupled extension of
the network protocol.

For a detailed documentation of the CDVCS implementation you can have
a look at the
[introduction](https://replikativ.github.io/replikativ/). Or to
understand the [pub-sub message protocol for
replication](https://replikativ.github.io/replikativ/replication.html).



## JavaScript

We have experimental JavaScript
[bindings](http://topiq.polyc0l0r.net/replikativ/) for Browser and
nodejs.  There is a nodejs example client in the `nodejs` folder. The
client can participate in the p2p network. To test it you can start a
local peer with
[replikativ-demo](https://github.com/replikativ/replikativ-demo). If
you are interested in using the client, please join the gitter
chat. We would like to make it work with JavaScriptCore on iOS next.

*Any help or patches are very welcome :-)*

## Garbage Collection

The append logs used as well as referenced values by CRDTs you are no longer
interested in generate garbage. We don't have an automatic garbage collection
mechanism yet, but it is straightforward to start a second peer with a new
store, sync it and then replace the old one with it. You can then safely remove
the store of the old peer.

# Changelog

## 0.2.0-beta2
   - append-log for constant time fast writes for all CRDTs
   - accelerations of LCA for CDVCS
   - incremental and faster fetching, catching up with more than 1 MiB/s for edn values
   - reduced debug messages to speed up replikativ for "bigger" data workloads
   - generalized reduction over commits for map-reduce style queries
   - BUG: fix streaming support for CDVCS

## 0.2.0-beta1
   - implement a simple GSet and OR-Map where the values are inlined in the metadata
   - make stage API robust for concurrent operations
   - use new full.async supervision for error handling and remove explicit error channels
   - clean up and simplify the code

## 0.1.4
   - bump versions of fixed dependencies

## 0.1.3
   - simplify and fix logging (use bare slf4j)
   - provide streaming for CDVCS

## 0.1.2
   - introduce reduced subscription for mobile/web clients
   - fix hooks
   - fix aot compilation
   - test cljs advanced compilation (in topiq)

## 0.1.1
   - simplify publication messages by breaking them apart and building on snapshot isolation
   - subscription filtering attempt
   
# Tasks to get your feet wet
- Port the tests from midje to `core.test` and [hydrox](https://github.com/helpshift/hydro).
- Introduce `clojure.spec` to stage/... API.
- Build a package for `npm`.
- Extend [topiq](https://github.com/replikativ/topiq) by some feature you'd like
  a social network to have.

# Roadmap

## 0.3.0
- Find exemplary ways to partition application data for efficient client side
  consumption, Datomic and Datascript. Look into datsync etc.
- allow to model some level of consistency between CRDTs to compose them (Antidote, research)
- Implement more useful CRDTs (counter, vector-clock, ...)
  from techreview and other papers and ship by default.
- Add a basic web toolbar for applications to communicate their synching state
  to the user in a uniform way.

## 0.4.0
- Java bindings
- Authentication with signed public-private key signatures
- Add block-level indirection to konserve. Needed to use fixed size binary blocks for
  quantifiable/tunable IO
- Use p2p block distribution similar to BitTorrent
- support WebRTC for torrent

## Long-term
- Drop publication with missing values and unsubscribe form CRDT in fetch middleware, allows peers to opt-out to partial replication.
- Encryption of transaction with CRDT key encrypted by userkeys, public key schema, explore pub/private key solutions. Maybe metadata signing can work (slowly) on a DHT?
- Distribute bandwidth between CRDTs.
- Negotiate middlewares with versioning.
- Provide example for durable undo and redo for `react`-like applications.
- Implement diverse prototypes, from real-time to "big-data".

## License

Copyright © 2013-2016 Christian Weilbach, Konrad Kühne

Distributed under the Eclipse Public License, the same as Clojure.
