(ns poly.sample.warehouse.components.orders)

(defn open-orders [db]
  (filter #(= :open (:status %)) (:orders db)))

(comment
  (open-orders {:orders [{:id 1 :status :open} {:id 2 :status :closed}]}))
