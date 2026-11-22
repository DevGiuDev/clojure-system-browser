(ns csb.tests
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [csb.extensions :as extensions]
            [csb.java-vm :as java-vm]
            [csb.service :as service]
            [csb.session :as session]
            [csb.source :as source]
            [csb.test-command :as command])
  (:import [java.io InputStream]
           [java.nio.file Files LinkOption OpenOption Path]
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
  (when-let [run (get-in state [:test-runner :last-run])]
    (assoc run :failures (select-keys (get-in state [:test-runner :failures]) (:targets run)))))

(defn- plain-output [text]
  (str/replace text #"\u001b\[[0-?]*[ -/]*[@-~]" ""))

(defn- failure-description [{:keys [case message expected actual]}]
  (let [parts (keep identity [(some-> case plain-output)
                              (some-> message plain-output)
                              (when expected (str "Expected: " (plain-output expected)))
                              (when actual (str "Actual: " (plain-output actual)))])]
    (if (seq parts) (str/join " · " parts) "See the test log for details.")))

(defn log-lines [{:keys [output failures]}]
  (into (mapv (fn [line]
                (let [text (plain-output line)]
                  {:text text
                   :error? (boolean
                            (or (re-find #"\u001b\[(?:\d+;)*(?:31|91)(?:;\d+)*m" line)
                                (re-find #"(?i)^\s*(?:FAIL in\b|ERROR in\b|Execution error\b|Exception in thread\b|×|[1-9]\d* (?:failure|error)s?\b)"
                                         text)))}))
              output)
        (map (fn [[[ns-sym test-sym] {:keys [status] :as failure}]]
               {:text (str (if (= :error status) "ERROR " "FAIL ")
                           ns-sym "/" test-sym " · " (failure-description failure))
                :error? true}))
        (sort-by (comp str key) failures)))

(defn- source-identity [definition]
  (select-keys definition [:id :revision]))

(defn source-failure [state {:keys [namespace name location] :as definition}]
  (when-let [{:keys [source status file line] :as failure}
             (get-in state [:test-runner :failures [namespace name]])]
    (when (= source (source-identity definition))
      (let [path (str/replace (:file definition) "\\" "/")
            reported (some-> file (str/replace "\\" "/"))
            row (when (and reported (or (= path reported) (str/ends-with? path (str "/" reported)))
                           line (<= (:row location) line (:end-row location)))
                  (- line (:row location)))]
        {:heading (str (status-label status) (when row (str " · Line " line)))
         :description (failure-description failure)
         :row row}))))

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

(defn- failure-fields [details]
  (when (map? details)
    (into (if (pos-int? (:line details)) {:line (:line details)} {})
          (keep (fn [key]
                  (let [value (get details key)]
                    (when (and (string? value) (<= (count value) 1024) (not (str/blank? value)))
                      [key (str/replace value #"\r\n?|\n" " ")]))))
          [:file :case :message :expected :actual])))

(defn- read-event [line prefix]
  (when (and (<= (count line) 8192) (str/starts-with? line prefix))
    (try
      (let [[[ns-name test-name :as id] status details :as event] (edn/read-string (subs line (count prefix)))]
        (when (and (vector? event) (<= 2 (count event) 3) (vector? id) (= 2 (count id)) (string? ns-name)
                   (or (nil? test-name) (string? test-name))
                   (#{:running :passed :failed :error :unavailable} status))
          [[(symbol ns-name) (some-> test-name symbol)] status (failure-fields details)]))
      (catch Exception _ nil))))

(defn- record-failure [runner target status details]
  (assoc-in runner [:failures target]
            (assoc details :status status
                   :source (get-in runner [:last-run :source-identities target]))))

(defn- checked-location [state run-id target details]
  (if (:line details)
    (let [{:keys [id source-identities]} (get-in state [:test-runner :last-run])
          {[file] :id :keys [revision]} (get source-identities target)
          current? (and (= id run-id) file
                        (try
                          (let [path (source/path file)]
                            (when (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                              (with-open [^InputStream input
                                          (Files/newInputStream path (into-array OpenOption [LinkOption/NOFOLLOW_LINKS]))]
                                (= revision (source/revision (.readAllBytes input))))))
                          (catch Exception _ false)))]
      (if current? details (dissoc details :line :file)))
    details))

(defn- record-line! [state id prefix line]
  (if-let [[[ns-sym test-sym :as target] status details] (read-event line prefix)]
    (let [details (checked-location @state id target details)]
      (update-current! state id
                       (fn [runner]
                         (let [previous (get-in runner [:cases target])
                               targeted? (contains? (get-in runner [:last-run :targets]) target)]
                           (cond-> runner
                             targeted?
                             (assoc-in [:cases target] (if (and (#{:failed :error} previous) (not= :error status))
                                                        previous status))
                             (and targeted? (#{:failed :error} status)
                                  (or (= :error status) (nil? (get-in runner [:failures target]))))
                             (record-failure target status details)
                             (nil? test-sym)
                             (update-in [:last-run :active-namespaces]
                                        (if (= :running status) (fnil conj #{}) disj) ns-sym)
                             (and (nil? test-sym) (#{:error :failed} status))
                             (update :namespace-errors (fnil conj #{}) ns-sym)
                             (= "" (peek (get-in runner [:last-run :output])))
                             (update-in [:last-run :output] pop))))))
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

(defn- abort-message [output]
  (let [lines (mapv plain-output output)
        index (last (keep-indexed #(when (re-find #"^(?:Execution error|Exception in thread)\b" %2) %1) lines))]
    (when index
      (let [reason (some #(when-not (str/blank? %) %) (subvec lines (inc index)))
            message (if (and reason (not (str/starts-with? reason "Full report at:")))
                      (str (lines index) " " reason)
                      (lines index))]
        (subs message 0 (min (count message) 512))))))

(defn- complete! [state id {:keys [exit status] :as result}]
  (update-current!
   state id
   (fn [runner]
     (let [{:keys [cancel? targets scope monitored? active-namespaces]
            framework :runner} (:last-run runner)
           [namespace-name test-name] scope
           lazytest? (= :lazytest framework)
           statuses (map #(get-in runner [:cases %]) targets)
           observed (some #{:passed :failed :error} statuses)
           missing? (and monitored? (seq targets)
                         (or (nil? observed)
                             (and namespace-name (not lazytest?) (not-every? #{:passed} statuses))))
           status (cond cancel? :cancelled
                        status status
                        (or (not (zero? exit)) (some #{:failed :error} statuses)) :failed
                        missing? :unavailable
                        :else :passed)
           pending (filter #(= :running (get-in runner [:cases %])) targets)
           aborted? (and lazytest? (#{:error :failed} status))
           errored-namespaces (when (#{:error :failed} status)
                                (cond-> (set active-namespaces)
                                  (and namespace-name (nil? test-name)) (conj namespace-name)))]
       (cond-> (-> (if (and aborted? (seq pending))
                     (let [message (or (:message result) (abort-message (get-in runner [:last-run :output])))]
                       (reduce #(record-failure %1 %2 :error (when message {:message message})) runner pending))
                     runner)
                   (update :cases #(if aborted?
                                     (into % (map (fn [target] [target :error])) pending)
                                     (apply dissoc % pending)))
                   (update :last-run #(-> %
                                          (merge result {:status status})
                                          (dissoc :process :cancel? :active-namespaces :source-identities))))
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
        definitions (mapcat val tree)
        descriptor (when root (command/detect root (some #(nil? (:test-framework %)) definitions)))
        framework (when (= :lazytest (:runner descriptor)) :lazytest)
        supported (filter #(= framework (:test-framework %)) definitions)
        namespaces (sort-by str (distinct (map :namespace supported)))
        selected? (some #(and (= ns-sym (:namespace %))
                              (or (nil? test-sym) (= test-sym (:name %)))) definitions)
        matched (group-by (juxt :namespace :name)
                          (filter #(and (or (nil? ns-sym) (= ns-sym (:namespace %)))
                                        (or (nil? test-sym) (= test-sym (:name %))))
                                  supported))
        affected (set (keys matched))
        run? (and (command/supports-scope? descriptor ns-sym) (or (nil? ns-sym) (seq affected)))
        id (str (UUID/randomUUID))
        run (if run?
              {:id id :status :running :scope [ns-sym test-sym] :targets affected :output []
               :runner (:runner descriptor)
               :source-identities (into {} (keep (fn [[target [definition duplicate]]]
                                                   (when-not duplicate [target (source-identity definition)])))
                                        matched)
               :monitored? (not= :bb (:driver descriptor))}
              {:id id :status :unavailable
               :message (cond
                          (nil? descriptor) "No test command or indexed JVM tests found."
                          (seq affected) "The configured command only supports suite runs."
                          selected? "The configured command does not run the selected tests."
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
                        run? (update-in [:test-runner :failures] #(apply dissoc % affected))
                        run? (update-in [:test-runner :namespace-errors]
                                        #(if ns-sym (disj % ns-sym) #{}))))))]
    (when (and run? (= id (get-in next [:test-runner :last-run :id])))
      (doto (Thread. ^Runnable #(run-process! state id root descriptor ns-sym test-sym namespaces)
                     "csb-test-runner")
        (.setDaemon true)
        (.start)))))
