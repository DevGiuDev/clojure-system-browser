(ns csb.tests
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [csb.extensions :as extensions]
            [csb.java-vm :as java-vm]
            [csb.service :as service]
            [csb.session :as session]
            [csb.test-command :as command])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util UUID]))

(def ^:private extension
  {:id :csb.ext/test-runner :api-version extensions/api-version
   :contributions
   {:views [{:id :tests/tree :title "Tests" :requires #{:source/read}
             :handler (fn [context _] ((:tests context)))}]}})

(def ^:private registry
  (doto (extensions/registry) (extensions/register! extension)))

(defn targets [{:keys [system]}]
  (when system
    (:value (extensions/invoke registry :views :tests/tree
                               {:capabilities #{:source/read}
                                :tests #(-> (service/list-tests system) :value)} nil))))

(defn selected-namespace [state tree]
  (or (some #(when (contains? tree %) %)
            [(get-in state [:tests-view :namespace]) (:namespace state)])
      (first (sort-by str (keys tree)))))

(defn selected-test [state definitions]
  (or (some #(when (= (:id %) (get-in state [:tests-view :test-id])) %) definitions)
      (first definitions)))

(defn select-namespace! [state ns-sym]
  (swap! state update :tests-view assoc :namespace ns-sym :test-id nil
         :detail-scroll 0))

(defn select-test! [state definition]
  (swap! state update :tests-view assoc :test-id (:id definition)
         :detail-scroll 0))

(defn open-source! [state definition]
  (when (and definition (session/select-definition! state (:id definition) {:reveal? true}))
    (swap! state #(-> %
                      (assoc-in [:tests-view :open?] false)
                      (assoc :scroll 0 :help? false :diagnostics? false :editor-error nil)))))

(defn active? [state]
  (= :running (get-in state [:test-runner :last-run :status])))

(defn case-status [state ns-sym test-sym]
  (let [key [ns-sym test-sym]]
    (or (get-in state [:test-runner :cases key])
        (when (and (active? state) (contains? (get-in state [:test-runner :last-run :targets]) key))
          :running)
        :idle)))

(defn namespace-status [state ns-sym definitions]
  (let [statuses (map #(case-status state ns-sym (:name %)) definitions)]
    (cond
      (or (some #{:running} statuses)
          (contains? (get-in state [:test-runner :last-run :active-namespaces]) ns-sym)) :running
      (or (some #{:failed :error} statuses)
          (contains? (get-in state [:test-runner :namespace-errors]) ns-sym)) :failed
      (and (seq statuses) (every? #{:passed} statuses)) :passed
      :else :idle)))

(defn status-label [status]
  (case status
    :running "Running" :passed "Passed" :failed "Failed" :error "Error"
    :cancelled "Cancelled" :unavailable "Unavailable" "Not run"))

(defn result [state]
  (get-in state [:test-runner :last-run]))

(defn result-label [{:keys [status command exit message java-home]}]
  (if status
    (str (status-label status)
         (when command (str " · " command))
         (when (some? exit) (str " · Exit " exit))
         (when message (str " · " message))
         (when java-home (str " · JDK " java-home)))
    "Choose a suite, namespace or test to run."))

(defn- test-root ^Path [state]
  (when-let [root (some-> (:system state) service/project-info :root)]
    (.toPath (io/file root))))

(defn- update-current! [state id f]
  (swap! state update :test-runner
         (fn [runner] (if (= id (get-in runner [:last-run :id])) (f runner) runner))))

(defn- append-output [lines line]
  (let [lines (conj (or lines []) (subs line 0 (min 4096 (count line))))]
    (if (> (count lines) 400) (vec (take-last 400 lines)) lines)))

(defn- read-event [line prefix]
  (when (str/starts-with? line prefix)
    (try
      (let [[[ns-name test-name] status :as event] (edn/read-string (subs line (count prefix)))]
        (when (and (vector? event) (= 2 (count event)) (string? ns-name)
                   (or (nil? test-name) (string? test-name))
                   (#{:running :passed :failed :error :unavailable} status))
          [[(symbol ns-name) (some-> test-name symbol)] status]))
      (catch Exception _ nil))))

(defn- record-line! [state id prefix line]
  (if-let [[[ns-sym test-sym :as target] status] (read-event line prefix)]
    (update-current! state id
                     (fn [runner]
                       (let [previous (get-in runner [:cases target])]
                         (cond-> runner
                           (contains? (get-in runner [:last-run :targets]) target)
                           (assoc-in [:cases target] (if (and (#{:failed :error} previous) (not= :error status))
                                                      previous status))
                           (nil? test-sym)
                           (update-in [:last-run :active-namespaces]
                                      (if (= :running status) (fnil conj #{}) disj) ns-sym)
                           (and (nil? test-sym) (#{:error :failed} status))
                           (update :namespace-errors (fnil conj #{}) ns-sym)
                           (= "" (peek (get-in runner [:last-run :output])))
                           (update-in [:last-run :output] pop)))))
    (update-current! state id #(update-in % [:last-run :output] append-output line))))

(defn- kill! [^Process process]
  (with-open [descendants (.descendants (.toHandle process))]
    (doseq [^java.lang.ProcessHandle child (reverse (.toList descendants))]
      (.destroyForcibly child)))
  (.destroyForcibly process))

(defn cancel! [state]
  (let [[_ next] (swap-vals! state
                            #(if (active? %) (assoc-in % [:test-runner :last-run :cancel?] true) %))]
    (when-let [process (get-in next [:test-runner :last-run :process])]
      (kill! process))))

(defn- complete! [state id {:keys [exit status] :as result}]
  (update-current!
   state id
   (fn [runner]
     (let [{:keys [cancel? targets scope monitored? active-namespaces]} (:last-run runner)
           [namespace-name test-name] scope
           statuses (map #(get-in runner [:cases %]) targets)
           observed (some #{:passed :failed :error} statuses)
           missing? (and monitored? (seq targets)
                         (or (nil? observed) (and namespace-name (not-every? #{:passed} statuses))))
           status (cond cancel? :cancelled
                        status status
                        (or (not (zero? exit)) (some #{:failed :error} statuses)) :failed
                        missing? :unavailable
                        :else :passed)
           pending (filter #(= :running (get-in runner [:cases %])) targets)
           errored-namespaces (when (#{:error :failed} status)
                                (cond-> (set active-namespaces)
                                  (and namespace-name (nil? test-name)) (conj namespace-name)))]
       (cond-> (-> runner
                   (update :cases #(apply dissoc % pending))
                   (update :last-run #(-> %
                                          (merge result {:status status})
                                          (dissoc :process :cancel? :active-namespaces))))
         (= :unavailable status)
         (assoc-in [:last-run :message] "Some selected tests did not report a result.")
         (and (not monitored?) (seq targets) (#{:passed :failed} status))
         (assoc-in [:last-run :message] "This command reports an exit code for the whole run.")
         (seq errored-namespaces)
         (update :namespace-errors (fnil into #{}) errored-namespaces))))))

(defn- child-run! [state id root {:keys [argv label environment]}]
  (let [home (java-vm/project-home (str root))
        builder (doto (ProcessBuilder. ^java.util.List argv)
                  (.directory (.toFile ^Path root))
                  (.redirectErrorStream true))
        token (str "CSB-TEST-" id)]
    (update-current! state id #(update % :last-run assoc :command label :java-home home))
    (.putAll (.environment builder) (assoc environment "CSB_TEST_EVENTS" token))
    (java-vm/configure-process! builder home)
    (let [process (.start builder)]
      (try
        (let [next (update-current! state id #(assoc-in % [:last-run :process] process))]
          (when (or (not= id (get-in next [:test-runner :last-run :id]))
                    (get-in next [:test-runner :last-run :cancel?]))
            (kill! process)))
        (.close (.getOutputStream process))
        (with-open [reader (.inputReader process)]
          (doseq [line (line-seq reader)]
            (record-line! state id (str token " ") line)))
        (complete! state id {:exit (.waitFor process)})
        (finally (when (.isAlive process) (kill! process)))))))

(defn- run-process! [state id root descriptor ns-sym test-sym namespaces]
  (try
    (let [attrs (make-array FileAttribute 0)
          temp (Files/createTempDirectory "csb-tests-" attrs)
          deps-file (.resolve temp "deps.edn")
          worker (.resolve temp "csb/test_worker.clj")]
      (try
        (spit (.toFile deps-file) "{:paths [\".\"]}")
        (Files/createDirectory (.getParent worker) attrs)
        (with-open [input (io/input-stream (io/resource "csb/test_worker.clj"))]
          (io/copy input (.toFile worker)))
        (child-run! state id root (command/invocation descriptor root temp ns-sym test-sym namespaces))
        (finally
          (doseq [path [worker (.getParent worker) deps-file temp]] (Files/deleteIfExists path)))))
    (catch Exception e
      (complete! state id {:status :error :message (ex-message e)}))))

(defn run! [state ns-sym test-sym]
  (let [snapshot @state
        root (test-root snapshot)
        tree (targets snapshot)
        namespaces (sort-by str (keys tree))
        descriptor (when root (command/detect root (seq tree)))
        definitions (if ns-sym (get tree ns-sym) (mapcat val tree))
        affected (into #{} (comp (filter #(or (nil? test-sym) (= test-sym (:name %))))
                                 (map (juxt :namespace :name)))
                       definitions)
        run? (and (command/supports-scope? descriptor ns-sym) (or (nil? ns-sym) (seq affected)))
        id (str (UUID/randomUUID))
        run (if run?
              {:id id :status :running :scope [ns-sym test-sym] :targets affected :output []
               :monitored? (not= :bb (:driver descriptor))}
              {:id id :status :unavailable
               :message (cond
                          (nil? descriptor) "No test command or indexed JVM tests found."
                          (seq affected) "The configured command only supports suite runs."
                          :else "Select an indexed test namespace or test.")})
        [_ next] (swap-vals!
                  state
                  (fn [s]
                    (if (or (active? s) (not (identical? (:system snapshot) (:system s))))
                      s
                      (cond-> (-> s
                                  (assoc-in [:test-runner :last-run] run)
                                  (assoc-in [:tests-view :detail-scroll] 0))
                        run? (update-in [:test-runner :cases] #(apply dissoc % affected))
                        run? (update-in [:test-runner :namespace-errors]
                                        #(if ns-sym (disj % ns-sym) #{}))))))]
    (when (and run? (= id (get-in next [:test-runner :last-run :id])))
      (doto (Thread. ^Runnable #(run-process! state id root descriptor ns-sym test-sym namespaces)
                     "csb-test-runner")
        (.setDaemon true)
        (.start)))))
