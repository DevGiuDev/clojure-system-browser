(ns poly.sample.dev.scratch)

(defonce seed 42)

(comment
  ;; Require the component in one form and use the alias in the next one:
  ;; whole-block evaluation sends the forms sequentially, so the alias of the
  ;; first form is available to the second.
  (require '[poly.sample.shop.components.invoice :as invoice])
  (invoice/total [{:price 12} {:price 8}]))
