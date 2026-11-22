(ns csb.test-worker
  (:require [clojure.test :as test]))

(defn- case-id [v]
  (let [{:keys [ns name]} (meta v)]
    (when (and ns name)
      [(str (ns-name ns)) (str name)])))

(defonce ^:private cases (atom {}))
(defonce ^:private event-output System/out)

(defn- publish! [id status]
  (when-let [token (System/getenv "CSB_TEST_EVENTS")]
    (.println ^java.io.PrintStream event-output (str "\n" token " " (pr-str [id status])))))

(defn- kaocha-var [event]
  (or (get-in event [:kaocha/testable :kaocha.var/var])
      (when-let [space (find-ns 'kaocha.testable)]
        (some-> (ns-resolve space '*current-testable*) deref :kaocha.var/var))))

(defn observe [event]
  (let [{:keys [type ns]} event]
    (if (and ns (#{:begin-test-ns :end-test-ns} type))
      (publish! [(str ns) nil] (if (= :begin-test-ns type) :running :passed))
      (when-let [id (case-id (or (:var event) (first test/*testing-vars*)
                                (first (:testing-vars event))
                                (kaocha-var event)))]
        (case type
          :begin-test-var (do (swap! cases assoc id :passed) (publish! id :running))
          (:fail :kaocha.type.var/zero-assertions) (swap! cases update id #(if (= % :error) % :failed))
          :error (swap! cases assoc id :error)
          :pending (swap! cases update id #(if (#{:failed :error} %) % :unavailable))
          :end-test-var (when-let [status (get @cases id)]
                          (publish! id status)
                          (swap! cases dissoc id))
          nil)))))

(defn- observe-clojure-test! [lein?]
  (let [^clojure.lang.MultiFn reporter test/report
        events (cond-> [:begin-test-var :end-test-var :fail :error]
                 (not lein?) (into [:begin-test-ns :end-test-ns]))]
    (doseq [event events]
      (let [original (get-method reporter event)]
        (.addMethod reporter event (fn [data] (observe data) (original data)))))))

(defn- observe-lein-namespaces! []
  ;; Leiningen replaces :begin-test-ns reporting and catches exceptions from test-ns.
  (alter-var-root
   #'test/test-ns
   (fn [run]
     (fn [ns-name]
       (let [id [(str ns-name) nil]]
         (publish! id :running)
         (try
           (let [summary (run ns-name)]
             (publish! id (if (test/successful? summary) :passed :failed))
             summary)
           (catch Throwable e
             (publish! id :error)
             (throw e))))))))

(defn- observe-kaocha! []
  (alter-var-root
   (requiring-resolve 'kaocha.api/run)
   (fn [run]
     (fn [config]
       (run (update config :kaocha/reporter
                    (fn [reporters]
                      (conj (if (sequential? reporters) (vec reporters) (vec (keep identity [reporters])))
                            'csb.test-worker/observe))))))))

(when (System/getenv "CSB_TEST_EVENTS")
  (let [runner (System/getenv "CSB_TEST_RUNNER")]
    (observe-clojure-test! (= "lein" runner))
    (case runner
      "lein" (observe-lein-namespaces!)
      "kaocha" (observe-kaocha!)
      nil)))

(defn exec [options]
  (let [f (requiring-resolve (symbol (System/getenv "CSB_TEST_EXEC_FN")))
        result (f options)]
    (when (and (map? result) (some #(and (number? %) (pos? %)) [(:fail result) (:error result)]))
      (System/exit 1))
    result))

(defn- run-test [target]
  (let [v (or (find-var target) (throw (ex-info "Test var not found" {:test target})))]
    (when-not (:test (meta v))
      (throw (ex-info "Var is not a clojure.test test" {:test target})))
    (binding [test/*report-counters* (ref test/*initial-report-counters*)]
      (test/test-vars [v])
      (let [summary @test/*report-counters*]
        (test/do-report (assoc summary :type :summary))
        summary))))

(defn -main [scope & args]
  (try
    (let [targets (mapv symbol args)
          namespaces (if (= scope "test") [(symbol (namespace (first targets)))] targets)]
      (apply require namespaces)
      (let [result (if (= scope "test")
                     (run-test (first targets))
                     (apply test/run-tests namespaces))]
        (System/exit (if (test/successful? result) 0 1))))
    (finally (shutdown-agents))))
