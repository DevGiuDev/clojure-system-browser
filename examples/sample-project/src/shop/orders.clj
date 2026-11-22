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

(comment
  ;; REPL examples for this namespace. Open the project in the browser,
  ;; connect the nREPL panel and evaluate the whole block or one selection.
  (total [{:amount 250} {:amount 30}])
  (discount 100)
  (receipt "Ada" [{:amount 250} {:amount 30}]))
