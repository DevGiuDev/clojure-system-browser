(ns shop.orders)

;; This comment belongs to the file and must survive definition edits.
(defn total
  "Calculate the total of a collection of order lines."
  [lines]
  (reduce + 0 (map :amount lines)))


(defn- discount [amount]
  (* amount 0.9))

(defn receipt [customer lines]
"Prueba de comentario"
  (str "Customer: " customer "\nTotal: " (discount (total lines))))
