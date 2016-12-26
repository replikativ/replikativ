### Related Work

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

