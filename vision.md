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

