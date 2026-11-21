(ns csb.architecture-test
  (:require [clojure.java.io :as io]
            [csb.desktop-launcher :as desktop-launcher]
            [clojure.test :refer [deftest is]]))

(deftest core-tests-have-no-ui-or-nrepl-classes
  (doseq [name ["javafx.application.Platform" "com.googlecode.lanterna.screen.Screen"]]
    (is (= :absent (try
                    (Class/forName name false (.getContextClassLoader (Thread/currentThread)))
                    :present
                    (catch ClassNotFoundException _ :absent)))))
  (is (nil? (io/resource "nrepl/core.clj")))
  (is (nil? (find-ns 'cljfx.api)))
  (is (nil? (find-ns 'nrepl.core))))

(deftest desktop-runtime-check-can-run-without-javafx
  (is (= {:type :unsupported-java :required 25 :actual 21}
         (try (desktop-launcher/check-runtime! 21)
              (catch clojure.lang.ExceptionInfo e (ex-data e)))))
  (is (nil? (desktop-launcher/check-runtime! 25)))
  (is (nil? (desktop-launcher/check-runtime! 27)))
  (is (nil? (find-ns 'csb.desktop))))
