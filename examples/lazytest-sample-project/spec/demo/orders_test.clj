(ns demo.orders-test
  (:require [demo.orders :as orders]
            [lazytest.core :as lt :refer [defdescribe describe expect it]]))

(lt/defdescribe subtotal
  (describe "an order with items"
    (it "adds the item prices"
      (expect (= 25 (orders/subtotal [10 15])))))
  (it "returns zero for an empty order"
    (expect (= 0 (orders/subtotal [])))))

(defdescribe ^:demo-failure failure-example
  (it "fails on purpose with an incorrect subtotal"
    (expect (= 30 (orders/subtotal [10 15])))))

(defdescribe ^:skip skipped-example
  (it "would fail if it ran"
    (expect false)))

(defdescribe ^:setup-error setup-error-example
  {:context [(lt/before (throw (ex-info "Intentional setup error" {})))]}
  (it "never reaches the assertion"
    (expect true)))
