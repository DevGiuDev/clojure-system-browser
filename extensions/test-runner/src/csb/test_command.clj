(ns csb.test-command
  (:require [clojure.string :as str]
            [csb.test-config :as config])
  (:import [java.nio.file Path]))

(defn- exec-symbol [{:keys [exec-fn ns-default ns-aliases]}]
  (when (symbol? exec-fn)
    (if-let [prefix (namespace exec-fn)]
      (symbol (str (get ns-aliases (symbol prefix) prefix)) (name exec-fn))
      (when ns-default (symbol (str ns-default) (name exec-fn))))))

(defn detect [^Path root indexed?]
  (let [deps (config/read-config root "deps.edn")
        aliases (:aliases deps)
        test-aliases (config/test-aliases deps)
        configured (some (fn [id]
                          (let [alias (get aliases id)
                                opts (:main-opts alias)
                                exec (exec-symbol alias)]
                            (cond
                              (and (sequential? opts) (seq opts) (every? string? opts))
                              {:driver :main :main (vec opts) :aliases [id]
                               :runner (some {"kaocha.runner" :kaocha "cognitect.test-runner" :cognitect} opts)}
                              exec
                              {:driver :exec :exec exec :aliases [id]
                               :runner ({'cognitect.test-runner.api/test :cognitect} exec)})))
                        test-aliases)]
    (cond
      configured configured
      (contains? (:tasks (config/read-config root "bb.edn")) 'test) {:driver :bb}
      (config/file? root "project.clj") {:driver :lein}
      (and indexed? (config/file? root "deps.edn"))
      {:driver :clojure-test :aliases (cond-> []
                                       (and (map? (:dev aliases)) (config/file? root "workspace.edn")) (conj :dev)
                                       (seq test-aliases) (conj (first test-aliases)))}
      :else nil)))

(defn supports-scope? [descriptor ns-sym]
  (and descriptor (or (nil? ns-sym) (not= :bb (:driver descriptor)))))

(defn- qualified-target [ns-sym test-sym]
  (when test-sym (symbol (str ns-sym) (name test-sym))))

(defn- remove-scope-options [options runner]
  (let [[module selectors] (case runner
                             :kaocha ["kaocha.runner" #{"--focus" "--focus-meta"}]
                             :cognitect ["cognitect.test-runner"
                                         #{"-n" "--namespace" "-r" "--namespace-regex" "-v" "--var"}])
        [launcher args] (split-at (inc (.indexOf ^java.util.List options module)) options)]
    (loop [argv (vec launcher) args (seq args)]
      (if-let [arg (first args)]
        (let [[flag inline] (str/split arg #"=" 2)]
          (if (contains? selectors flag)
            (recur argv (if inline (next args) (nnext args)))
            (recur (conj argv arg) (next args))))
        argv))))

(defn- clojure-opts [{:keys [driver runner main exec]} root temp ns-sym test-sym namespaces]
  (let [test-id (qualified-target ns-sym test-sym)
        native? (or (nil? ns-sym) runner)
        load-worker ["-i" (str (.resolve ^Path temp "csb/test_worker.clj"))]]
    (cond
      (and native? (= :exec driver))
      {:mode :exec
       :alias (cond-> {:exec-fn 'csb.test-worker/exec}
                ns-sym (assoc :exec-args {:nses [ns-sym] :patterns []
                                          :vars (if test-id [test-id] [])}))
       :environment {"CSB_TEST_EXEC_FN" (str exec)}}

      (and native? (= :main driver))
      {:mode :main
       :alias {:main-opts (into (into load-worker (if ns-sym (remove-scope-options main runner) main))
                               (when ns-sym
                                 (case runner
                                   :kaocha ["--focus" (str (or test-id ns-sym))]
                                   :cognitect (cond-> ["-n" (str ns-sym)] test-id (into ["-v" (str test-id)])))))}
       :environment (when (= :kaocha runner) {"CSB_TEST_RUNNER" "kaocha"})}

      :else
      {:mode :main
       :alias {:extra-paths (config/test-paths root (config/read-config root "deps.edn"))
               :main-opts (into ["-m" "csb.test-worker" (if test-id "test" "namespaces")]
                                (map str (cond test-id [test-id] ns-sym [ns-sym] :else namespaces)))}})))

(defn invocation [{:keys [driver aliases] :as descriptor} root temp ns-sym test-sym namespaces]
  (case driver
    :bb {:argv ["bb" "test"] :label "bb test"}
    :lein (let [args (cond-> ["test"]
                       (and ns-sym (nil? test-sym)) (conj (str ns-sym))
                       test-sym (into [":only" (str (qualified-target ns-sym test-sym))]))
                observer (str (.resolve ^Path temp "csb/test_worker.clj"))]
            {:argv (into ["lein" "update-in" ":injections" "conj"
                          (pr-str (list 'load-file observer)) "--"] args)
             :label (str/join " " (into ["lein"] args))
             :environment {"CSB_TEST_RUNNER" "lein"}})
    (let [{:keys [mode alias environment]} (clojure-opts descriptor root temp ns-sym test-sym namespaces)
          monitor-alias :csb-test-process
          extra-deps {:aliases {monitor-alias (assoc alias :extra-deps
                                                    {'csb/test-process-monitor {:local/root (str temp)}})}}
          mode-option (if (= mode :exec) "-X" "-M")
          alias-args (fn [ids] (apply str mode-option (map #(str ":" (name %)) ids)))]
      {:argv ["clojure" "-Srepro" "-Sdeps" (pr-str extra-deps) (alias-args (conj aliases monitor-alias))]
       :label (str "clojure " (alias-args aliases)
                   (when (or (= :clojure-test driver) (and ns-sym (nil? (:runner descriptor))))
                     " · clojure.test")
                   (when ns-sym (str " · " (or (qualified-target ns-sym test-sym) ns-sym))))
       :environment environment})))
