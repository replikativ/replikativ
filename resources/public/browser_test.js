var r = replikativ.js;

var user = "mail:alice@stechuhr.de";
var lwwrId = r.createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a");
var uri = "ws://127.0.0.1:31778";

var target = {counter: 0};

function logError(err) {
  console.log(err);
}

var sync = {};

function setupReplikativ() {
  return r.newMemStore().then(function(store) {
    sync.store = store;
    return r.clientPeer(store);
  }, logError).then(function(peer) {
    sync.peer = peer;
    return r.createStage(user, peer);
  }, logError).then(function(stage) {
    sync.stage = stage;
    sync.stream = r.streamLWWR(stage, user, lwwrId, function(newValue) {
      target.counter = newValue;
      console.info(target.counter);
    });
    return r.createLWWR(stage, {id: lwwrId, description: "captures"})
  }, logError).then(function() {
    return r.connect(sync.stage, uri);
  }, logError).then(function () {
    console.log("stage connected!")
    return r.setLWWR(sync.stage, user, lwwrId, target.counter + 1);
  }, logError).then(function() {
      console.info(target.counter);
  }, logError);
}

function increaseCounter() {
  r.setLWWR(sync.stage, user, lwwrId, target.counter + 1).then(function() {
    console.info(target.counter);
  }, logError)
}

setupReplikativ();

