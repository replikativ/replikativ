# Javascript replikativ tutorial

The following tutorial sets a Last Writer Wins Register by increasing it everytime it is called.

First we need to define some constants:

```javascript
var r = replikativ.js;
var userId = "mail:alice@replikativ.io";
var lwwrId = r.createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a");
var uri = "ws://127.0.0.1:31778";
var localState = {counter: 0};
var replica = {};
```

This assumes we don't use any module system and just include `repliativ.js` as plain file in our HTML. So we need to define `r` as our `replikativ.js` namespace.

First we set a `userId` that identifies the user's peer, `lwwrId` identifies the CRDT we will be replicating and `uri` is the address of the peer we are synching with. We have to state objects: `localState` is our local data state and `replica` holds all replikativ entities such as stage, peer and store.   

Next we define our error function, just a simple console log:

```javascript
function logError(err) {
  console.log(err);
}
```

Now we have to initialize all our replikativ entities and connect to a server peer. We will do that step by step:


``` javascript
function setupReplikativ() {
  return r.newMemStore().then(function(store) {
    replica.store = store;
  }, logError);
}
```

First we initialize an in-memory key-value `store` provided by replikativ. Actually we use [konserve](https://github.com/replikativ/konserve) under the hood. 

``` javascript
function setupReplikativ() {
  return r.newMemStore().then(function(store) {
    replica.store = store;
  }, logError);
}
```

Next we will create our local client `peer`.


``` javascript
function setupReplikativ() {
  return r.newMemStore().then(function(store) {
    replica.store = store;
    return r.clientPeer(store);
  }, logError).then(function(peer) {
    replica.peer = peer;
  }, logError);
}
```

Now the `stage` can be set up where we will interact with our data.

``` javascript
function setupReplikativ() {
  return r.newMemStore().then(function(store) {
    replica.store = store;
    return r.clientPeer(store);
  }, logError).then(function(peer) {
    replica.peer = peer;
    return r.createStage(userId, peer);
  }, logError).then(function(stage) {
    replica.stage = stage;
  }, logError);
}
```

In order to interact with the data, we should stream our LWWR and set the local counter to the latest value.

``` javascript
function setupReplikativ() {
  return r.newMemStore().then(function(store) {
    replica.store = store;
    return r.clientPeer(store);
  }, logError).then(function(peer) {
    replica.peer = peer;
    return r.createStage(userId, peer);
  }, logError).then(function(stage) {
    replica.stage = stage;
    replica.stream = r.streamLWWR(stage, userId, lwwrId, function(newValue) {
      localState.counter = newValue;
      console.info(localState.counter);
    });
  }, logError);
  }
```

Now we can create the our local Last Writer Wins Register.

``` javascript
function setupReplikativ() {
  return r.newMemStore().then(function(store) {
    replica.store = store;
    return r.clientPeer(store);
  }, logError).then(function(peer) {
    replica.peer = peer;
    return r.createStage(userId, peer);
  }, logError).then(function(stage) {
    replica.stage = stage;
    replica.stream = r.streamLWWR(stage, userId, lwwrId, function(newValue) {
      localState.counter = newValue;
      console.info(localState.counter);
    });
    return r.createLWWR(stage, {id: lwwrId, description: "captures"})
  }, logError).then(function() {}, logError);
}
```

Everything is setup now and we may connect to a remote peer.

``` javascript
function setupReplikativ() {
  return r.newMemStore().then(function(store) {
    replica.store = store;
    return r.clientPeer(store);
  }, logError).then(function(peer) {
    replica.peer = peer;
    return r.createStage(userId, peer);
  }, logError).then(function(stage) {
    replica.stage = stage;
    replica.stream = r.streamLWWR(stage, userId, lwwrId, function(newValue) {
      localState.counter = newValue;
      console.info(localState.counter);
    });
    return r.createLWWR(stage, {id: lwwrId, description: "captures"})
  }, logError).then(function() {
    return r.connect(replica.stage, uri);
  }, logError);
}
```
We want to how often this script runs, so we increase the counter at the end of the setup.

``` javascript
function setupReplikativ() {
  return r.newMemStore().then(function(store) {
    replica.store = store;
    return r.clientPeer(store);
  }, logError).then(function(peer) {
    replica.peer = peer;
    return r.createStage(userId, peer);
  }, logError).then(function(stage) {
    replica.stage = stage;
    replica.stream = r.streamLWWR(stage, userId, lwwrId, function(newValue) {
      localState.counter = newValue;
      console.info(localState.counter);
    });
    return r.createLWWR(stage, {id: lwwrId, description: "captures"})
  }, logError).then(function() {
    return r.connect(replica.stage, uri);
  }, logError).then(function () {
    console.log("stage connected!")
    return r.setLWWR(replica.stage, userId, lwwrId, localState.counter + 1);
  }, logError).then(function() {
      console.info(localState.counter);
  }, logError);
}
```

That's it, now we run the setup as final statement.

```javascript
setupReplikativ();
```
