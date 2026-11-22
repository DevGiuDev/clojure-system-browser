(ns poly.sample.shop.components.invoice)

(defn total [items]
  (reduce + 0 (map :price items)))

(comment
  (total [{:price 12} {:price 8}]))
