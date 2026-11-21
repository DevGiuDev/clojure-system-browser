(ns poly.sample.shop.components.invoice-test
  (:require [clojure.test :refer [deftest is]]
            [poly.sample.shop.interface :as shop]))

(deftest total-of-empty-items
  (is (= 0 (shop/total []))))

(deftest total-adds-prices
  (is (= 5 (shop/total [{:price 2} {:price 3}]))))
