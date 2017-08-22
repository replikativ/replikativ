(defproject io.replikativ/replikativ "0.2.5-SNAPSHOT"
  :description "An open, scalable and distributive infrastructure for a data-driven community of applications."
  :url "http://github.com/replikativ/replikativ"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src"]
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 #_[org.clojure/clojure "1.9.0-alpha16" :scope "provided"]
                 [org.clojure/clojurescript "1.9.542" :scope "provided"]

                 [io.replikativ/superv.async "0.2.9"]
                 [io.replikativ/incognito "0.2.1"]
                 [io.replikativ/konserve "0.4.9"]

                 [http-kit "2.2.0"]
                 [com.cognitect/transit-cljs "0.8.239" :scope "provided"]
                 [io.replikativ/kabel "0.2.2"]]

  :profiles {:dev {:dependencies [[com.fzakaria/slf4j-timbre "0.3.5"]
                                  [com.cemerick/piggieback "0.2.1"]]
                   :figwheel {:nrepl-port 7888
                              :nrepl-middleware ["cider.nrepl/cider-middleware"
                                                 "cemerick.piggieback/wrap-cljs-repl"]}
                   :plugins [[lein-figwheel "0.5.8"]]}}

  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-codox "0.10.1"]]

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
      :output-to "nodejs/replikativ.js"
      :output-dir "nodejs/"
      ;:asset-path "out"
      :source-map "nodejs/replikativ.js.map"
      :target :nodejs
      ;:elide-asserts true
      ;:pretty-print true
      :optimizations :simple
      }}
    {:id "browser-js-simple"
     :source-paths ["src"]
     :compiler
     {:main replikativ.js
      :output-to "resources/public/js/simple/main.js"
      :output-dir "resources/public/js/simple/"
      :source-map "resources/public/js/simple/main.js.map"
      :optimizations :simple
      :pretty-print true}}
    {:id "browser-js"
     :source-paths ["src"]
     :compiler
     {:main replikativ.js
      :output-to "resources/public/js/main.js"
      :output-dir "resources/public/js/"
      :optimizations :advanced
      :pretty-print true
      :source-map true}}
    {:id           "min"
     :source-paths ["src"]
     :compiler
     {:main          replikativ.js
      :output-to     "resources/public/js/replikativ.js"
      :optimizations :simple
      :pretty-print  true}}]}

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
