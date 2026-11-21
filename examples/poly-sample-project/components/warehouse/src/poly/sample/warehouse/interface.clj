(ns poly.sample.warehouse.interface
  (:require [poly.sample.warehouse.components.orders :as orders]))

(defn open-orders [db]
  (orders/open-orders db))
