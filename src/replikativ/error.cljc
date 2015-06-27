(ns replikativ.error
  "Error handling macros with on top of go channels."
  (:require #?(:clj [clojure.core.async :as async :refer
                     [<! <!! timeout chan alt! go put! filter< map< go-loop sub unsub pub close!]]
               :cljs [cljs.core.async :as async :refer
                      [<! >! timeout chan put! filter< map< sub unsub pub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]])))


(defn throwable? [x]
  (instance? #?(:clj Throwable :cljs js/Error) x))

(defn throw-err [e]
  (when (throwable? e) (throw e)) e)

#?(:clj (defmacro <? [ch]
          `(throw-err (<! ~ch)))
   :cljs (defmacro <? [ch]
           `(throw-err (cljs.core.async/<! ~ch))))

#?(:clj (defmacro <!? [ch]
          `(throw-err (<!! ~ch))))


(defmacro go<? [& body]
  `(go (try
         ~@body
         (catch Exception e#
           e#))))

(defmacro go>? [err-chan & body]
  `(go (try
         ~@body
         (catch Exception e#
           (>! ~err-chan e#)))))

(defmacro go-loop>? [err-chan bindings & body]
  `(go (try
         (loop ~bindings
           ~@body)
         (catch Exception e#
           (>! ~err-chan e#)))))

(defmacro go-loop<? [bindings & body]
  `(go<? (loop ~bindings ~@body) ))



(comment
  (ns test
    (:require [cljs.core.async :as async]
              [replikativ.error :as err])
    (:require-macros [replikativ.error :refer [<?]]
                     [cljs.core.async.macros :refer [go]]))


  (go (println (<? (go 1123))))

  )
