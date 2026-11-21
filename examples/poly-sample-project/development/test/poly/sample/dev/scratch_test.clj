(ns poly.sample.dev.scratch-test
  (:require [clojure.test :refer [deftest is]]
            [poly.sample.dev.scratch :as scratch]))

(deftest seed-is-stable
  (is (pos? scratch/seed)))
