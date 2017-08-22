# API

## Core Functions

Relying heavily on async communication, many core functions return promises that provide a given replikativ entity on success and an Javascript Error on error.

<a id="newMemStore"></a>
### `newMemStore()`

Creates an immutable in-memory key-value store. 

#### Returns

A Promise that provides an in-memory store.

#### Example

```javascript
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

```javascript
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

```javascript
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

```javascript
const stage = someReplikativStage(); // make sure the stage exists at this point
const uri = "ws://127.0.0.1:31778";

connect(stage, uri).then(function() {
  console.info("Connected to peer" + uri);
}, function(error) {
  console.error(error);
});
```

<a id="LWWR"></a>
## `LWWR`

Provides core functions for Last Writer Wins Register.

<a id="LWWR-create"></a>
### `createLWWR(stage, options)`

Creates a new LWWR given a local stage and options.

#### Arguments

- `stage`: a replikativ stage
- `options`: a JS Object that sets different optional values for following keys:
  - `id`: CRDT identifier, usually a Clojurescript UUID provided by our helper function: `createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a")`
  - `description`: a short description for this crdt

#### Returns

A promise that provides a replikativ LWWR.

#### Example

```javascript
const stage = someReplikativStage(); // make sure the stage exists at this point
const options = {
  id: createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a"),
  description: "some nice description"
}

createLWWR(stage, options).then(function() {
  console.info("LWWR created");
}, function(error) {
  console.error(error);
});

```

<a id="LWWR-stream"></a>
### `streamLWWR(stage, userId, lwwrUUID, streamingCallback)`
Streams the LWWR given a stream interaction callback.

#### Arguments
- `stage`: a replikativ stage
- `userId`: user identifier, usually in the form of `"mail:alice@replikativ.io"`
- `lwwrUUID`: LWWR identifier, usually a Clojurescript UUID provided by our helper function: `createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a")`
- `streamingCallback`: a callback function with the new register state as argument

#### Returns
Nothing.

#### Example

```javascript
const stage = someReplikativStage(); // make sure the stage exists at this point
const userId = "mail:alice@replikativ.io";
const lwwrUUID = createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a");
const streamingCallback = function(newState) {
  console.info(newState);
}

streamLWWR(stage, userId, lwwrUUID, streamingCallback);
```

<a id="LWWR-set"></a>
### `setLWWR(stage, userId, lwwrUUID, value)`

Sets a Last Writer Wins Register to new value.

#### Arguments

- `stage`: a replikativ stage
- `userId`: user identifier, usually in the form of `"mail:alice@replikativ.io"`
- `lwwrUUID`: LWWR identifier, usually a Clojurescript UUID provided by our helper function: `createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a")`
- `value`: a new js value, might be an Object or another primitive

#### Returns

A Promise that provides possible errors.

#### Example

```javascript
const stage = someReplikativStage(); // make sure the stage exists at this point
const userId = "mail:alice@replikativ.io";
const lwwrUUID = createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a");
const newValue = {counter: 1};

setLWWR(stage, userId, lwwrUUID, newValue).then(function() {
  console.info("Register sucessfully set!");
}, function(error) {
  console.error(error);
});
```

<a id="ORMap"></a>
## `ORMap`

Provides core functions for Observed Remove Map.

<a id="ormap-create"></a>
### `createORMap(stage, options)`

Creates a new OR-Map given a local stage and options.

#### Arguments

- `stage`: a replikativ stage
- `options`: a JS Object that sets different optional values for following keys:
  - `id`: CRDT identifier, usually a Clojurescript UUID provided by our helper function: `createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a")`
  - `description`: a short description for this crdt

#### Returns

A promise that provides a replikativ OR-Map.

#### Example

```javascript
const stage = someReplikativStage(); // make sure the stage exists at this point
const options = {
  id: createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a"),
  description: "some nice description"
}

createORMap(stage, options).then(function() {
  console.info("LWWR created");
}, function(error) {
  console.error(error);
});

```


<a id="ormap-stream"></a>
### `streamORMap(stage, userId, ormapUUID, evalFunctions, target)`
Streams the OR-Map changes into given target object and provided evaluation functions.

#### Arguments
- `stage`: a replikativ stage
- `userId`: user identifier, usually in the form of `"mail:alice@replikativ.io"`
- `ormapUUID`: OR-Map identifier, usually a Clojurescript UUID provided by our helper function: `createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a")`
- `evalFunctions`: a plain Object that holds evaluation functions, that could be applied to the target object, similar to update functions on react local state
- `target`: target Object that receives updates

#### Returns

Nothing.

#### Example

```javascript
const stage = someReplikativStage(); // make sure the stage exists at this point
const userId = "mail:alice@replikativ.io";
const ormapId = createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a");
const evalFunctions = {"add": function(supvervisor, old, params) {
  return {
  message: params
  }}
}

let target = {counter: 1};

streamORMap(stage, userId, ormapId, evalFunctions, target);
```

<a id="ormap-associate"></a>
### `associateORMap(stage, userId, ormapUUID, key, transactions)`

Associates a key of an OR-Map with a new value by applying a transaction.

#### Arguments

- `stage`: a replikativ stage
- `userId`: user identifier, usually in the form of `"mail:alice@replikativ.io"`
- `ormapUUID`: OR-Map identifier, usually a Clojurescript UUID provided by our helper function: `createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a")`
- `key`: a key in the OR-Map
- `transactions`: transaction list that is applied to the value of the OR-Map, has to be in the form of [["eval-function", "value"]], where the "eval-function" need to be defined in the eval-functions object provided by the user to the stream function.

#### Returns
Promise that provides no new data.

#### Example

```javascript
const stage = someReplikativStage(); // make sure the stage exists at this point
const userId = "mail:alice@replikativ.io";
const ormapId = createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a");

associateORMap(stage, userId, ormapId, "message", ["add", "hello replikativ!"]).then(function() {
  console.info("new value associated to 'message'");
}, function(error) {
  console.error(error);
});
```

<a id="helper-functions"></a>
## Helper Functions

Some helper functions from our environment that eases interaction with our system.

<a id="createUUID"></a>
### `createUUID(uuidString)`
Creates Clojurescript UUID literal from plain string.

#### Arguments
- `uuidString`: plain Javascript String, has to be UUID formatted

#### Return
Valid Clojurscript UUID literal that could be used within replikativ as identifier.

<a id="toEdn"></a>
### `toEdn(jsObject)`
Converts JSON Objects to [edn](https://github.com/edn-format/edn).

#### Arguments

- `jsObject`: a Javascript Object

#### Returns
A valid edn Object that might be useful in some replikativ interactions.

<a id="hashIt"></a>
### `hashIt(jsObject)`

Creates a hash value of a given JSON Object.

#### Arguments

- `jsObject`: a Javascript Object

#### Returns
A hashed value as Clojurescript UUID literal.
