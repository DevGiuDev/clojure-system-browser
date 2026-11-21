(ns poly.sample.worker.bases.cli
  (:require [poly.sample.shop.interface :as shop])
  (:gen-class))

(defn -main [& _]
  (println (shop/total [{:price 2} {:price 3}])))
