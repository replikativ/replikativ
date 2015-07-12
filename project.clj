(defproject es.topiq/replikativ "0.1.0-SNAPSHOT"
  :description "A scalable distributive p2p system for convergent replicated data types."
  :url "http://github.com/ghubber/replikativ"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3308"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [com.cognitect/transit-clj "0.8.275"]
                 [http-kit "2.1.19"]
                 [http.async.client "0.6.0"]
                 [es.topiq/full.async "0.2.8-beta1"]
                 [es.topiq/hasch "0.3.0-beta3"]
                 [net.polyc0l0r/konserve "0.2.3"]
                 [com.taoensso/timbre "4.0.2"]]
  :profiles {:dev {:dependencies [[midje "1.6.3"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  [org.clojure/tools.nrepl "0.2.10"]]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}}
  :plugins [[lein-cljsbuild "1.0.6"]]

  :codox {:sources ["src"]}

  :cljsbuild
  {:builds
   [{:source-paths ["src"]
     :compiler
     {:output-to "resources/public/js/main.js"
      :optimizations :simple
      :pretty-print true}}]}

  :documentation
  {:files {"index"
           {:input "test/doc/intro.clj"
            :title "replikativ repository API"
            :sub-title "An introduction to the Repository functionality."
            :author "christian weilbach"
            :email  "ch_weil topiq es"}
           "synching"
           {:input "test/doc/sync.clj"
            :title "replikativ synching API"
            :sub-title "An introduction to the Synching functionality."
            :author "christian weilbach"
            :email  "ch_weil topiq es"}}})
