(ns geschichte.go-for
  "Adapted for comprehension to allow synchronous channel ops."
  (:require #+clj [clojure.core.async :as async :refer
                   [<! <!! timeout chan alt! go put! filter< map< go-loop sub unsub pub close!]]
            #+cljs [cljs.core.async :as async :refer
                    [<! >! timeout chan put! filter< map< sub unsub pub close!]]
            [geschichte.error :refer [go<? go-loop>? <? #+clj <!?]] #+cljs :include-macros)
  #+cljs (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]))


(defmacro ^{:private true} assert-args
  [& pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                  (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
     ~(let [more (nnext pairs)]
        (when more
          (list* `assert-args more)))))

(defn chan? [x]
  (extends? #+clj clojure.core.async.impl.protocols/Channel
            #+cljs cljs.core.async.impl.protocols/Channel
            (type x)))

(defmacro go-for
  "List comprehension. Takes a vector of one or more
  binding-form/collection-expr pairs, each followed by zero or more
  modifiers, and yields a lazy sequence of evaluations of expr.
  Collections are iterated in a nested fashion, rightmost fastest, and
  nested coll-exprs can refer to bindings created in prior
  binding-forms.  Supported modifiers are: :let [binding-form expr
  ...],
   :while test, :when test.

  (take 100 (<! (go-for [x (range 100000000) y (range 1000000) :while (< y x)] [x y])))"
  {:added "1.0"}
  [seq-exprs body-expr]
  (assert-args
   (vector? seq-exprs) "a vector for its binding"
   (even? (count seq-exprs)) "an even number of forms in binding vector")
  (let [to-groups (fn [seq-exprs]
                    (reduce (fn [groups [k v]]
                              (if (keyword? k)
                                (conj (pop groups) (conj (peek groups) [k v]))
                                (conj groups [k v])))
                            [] (partition 2 seq-exprs)))
        err (fn [& msg] (throw #+clj (IllegalArgumentException. ^String (apply str msg))
                              #+cljs (js/Error (apply str msg))))
        emit-bind (fn emit-bind [cache-sym [[bind expr & mod-pairs]
                                           & [[_ next-expr] :as next-groups]]]
                    (let [giter (gensym "iter__")
                          gxs (gensym "s__")
                          do-mod (fn do-mod [[[k v :as pair] & etc]]
                                   (cond
                                    (= k :let) `(let ~v ~(do-mod etc))
                                    (= k :while) `(when ~v ~(do-mod etc))
                                    (= k :when) `(if ~v
                                                   ~(do-mod etc)
                                                   (recur (rest ~gxs)))
                                    (keyword? k) (err "Invalid 'for' keyword " k)
                                    next-groups
                                    `(let [iterys# ~(emit-bind cache-sym next-groups)
                                           fs# (<? (iterys# ~next-expr))]
                                       (if fs#
                                         (concat fs# (<? (~giter (rest ~gxs))))
                                         (recur (rest ~gxs))))
                                    :else `(cons ~body-expr (<? (~giter (rest ~gxs))))))]
                      `(fn ~giter [~gxs]
                         (go<?
                           (loop [~gxs ~gxs]
                             (let [~gxs (seq ~gxs)]
                               (when-let [~bind (first ~gxs)]
                                 ~(do-mod mod-pairs))))))))
        cache-sym (gensym "cch__")]
    `(let [~cache-sym (atom {})
           iter# ~(emit-bind cache-sym (to-groups seq-exprs))]
       (binding [*cache* ~cache-sym]
         (iter# ~(second seq-exprs))))))

;; TODO remove if unnecessary (probably)

(def ^:dynamic *cache*)

(defn c-into [coll ch]
  (go (if (@*cache* ch) (@*cache* ch)
          (let [s (<! (async/into coll ch))]
            (swap! *cache* assoc ch s)
            s))))

(defmacro c-s<! [exp]
  `(seq (<! (c-into [] ~exp))))

(defmacro c<! [ch]
  `(if (@*cache* ~ch) (@*cache* ~ch)
       (let [r# (<! ~ch)]
         (swap! *cache* assoc ~ch r#)
         r#)))



(comment
  (require '[clojure.pprint :refer [pprint]])

  (let [ch (chan)]
    (put! ch 1)
    (pprint (macroexpand-1  '(go-for [foo [1 2]
                                      c ch]
                                     [foo c]))))


  (let [ch (chan)
        bch (chan)]
    (put! bch ch)
    (close! bch)
    (put! ch 1)
    (put! ch 3)
    (close! ch)
    (<!?
     (go<? (let [b (<! bch)
                 c (<! (async/into [] b))]
             (<? (go-for [foo [1 2]
                          b (<! (go 42))
                          c c]
                         [foo b c]))))))

  )
