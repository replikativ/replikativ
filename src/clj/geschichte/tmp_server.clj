(ns geschichte.tmp-server
  (:require [cemerick.austin.repls :refer (browser-connected-repl-js)]
            [ring.adapter.jetty :refer [run-jetty]]
            [net.cgrand.enlive-html :as enlive]
            [compojure.route :refer (resources)]
            [compojure.core :refer (GET defroutes)]
            [clojure.java.io :as io]) )


                                        ; ring server for cljs repl and testing
(enlive/deftemplate page
  (io/resource "public/index.html")
  []
  [:body] (enlive/append
           (enlive/html [:script (browser-connected-repl-js)])))


(defroutes site
  (resources "/")
  (GET "/*" req (page)))


#_(def html-server (run-jetty #'site {:port 8080 :join? false}))
#(.stop html-server)
