(defproject io.replikativ/replikativ "0.2.0-SNAPSHOT"
  :description "A scalable distributive p2p system for confluent replicated data types."
  :url "http://github.com/replikativ/replikativ"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.51"]

                 [io.replikativ/superv.async "0.2.1-SNAPSHOT"]
                 [io.replikativ/konserve "0.4.4-SNAPSHOT"]

                 [io.replikativ/kabel "0.1.9-SNAPSHOT"]]

  :profiles {:dev {:dependencies [[midje "1.8.2"]
                                  [com.fzakaria/slf4j-timbre "0.3.2"]
                                  [com.cemerick/piggieback "0.2.1"]]
                   :figwheel {:nrepl-port 7888
                              :nrepl-middleware ["cider.nrepl/cider-middleware"
                                                 "cemerick.piggieback/wrap-cljs-repl"]}
                   :plugins [[lein-figwheel "0.5.8"]]}}

  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-codox "0.10.1"]
            [lein-midje "3.2.1"]]

  :codox {:source-paths ["src"]
          :output-path "doc"}

  :clean-targets ^{:protect false} ["target" "out" "resources/public/js"
                                    "nodejs/out" "nodejs/replikativ.js"]

  :cljsbuild
  {:builds
   [{:id "cljs_repl"
     :source-paths ["src"]
     :figwheel true
     :compiler
     {:main replikativ.core
;      :verbose true
      :asset-path "js/out"
      :output-to "resources/public/js/main.js"
      :optimizations :none
      :pretty-print true
                                        ;:source-map true
      }}
    {:id "nodejs"
     :source-paths ["src"]
     ;:assert false
     :compiler
     {:main replikativ.js
      :output-to "target/nodejs/replikativ.js"
      :output-dir "target/nodejs/"
      ;:asset-path "out"
      :source-map "target/nodejs/replikativ.js.map"
      :target :nodejs
      ;:elide-asserts true
      ;:pretty-print true
      :optimizations :simple
      }}
    {:id "dev"
     :source-paths ["src"]
     :compiler
     {:output-to "resources/public/js/main.js"
      :output-dir "resources/public/js/"
      :optimizations :simple
      :pretty-print true
      :source-map true}}]}

  :documentation
  {:files {"index"
           {:input "test/doc/intro.clj"
            :title "repository API"
            :sub-title "An introduction to the Repository functionality."
            :author "christian weilbach"
            :email  "ch_weil topiq es"}
           "replication"
           {:input "test/doc/replicate.clj"
            :title "synching API"
            :sub-title "An introduction to the replication protocol."
            :author "christian weilbach"
            :email  "ch_weil topiq es"}
           #_"stage"
           #_{:input "test/doc/stage.clj"
              :title "stage API"
              :sub-title "An introduction to the state API."
              :author "christian weilbach"
              :email  "ch_weil topiq es"}
           "hooks"
           {:input "test/doc/hooks.clj"
            :title "hooks API"
            :sub-title "An introduction to pull hooks."
            :author "christian weilbach"
            :email  "ch_weil topiq es"}}})
