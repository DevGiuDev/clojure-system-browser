(ns csb.test-worker
  (:require [clojure.string :as str]
            [clojure.test :as test]))

(defn- case-id [v]
  (let [{:keys [ns name]} (meta v)]
    (when (and ns name)
      [(str (ns-name ns)) (str name)])))

(defonce ^:private cases (atom {}))
(defonce ^:private event-output System/out)

(defn- publish!
  ([id status] (publish! id status nil))
  ([id status details]
   (when-let [token (System/getenv "CSB_TEST_EVENTS")]
     (let [event (cond-> [id status] details (conj details))]
       (.println ^java.io.PrintStream event-output (str "\n" token " " (pr-str event)))))))

(defn- clip-text [text]
  (if (> (count text) 240) (str (subs text 0 239) "…") text))

(defn- short-text [text]
  (when (string? text)
    (let [text (str/trim (str/replace text #"\s+" " "))]
      (when (seq text) (clip-text text)))))

(defn- printed [value]
  (try
    (binding [*print-length* 12 *print-level* 5 *print-meta* false]
      (clip-text (pr-str value)))
    (catch Throwable _ nil)))

;; clojure.core/requiring-resolve and ex-message are 1.10+. The worker loads
;; inside the project's JVM, which may pin an older Clojure (minimum 1.8).
(defn- resolve-requiring [sym]
  (let [ns (symbol (namespace sym))]
    (require ns)
    (resolve sym)))

(defn- failure-details [{:keys [file line doc message expected actual thrown] :as event}]
  (let [unexpected? (instance? Throwable actual)
        exception (if unexpected? actual thrown)
        cause (when exception (or (short-text (.getMessage ^Throwable exception))
                                  (-> exception class .getSimpleName)))
        case-name (short-text doc)
        message (if unexpected? cause (or (short-text message) cause))]
    (cond-> {}
      case-name (assoc :case case-name)
      message (assoc :message message)
      (some? expected) (assoc :expected (printed expected))
      (and (contains? event :actual) (not unexpected?)
           (or (nil? thrown) (some? actual) (some? expected)))
      (assoc :actual (printed actual))
      (string? file) (assoc :file (subs file 0 (min 1024 (count file))))
      (and (integer? line) (pos? line)) (assoc :line line))))

(defn report-lazytest [_config {:keys [type var doc results]}]
  (when-let [id (case type
                  (:begin-test-var :end-test-var) (case-id var)
                  (:begin-test-ns :end-test-ns) (when (symbol? doc) [(str doc) nil])
                  nil)]
    (case type
      (:begin-test-var :begin-test-ns) (publish! id :running)
      (let [{:keys [total fail]} ((resolve-requiring 'lazytest.results/summarize) results)
            failure (when (and (= :end-test-var type) (pos? fail))
                      (some #(when (= :fail (:type %)) %)
                            ((resolve-requiring 'lazytest.results/result-seq) results)))]
        (publish! id (cond (pos? fail) :failed (pos? total) :passed :else :unavailable)
                  (some-> failure failure-details))))))

(defn- kaocha-var [event]
  (or (get-in event [:kaocha/testable :kaocha.var/var])
      (when-let [space (find-ns 'kaocha.testable)]
        (some-> (ns-resolve space '*current-testable*) deref :kaocha.var/var))))

(defn- remember-failure! [id status event]
  (let [details (failure-details event)]
    (swap! cases update id
           (fn [result]
             (cond
               (= :error (:status result)) result
               (or (= :error status) (nil? (:details result))) {:status status :details details}
               :else (assoc result :status status))))))

(defn observe [event]
  (let [{:keys [type ns]} event]
    (if (and ns (#{:begin-test-ns :end-test-ns} type))
      (publish! [(str ns) nil] (if (= :begin-test-ns type) :running :passed))
      (when-let [id (case-id (or (:var event) (first test/*testing-vars*)
                                (first (:testing-vars event))
                                (kaocha-var event)))]
        (case type
          :begin-test-var (do (swap! cases assoc id {:status :passed}) (publish! id :running))
          (:fail :kaocha.type.var/zero-assertions) (remember-failure! id :failed event)
          :error (remember-failure! id :error event)
          :pending (swap! cases update-in [id :status] #(if (#{:failed :error} %) % :unavailable))
          :end-test-var (when-let [{:keys [status details]} (get @cases id)]
                          (publish! id status details)
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
   (resolve-requiring 'kaocha.api/run)
   (fn [run]
     (fn [config]
       (run (update config :kaocha/reporter
                    (fn [reporters]
                      (conj (if (sequential? reporters) (vec reporters) (vec (keep identity [reporters])))
                            'csb.test-worker/observe))))))))

(when (System/getenv "CSB_TEST_EVENTS")
  (let [runner (System/getenv "CSB_TEST_RUNNER")]
    (when-not (= "lazytest" runner)
      (observe-clojure-test! (= "lein" runner)))
    (case runner
      "lein" (observe-lein-namespaces!)
      "kaocha" (observe-kaocha!)
      nil)))

(defn exec [options]
  (let [f (resolve-requiring (symbol (System/getenv "CSB_TEST_EXEC_FN")))
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
