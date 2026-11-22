(ns csb.repl
  "Shared nREPL commands and panel state for Desktop and TUI."
  (:require [clojure.string :as str]
            [csb.model :as model]
            [csb.runtime-inspection :as inspection]
            [csb.service :as service]
            [nrepl.transport :as transport])
  (:import [java.io Closeable IOException]
           [java.net InetSocketAddress Socket]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path]
           [java.util UUID]
           [java.util.concurrent Callable FutureTask TimeUnit TimeoutException]))

(def default-host "127.0.0.1")
(def ^:private max-code 16384)
(def ^:private max-entries 200)
(def ^:private max-entry 4096)

(defn- identifier [] (str (UUID/randomUUID)))
(defn- deadline [ms] (+ (System/nanoTime) (* 1000000 ms)))
(defn- expired? [until] (and until (>= (System/nanoTime) until)))
(defn- remaining-ms [until] (quot (- until (System/nanoTime)) 1000000))
(defn- statuses [response]
  (into #{} (map #(if (keyword? %) (subs (str %) 1) %)) (:status response)))

(defn- append [panel kind text]
  (if (nil? text)
    panel
    (let [text (str text)
          shown (if (> (count text) max-entry) (str (subs text 0 max-entry) " …") text)]
      (update panel :entries (fn [entries]
                               (let [entries (conj (or entries []) [kind shown])]
                                 (if (> (count entries) max-entries)
                                   (into [] (next entries))
                                   entries)))))))

(defn transcript [panel]
  (str/join "\n" (map (fn [[kind text]]
                        (str ({:input "> " :value "=> " :out "out: " :err "err: "
                               :error "error: " :notice "· "} kind)
                             (str/replace text #"\r?\n$" "")))
                      (:entries panel))))

(defn- change! [state connection f]
  (swap! state update :repl
         (fn [panel]
           (if (identical? connection (:connection panel)) (f panel) panel))))

(defn- exchange! [wire request until]
  (let [id (identifier)]
    (transport/send wire (assoc request :id id))
    (loop [data {}]
      (let [remaining (remaining-ms until)]
        (when-not (pos? remaining)
          (model/fail! :timeout "nREPL handshake timed out" {}))
        (if-let [response (transport/recv wire remaining)]
          (if (= id (:id response))
            (let [data (merge data response)]
              (if (contains? (statuses response) "done") data (recur data)))
            (recur data))
          (model/fail! :timeout "nREPL handshake timed out" {}))))))

(defn- submit
  ([panel code ns] (submit panel code ns nil))
  ([panel code ns queue]
   (cond
     (not= :connected (:phase panel)) (assoc panel :status "Connect to nREPL first")
     (:active panel) panel
     (str/blank? code) (assoc panel :status "Enter an expression")
     (> (count code) max-code) (assoc panel :status "Input exceeds 16384 characters")
     :else
     (-> panel
         (append :input (str (when ns (str "[" ns "] ")) code))
         (assoc :active {:id (identifier) :code code :ns ns :due (deadline (:eval-timeout-ms panel))
                         :block-queue queue}
                :status "Evaluating…")))))

(defn evaluate-input! [state]
  (swap! state update :repl (fn [panel]
                              (let [next (submit panel (:input panel) nil)]
                                (if (and (nil? (:active panel)) (:active next))
                                  (assoc next :input "")
                                  next)))))

(defn- block-advance
  "Done branch of a whole-block evaluation: advance to the next body form
   after a complete response without error; an error, interrupt or timeout
   drops the remaining forms. Threaded by cond->, so `panel` comes first."
  [panel active failure status]
  (if (and (seq (:block-queue active)) (not failure) (not (:failed? active))
           (nil? (:stop active)) (not (contains? status "interrupted")))
    (let [queue (:block-queue active)]
      (submit (assoc panel :active nil) (first queue) (:ns active) (next queue)))
    (assoc panel :active nil :status (cond
                                       (= :timeout (:stop active)) "Evaluation timed out"
                                       (contains? status "interrupted") "Evaluation interrupted"
                                       (or failure (:failed? active)) "Evaluation failed"
                                       :else "Evaluation finished"))))

(defn- evaluation-response [panel response]
  (let [active (:active panel)
        current? (= (:id response) (:id active))
        status (statuses response)
        failure (some status ["namespace-not-found" "unknown-session" "unknown-op"
                              "unknown-code-type" "server-error" "eval-error" "error"])
        panel (-> panel
                  (append :out (:out response))
                  (append :err (:err response)))]
    (if current?
      (cond-> (append panel :value (:value response))
        (and (:ns response) (:value response) (nil? (:ns active)))
        (assoc :namespace (:ns response))
        failure (-> (assoc-in [:active :failed?] true)
                    (append :error (str failure (when-let [ex (:root-ex response)] (str " · " ex)))))
        (contains? status "nrepl.middleware.print/truncated")
        (append :notice "Printed value truncated")
        (contains? status "need-input")
        (assoc :status "Program is waiting for stdin. Interrupt to return to the REPL.")
        (contains? status "done")
        (block-advance active failure status))
      panel)))

(defn- inspection-ended [panel error observation]
  (-> panel
      (assoc :active nil)
      (update :inspection assoc :error error :observation observation)))

(defn- stopped [panel reason]
  (if (get-in panel [:active :inspection-target])
    (inspection-ended panel
                      (:error (model/error reason (if (= :timeout reason)
                                                    "Runtime inspection timed out"
                                                    "Runtime inspection interrupted")))
                      nil)
    (assoc panel :active nil :status (if (= :timeout reason)
                                      "Evaluation timed out"
                                      "Evaluation interrupted"))))

(defn- inspection-response [panel response]
  (let [{:keys [id inspection-target stop inspection-error observation]} (:active panel)
        status (statuses response)]
    (if (not= id (:id response))
      panel
      (let [value (:value response)
            error (or inspection-error (inspection/failure status))
            parsed (when (and value (not error))
                     (try
                       (model/ok (inspection/read-observation inspection-target value))
                       (catch Exception _
                         (model/error :protocol "Invalid runtime inspection response"))))
            error (or error (:error parsed))
            observation (or (:value parsed) observation)]
        (if (status "done")
          (if (or stop (status "interrupted"))
            (stopped panel (or stop :interrupt))
            (inspection-ended panel
                              (or error (when (nil? observation)
                                          (:error (model/error :protocol "Runtime inspection returned no data"))))
                              (when-not error observation)))
          (update panel :active assoc :inspection-error error :observation observation))))))

(defn- stop-request! [state connection reason]
  (change! state connection #(-> %
                                 (assoc-in [:active :stop] reason)
                                 (cond-> (nil? (get-in % [:active :inspection-target]))
                                   (assoc :status (if (= :timeout reason)
                                                    "Evaluation timed out; interrupting…"
                                                    "Interrupting…")))))
  {:until (deadline 1500) :retry-at (System/nanoTime)})

(defn- interrupt-if-due! [wire session id interrupt]
  (if (expired? (:retry-at interrupt))
    (let [request-id (identifier)]
      (transport/send wire {:op "interrupt" :id request-id :session session :interrupt-id id})
      (assoc interrupt :request-id request-id :retry-at nil))
    interrupt))

(defn- run-session! [state connection wire console-session inspection-session interrupt?]
  (loop [sent nil interrupt nil]
    (let [{:keys [phase active]} (:repl @state)
          {:keys [id code ns due stop inspection-target]} active
          session (if inspection-target inspection-session console-session)
          new? (and id (not= sent id))
          reason (or stop (when (expired? due) :timeout))]
      (when (= :connected phase)
        (if (and new? reason)
          (do
            (change! state connection #(stopped % reason))
            (recur nil nil))
          (let [interrupt (when reason (or interrupt (stop-request! state connection reason)))]
            (when new?
              (transport/send wire (cond-> {:op "eval" :id id :session session :code code
                                            :nrepl.middleware.print/quota
                                            (if inspection-target 262144 65536)}
                                     ns (assoc :ns ns))))
            (when (expired? (:until interrupt))
              (model/fail! reason (if (= :timeout reason)
                                    (str (if inspection-target "Runtime inspection" "Evaluation")
                                         " timed out; connection closed")
                                    "Interrupt did not finish; connection closed") {}))
            (let [interrupt (if interrupt? (interrupt-if-due! wire session id interrupt) interrupt)
                  response (transport/recv wire 35)
                  response-id (:id response)
                  status (statuses response)]
              (when response-id
                (when (and (= id response-id) (contains? status "unknown-session"))
                  (model/fail! :session-closed "nREPL session closed; reconnect to continue" {}))
                (cond
                  (= console-session (:session response))
                  (change! state connection #(evaluation-response % response))
                  (and inspection-session (= inspection-session (:session response)))
                  (change! state connection #(inspection-response % response))))
              (recur id (cond-> interrupt
                          (and response-id (= (:request-id interrupt) response-id)
                               (some status ["session-idle" "interrupt-id-mismatch"]))
                          (assoc :retry-at (deadline 70)))))))))))

(defn- clone-session! [wire until]
  (or (:new-session (exchange! wire {:op "clone"} until))
      (model/fail! :protocol "nREPL did not return a session" {})))

(defn- close-session! [wire session]
  (when session
    (try (exchange! wire {:op "close" :session session} (deadline 500))
         (catch Exception _))))

(defn- resolve-endpoint! [host port until]
  (let [lookup (FutureTask. ^Callable #(InetSocketAddress. ^String host (int port)))]
    (doto (Thread. lookup "csb-nrepl-dns") (.setDaemon true) (.start))
    (try
      (.get lookup (max 1 (remaining-ms until)) TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (model/fail! :timeout "nREPL host lookup timed out" {}))
      (finally (.cancel lookup true)))))

(defn- run-connection! [state connection host port connect-timeout-ms]
  (let [status
        (try
          (with-open [socket (Socket.)]
            (let [until (deadline connect-timeout-ms)
                  endpoint (resolve-endpoint! host port until)]
              (.connect socket endpoint (int (max 1 (remaining-ms until)))))
            (with-open [^Closeable wire (transport/bencode socket)]
              (let [until (deadline connect-timeout-ms)
                    description (exchange! wire {:op "describe"} until)
                    ops (:ops description)]
                (when-not (every? #(contains? ops %) [:clone :close :eval])
                  (model/fail! :unsupported "This server does not offer clone, close and eval" {}))
                (let [console-session (clone-session! wire until)]
                  (try
                    (let [inspection-session (try (clone-session! wire until)
                                                  (catch clojure.lang.ExceptionInfo _ nil))
                          interrupt? (contains? ops :interrupt)]
                      (try
                        (change! state connection
                                 #(if (= :connecting (:phase %))
                                    (assoc % :phase :connected :status "Connected" :interrupt? interrupt?
                                           :inspection? (some? inspection-session)
                                           :namespace (or (get-in description [:aux :current-ns]) "user"))
                                    %))
                        (run-session! state connection wire console-session inspection-session interrupt?)
                        (finally (close-session! wire inspection-session))))
                    (finally (close-session! wire console-session)))))))
          "Disconnected"
          (catch Exception e
            (let [phase (get-in @state [:repl :phase])]
              (case phase
                :disconnecting "Disconnected"
                :connected (if (:type (ex-data e))
                             (ex-message e)
                             (str "Connection lost · " (ex-message e)))
                (str "Connection failed · " (ex-message e))))))]
    (change! state connection
             #(-> %
                  (dissoc :connection :active :namespace :interrupt? :inspection :inspection?)
                  (assoc :phase :disconnected :status status)))))

(defn- parse-port [text]
  (when-let [port (some-> text str/trim parse-long)]
    (when (<= 1 port 65535) port)))

(defn- read-project-port [root]
  (when root
    (let [file (Path/of ^String root (into-array String [".nrepl-port"]))
          no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])]
      (try
        (when (Files/isRegularFile file no-links)
          (with-open [input (Files/newInputStream file (into-array OpenOption no-links))]
            (let [bytes (.readNBytes input 65)]
              (when (<= (alength bytes) 64)
                (parse-port (String. bytes StandardCharsets/UTF_8))))))
        (catch IOException _ nil)
        (catch SecurityException _ nil)))))

(defn show-panel! [state]
  (let [{:keys [system repl]} @state
        root (some-> system service/project-info :root)
        {:keys [host port]} (get-in @state [:settings :repl] {})
        changed-project? (not= root (:port-project repl))
        detect? (or changed-project?
                    (and (nil? (:connection repl)) (not (:port-overridden? repl))
                         (= default-host (get repl :host default-host))))
        ;; The project's .nrepl-port wins; the settings port is the fallback.
        port (when detect? (str (or (read-project-port root) port)))]
    (swap! state (fn [s]
                   (cond-> (assoc s :repl-open? true)
                     detect? (update :repl assoc :port-project root :port port :port-overridden? false)
                     changed-project? (update :repl #(if host (assoc % :host host)
                                                        (dissoc % :host))))))))

(defn edit-port [panel text]
  (if (= (or (:port panel) "") text)
    panel
    (assoc panel :port text :port-overridden? true)))

(defn connect!
  "Connects asynchronously to the endpoint entered in the panel."
  ([state] (connect! state {}))
  ([state {:keys [connect-timeout-ms eval-timeout-ms]
           :or {connect-timeout-ms 3000 eval-timeout-ms 60000}}]
   (let [connection (Object.)
         port #(parse-port (:port %))
         address #(str/trim (get % :host default-host))
         [_ next] (swap-vals! state update :repl
                              (fn [panel]
                                (if (contains? #{nil :disconnected} (:phase panel))
                                  (cond
                                    (not (port panel))
                                    (assoc panel :phase :disconnected :status "Enter a port from 1 to 65535")
                                    (str/blank? (address panel))
                                    (assoc panel :phase :disconnected :status "Enter an nREPL host")
                                    :else
                                    (assoc panel :connection connection :phase :connecting
                                           :eval-timeout-ms eval-timeout-ms :status "Connecting…" :entries []))
                                  panel)))
         panel (:repl next)]
     (when (identical? connection (:connection panel))
       (let [number (port panel) host (address panel)]
         (.start (Thread. ^Runnable #(run-connection! state connection host number connect-timeout-ms)
                          "csb-nrepl")))))))

(defn disconnect! [state]
  (when (get-in @state [:repl :connection])
    (swap! state update :repl
           #(if (:connection %) (assoc % :phase :disconnecting :status "Disconnecting…") %))))

(defn- jvm-file? [entry]
  (when-let [file (:file entry)]
    (or (str/ends-with? file ".clj") (str/ends-with? file ".cljc"))))

(defn jvm-definition? [{:keys [definition]}]
  (jvm-file? definition))

(defn evaluable?
  "Whether the panel button can evaluate the current entry: a Clojure JVM
   definition, or a comment block with at least one body form."
  [{:keys [definition]}]
  (if (:block? definition)
    (boolean (and (jvm-file? definition) (seq (:body-forms definition))))
    (boolean (jvm-definition? {:definition definition}))))

(defn row-evaluable?
  "Whether a Definitions row offers Eval in REPL: blocks need a JVM file and
   body forms, definitions a JVM file. Revisions are checked when running."
  [entry]
  (if (:block? entry)
    (boolean (and (jvm-file? entry) (seq (:body-forms entry))))
    (boolean (jvm-file? entry))))

(defn selection-evaluable? [definition]
  (boolean (jvm-file? definition)))

(defn- block-tag [block]
  (str (last (str/split (str (:file block)) #"[\\/]")) ":" (get-in block [:location :row])))

(defn- indexed-entry [system entry]
  (when system
    (let [result (service/get-definition-source system (:id entry))]
      (when (= :ok (:status result)) (:value result)))))

(defn- busy-status [s]
  (assoc-in s [:repl :status] "Wait for the current evaluation or interrupt it"))

(defn- start-block! [state block]
  (swap! state
         (fn [{:keys [repl] :as s}]
           (let [forms (mapv :source (:body-forms block))]
             (cond
               (not= :connected (:phase repl))
               (assoc-in s [:repl :status] "Connect to nREPL first")
               (:active repl)
               (busy-status s)
               (some #(> (count %) max-code) forms)
               (assoc-in s [:repl :status] "A form of the block exceeds 16384 characters")
               :else
               (-> s
                   (update :repl append :notice
                           (str "Block " (block-tag block) " · " (count forms)
                                (if (= 1 (count forms)) " form" " forms")))
                   (update :repl submit (first forms) (str (:namespace block))
                           (vec (next forms)))))))))

(defn- evaluate-block! [state block]
  (let [{:keys [system]} @state
        current (indexed-entry system block)]
    (cond
      (nil? system)
      (swap! state assoc-in [:repl :status] "Open a project before evaluating a block")
      (or (nil? current) (not= (:revision current) (:revision block)))
      (swap! state assoc-in [:repl :status]
             "The block changed since it was listed; reload before evaluating")
      (not (jvm-file? current))
      (swap! state assoc-in [:repl :status]
             "Only blocks in .clj or .cljc files can be evaluated")
      (empty? (:body-forms current))
      (swap! state assoc-in [:repl :status] "The block has no forms to evaluate")
      :else (start-block! state current))))

(defn evaluate-definition! [state]
  (if (:block? (:definition @state))
    (evaluate-block! state (:definition @state))
    (swap! state
           (fn [{:keys [definition draft] :as s}]
             (if (jvm-definition? s)
               (update s :repl submit draft (str (:namespace definition)))
               (assoc-in s [:repl :status] "Select a Clojure JVM definition first"))))))

(defn evaluate-row!
  "Evaluate a Definitions row: the whole block for a comment block, otherwise
   the visible draft when it still belongs to that row and revision, or the
   indexed source of the row itself."
  [state entry]
  (if (:block? entry)
    (evaluate-block! state entry)
    (let [{:keys [definition system]} @state]
      (if (and definition
               (= (:id definition) (:id entry))
               (= (:revision definition) (:revision entry)))
        (evaluate-definition! state)
        (let [current (indexed-entry system entry)]
          (cond
            (nil? system)
            (swap! state assoc-in [:repl :status] "Open a project before evaluating")
            (or (nil? current) (not= (:revision current) (:revision entry)))
            (swap! state assoc-in [:repl :status]
                   "The row changed since it was listed; reload before evaluating")
            (not (jvm-file? current))
            (swap! state assoc-in [:repl :status]
                   "Only definitions in .clj or .cljc files can be evaluated")
            :else
            (swap! state (fn [{:keys [repl] :as s}]
                           (if (:active repl)
                             (busy-status s)
                             (update s :repl submit (:source current)
                                     (str (:namespace current))))))))))))

(defn evaluate-selection!
  "Send the selected text exactly as captured, in the namespace of the shown
   definition. A selection including the outer `(comment …)` form is evaluated
   literally by the runtime: it returns nil and runs no body form."
  [state definition text]
  (swap! state
         (fn [s]
           (if (selection-evaluable? definition)
             (update s :repl submit text (str (:namespace definition)))
             (assoc-in s [:repl :status]
                       "Only selections in .clj or .cljc files can be evaluated")))))

(defn interrupt! [state]
  (swap! state update :repl
         #(if (and (= :connected (:phase %)) (:interrupt? %) (:active %) (nil? (get-in % [:active :stop])))
            (assoc-in % [:active :stop] :interrupt)
            %)))

(defn- inspect [panel target selected]
  (if (and (= :connected (:phase panel)) (nil? (:active panel)))
    (let [next (update panel :inspection #(assoc % :target target :selected selected
                                                  :observation nil :error nil))]
      (if (:inspection? panel)
        (assoc next :active {:id (identifier) :ns "clojure.core"
                             :code (inspection/request-code target)
                             :due (deadline (min 6000 (:eval-timeout-ms panel)))
                             :inspection-target target})
        (inspection-ended next (:error (model/error :unsupported "The server did not provide an inspection session")) nil)))
    panel))

(defn show-inspection! [state]
  (swap! state
         (fn [{:keys [repl] :as s}]
           (if (and (= :connected (:phase repl)) (nil? (:active repl)))
             (cond-> (assoc s :inspection-open? true)
               (nil? (get-in repl [:inspection :target])) (update :repl inspect [] nil))
             s))))

(defn hide-inspection! [state]
  (swap! state dissoc :inspection-open?))

(defn set-perspective! [state perspective]
  (swap! state update :repl
         #(if (:inspection %) (assoc-in % [:inspection :perspective] perspective) %)))

(defn inspection-rows [state]
  (vec (inspection/rows state)))

(defn inspection-selection [state rows]
  (let [saved (get-in state [:repl :inspection :selected])]
    (or (some #(when (= saved (:name %)) %) rows) (first rows))))

(defn select-inspection! [state name]
  (swap! state assoc-in [:repl :inspection :selected] name))

(defn open-inspection! [state]
  (swap! state
         (fn [s]
           (let [target (get-in s [:repl :inspection :target] [])]
             (if-let [entry (inspection-selection s (inspection-rows s))]
               (update s :repl inspect (conj target (:name entry)) nil)
               s)))))

(defn back-inspection! [state]
  (swap! state update :repl
         (fn [panel]
           (let [target (get-in panel [:inspection :target])]
             (if (seq target) (inspect panel (pop target) (peek target)) panel)))))

(defn refresh-inspection! [state]
  (swap! state update :repl
         (fn [panel]
           (let [{:keys [target selected]} (:inspection panel)]
             (inspect panel (or target []) selected)))))
