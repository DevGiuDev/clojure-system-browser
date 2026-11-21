(ns csb.repl
  "Shared nREPL commands and panel state for Desktop and TUI."
  (:require [clojure.string :as str]
            [csb.model :as model]
            [csb.service :as service]
            [nrepl.transport :as transport])
  (:import [java.io Closeable IOException]
           [java.net InetSocketAddress Socket]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path]
           [java.util UUID]))

(def ^:private host "127.0.0.1")
(def ^:private max-code 16384)
(def ^:private max-entries 200)
(def ^:private max-entry 4096)

(defn- identifier [] (str (UUID/randomUUID)))
(defn- deadline [ms] (+ (System/nanoTime) (* 1000000 ms)))
(defn- expired? [until] (and until (>= (System/nanoTime) until)))
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
      (let [remaining (quot (- until (System/nanoTime)) 1000000)]
        (when-not (pos? remaining)
          (model/fail! :timeout "nREPL handshake timed out" {}))
        (if-let [response (transport/recv wire remaining)]
          (if (= id (:id response))
            (let [data (merge data response)]
              (if (contains? (statuses response) "done") data (recur data)))
            (recur data))
          (model/fail! :timeout "nREPL handshake timed out" {}))))))

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
        (assoc :active nil :status (cond
                                     (= :timeout (:stop active)) "Evaluation timed out"
                                     (contains? status "interrupted") "Evaluation interrupted"
                                     (or failure (:failed? active)) "Evaluation failed"
                                     :else "Evaluation finished")))
      panel)))

(defn- stop-evaluation! [state connection reason]
  (change! state connection #(-> %
                                 (assoc-in [:active :stop] reason)
                                 (assoc :status (if (= :timeout reason)
                                                  "Evaluation timed out; interrupting…"
                                                  "Interrupting…"))))
  {:until (deadline 1500) :retry-at (System/nanoTime)})

(defn- interrupt-if-due! [wire session id interrupt]
  (if (expired? (:retry-at interrupt))
    (let [request-id (identifier)]
      (transport/send wire {:op "interrupt" :id request-id :session session :interrupt-id id})
      (assoc interrupt :request-id request-id :retry-at nil))
    interrupt))

(defn- run-session! [state connection wire session interrupt?]
  (loop [sent nil interrupt nil]
    (let [{:keys [phase active]} (:repl @state)
          {:keys [id code ns due stop]} active
          new? (and id (not= sent id))
          reason (or stop (when (expired? due) :timeout))]
      (when (= :connected phase)
        (if (and new? reason)
          (do
            (change! state connection #(assoc % :active nil :status (if (= :timeout reason)
                                                                      "Evaluation timed out"
                                                                      "Evaluation interrupted")))
            (recur nil nil))
          (let [interrupt (when reason (or interrupt (stop-evaluation! state connection reason)))]
            (when new?
              (transport/send wire (cond-> {:op "eval" :id id :session session :code code
                                            :nrepl.middleware.print/quota 65536}
                                     ns (assoc :ns ns))))
            (when (expired? (:until interrupt))
              (model/fail! reason (if (= :timeout reason)
                                    "Evaluation timed out; connection closed"
                                    "Interrupt did not finish; connection closed") {}))
            (let [interrupt (if interrupt? (interrupt-if-due! wire session id interrupt) interrupt)
                  response (transport/recv wire 35)
                  response-id (:id response)
                  status (statuses response)]
              (when (and (= session (:session response)) response-id)
                (when (and (= id response-id) (contains? status "unknown-session"))
                  (model/fail! :session-closed "nREPL session closed; reconnect to continue" {}))
                (change! state connection #(evaluation-response % response)))
              (recur id (cond-> interrupt
                          (and response-id (= (:request-id interrupt) response-id)
                               (some status ["session-idle" "interrupt-id-mismatch"]))
                          (assoc :retry-at (deadline 70)))))))))))

(defn- run-connection! [state connection port connect-timeout-ms]
  (let [status
        (try
          (with-open [socket (Socket.)]
            (.connect socket (InetSocketAddress. host (int port)) (int connect-timeout-ms))
            (with-open [^Closeable wire (transport/bencode socket)]
              (let [until (deadline connect-timeout-ms)
                    description (exchange! wire {:op "describe"} until)
                    ops (:ops description)]
                (when-not (every? #(contains? ops %) [:clone :close :eval])
                  (model/fail! :unsupported "This server does not offer clone, close and eval" {}))
                (let [session (:new-session (exchange! wire {:op "clone"} until))
                      interrupt? (contains? ops :interrupt)]
                  (when-not session (model/fail! :protocol "nREPL did not return a session" {}))
                  (try
                    (change! state connection
                             #(if (= :connecting (:phase %))
                                (assoc % :phase :connected :status "Connected" :interrupt? interrupt?
                                       :namespace (or (get-in description [:aux :current-ns]) "user"))
                                %))
                    (run-session! state connection wire session interrupt?)
                    (finally
                      (try (exchange! wire {:op "close" :session session} (deadline 500))
                           (catch Exception _))))))))
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
                  (dissoc :connection :active :namespace :interrupt?)
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
        detect? (or (not= root (:port-project repl))
                    (and (nil? (:connection repl)) (not (:port-overridden? repl))))
        port (when detect? (str (read-project-port root)))]
    (swap! state (fn [s]
                   (cond-> (assoc s :repl-open? true)
                     detect? (update :repl assoc :port-project root :port port :port-overridden? false))))))

(defn edit-port [panel text]
  (if (= (or (:port panel) "") text)
    panel
    (assoc panel :port text :port-overridden? true)))

(defn connect!
  "Connects asynchronously to the loopback port entered in the panel."
  ([state] (connect! state {}))
  ([state {:keys [connect-timeout-ms eval-timeout-ms]
           :or {connect-timeout-ms 3000 eval-timeout-ms 60000}}]
   (let [connection (Object.)
         port #(parse-port (:port %))
         [_ next] (swap-vals! state update :repl
                              (fn [panel]
                                (if (contains? #{nil :disconnected} (:phase panel))
                                  (if (port panel)
                                    (assoc panel :connection connection :phase :connecting
                                           :eval-timeout-ms eval-timeout-ms :status "Connecting…" :entries [])
                                    (assoc panel :phase :disconnected :status "Enter a port from 1 to 65535"))
                                  panel)))
         panel (:repl next)]
     (when (identical? connection (:connection panel))
       (let [number (port panel)]
         (.start (Thread. ^Runnable #(run-connection! state connection number connect-timeout-ms)
                          "csb-nrepl")))))))

(defn disconnect! [state]
  (when (get-in @state [:repl :connection])
    (swap! state update :repl
           #(if (:connection %) (assoc % :phase :disconnecting :status "Disconnecting…") %))))

(defn- submit [panel code ns]
  (cond
    (not= :connected (:phase panel)) (assoc panel :status "Connect to nREPL first")
    (:active panel) panel
    (str/blank? code) (assoc panel :status "Enter an expression")
    (> (count code) max-code) (assoc panel :status "Input exceeds 16384 characters")
    :else
    (-> panel
        (append :input (str (when ns (str "[" ns "] ")) code))
        (assoc :active {:id (identifier) :code code :ns ns :due (deadline (:eval-timeout-ms panel))}
               :status "Evaluating…"))))

(defn evaluate-input! [state]
  (swap! state update :repl (fn [panel]
                              (let [next (submit panel (:input panel) nil)]
                                (if (and (nil? (:active panel)) (:active next))
                                  (assoc next :input "")
                                  next)))))

(defn jvm-definition? [{:keys [definition]}]
  (when-let [file (:file definition)]
    (or (str/ends-with? file ".clj") (str/ends-with? file ".cljc"))))

(defn evaluate-definition! [state]
  (swap! state
         (fn [{:keys [definition draft] :as s}]
           (if (jvm-definition? s)
             (update s :repl submit draft (str (:namespace definition)))
             (assoc-in s [:repl :status] "Select a Clojure JVM definition first")))))

(defn interrupt! [state]
  (swap! state update :repl
         #(if (and (= :connected (:phase %)) (:interrupt? %) (:active %) (nil? (get-in % [:active :stop])))
            (assoc-in % [:active :stop] :interrupt)
            %)))
