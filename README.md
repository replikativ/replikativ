# replikativ [![CircleCI](https://circleci.com/gh/replikativ/replikativ.svg?style=svg)](https://circleci.com/gh/replikativ/replikativ) <a href="https://gitter.im/replikativ/replikativ?utm_source=badge&amp;utm_medium=badge&amp;utm_campaign=pr-badge&amp;utm_content=badge"><img src="https://camo.githubusercontent.com/da2edb525cde1455a622c58c0effc3a90b9a181c/68747470733a2f2f6261646765732e6769747465722e696d2f4a6f696e253230436861742e737667" alt="Gitter" data-canonical-src="https://badges.gitter.im/Join%20Chat.svg" style="max-width:100%;"></a>

[Project homepage](http://replikativ.io)

# [Changelog](./CHANGELOG.md)
  
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
