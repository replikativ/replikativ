(ns ^:shared geschichte.zip
  (:require [clojure.zip :as zip]))


(defn universal-zip [root]
  "Zipper for nested data-structures."
  (let [branch? (fn [node]
                  (when node
                    (or (map? node)
                        (vector? node)
                        (seq? node))))
        children (fn [node]
                   (cond
                    (nil? node) nil
                    (map? node) (seq node)
                    :else node))
        make-node (fn [node children]
                    (cond
                     (nil? node) nil
                     (vector? node) (into [] children)
                     (map? node) (reduce #(let [[k v] %2] (assoc %1 k v)) {} children)
                     (seq? node) (apply list children) ; HACK fixes order -> into '()
                     :else node))]
  (zip/zipper branch? children make-node root)))


(defn tree-remove [zipper matcher]
  (loop [loc zipper]
    (if (zip/end? loc)
      (zip/root loc)
      (if-let [matcher-result (matcher (zip/node loc))]
        (recur (zip/next (zip/remove loc)))
        (recur (zip/next loc))))))


(defn tree-edit [zipper matcher editor]
  (loop [loc zipper]
    (if (zip/end? loc)
      (zip/root loc)
      (if-let [matcher-result (matcher (zip/node loc))]
        (do #_(println (zip/node loc) " results in " matcher-result)
          (recur (zip/next (zip/edit loc (partial editor loc matcher-result)))))
        (recur (zip/next loc))))))


(defn tree-find
  [zipper matcher]
  (loop [loc zipper
         node-list '()]
    (if (zip/end? loc)
      node-list
      (if-let [matcher-result (matcher (zip/node loc))]
        (recur (zip/next loc) (conj node-list (zip/node loc)))
        (recur (zip/next loc) node-list)))))
