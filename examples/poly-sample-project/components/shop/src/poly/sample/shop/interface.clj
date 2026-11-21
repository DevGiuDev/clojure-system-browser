(ns poly.sample.shop.interface
  (:require [poly.sample.shop.components.invoice :as invoice]))

(defn total [items]
  (invoice/total items))
