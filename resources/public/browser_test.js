var r = replikativ.js;

var user = "mail:alice@stechuhr.de";
var ormapId = r.createUUID("07f6aae2-2b46-4e44-bfd8-058d13977a8a");
var uri = "ws://127.0.0.1:31778";

var props = {captures: []};

var streamEvalFuncs = {
  "add": function(supervisor, old, params) {
    var oldCaptures = old.captures;
    oldCaptures.push(params);
    return {captures: oldCaptures};
  }};


function logError(err) {
  console.log(err);
}

var sync = {};

function setupReplikativ() {
  r.newMemStore().then(function(store) {
    sync.store = store;
    return r.clientPeer(store);
  }, logError).then(function(peer) {
    sync.peer = peer;
    return r.createStage(user, peer);
  }, logError).then(function(stage) {
    sync.stage = stage;
    sync.stream = r.streamIntoIdentity(stage, user, ormapId, streamEvalFuncs, props)
    return r.createOrMap(stage, {id: ormapId, description: "captures"})
  }, logError).then(function() {
    return r.connect(sync.stage, uri);
  }, logError).then(function () {
    console.log("stage connected!")
  }, logError)
}

function checkIt(value) {
  r.associate(sync.stage, user, ormapId, r.hashIt(r.toEdn(value)), [["add", value]])
    .then(function(result) {
      console.log("associated with " + value);
    }, logError);
}

setupReplikativ();

