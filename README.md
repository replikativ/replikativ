# replikativ [![CircleCI](https://circleci.com/gh/replikativ/replikativ.svg?style=svg)](https://circleci.com/gh/replikativ/replikativ) <a href="https://gitter.im/replikativ/replikativ?utm_source=badge&amp;utm_medium=badge&amp;utm_campaign=pr-badge&amp;utm_content=badge"><img src="https://camo.githubusercontent.com/da2edb525cde1455a622c58c0effc3a90b9a181c/68747470733a2f2f6261646765732e6769747465722e696d2f4a6f696e253230436861742e737667" alt="Gitter" data-canonical-src="https://badges.gitter.im/Join%20Chat.svg" style="max-width:100%;"></a>

A distributed replication system for CRDTs (Conflict-free Replicated Data Types) that enables serverless, peer-to-peer data synchronization between Clojure and ClojureScript applications.

[Project homepage](https://github.com/replikativ/replikativ) | [API Documentation](doc/index.html) | [Changelog](./CHANGELOG.md)

## Features

- **Cross-platform**: Works seamlessly between JVM (Clojure) and JavaScript (ClojureScript/Node.js)
- **Multiple CRDTs**: CDVCS (git-like), ORMap, LWWR (Last-Writer-Wins Register), SimpleGSet
- **P2P synchronization**: Direct peer-to-peer replication without central authority
- **Composable**: Built on core.async with middleware architecture

## Development Setup

### Prerequisites

- [Clojure CLI tools](https://clojure.org/guides/install_clojure) (1.11.1+)
- Java 11+ (for JVM development)
- Node.js 18+ (for ClojureScript/integration tests)

### Dependencies

This project uses `deps.edn` for dependency management. All dependencies will be automatically downloaded when running commands.

Key dependencies:
- `io.replikativ/kabel` - Network layer for peer communication
- `io.replikativ/konserve` - Storage abstraction
- `io.replikativ/superv.async` - Supervised async programming
- `thheller/shadow-cljs` - ClojureScript compiler

**Important:** After cloning the repository or updating git dependencies, run:

```bash
clj -X:deps prep
```

This prepares git dependencies (like kabel) for use. Required before running other commands.

### Development Workflow

Start a REPL with development dependencies:

```bash
clojure -M:dev
```

Or connect your editor (Emacs/Cider, Cursive, VSCode/Calva) using the `:dev` alias.

## Testing

### Running Clojure Tests

Run all Clojure tests on the JVM:

```bash
clojure -M:test
```

Or run specific test namespaces:

```bash
clojure -M:test -n replikativ.cdvcs-test
```

### Running ClojureScript Tests

The project uses shadow-cljs for ClojureScript compilation and testing.

### Cross-Platform Integration Tests

Test the full stack with JVM server + Node.js client:

```bash
./test-integration.sh
```

This script:
1. Starts a JVM server hosting test CRDTs
2. Compiles ClojureScript tests for Node.js
3. Runs Node.js client tests that sync with the server
4. Automatically cleans up processes

The integration test verifies:
- Cross-platform CRDT synchronization
- LWWR replication between JVM and Node.js
- WebSocket connection handling

### Manual ClojureScript Compilation

Compile for different targets:

```bash
# Node.js (for integration tests)
clojure -M:shadow-cljs compile integration

# Browser development build
clojure -M:shadow-cljs compile browser-simple

# Browser production build (advanced optimization)
clojure -M:shadow-cljs compile browser-advanced

# Watch mode for development
clojure -M:shadow-cljs watch dev
```

## Building

### Library JAR

Build the library JAR for distribution:

```bash
clojure -T:jar jar
```

This creates `target/replikativ-0.2.5.jar`.

**Install locally to ~/.m2:**

```bash
clojure -T:jar install
```

**Deploy to Clojars (maintainers only):**

```bash
# Requires CLOJARS_USERNAME and CLOJARS_PASSWORD env vars
clojure -T:jar deploy
```

### API Documentation

Generate API documentation with Codox:

```bash
clojure -X:codox
```

This generates HTML documentation in the `doc/` directory. Open `doc/index.html` in a browser to view the API docs.

## Project Structure

```
replikativ/
├── src/replikativ/          # Core library code
│   ├── crdt/                # CRDT implementations
│   │   ├── cdvcs/          # Git-like CRDT
│   │   ├── lwwr/           # Last-Writer-Wins Register
│   │   ├── ormap/          # Observed-Remove Map
│   │   └── simple_gset/    # Grow-only Set
│   ├── p2p/                # P2P middleware (fetch, hooks)
│   ├── peer.cljc           # Peer management
│   ├── stage.cljc          # High-level API
│   └── core.cljc           # Replication protocol
├── test/                    # Test files
│   └── replikativ/
│       ├── integration_test.cljs      # Cross-platform tests
│       └── integration_server.clj     # Test server
├── deps.edn                # Project dependencies
├── shadow-cljs.edn         # ClojureScript build config
└── test-integration.sh     # Integration test runner
```

## Getting Started

### Installation

Add replikativ to your `deps.edn`:

```clojure
{:deps {io.replikativ/replikativ {:git/url "https://github.com/replikativ/replikativ"
                                  :git/sha "LATEST-SHA-HERE"}
        io.replikativ/konserve {:mvn/version "0.8.321"}}}
```

Or with Leiningen in `project.clj`:

```clojure
:dependencies [[io.replikativ/replikativ "0.2.5"]
               [io.replikativ/konserve "0.8.321"]]
```

### Core Concepts

**Peer** - A network node that can synchronize CRDTs with other peers. Each peer has:
- A storage backend (konserve) for persistence
- Network handlers for communication (via kabel)
- Middleware for handling replication (fetch, hooks)

**Stage** - Your working area for CRDT operations. Think of it as a "workspace" where you:
- Create and modify CRDTs locally
- Subscribe to CRDTs from other peers
- Trigger synchronization

**CRDT** - Conflict-free Replicated Data Type. Data structures that can be modified independently on different peers and always converge to the same state when synced.

### Complete Example: Syncing Data Between Two Peers

#### Server Side (JVM)

```clojure
(ns my-app.server
  (:require [replikativ.peer :refer [server-peer]]
            [replikativ.stage :refer [create-stage!]]
            [replikativ.crdt.lwwr.stage :as lwwr]
            [konserve.memory :refer [new-mem-store]]
            [kabel.peer :refer [start]]
            [superv.async :refer [<?? S]]))

(defn start-server! []
  (let [;; Create storage
        store (<?? S (new-mem-store))

        ;; Create peer with WebSocket endpoint
        peer (<?? S (server-peer S store "ws://localhost:47297"))

        ;; Create stage for this user
        stage (<?? S (create-stage! "alice@example.com" peer))

        ;; Start accepting connections
        _ (start peer)

        ;; Create a shared CRDT with initial value
        lwwr-id #uuid "550e8400-e29b-41d4-a716-446655440000"]

    (<?? S (lwwr/create-lwwr! stage
                              :id lwwr-id
                              :description "Shared counter"
                              :init-val {:counter 0}))

    (println "Server started on ws://localhost:47297")
    (println "Hosting LWWR:" lwwr-id)

    {:peer peer :stage stage :lwwr-id lwwr-id}))
```

#### Client Side (JVM or Node.js via ClojureScript)

```clojure
(ns my-app.client
  (:require [replikativ.peer :refer [client-peer]]
            [replikativ.stage :refer [create-stage! connect!]]
            [replikativ.crdt.lwwr.stage :as lwwr]
            [replikativ.crdt.lwwr.realize :refer [stream-into-atom!]]
            [konserve.memory :refer [new-mem-store]]
            [kabel.peer :refer [stop]]
            [superv.async :refer [<?? S]]))

(defn start-client! []
  (let [;; Create storage
        store (<?? S (new-mem-store))

        ;; Create client peer (no server endpoint)
        peer (<?? S (client-peer S store))

        ;; Create stage
        stage (<?? S (create-stage! "bob@example.com" peer))

        ;; Use the same CRDT ID as server
        lwwr-id #uuid "550e8400-e29b-41d4-a716-446655440000"
        user "alice@example.com"

        ;; Create local CRDT (required before streaming)
        _ (<?? S (lwwr/create-lwwr! stage :id lwwr-id))

        ;; Set up reactive atom to receive updates
        val-atom (atom nil)]

    ;; Stream CRDT changes into atom
    (stream-into-atom! stage [user lwwr-id] val-atom)

    ;; Connect to server - triggers initial sync
    (<?? S (connect! stage "ws://localhost:47297"))

    (println "Connected to server!")
    (println "Current value:" @val-atom)
    ;; => {:counter 0}

    ;; Modify the CRDT - automatically syncs to server
    (<?? S (lwwr/set-register! stage [user lwwr-id]
                               {:counter (inc (:counter @val-atom))}))

    (Thread/sleep 100) ;; Wait for sync
    (println "Updated value:" @val-atom)
    ;; => {:counter 1}

    ;; The atom automatically updates when server changes it too!

    {:peer peer :stage stage :val-atom val-atom}))
```

### Choosing the Right CRDT

| CRDT | Use Case | Key Feature | Example |
|------|----------|-------------|---------|
| **LWWR** | Simple shared state | Last write wins, no conflicts | User preferences, feature flags, simple counters |
| **CDVCS** | Version-controlled data | Git-like with branches & merges | Document editing, code repositories, audit trails |
| **ORMap** | Collaborative maps | Add/remove keys concurrently | Shopping cart, user directory, metadata |
| **SimpleGSet** | Accumulating collections | Grow-only set | Tags, categories, event logs |

**Rule of thumb:**
- Start with **LWWR** for simple use cases
- Use **CDVCS** when you need version history
- Use **ORMap** for collaborative key-value editing
- Use **SimpleGSet** when you never need to remove items

### More Examples

See the integration tests for complete working examples:
- [test/replikativ/integration_test.cljs](test/replikativ/integration_test.cljs) - Full cross-platform sync example
- [test/replikativ/integration_server.clj](test/replikativ/integration_server.clj) - Server setup
- [test/replikativ/lwwr_test.clj](test/replikativ/lwwr_test.clj) - LWWR operations
- [test/replikativ/cdvcs_test.clj](test/replikativ/cdvcs_test.clj) - Version control patterns

For more detailed documentation, see the [API documentation](doc/index.html) or visit the [project homepage](https://github.com/replikativ/replikativ).
  
# Roadmap (suggestions)

## 0.3.0
- Investigate JS side integration of http://y-js.org/
- Investigate integration with similar systems, eg. IPFS pubsub 
- Split middleware from replicated datatype implementations
- Improve network IO library kabel (Android support) [DONE]
- Move hashing into fetch middleware to simplify parallelization. [DONE]
- Experimental automatic Gossip protocol
- Experimental Snapshot Isolation
- Build reasonable small support libraries to partition application data for
  efficient client side consumption, Datomic and Datascript. Look into datsync
  etc.
- Add a monitoring interface as a cljs library with basic web views for
  applications to communicate their synching state to the user in a uniform way. [DONE]
- Introduce `clojure.spec` to stage/... API.
  
## 0.4.0
- Authentication with signed public-private key signatures
- Model some level of consistency between CRDTs, probably Snapshot Isolation, to
  compose CRDTs. (NMSI, Antidote, research)
- Implement more useful CRDTs (counter, vector-clock, ...)
  from techreview and other papers and ship by default.

## 0.5.0
- Use p2p block distribution similar to BitTorrent for immutable values (similar
  to blocks)
- support WebRTC for value distribution similar to BitTorrent
- Java bindings

## Long-term (1.0.0)
- Encryption of transaction with CRDT key encrypted by userkeys, public key
  schema, explore pub/private key solutions. Maybe metadata signing can work
  (slowly) on a DHT?
- Distribute bandwidth between CRDTs.
- Negotiate middlewares with versioning.
- Implement diverse prototypes, from real-time to "big-data".

## Contributors

- Konrad Kuehne
- Christian Weilbach

## Support

If you would like to get some commercial support for replikativ, feel free to
contact us at [lambdaforge](http://lambdaforge.io).

## License

Copyright © 2013-2018 Christian Weilbach, Konrad Kühne

Distributed under the Eclipse Public License, the same as Clojure.
