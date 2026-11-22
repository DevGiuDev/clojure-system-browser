(ns demo.delivery-test
  (:require [demo.orders :as orders]
            [lazytest.core :refer [defdescribe expect it]]))

(defdescribe delivery-fee
  (it "charges for orders below the free-delivery threshold"
    (expect (= 5 (orders/delivery-fee 49))))
  (it "offers free delivery at the threshold"
    (expect (= 0 (orders/delivery-fee 50)))))
