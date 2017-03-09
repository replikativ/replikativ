console.debug = console.info; // no console.debug in nodejs, wat?

replikativ = require("./replikativ.js");

// Can we do better than these callbacks? Most importantly I would
// like to have a demo which can be evaluated step-wise in the REPL.

start_stage = function(s) {
    stage=s;
    //replikativ.connect(stage, "wss://topiq.es/replikativ/ws");
    replikativ.create_cdvcs(stage, function(id) {
        cdvcs_id=id;
        value = undefined;
        replikativ.transact(stage, "mail:whilo@topiq.es", id,
                            [["init",42]],
                            function(e) {
                                replikativ.head_value(stage,
                                                      {"init": function(old, arg) {
                                                          value = arg;
                                                      }},
                                                      "mail:whilo@topiq.es",
                                                      id, function(e) {
                                                          console.log("VALUE:", value);
                                                      });
                            });
    });
};

start_peer = function(p) {
    peer = p;
    replikativ.create_stage("mail:whilo@topiq.es", peer, start_stage);
};

replikativ.new_mem_store(
    function(s) {
        store = s;
        replikativ.client_peer(store, start_peer);
});


