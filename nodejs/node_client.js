// var WebSocket = require("ws");
WebSocket = require('websocket').w3cwebsocket;

console.debug = console.info; // no console.debug in nodejs (?)

require("./replikativ.js");

// Can we do better than these reverse callbacks? Most importantly I would
// like to have a demo which can be evaluated step-wise in the REPL.

var user = "mail:eve@topiq.es"; // set to your mail

var subcribe_crdts = function() {
    replikativ.js.subscribe_crdts(stage,
                                  {user: ["26558dfe-59bb-4de4-95c3-4028c56eb5b5" // topiq db
                                         ]},
                                  function() { console.log("Ready for replication!"); } );
};

var transact = function(id){
    replikativ.js.transact(stage, user, id,
                           "function(old, params) { return params; }",
                           new Date(),
                           function() { replikativ.js.commit(stage, {user: [id]}); });
};


var setup = function() {
    replikativ.js.create_cdvcs(stage, transact);
};


var start_stage = function(s) {
    stage=s;
    replikativ.js.connect(stage, "ws://eisler.polyc0l0r.net:8080/replikativ/ws",
                          // subscribe_crdts
                          setup);
};

var start_peer = function(p) {
    peer = p;
    replikativ.js.create_stage(user, peer, start_stage);
};

konserve.js.new_mem_store(
    function(s) {
        store = s;
        replikativ.js.client_peer(store, start_peer);
});
