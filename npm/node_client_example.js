require("source-map-support").install();
console.debug = console.info;


var r = require("./replikativ.js");

var userId = "mail:alice@replikativ.io";
var lwwrId = r.createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a");
var uri = "ws://127.0.0.1:31778";

var localState = {counter: 0};

function logError(err) {
  console.log(err);
}

var replica = {};

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
      console.info("increase");
  }, logError);
}

setupReplikativ();
