(ns geschichte.stage)


(defn transact [stage params trans-code]
  (update-in stage [:transactions] conj [params trans-code]))
