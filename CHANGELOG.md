# Changelog


## 0.2.4
   - BREAKING: commit hashes of OR-Map have changed
   - support high throughput binary protocol
   - remove hash middleware and hash in fetching

## 0.2.3
   - fix a bug in kabel middleware initialization
   - draft a new merging map CRDT

## 0.2.2
   - JS API WIP
   - some fixes
   - bump deps

## 0.2.1
   - use fixed deps, kabel fixing race condition

## 0.2.0
   - bump deps

## 0.2.0-rc1
   - feature complete
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
 
