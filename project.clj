(defproject geschichte "0.1.0-SNAPSHOT"
  :description "A distributed version control system with global synching."
  :url "http://github.com/ghubber/geschichte"
  :license {:name "GNU Lesser General Public License 2.1 or later"
            :url "https://www.gnu.org/licenses/old-licenses/lgpl-2.1"}
  :source-paths ["src/cljs" "src/clj"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-2173"]
                 [org.clojure/core.incubator "0.1.3"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [http-kit "2.1.16"]
                 [http.async.client "0.5.2"]]
  :profiles {:dev {:dependencies [[midje "1.6.2"]]}}
  :plugins [[lein-cljsbuild "1.0.1"]
            [com.cemerick/austin "0.1.3"]]

  :cljsbuild
  {:crossovers [geschichte.meta
                geschichte.sync
                geschichte.data
                geschichte.repo
                geschichte.protocols
                geschichte.zip]
   :crossover-path "crossover-cljs"
   :crossover-jar false
   :builds
   [{:source-paths ["src/cljs"
                    "crossover-cljs"]
     :compiler
     {:output-to "resources/public/js/main.js"
      :optimizations :simple
      :pretty-print true}}]}

  :documentation
  {:files {"index"
           {:input "test/doc/intro.clj"
            :title "geschichte repository API"
            :sub-title "An introduction to the Repository functionality."
            :author "christian weilbach"
            :email  "ch_weil polyc0l0r net"}
           "synching"
           {:input "test/doc/sync.clj"
            :title "geschichte synching API"
            :sub-title "An introduction to the Synching functionality."
            :author "christian weilbach"
            :email  "ch_weil polyc0l0r net"}}})
