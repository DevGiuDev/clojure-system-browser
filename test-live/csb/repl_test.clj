(ns csb.repl-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is testing]]
            [csb.repl :as repl]
            [csb.session :as session]
            [csb.tests :as tests]
            [nrepl.misc :as misc]
            [nrepl.server :as server]
            [nrepl.transport :as wire])
  (:import [java.io Closeable IOException]
           [java.lang ProcessBuilder$Redirect]
           [java.net ServerSocket]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(defn- await! [state predicate]
  (let [done (promise) key (Object.)
        check (fn [s] (when (predicate (:repl s)) (deliver done (:repl s))))]
    (add-watch state key (fn [_ _ _ s] (check s)))
    (try
      (check @state)
      (or (deref done 6000 nil)
          (throw (ex-info "Timed out waiting for REPL" {:repl (dissoc (:repl @state) :connection)})))
      (finally (remove-watch state key)))))

(defn- transcript [state]
  (repl/transcript (:repl @state)))

(defn- connect! [state port options]
  (swap! state assoc-in [:repl :port] (str port))
  (repl/connect! state options)
  (await! state #(= :connected (:phase %))))

(defn- finished! [state]
  (await! state #(and (= :connected (:phase %)) (nil? (:active %)))))

(defn- evaluate! [state code]
  (swap! state assoc-in [:repl :input] code)
  (repl/evaluate-input! state))

(deftest basic-nrepl-session-streams-results-and-keeps-context
  (with-open [running (server/start-server :bind "127.0.0.1" :port 0)]
    (let [state (session/create)]
      (try
        (connect! state (:port running) {})
        (evaluate! state "(do (println \"output marker\")\n (binding [*out* *err*] (println \"error stream\")) (+ 20 22))")
        (finished! state)
        (is (str/includes? (transcript state) "out: output marker"))
        (is (str/includes? (transcript state) "err: error stream"))
        (is (str/includes? (transcript state) "=> 42"))
        (evaluate! state "(in-ns 'clojure.string)")
        (is (= "clojure.string" (:namespace (finished! state))))
        (evaluate! state "(upper-case \"csb\")")
        (finished! state)
        (is (str/includes? (transcript state) "=> \"CSB\""))
        (evaluate! state "(throw (ex-info \"repl failure\" {}))")
        (is (= "Evaluation failed" (:status (finished! state))))
        (is (str/includes? (transcript state) "repl failure"))
        (evaluate! state "(repeat 8192 :very-long-value)")
        (finished! state)
        (is (str/includes? (transcript state) "Printed value truncated"))
        (finally (repl/disconnect! state)))
      (is (= "Disconnected" (:status (await! state #(= :disconnected (:phase %)))))))))

(deftest definition-evaluation-uses-source-namespace-without-saving
  (with-open [running (server/start-server :bind "127.0.0.1" :port 0)]
    (let [state (session/create)
          definition {:file "src/clojure/string.clj" :namespace 'clojure.string}
          draft "(clojure.core/str (ns-name *ns*) \" from draft\")"]
      (try
        (connect! state (:port running) {})
        (swap! state assoc :definition definition :draft draft :dirty? true)
        (repl/evaluate-definition! state)
        (is (= "user" (:namespace (finished! state))))
        (is (str/includes? (transcript state) "=> \"clojure.string from draft\""))
        (is (= draft (:draft @state)))
        (is (:dirty? @state))
        (swap! state assoc-in [:definition :namespace] 'csb.test.namespace-not-loaded)
        (repl/evaluate-definition! state)
        (is (= "Evaluation failed" (:status (finished! state))))
        (is (str/includes? (transcript state) "namespace-not-found"))
        (finally (repl/disconnect! state)))
      (await! state #(= :disconnected (:phase %))))))

(deftest interruption-and-timeout-keep-the-panel-usable
  (with-open [running (server/start-server :bind "127.0.0.1" :port 0)]
    (let [state (session/create)]
      (try
        (connect! state (:port running) {:eval-timeout-ms 900})
        (evaluate! state "(do (println \"started\") (flush) (Thread/sleep 30000))")
        (await! state #(str/includes? (repl/transcript %) "out: started"))
        (is (:active (:repl @state)))
        (repl/interrupt! state)
        (is (= "Evaluation interrupted" (:status (finished! state))))
        (evaluate! state "(Thread/sleep 30000)")
        (is (= "Evaluation timed out" (:status (finished! state))))
        (evaluate! state "(+ 5 6)")
        (finished! state)
        (is (str/includes? (transcript state) "=> 11"))
        (finally (repl/disconnect! state)))
      (await! state #(= :disconnected (:phase %))))))

(deftest reports-connection-failure-handshake-timeout-and-lost-connection
  (let [state (session/create)]
    (repl/connect! state {})
    (is (str/includes? (get-in @state [:repl :status]) "port"))
    (testing "a listener which never answers nREPL"
      (with-open [listener (ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))]
        (swap! state assoc-in [:repl :port] (str (.getLocalPort listener)))
        (repl/connect! state {:connect-timeout-ms 300})
        (is (str/includes? (:status (await! state #(= :disconnected (:phase %)))) "timed out"))))
    (testing "the server vanishes while connected"
      (with-open [running (server/start-server :bind "127.0.0.1" :port 0)]
        (connect! state (:port running) {})
        (.close ^Closeable running)
        (is (str/includes? (:status (await! state #(= :disconnected (:phase %)))) "Connection lost")))
      (with-open [replacement (server/start-server :bind "127.0.0.1" :port 0)]
        (connect! state (:port replacement) {})
        (repl/disconnect! state)
        (await! state #(= :disconnected (:phase %)))))))

(deftest interrupt-is-retried-when-the-server-has-not-started-evaluation
  (let [handler (server/default-handler)
        received (promise)
        unblocked (promise)
        delayed (fn [{:keys [op] :as message}]
                  (case op
                    "eval" (do (deliver received true)
                               (when (deref unblocked 2500 false) (handler message)))
                    "interrupt" (do (handler message) (deliver unblocked true))
                    (handler message)))]
    (with-open [running (server/start-server :bind "127.0.0.1" :port 0 :handler delayed)]
      (let [state (session/create)]
        (try
          (connect! state (:port running) {})
          (evaluate! state "(Thread/sleep 30000)")
          (is (deref received 3000 false))
          (repl/interrupt! state)
          (is (= "Evaluation interrupted" (:status (finished! state))))
          (finally (repl/disconnect! state)
                   (await! state #(= :disconnected (:phase %)))))))))

(deftest inspection-keeps-observations-separate-from-evaluation
  (let [java (str (System/getProperty "java.home") "/bin/java")
        code (str "(do (require 'nrepl.server)"
                  "(create-ns 'shop.orders)"
                  "(intern 'shop.orders (with-meta 'total {:private true :doc \"Runtime documentation\"}) 41)"
                  "(intern 'shop.orders 'dynamic-only 42)"
                  "(let [server (nrepl.server/start-server :bind \"127.0.0.1\" :port 0)]"
                  " (println (:port server)) (flush) @(promise)))")
        process (.start (doto (ProcessBuilder. ^java.util.List
                                               [java "-cp" (System/getProperty "java.class.path")
                                                "clojure.main" "-e" code])
                          (.redirectError ProcessBuilder$Redirect/INHERIT)))
        state (session/create)
        rows #(repl/inspection-rows @state)]
    (try
      (let [port (deref (future (with-open [reader (.inputReader process)]
                                  (some-> (.readLine reader) parse-long)))
                        15000 nil)]
        (when-not port (throw (ex-info "Example nREPL did not report a port" {})))
        (is (pos? port))
        (session/open! state "examples/sample-project" {})
        (swap! state assoc-in [:repl :host] "localhost")
        (connect! state port {})
        (evaluate! state "(in-ns 'clojure.string)")
        (finished! state)
        (let [before (transcript state)]
          (repl/show-inspection! state)
          (finished! state)
          (is (some #(= "shop.orders" (:name %)) (rows)))
          (repl/set-perspective! state :combined)
          (repl/select-inspection! state "shop.orders")
          (repl/open-inspection! state)
          (finished! state)
          (let [observations (into {} (map (juxt :name :presence)) (rows))]
            (is (= :present-in-both (observations "total")))
            (is (= :source-only (observations "discount")))
            (is (= :runtime-only (observations "dynamic-only"))))
          (repl/select-inspection! state "total")
          (repl/open-inspection! state)
          (let [panel (finished! state)]
            (is (= {:private true :doc "Runtime documentation"}
                   (select-keys (get-in panel [:inspection :observation]) [:private :doc])))
            (is (= "clojure.string" (:namespace panel)))
            (is (= before (transcript state))))
          (repl/back-inspection! state)
          (finished! state)
          (is (= ["shop.orders"] (get-in @state [:repl :inspection :target])))
          (repl/select-inspection! state "discount")
          (repl/open-inspection! state)
          (is (= :missing (get-in (finished! state) [:inspection :observation])))
          (repl/disconnect! state)
          (is (nil? (:inspection (await! state #(= :disconnected (:phase %))))))))
      (finally
        (repl/disconnect! state)
        (.destroyForcibly process)))))

(deftest inspection-failure-does-not-report-absence
  (let [handler (server/default-handler)
        reject-inspection (fn [{:keys [op ns transport] :as request}]
                            (if (and (= op "eval") (= ns "clojure.core"))
                              (wire/send transport (misc/response-for request :status #{:forbidden :done}))
                              (handler request)))]
    (with-open [running (server/start-server :bind "127.0.0.1" :port 0 :handler reject-inspection)]
      (let [state (session/create)]
        (try
          (session/open! state "examples/sample-project" {})
          (connect! state (:port running) {})
          (repl/show-inspection! state)
          (let [panel (finished! state)]
            (is (= :forbidden (get-in panel [:inspection :error :type]))))
          (repl/set-perspective! state :combined)
          (is (= :unknown (->> (repl/inspection-rows @state)
                               (some #(when (= "shop.orders" (:name %)) %)) :presence)))
          (evaluate! state "(+ 1 2)")
          (finished! state)
          (is (str/includes? (transcript state) "=> 3"))
          (finally (repl/disconnect! state)
                   (await! state #(= :disconnected (:phase %)))))))))

(defn- run-project-tests! [state namespace-name test-name]
  (tests/run! state namespace-name test-name)
  (let [completed (promise)
        key (Object.)
        check #(when (and (tests/result %) (not (tests/active? %))) (deliver completed %))]
    (add-watch state key (fn [_ _ _ snapshot] (check snapshot)))
    (try
      (check @state)
      (or (deref completed 30000 nil)
          (throw (ex-info "Timed out waiting for project tests" (tests/result @state))))
      (finally
        (remove-watch state key)
        (tests/cancel! state)))))

(deftest tests-run-from-the-project-command-without-an-nrepl
  (let [state (session/create)]
    (session/open! state "examples/poly-sample-project" {})
    (let [snapshot (run-project-tests! state nil nil)
          run (tests/result snapshot)]
      (is (= :passed (:status run)) (pr-str run))
      (is (str/includes? (str/join "\n" (:output run)) "0 failures, 0 errors"))
      (is (= :passed (tests/case-status snapshot 'poly.sample.shop.components.invoice-test 'total-adds-prices)))
      (is (= 4 (count (filter #{:passed} (vals (get-in snapshot [:test-runner :cases])))))))))

(deftest lazytest-runs-and-reports-suites-in-the-project-process
  (let [sample (io/file "examples/lazytest-sample-project")
        sample-config (edn/read-string (slurp (io/file sample "deps.edn")))
        root (.toFile (Files/createTempDirectory "csb-lazytest-" (make-array FileAttribute 0)))
        config (io/file root "deps.edn")
        state (session/create)
        orders-ns 'demo.orders-test
        delivery-ns 'demo.delivery-test
        write-project! (fn [args]
                         (spit config
                               (pr-str (assoc-in sample-config [:aliases :lazytest :main-opts]
                                                 (into ["--report" "stderr" "-m" "lazytest.main"] args)))))]
    (try
      (doseq [path ["deps.edn" "src/demo/orders.clj"
                    "spec/demo/orders_test.clj" "spec/demo/delivery_test.clj"]]
        (let [file (io/file root path)]
          (io/make-parents file)
          (io/copy (io/file sample path) file)))
      (spit (io/file root "spec/demo/legacy_test.clj")
            "(ns demo.legacy-test (:require [clojure.test :refer [deftest is]]))
             (deftest legacy (is false))")
      (session/open! state root {})
      (let [snapshot (run-project-tests! state nil nil)
            run (tests/result snapshot)
            tree (tests/targets snapshot)
            subtotal (some #(when (= 'subtotal (:name %)) %) (tree orders-ns))]
        (is (= :failed (:status run)) (pr-str run))
        (is (= [:passed :failed :idle :idle]
               (mapv #(tests/case-status snapshot orders-ns %)
                     ['subtotal 'failure-example 'skipped-example 'setup-error-example])))
        (is (= :passed (tests/case-status snapshot delivery-ns 'delivery-fee)))
        (is (= :passed (tests/namespace-status snapshot delivery-ns (tree delivery-ns))))
        (is (= :idle (tests/case-status snapshot 'demo.legacy-test 'legacy)))
        (is (some #(str/includes? % "fails on purpose") (:output run)))
        (let [failure (get (:failures run) [orders-ns 'failure-example])]
          (is (= "fails on purpose with an incorrect subtotal" (:case failure)))
          (is (= "(= 30 (orders/subtotal [10 15]))" (:expected failure)))
          (is (pos-int? (:line failure))))
        (tests/open-source! state subtotal)
        (is (= [(:id subtotal) false]
               ((juxt :id :writable?) (:definition @state)))))
      (write-project! ["--namespace=demo.delivery-test" "-ndemo.delivery-test"
                       "--var" "demo.orders-test/failure-example" "-vdemo.orders-test/failure-example"
                       "--watch" "--output" "dots" "--" "spec"])
      (let [snapshot (run-project-tests! state orders-ns 'subtotal)
            run (tests/result snapshot)
            output (str/join "\n" (:output run))]
        (is (= :passed (:status run)) (pr-str run))
        (is (= :passed (tests/case-status snapshot orders-ns 'subtotal)))
        (is (str/includes? output "Ran 2 test cases"))
        (is (not (str/includes? output "fails on purpose"))))
      (write-project! ["--dir" "spec" "--namespace" "demo.delivery-test"
                       "--exclude" "demo-failure" "--exclude" "setup-error"])
      (let [snapshot (run-project-tests! state orders-ns nil)]
        (is (= :passed (:status (tests/result snapshot))) (pr-str (tests/result snapshot)))
        (is (= :idle (tests/case-status snapshot orders-ns 'skipped-example)))
        (is (str/includes? (str/join "\n" (:output (tests/result snapshot))) "Ran 2 test cases")))
      (write-project! ["--dir" "spec"])
      (let [snapshot (run-project-tests! state orders-ns 'setup-error-example)
            run (tests/result snapshot)]
        (is (= :failed (:status run)) (pr-str run))
        (is (= :error (tests/case-status snapshot orders-ns 'setup-error-example)))
        (is (some-> (get-in run [:failures [orders-ns 'setup-error-example] :message])
                    (str/includes? "Intentional setup error")))
        (is (some #(str/includes? % "Intentional setup error") (:output run))))
      (let [file (io/file root "spec/demo/orders_test.clj")]
        (spit file (str "\n" (slurp file)))
        (let [snapshot (run-project-tests! state orders-ns 'failure-example)
              failure (get-in (tests/result snapshot) [:failures [orders-ns 'failure-example]])]
          (is (= :failed (:status failure)))
          (is (= "(= 30 (orders/subtotal [10 15]))" (:expected failure)))
          (is (nil? (:line failure)))))
      (finally
        (doseq [file (reverse (file-seq root))] (io/delete-file file true))))))

(defn- lein-executable? []
  (when-let [process (try (.start (ProcessBuilder. ^java.util.List ["lein" "version"]))
                          (catch IOException _ nil))]
    (try
      (and (.waitFor process 10 TimeUnit/SECONDS) (zero? (.exitValue process)))
      (finally (when (.isAlive process) (.destroyForcibly process))))))

(deftest leiningen-suite-reports-test-and-namespace-colours
  (when (lein-executable?)
    (let [root (.toFile (Files/createTempDirectory "csb-lein-test-" (make-array FileAttribute 0)))
          project (io/file root "project.clj")
          state (session/create)]
      (try
        (spit project
              (format "(defproject csb-test-fixture \"0.1.0\"
                         :dependencies [[org.clojure/clojure \"%s\"]]
                         :profiles {:test {:injections [(System/setProperty \"csb.fixture\" \"ready\")]}})"
                      (clojure-version)))
        (let [good (io/file root "test/demo/good_test.clj")]
          (io/make-parents good)
          (spit good "(ns demo.good-test (:require [clojure.test :refer [deftest is]]))
                      (deftest profile-injection (is (= \"ready\" (System/getProperty \"csb.fixture\"))))")
          (spit (io/file root "test/demo/bad_test.clj")
                "(ns demo.bad-test (:require [clojure.test :refer [deftest is]]))
                 (deftest passing-check (is true))
                 (deftest failed-check (is (= 1 2)))
                 (deftest raised (throw (ex-info \"Deliberate test exception\" {})))")
          (spit (io/file root "test/demo/fixture_test.clj")
                "(ns demo.fixture-test (:require [clojure.test :refer [deftest is use-fixtures]]))
                 (use-fixtures :once (fn [f] (f) (throw (ex-info \"Teardown failed\" {}))))
                 (deftest body-passes (is true))"))
        (session/open! state root {})
        (let [snapshot (run-project-tests! state nil nil)
              run (tests/result snapshot)
              tree (tests/targets snapshot)]
          (is (= :failed (:status run)) (pr-str run))
          (is (= :passed (tests/case-status snapshot 'demo.good-test 'profile-injection)))
          (is (= :failed (tests/case-status snapshot 'demo.bad-test 'failed-check)))
          (is (= "Deliberate test exception"
                 (get-in run [:failures ['demo.bad-test 'raised] :message])))
          (is (= :passed (tests/namespace-status snapshot 'demo.good-test (tree 'demo.good-test))))
          (is (= :failed (tests/namespace-status snapshot 'demo.fixture-test (tree 'demo.fixture-test))))
          (is (some #(str/includes? % "lein test :only demo.bad-test/failed-check") (:output run))))
        (let [snapshot (run-project-tests! state 'demo.bad-test 'passing-check)
              run (tests/result snapshot)]
          (is (= :passed (:status run)) (pr-str run))
          (is (= :passed (tests/case-status snapshot 'demo.bad-test 'passing-check)))
          (is (some #(str/includes? % "Ran 1 tests") (:output run))))
        (finally
          (doseq [file (reverse (file-seq root))] (io/delete-file file true)))))))

(defn -main [& _]
  (let [r (test/run-tests 'csb.repl-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
