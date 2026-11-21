(ns poly.sample.shop.components.invoice)

(defn total [items]
  (reduce + 0 (map :price items)))
