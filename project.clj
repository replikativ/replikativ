(defproject geschichte "0.1.0-SNAPSHOT"
  :description "A distributed verscion control system with global synching."
  :url "http://github.com/ghubber/geschichte"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src/cljs" "src/clj"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-2120"]
                 [org.clojure/core.incubator "0.1.3"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [http-kit "2.1.13"]
                 [ring "1.2.1"]
                 [enlive "1.1.5"]
                 [compojure "1.1.6"]
                 [lamina "0.5.0"]
                 [aleph "0.3.1"]]
  :profiles {:dev {:dependencies [[midje "1.6.0"]]}}
  :plugins [[lein-cljsbuild "1.0.1"]
            [com.cemerick/austin "0.1.3"]]

  :cljsbuild
  {:crossovers [geschichte.meta
                geschichte.synch
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
  {:files {"index"                      ;; my-first-document
           {:input "test/doc/intro.clj" ;; test/docs/my_first_document.clj
            :title "geschichte Introduction" ;; My First Document
            :sub-title "An introduction to the repository functionality." ;; Learning how to use midje-doc
            :author "Christian Weilbach"
            :email  "ch_weil polyc0l0r net"}}})
