(ns shop.orders-test
  (:require [clojure.test :refer [deftest is]]
            [shop.orders :as orders]))

(deftest total-test
  (is (= 7 (orders/total [{:amount 3} {:amount 4}]))))
