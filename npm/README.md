# replikativ

[replikativ](http://replikativ.io) is an eventual consistent synchronisation system. It is build with [Clojure](https://clojure.org)/[Script](https://clojurescript.org) and can synchronize between different runtimes including the browser, nodejs and the JVM. This package provides JavaScript bindings for replikativ.

~~~javascript
// WebSocket = require('websocket').w3cwebsocket; // set by kabel
require("source-map-support").install();
console.debug = console.info; // no console.debug in nodejs

var replikativ = require("replikativ")

var user = "mail:eve@topiq.es"; // set to your mail 
 
// this simple eval function mapping just replaces the value
// of the datatype on each operation
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
    replikativ.transact(stage, user, cdvcs_id,
                           "function(old, params) { return params; }",
                           new Date(),
                           commit);
};
 
 
var setup = function(_) {
    replikativ.create_cdvcs(stage, function(id) { cdvcs_id = id; transact(); });
};
 
 
var start_stage = function(s) {
    stage=s;
    console.log("Created stage.");
    replikativ.connect(stage, "ws://localhost:31744/replikativ/ws",
                          //subscribe_crdts 
                          setup
                         );
};
 
var start_peer = function(p) {
    peer = p;
    console.log("Created peer.");
    replikativ.create_stage(user, peer, start_stage);
};
 
replikativ.new_mem_store(
    function(s) {
        store = s;
        console.log("Created store.");
        replikativ.client_peer(store, start_peer);
});

);
~~~

For further documentation and examples, please take a look at http://replikativ.io.

