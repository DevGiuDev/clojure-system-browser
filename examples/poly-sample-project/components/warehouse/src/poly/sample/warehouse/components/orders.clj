(ns poly.sample.warehouse.components.orders)

(defn open-orders [db]
  (filter #(= :open (:status %)) (:orders db)))
