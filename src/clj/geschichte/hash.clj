(ns geschichte.hash
  "Platform neutral hash functionality."
  (:require [geschichte.platform :refer [byte->hex]]))


(defn benc
  "Dumb binary encoding which ensures basic types (incl. collections)
cannot collide with different content. MAY NOT CHANGE OR BREAKS HASHES."
  [x]
  (let [b (byte x)]
    (if (< b -98)
      [-98 (+ b 98)]
      [b])))

;; changes break hashes!
(def magics {:string -99
             :symbol -100
             :keyword -101
             :number -102
             :vector -103
             :seq -104
             :map -105
             :set -106
             :sorted-set -107
             :sorted-map -108
             :record -109})


(defn str-hash [bytes]
  (apply str (map byte->hex bytes)))
