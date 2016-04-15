// WebSocket = require('websocket').w3cwebsocket; // set by kabel
require("source-map-support").install();

console.debug = console.info; // no console.debug in nodejs (?)

require("./replikativ.js");

var user = "mail:eve@topiq.es"; // set to your mail

var subscribe_crdts = function() {
    replikativ.js.subscribe_crdts(stage,
                                  {"mail:eve@topiq.es":
                                   ["26558dfe-59bb-4de4-95c3-4028c56eb5b5" // topiq db
                                   ]},
                                  function(_) { console.log("Ready for replication!"); } );
};

var eval_fns = {"function(old, params) { return params; }":
                function(old, params) { return params; }};

var head_value = function(_) {
    console.log("Committed on ", cdvcs_id);
    replikativ.js.head_value(stage,
                             eval_fns,
                             "mail:eve@topiq.es",
                             cdvcs_id,
                             function(val) { console.log("Head value:", val); });
};


var commit = function(_) {
    replikativ.js.commit(stage, {"mail:eve@topiq.es": [cdvcs_id]},head_value);
    // loop a bit for demo purposes
    setTimeout(transact, 10000);
};

var transact = function(){
    console.log("Transacting on CDVCS:", cdvcs_id);
    replikativ.js.transact(stage, user, cdvcs_id,
                           "function(old, params) { return params; }",
                           new Date(),
                           commit);
};


var setup = function(_) {
    replikativ.js.create_cdvcs(stage, function(id) { cdvcs_id = id; transact(); });
};


var start_stage = function(s) {
    stage=s;
    console.log("Created stage.");
    replikativ.js.connect(stage, "ws://localhost:31744/replikativ/ws",
                          //subscribe_crdts
                          setup
                         );
};

var start_peer = function(p) {
    peer = p;
    console.log("Created peer.");
    replikativ.js.create_stage(user, peer, start_stage);
};

replikativ.js.new_mem_store(
    function(s) {
        store = s;
        console.log("Created store.");
        replikativ.js.client_peer(store, start_peer);
});
