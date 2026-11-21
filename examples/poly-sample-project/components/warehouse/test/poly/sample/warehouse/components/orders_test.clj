(ns poly.sample.warehouse.components.orders-test
  (:require [clojure.test :refer [deftest is]]
            [poly.sample.warehouse.interface :as warehouse]))

(deftest open-orders-keeps-open-status
  (is (= [{:id 2 :status :open}]
         (warehouse/open-orders {:orders [{:id 1 :status :shipped}
                                          {:id 2 :status :open}]}))))
