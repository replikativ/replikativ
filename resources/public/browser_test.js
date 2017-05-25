var r = replikativ.js;

var user = "mail:alice@stechuhr.de";
var ormapId = cljs.core.uuid("07f6aae2-2b46-4e44-bfd8-058d13977a8a");
var uri = "ws://127.0.0.1:31778";

var props = {captures: []};

var streamEvalFuncs = {"function(prevState, props) {console.log(props); var captures = prevState.captures; var newState = captures.push(props); return {captures: newState};}": function(prevState, props) {
  console.log(props);
  var captures = prevState.captures;
  var newState = captures.push(props);
  return {captures: newState};
}};

function logError(err) {
  console.log(err);
}

var sync = {};

function setupReplikativ() {
  console.log("creating store...");
  r.newMemStore().then(function(store) {
    console.log("store created...");
    sync.store = store;
    return r.clientPeer(store);
  }, logError).then(function(peer) {
    console.log("peer created...");
    console.log("creating stage...");
    sync.peer = peer;
    return r.createStage(user, peer);
  }, logError).then(function(stage) {
    console.log("stage created!");
    sync.stage = stage;
    sync.stream = r.streamIntoIdentity(stage, user, ormapId, streamEvalFuncs, props)
    console.log("creating or-map...");
    return r.createOrMap(stage, {id: ormapId, description: "captures"})
  }, logError).then(function() {
    console.log("or-map created!");
    console.log("connecting stage...");
    return r.connect(sync.stage, uri);
  }, logError).then(function () {
    console.log("stage connected!");
  }, logError);
}

setupReplikativ();


