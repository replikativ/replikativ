# replikativ

`replikativ` is a replication system for confluent replicated data types ([CRDTs](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf)). It is primarily designed to work as a decentralized database for web applications, but can be used to distribute any state durably between different peers with different runtimes (JVM, js atm.). Instead of programming thin web-clients around a central server/cloud, you operate on your local data like a native application both on client- and (if you wish to) server-side. You can also view it in reverse as a cloud being expanded to all end-points.
You can write to CRDTs whenever you want and also access values whenever you want no matter if the remote peer (server) is *available* or not. In combination with our [CDVCS](http://arxiv.org/abs/1508.05545) datatype you can imagine it as a `git` for data (expressed e.g. in [edn](https://github.com/edn-format/edn)) + automatic eventual consistent replication.

## Motivation and Vision

The web is still a bag of data silos (*places* in Rich Hickey's terms). Despite existing cooperation on source code, data rarely is shared cooperatively, because it is accessed through a single (mostly proprietary) service, which also is fed with inputs to 'update' the data (read: it has an *API*). This creates a single point of perception to decide upon writes, which at the same time has to be economically viable and hence locks the data in.

While sophisticated new functional databases like [Datomic](http://www.datomic.com/) promise scalable relational programming and access to all data for the service provider, they still do not fit for distributed data. A /single writer/ with a /singular notion of time/ is still required. *replikativ* tries to apply some lessons learned from these efforts, building foremost on /immutablity/, but applies them to a different spot in the spectrum of storage management. The goal of `replikativ` is to build a distributed web and edit data /collectively/, while still allowing the right to fork and dissent for anybody. In general distributed 'serverless' applications should be possible.

Experience with a [bittorrent integration for static, read-only data](http://kde-apps.org/content/show.php/kio-magnet?content=136909) in `KDE`, distributed systems like `git` and `mercurial`, [Votorola](http://zelea.com/project/votorola/home.html) as well as [HistoDB](https://github.com/mirkokiefer/syncing-thesis) have been inspirations. [CRDTs](http://hal.inria.fr/docs/00/55/55/88/PDF/techreport.pdf) have been introduced to allow carefree synching of metadata values without 'places'.

Github for example already resembles an open community using tools to produce source code, but it is still a central site (service) and does not apply to the web itself. `replikativ` uses *p2p* web-technologies, like *websockets* (and eventually *webrtc*), to globally exchange values. It can also make use of `IndexedDB` in the browser. It is supposed to be *functionally pure* (besides replication io) and runs on `Clojure/ClojureScript`(/ClojureX?). On the JVM and node.js it could also hook into existing distributed systems beyond the web.

The motivation is to share data openly and develop applications on shared well-defined datatypes carrying over the immutable value semantics of [Clojure](http://clojure.org/). This allows not only to fork code, but much more importantly to fork the data of applications and extend it in unplanned ways. Or phrased differently, the vision is to decouple data from the infrastructure and allow an open system of collaboration.
A tradeoff is that your application maybe has to support /after-the-fact/ conflict resolution, if you need the strong sequential semantics of CDVCS. This can be achieved either automatically, e.g. with strict relational data-models like [datascript](https://github.com/tonsky/datascript), or in some cases users can decide better how to resolve conflicts.


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


Our vision is more ambitious by creating open data systems instead of just optimizing the privatized Internet of data silos, but ideally these solutions are built first to solve the practical problems of distributed applications even if they are run by a single party. So if you just care about developing consistent and scaling web applications this should be the easiest solution to you, if not feel free to complain :).

## Usage <a href="https://gitter.im/replikativ/replikativ?utm_source=badge&amp;utm_medium=badge&amp;utm_campaign=pr-badge&amp;utm_content=badge"><img src="https://camo.githubusercontent.com/da2edb525cde1455a622c58c0effc3a90b9a181c/68747470733a2f2f6261646765732e6769747465722e696d2f4a6f696e253230436861742e737667" alt="Gitter" data-canonical-src="https://badges.gitter.im/Join%20Chat.svg" style="max-width:100%;"></a>

Add this to your project dependencies:
[![Clojars Project](http://clojars.org/io.replikativ/replikativ/latest-version.svg)](http://clojars.org/io.replikativ/replikativ)

Now lets get it running (taken from the examples folder):
~~~clojure
(ns dev.remote.core
  (:require [replikativ.crdt.cdvcs.realize :refer [head-value]]
            [replikativ.crdt.cdvcs.stage :as s]
            [replikativ.stage :refer [create-stage! connect! subscribe-crdts!]]
            [replikativ.peer :refer [client-peer server-peer]]

            [kabel.platform :refer [create-http-kit-handler! start stop]]
            [konserve.memory :refer [new-mem-store]]

            [full.async :refer [<?? <? go-try go-loop-try]] ;; core.async error handling
            [clojure.core.async :refer [chan go-loop go]]))

(def uri "ws://127.0.0.1:31744")

;; just keep it fixed here, otherwise a random uuid is created to avoid conflicts
(def cdvcs-id #uuid "8e9074a1-e3b0-4c79-8765-b6537c7d0c44")

;; we allow you to model the state efficiently as a reduction over function applications
;; for this to work you supply an "eval"-like mapping to the actual functions
(def eval-fns
  ;; the CRDTs are reduced over the transaction history according to this function mapping
  ;; NOTE: this allows you to change reduction semantics of past transactions as well
  {'(fn [_ new] new) (fn [_ new] new)
   '+ +})


;; create a local ACID key-value store
(def server-store (<?? (new-mem-store)))

;; collect errors
(def err-ch (chan))

;; and just print them to the REPL
(go-loop [e (<? err-ch)]
  (when e
    (println "ERROR:" e)
    (recur (<? err-ch))))

(def server (server-peer (create-http-kit-handler! uri err-ch)
                         "SERVER"
                         server-store
                         err-ch))

(start server)
(comment
  (stop server))

;; let's get distributed :)
(def client-store (<?? (new-mem-store)))

(def client (client-peer "CLIENT" client-store err-ch))

;; to interact with a peer we use a stage
(def stage (<?? (create-stage! "eve@replikativ.io" client err-ch)))

(<?? (connect! stage uri))

;; create a new CDVCS
(<?? (s/create-cdvcs! stage :description "testing" :id cdvcs-id))

;; prepare a transaction
(<?? (s/transact stage ["eve@replikativ.io" cdvcs-id]
                 ;; set a new value for this CDVCS
                 '(fn [_ new] new)
                 0))

;; commit it
(<?? (s/commit! stage {"eve@replikativ.io" #{cdvcs-id}}))


;; did it work locally?
(<?? (head-value client-store
                 eval-fns
                 ;; manually verify metadata presence
                 (:state (get @(:state client-store) ["eve@replikativ.io" cdvcs-id]))))
;; => 0

;; let's alter the value with a simple addition
(<?? (s/transact stage ["eve@replikativ.io" cdvcs-id]
                 '+ 1123))

;; commit it
(<?? (s/commit! stage {"eve@replikativ.io" #{cdvcs-id}}))

;; and did everything also apply remotely?
(<?? (head-value server-store
                 eval-fns
                 ;; manually verify metadata presence
                 (:state (get @(:state server-store) ["eve@replikativ.io" cdvcs-id]))))
;; => 1123
~~~

The ClojureScript API is the same, except that you cannot have blocking IO and cannot open a websocket server in the browser (but we have already WebRTC in mind ;) ):

~~~clojure
(ns dev.client.core
  (:require [konserve.memory :refer [new-mem-store]]
            [replikativ.peer :refer [client-peer]]
            [replikativ.stage :refer [create-stage! connect! subscribe-crdts!]]
            [replikativ.crdt.cdvcs.stage :as s]
            [cljs.core.async :refer [>! chan timeout]]
            [full.cljs.async :refer [throw-if-throwable]])
  (:require-macros [full.cljs.async :refer [go-try <? go-loop-try]]
                   [cljs.core.async.macros :refer [go-loop]]))

(def cdvcs-id #uuid "8e9074a1-e3b0-4c79-8765-b6537c7d0c44")

(def uri "ws://127.0.0.1:31744")

(enable-console-print!)

(def eval-fns
  {'(fn [old params] params) (fn [old params] params)
   '+ +})

(defn start-local []
  (go-try
   (let [local-store (<? (new-mem-store))
         err-ch (chan)
         local-peer (client-peer "CLJS CLIENT" local-store err-ch)
         stage (<? (create-stage! "eve@replikativ.io" local-peer err-ch))
         _ (<? (s/create-cdvcs! stage :description "testing" :id cdvcs-id))
         _ (go-loop [e (<? err-ch)]
             (when e
               (.log js/console "ERROR:" e)
               (recur (<? err-ch))))]
     {:store local-store
      :stage stage
      :error-chan err-ch
      :peer local-peer})))


(go-try
   (def client-state (<? (start-local)))
   (<? (connect! (:stage client-state) uri))
   (<? (s/transact (:stage client-state)
                   ["eve@replikativ.io" cdvcs-id]
                   '(fn [old params] params)
                   666))

   (<? (s/commit! (:stage client-state) {"eve@replikativ.io" #{cdvcs-id}}))
~~~

For more detailed examples have [a look at the tests for the pull-hooks as well](https://replikativ.github.io/replikativ/hooks.html).

A full-blown prototype application in combination with [datascript](https://github.com/tonsky/datascript) and [Om](https://github.com/omcljs/om), with an example deployment, can be found here: [topiq](https://github.com/whilo/topiq). In an experimental and slightly outdated project, we have also used replikativ for big [hdf5 binary blob synchronisation with datomic and analysis with gorilla](https://github.com/whilo/cnc).

The API docs are [here](https://replikativ.github.io/replikativ/doc/index.html).

## Alternatives

A interesting alternative about whom we only found out last year is
[swarm.js](https://github.com/gritzko/swarm). Besides many
commonalities there are a few differences, so we could not just drop
`replikativ` and join `swarm.js`. To our understanding the `swarm.js`
authors are not sharing the vision of open data exchange and CRDTs are
not mapped in a global namespace which can be distributed without
conflicts. Additionally `replikativ` does not use a pure op-based
replication (swarm.js only uses an efficient state CvRDT
representation on the initial fetch), but otherwise replays all
operations, which can be very inefficient if there is a lot of dead
history (e.g. for a LWWR). `replikativ` is also as host agnostic as
Clojure and not a pure JavaScript implementation, which has to
unconditionally accept all the weaknesses of JavaScript. So we can use
the strong value semantics of Clojure to decouple our design. You can
also use a Java runtime wherever you prefer to do so :).

We hope to be able to meet the authors of `swarm.js` somewhen soon and
discuss some of these issues as we still like their work. There are
many other CRDT implementations of course, e.g. in `riak`, but they
only losely relate to our approach to expand the network to the
endpoints.

## Design

`replikativ` consists of two parts, a core of CRDTs, especially a newly crafted one for the [git-like CDVCS datatype](http://arxiv.org/abs/1508.05545) in the `replikativ.crdt.cdvcs` namespaces, and a generic replication protocol for CRDTs in `replikativ.core` and some middlewares. The replication can be externally extended to any CRDT (as long as all connected peers support it then). We will provide as many implementations as possible by default for the open, global replication system. Together the CRDTs and the replication provides conflict-free convergent replication. The datatypes decouple resolution of application level state changes from replication over a network.

The replication protocol partitions the global state space into user specific places for CRDTs, `[user-id crdt-id]`. All replication happens between these places. All peers are supposed to automatically replicate CRDTs of each user they subscribe to.

We make heavy use of [core.async](https://github.com/clojure/core.async) to model peers platform- and network-agnostic just as peers having a pair of messaging channels from [kabel](https://github.com/replikativ/kabel) for `edn` messages. We build on platform-neutral durable storage through [konserve](https://github.com/replikativ/konserve). At the core is a `pub-sub` scheme between peers, but most functionality is factored into `middlewares` filtering and tweaking the in/out channel pair of each peers pub-sub core. This allows decoupled extension of the network protocol.

For a detailed documentation of the CDVCS implementation you can have a look at the [introduction](https://replikativ.github.io/replikativ/). Or to understand the [pub-sub message protocol for replication](https://replikativ.github.io/replikativ/replication.html).


## JavaScript

It is supposed to work from JavaScript as well, ping us and we will have a look what is necessary to make interop more painfree if you have problems.

*Any help or patches are very welcome :-)*

# Roadmap

- Add authentication to kabel and then to replikativ
- Implement useful CRDTs (LWW-register, OR-set, counter, vector-clock, ...) from techreview and other papers and ship by default.
- Improve error-handling and handle reconnections gracefully. [WIP with PR pending, already in full.monty]
- Drop publication with missing values and unsubscribe form CRDT in fetch middleware, allows peers to opt-out to partial replication.
- Encryption of transaction with repo key encrypted by userkeys, public key schema, explore pub/private key solutions. Maybe metadata signing can work (slowly) on a DHT?
- Introduce strong typing with `core.typed`.
- Make usage from JavaScript straightforward (including JSON values). Browser and nodejs.
- Limit inline value size, avoid pulling huge fetched values in memory.
- Distribute bandwidth between CRDTs.
- Negotiate middlewares with versioning.
- Add a basic web toolbar for applications to communicate their synching state to the user in a uniform way.
- Provide example for durable undo and redo for `react`-like applications.
- Implement diverse prototypes, from real-time to "big-data".

## License

Copyright © 2013-2016 Christian Weilbach
Copyright © 2015 Konrad Kühne

Distributed under the Eclipse Public License, the same as Clojure.
