(defproject net.polyc0l0r/geschichte "0.1.0-SNAPSHOT"
  :description "A distributed version control system with global synching."
  :url "http://github.com/ghubber/geschichte"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src/cljs" "src/clj"]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2511"]
                 [org.clojure/core.async "0.1.338.0-5c5012-alpha"]
                 [http-kit "2.1.19"]
                 [http.async.client "0.5.2"]
                 [net.polyc0l0r/hasch "0.2.3" :exclusions [org.clojure/clojure com.keminglabs/cljx]]
                 [net.polyc0l0r/konserve "0.2.3-SNAPSHOT"]
                 [com.taoensso/timbre "3.3.1"]]
  :profiles {:dev {:dependencies [[midje "1.6.3"]]}}
  :plugins [[lein-cljsbuild "1.0.3"]
            [com.keminglabs/cljx "0.5.0"
             :exclusions [watchtower]]
            ;; TODO add weasel
            ]

  :cljx {:builds [{:source-paths ["src/cljx"]
                   :output-path "target/classes"
                   :rules :clj}

                  {:source-paths ["src/cljx"]
                   :output-path "target/classes"
                   :rules :cljs}]}

  :prep-tasks [["cljx" "once"] "javac" "compile"]

  :cljsbuild
  {:builds
   [{:source-paths ["src/cljs"
                    "target/classes"]
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
