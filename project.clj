(defproject io.replikativ/replikativ "0.1.1-SNAPSHOT"
  :description "A scalable distributive p2p system for confluent replicated data types."
  :url "http://github.com/replikativ/replikativ"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.107"]
                 [org.clojure/core.async "0.2.374"]

                 [es.topiq/full.async "0.2.8-beta1"]
                 [kordano/full.cljs.async "0.1.3-alpha"]
                 [io.replikativ/hasch "0.3.0-beta6"]
                 [io.replikativ/konserve "0.3.3"]

                 ;; fixes websocket issue https://github.com/http-kit/http-kit/issues/179
                 [http-kit "2.1.21-alpha2"]
                 [io.replikativ/kabel "0.1.2-SNAPSHOT"]]

  :profiles {:dev {:dependencies [[midje "1.6.3"]
                                  [com.cemerick/piggieback "0.2.1"]]
                   :figwheel {:nrepl-port 7888
                              :nrepl-middleware ["cider.nrepl/cider-middleware"
                                                 "cemerick.piggieback/wrap-cljs-repl"]}
                   :plugins [[lein-figwheel "0.5.0-2"]]}}

  :plugins [[lein-cljsbuild "1.1.1"]
            [lein-codox "0.9.1"]]


  :codox {:source-paths ["src"]
          :output-path "doc"}

  :clean-targets ^{:protect false} ["target" "out" "test/dev/client/out" "resources/public/js"]

  :cljsbuild
  {:builds
   [{:id "cljs_repl"
     :source-paths ["src" "examples"]
     :figwheel true
     :compiler
     {:main dev.client.core
      :asset-path "js/out"
      :output-to "resources/public/js/client.js"
      :output-dir "resources/public/js/out"
      :optimizations :none
      :pretty-print true}}
    {:id "dev"
     :source-paths ["src"]
     :compiler
     {:output-to "resources/public/js/main.js"
      :optimizations :simple
      :pretty-print true}}]}

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
