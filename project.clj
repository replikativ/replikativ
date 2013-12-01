(defproject geschichte "0.1.0-SNAPSHOT"
  :description "A distributed verscion control system with global synching."
  :url "http://github.com/ghubber/geschichte"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.incubator "0.1.3"]
                 [http-kit "2.1.13"]
                 [lamina "0.5.0"]
                 [aleph "0.3.0"]]
  :profiles {:dev {:dependencies [[midje "1.5.1"]]}}
  :documentation
  {:files {"index"                      ;; my-first-document
           {:input "test/doc/intro.clj" ;; test/docs/my_first_document.clj
            :title "geschichte Introduction" ;; My First Document
            :sub-title "An introduction to the repository functionality." ;; Learning how to use midje-doc
            :author "Christian Weilbach"
            :email  "ch_weil polyc0l0r net"}}})
