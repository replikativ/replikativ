(ns geschichte.store)

;; Address globally aggregated immutable key-value store(s).

(defprotocol IKeyValueStore
  "Semantic of kv-stores. TODO break-down"
  (-get [this key cb])
  (-del [this key cb])
  (-put [this key value cb])
; "Executes changes described in trans map:
;    {:puts {key val ...}
;     :dels #{& keys}
;     :gets #{& keys}}"
  (-transact [this trans cb]))

(defn get-globally
  "Fetches immutable global value.
   TODO Add on-path reference and delta-patch resolution.
   A P2P DHT would fit as one of the kvs as well."
  [kvs path cb]
  (-get (:local kvs) (first path)
        #(cb (get-in (:result %) (rest path)))))

(defn get-with-local-updates
  "Overlay a local mutable map as staged changes
   over the global immutable value."
  [staged kvs path cb]
  (if-let [changed (get-in staged path)]
      (cb changed)
      (get-globally kvs path cb)))
