(ns csb.test-runner
  (:require [clojure.test :as test]
            [csb.source-test]
            [csb.extensions-test]
            [csb.session-test]
            [csb.architecture-test]
            [csb.editor-test]
            [csb.syntax-test]))

(defn -main [& _]
  (let [r (test/run-tests 'csb.source-test 'csb.extensions-test 'csb.session-test 'csb.architecture-test 'csb.syntax-test 'csb.editor-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
