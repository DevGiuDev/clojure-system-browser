(ns demo.orders)

(defn subtotal [prices]
  (reduce + 0 prices))

(defn delivery-fee [subtotal]
  (if (>= subtotal 50) 0 5))
