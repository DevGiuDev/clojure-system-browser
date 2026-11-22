(ns csb.ui-fixture
  "Disposable copies of the bundled example; UI checks never edit the repository example."
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Arrays]))

(defn write! [^Path file text]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file text (make-array java.nio.file.OpenOption 0)))

(defn bytes= [text ^Path file]
  (Arrays/equals (.getBytes ^String text StandardCharsets/UTF_8) (Files/readAllBytes file)))

(defn with-example [f]
  (let [root (Files/createTempDirectory "csb-ui-" (make-array FileAttribute 0))]
    (try
      (doseq [file ["deps.edn" "src/shop/orders.clj" "test/shop/orders_test.clj"]]
        (let [text (slurp (str "examples/sample-project/" file) :encoding "UTF-8")]
          (write! (.resolve root file)
                  (if (= file "src/shop/orders.clj")
                    (str "\uFEFF;; café 😀\r\n" (str/replace text "\n" "\r\n"))
                    text))))
      (f root)
      (finally
        (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
          (doseq [file (reverse (sort (iterator-seq (.iterator stream))))]
            (Files/deleteIfExists ^Path file)))))))

(def total-draft "(defn total [lines]\n  (+ 10 (reduce + 0 (map :amount lines))))")

(defn with-test-project [f]
  (with-example
    (fn [^Path root]
      (write! (.resolve root "deps.edn")
              "{:paths [\"src\"] :aliases {:test {:extra-paths [\"test\"] :main-opts [\"-m\" \"shop.test-driver\"]}}}")
      (write! (.resolve root "test/shop/orders_test.clj")
              "(ns shop.orders-test (:require [clojure.test :as t]))
               (def ^:dynamic *fixture* false)
               (t/use-fixtures :once (fn [run] (binding [*fixture* true] (run))))
               (t/deftest passing-test (t/is *fixture*))
               (t/deftest failing-test (t/is (= 1 2)))")
      (write! (.resolve root "test/shop/test_driver.clj")
              "(ns shop.test-driver (:require [clojure.test :as t] [shop.orders-test]))
               (defn -main [& _]
                 (println \"Configured suite command\")
                 (println \"Project JAVA_HOME\" (System/getenv \"JAVA_HOME\"))
                 (println \"Project Java runtime\" (System/getProperty \"java.home\"))
                 (binding [*out* *err*] (println \"Suite stderr\"))
                 (let [result (t/run-tests 'shop.orders-test)]
                   (System/exit (if (t/successful? result) 0 1))))")
      (f root))))

(defn await-state! [state predicate]
  (let [ready (promise)
        key (Object.)
        check #(when (predicate %) (deliver ready true))]
    (add-watch state key (fn [_ _ _ value] (check value)))
    (try
      (check @state)
      (when-not (deref ready 15000 false)
        (throw (ex-info "Timed out waiting for client state" {})))
      (finally (remove-watch state key)))))
