# replikativ <a href="https://gitter.im/replikativ/replikativ?utm_source=badge&amp;utm_medium=badge&amp;utm_campaign=pr-badge&amp;utm_content=badge"><img src="https://camo.githubusercontent.com/da2edb525cde1455a622c58c0effc3a90b9a181c/68747470733a2f2f6261646765732e6769747465722e696d2f4a6f696e253230436861742e737667" alt="Gitter" data-canonical-src="https://badges.gitter.im/Join%20Chat.svg" style="max-width:100%;"></a>

`replikativ` is a replication system for confluent replicated data types
([CRDTs](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf)). You can
think about them as durable eventual consistent persistent datastructures.
replikativ is primarily designed to work as a decentralized database for web
applications, but can be used to distribute any state durably between different
peers with different runtimes (JVM, js atm.). Instead of programming thin
web-clients around a central server/cloud, you operate on your local data like a
native application both on client- and (if you wish to) server-side. You can
also view it in reverse as a cloud being expanded to all end-points. You can
write to CRDTs whenever you want and also access values whenever you want no
matter if the remote peer(s) is *available* or not. In combination with
our [CDVCS](http://arxiv.org/abs/1508.05545) datatype you can also use it as
`git` for data (expressed e.g. in [edn](https://github.com/edn-format/edn)) +
automatic eventual consistent replication.

## Quickstart
Add this to your project dependencies:
[![Clojars Project](http://clojars.org/io.replikativ/replikativ/latest-version.svg)](http://clojars.org/io.replikativ/replikativ)

~~~clojure
(ns replikativ.ormap-demo
  (:require [superv.async :refer [<?? S]]
            [kabel.peer :refer [start stop]]
            [konserve
             [filestore :refer [new-fs-store]]
             [memory :refer [new-mem-store]]]
            [replikativ
             [peer :refer [server-peer]]
             [stage :refer [connect! create-stage!]]]
            [replikativ.crdt.ormap.stage :as ors]))

(def user "mail:prototype@your-domain.com") ;; will be used to authenticate you (not yet)
(def ormap-id #uuid "7d274663-9396-4247-910b-409ae35fe98d") ;; application specific datatype address

(def store-a (<?? S (new-fs-store "/tmp/test"))) ;; durable store
(def peer-a (<?? S (server-peer S store-a "ws://127.0.0.1:9090"))) ;; network and file IO
(<?? S (start peer-a))
(def stage-a (<?? S (create-stage! user peer-a))) ;; API for peer
(<?? S (ors/create-ormap! stage-a :id ormap-id))

(def store-b (<?? S (new-mem-store))) ;; store for testing
(def peer-b (<?? S (server-peer S store-b "ws://127.0.0.1:9091")))
(<?? S (start peer-b))
(def stage-b (<?? S (create-stage! user peer-b)))
(<?? S (ors/create-ormap! stage-b :id ormap-id))

;; now you are set up

;; for this datatype metadata and commit data is separated
;; [['store :bars]] is encoding a function application to apply to some local state
(<?? S (ors/assoc! stage-b [user ormap-id] :foo [['assoc :bars]]))
(<?? S (ors/get stage-b [user ormap-id] :foo))

(<?? S (connect! stage-a "ws://127.0.0.1:9091")) ;; wire the peers up

(<?? S (ors/get stage-a [user ormap-id] :foo)) ;; do they converge?
;; accordingly we provide a dissoc operation on removal
(<?? S (ors/dissoc! stage-a [user ormap-id] :foo [['dissoc :bars]])) 
;; play around :)

;; ...

(<?? S (stop peer-a))
(<?? S (stop peer-b))
~~~
The ClojureScript API is the same, except that you cannot have blocking IO and cannot open a websocket server in the browser (but we have already WebRTC in mind ;) ):


## Projects using replikativ

- [clj demo project for CDVCS](https://github.com/replikativ/replikativ-demo)
  and the corresponding
  [cljs adder demo project](https://github.com/replikativ/replikativ-cljs-demo).
- [twitter-collector](https://github.com/replikativ/twitter-collector) A tweet
  collector to stream large amounts of tweets into Datomic.
- [topiq](https://github.com/replikativ/topiq), a blend of Twitter and Reddit
  exploiting the full state replication on web-client side with a simple server
  peer acting as a hub connecting clients.
- [cnc](https://github.com/whilo/cnc) In an experimental and outdated project,
we have also used replikativ for big hdf5 binary blob synchronisation with
datomic and analysis with gorilla.
- If you build some prototype, ping back and I will add it here.

## Performance

The initial prototypes were unoptimized and only worked well up to a few
thousand write operations. Since `0.2.0-beta2` we use an extended storage
protocol with append-only logs and now have a fairly fast local eventual
consistent database. For the OR-Map with small values an `or-assoc` operation
roughly takes ~10 ms on my Laptop, which is approximately the IO cost (if you
fsync). We have further evaluated the performance of `replikativ` by constantly
writing tweets into CDVCS and synchronizing it with a laptop from day to day for
realtime analysis
with [twitter-collector](https://github.com/replikativ/twitter-collector). The
system scaled up to weeks of usage, 100,000s of write operations and more than
10 GiB of managed data (a commit every 1-2s). We anticipate that the system
scales further, but for now we support these performance numbers for all CRDTs
with non-inline (i.e. not in the metadata) values (CDVCS, OR-Map, LWWR) for now.
We also realized 1000 write operations per second on a single peer for very small
transactions if you properly batch them (e.g. every 10 ms).

We are interested in performance measures of real world applications to iron
`replikativ` out as a solid default storage in front of business focused
databases for fast domain specific queries. Ideally it will work similar to
Apache Kafka or Apache Samza as a fairly care-free primary storage system
building materialized views on streams of CRDTs, but in a decentralized and more
decoupled fashion.


### Garbage Collection

The append logs used as well as referenced values by CRDTs you are no longer
interested in generate garbage. We don't have an automatic garbage collection
mechanism yet, but it is straightforward to start a second peer with a new
store, sync it and then replace the old one with it. You can then safely remove
the store of the old peer.



## [Motivation and vision](motivation.md)

## [Related work](related_work.md)

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

The API docs are [here](https://replikativ.github.io/replikativ/doc/index.html).

## [JavaScript](javascript.md)

# Changelog

## 0.2.0-beta3
   - port to supervised async
   - fix reconnection in Clojure

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
   
# Roadmap

## 0.3.0
- Authentication with signed public-private key signatures
- Add a logical clock mechanism to the middleware (peers) for Snapshot Isolation
- Find more exemplary ways to partition application data for efficient client
  side consumption, Datomic and Datascript. Look into datsync etc.
- Introduce `clojure.spec` to stage/... API.
  
## 0.4.0
- Model some level of consistency between CRDTs, probably Snapshot Isolation, to
  compose CRDTs. (Antidote, research)
- Implement more useful CRDTs (counter, vector-clock, ...)
  from techreview and other papers and ship by default.

## 0.4.0
- Java bindings
- Add block-level indirection to konserve. Needed to use fixed size binary blocks for
  quantifiable/tunable IO

## 0.5.0
- Add a monitoring interface with a basic web toolbar for applications to
  communicate their synching state to the user in a uniform way.
- Use p2p block distribution similar to BitTorrent for immutable values (similar to blocks)
- support WebRTC for torrent

## Long-term (1.0.0)
- Drop publication with missing values and unsubscribe form CRDT in fetch
  middleware, allows peers to opt-out to partial replication.
- Encryption of transaction with CRDT key encrypted by userkeys, public key
  schema, explore pub/private key solutions. Maybe metadata signing can work
  (slowly) on a DHT?
- Distribute bandwidth between CRDTs.
- Negotiate middlewares with versioning.
- Provide example for durable undo and redo for `react`-like applications.
- Implement diverse prototypes, from real-time to "big-data".

## Contributors

- Konrad Kuehne
- Christian Weilbach

## License

Copyright © 2013-2016 Christian Weilbach, Konrad Kühne

Distributed under the Eclipse Public License, the same as Clojure.
