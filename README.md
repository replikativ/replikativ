# replikativ <a href="https://gitter.im/replikativ/replikativ?utm_source=badge&amp;utm_medium=badge&amp;utm_campaign=pr-badge&amp;utm_content=badge"><img src="https://camo.githubusercontent.com/da2edb525cde1455a622c58c0effc3a90b9a181c/68747470733a2f2f6261646765732e6769747465722e696d2f4a6f696e253230436861742e737667" alt="Gitter" data-canonical-src="https://badges.gitter.im/Join%20Chat.svg" style="max-width:100%;"></a>

[Project homepage](http://replikativ.io)

`replikativ` is a replication system for confluent replicated data types
([CRDTs](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf)). Simply
spoken you can imagine them as durable eventual consistent persistent
datastructures. replikativ is primarily designed to work as a decentralized
database for distributed applications including web and app clients, but can be
used to distribute any state durably between different peers with different
runtimes (JVM, js atm.) locally and globally. Instead of programming thin
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
;; [['assoc :bars]] is encoding a user-defined function application 
;; of 'store to apply to some local state
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

- [chat42](https://github.com/replikativ/chat42) A minimal web-chat example.
- [clj demo project for CDVCS](https://github.com/replikativ/replikativ-demo)
  and the corresponding
  [cljs adder demo project](https://github.com/replikativ/replikativ-cljs-demo).
- [twitter-collector](https://github.com/replikativ/twitter-collector) A tweet
  collector to stream large amounts of tweets into local instances of Datomic.
- [filesync-replikativ](https://github.com/replikativ/filesync-replikativ) A
  prototype file synchronization daemon similar to git or dropbox, automatically
  synchronizing a folder of the filesystem into replikativ.
- [topiq](https://github.com/replikativ/topiq) A blend of Twitter and Reddit, 
  exploiting the full state replication on web-client side with a simple server
  peer acting as a hub connecting clients.
- [cnc](https://github.com/whilo/cnc) In an experimental and outdated project,
we have also used replikativ for big hdf5 binary blob synchronisation with
datomic and analysis with gorilla.
- If you build some prototype, ping back and I will add it here.

# [Changelog](./CHANGELOG.md)
  
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
