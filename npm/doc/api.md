# API

## Core Functions

Relying heavily on async communication, many core functions return promises that provide a given replikativ entity on success and an Javascript Error on error.

<a id="newMemStore"></a>
### `newMemStore()`

Creates an immutable in-memory key-value store. 

#### Returns

A Promise that provides an in-memory store.

#### Example

```
let newStore;
newMemStore().then(function(store) {
  newStore = store; 
}, function(error) {
  console.error(error)
});
```

<a id="clientPeer"></a>
### `clientPeer(store)`

Creates a client-side peer only that streams into a given key-value store.

#### Arguments

- `store`: a key value store, preferably a [konserve](https://github.com/replikativ/konserve) memory store in the browser.

#### Returns

A Promise that provides a replikativ client peer.

#### Example

```
const store = someKVStore(); // make sure the store exists at this point
let newPeer;

clientPeer(store).then(function(peer) {
  newPeer = peer;
}, function(error) {
  console.error(error);
});
```

<a id="createStage"></a>
### `createStage(userId, peer)`

Creates a stage for given user ID and peer.

#### Arguments

- `userId`: user identifier, usually in the form of `"mail:alice@replikativ.io"`
- `peer`: a replikativ peer

#### Returns

A Promise that provides a replikativ stage.

#### Example

```
const peer = someReplikativPeer(); // make sure the peer exists at this point
let newStage;
const userId = "mail:alice@replikativ.io";

createStage(userId, peer).then(function(stage) {
  newStage = stage;
}, function(error) {
  console.error(error);
});
```

<a id="connect"></a>
### `connect(stage, uri)`

Connects a `stage` to another peer by `uri` via websockets.

#### Arguments
- `stage`: a replikativ stage
- `uri`: a websocket uri that identifies another running replikativ peer, for example if you have a local peer: `"ws://127.0.0.1:31778"`

#### Returns

A Promise that provides errors if connection fails.

#### Example

```
const stage = someReplikativStage(); // make sure the peer exists at this point
const uri = "ws://127.0.0.1:31778";

connect(stage, uri).then(function() {
  console.info("Connected to peer" + uri);
}, function(error) {
  console.error(error);
});
```

<a id="LWWR"></a>
## `LWWR`

Provides core functions for LWWR crdt.

<a id="LWWR-create"></a>
### `create`

<a id="LWWR-stream"></a>
### `stream`

<a id="LWWR-set"></a>
### `set`

<a id="ORMap"></a>
## `ORMap`

<a id="ormap-create"></a>
### `create`

<a id="ormap-stream"></a>
### `stream`

<a id="ormap-associate"></a>
### `associate`

<a id="helper-functions"></a>
## Helper Functions

Some helper functions from our environment that eases interaction with our system.

<a id="createUUID"></a>
### `createUUID(idString)`

<a id="toEdn"></a>
### `toEdn(jsObject)`

<a id="hashIt"></a>
### `hashIt(jsObject)`
