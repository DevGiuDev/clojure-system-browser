(ns csb.tui
  (:require [clojure.string :as str]
            [csb.cli :as cli]
            [csb.editor :as editor]
            [csb.repl :as repl]
            [csb.service :as service]
            [csb.session :as session]
            [csb.tests :as tests]
            [csb.tui-terminal :as terminal]
            [csb.tui-view :as view])
  (:import [com.googlecode.lanterna TerminalSize]
           [com.googlecode.lanterna.gui2 TextBox TextBox$Style]
           [com.googlecode.lanterna.input KeyStroke KeyType]
           [com.googlecode.lanterna.screen TerminalScreen]
           [com.googlecode.lanterna.terminal DefaultTerminalFactory])
  ;; Generates the class with the static main the release uberjar needs
  ;; (java -jar); harmless for the documented clojure -M:tui launch.
  (:gen-class))

(defn- render-if-changed! [^TerminalScreen screen state last-frame]
  (let [resized (.doResizeIfNecessary screen)
        frame [state (.getTerminalSize screen)]]
    (when (or resized (not= frame @last-frame))
      (view/draw! screen state (or resized (nil? @last-frame)))
      (vreset! last-frame frame))))

(defn- move! [state direction]
  (let [s @state focus (get s :focus 0)
        values (case focus 0 (vec (session/namespaces s)) 1 (:entries (session/categories s)) 2 (session/definitions s))
        selected (case focus 0 (:namespace s) 1 (:category s) 2 (get-in s [:definition :id]))
        ids (case focus 0 values 1 (mapv :category values) 2 (mapv :id values))
        idx (.indexOf ^java.util.List (vec ids) selected)
        next-idx (max 0 (min (dec (count values)) (+ idx direction)))]
    (when (seq values)
      (case focus
        0 (session/select-namespace! state (nth values next-idx))
        1 (session/select-category! state (nth ids next-idx))
        2 (session/select-definition! state (nth ids next-idx)))
      (swap! state assoc :scroll 0 :editor-error nil))))

(defn- editor-error! [state error argv temp]
  (swap! state assoc :editor-error {:error error :argv argv :temp (some-> temp str)}
         :help? false :diagnostics? false :source-order? false :scroll 0
         :status (if temp
                   (str "Editor failed; draft retained at " temp)
                   (str "Editor configuration error: " (:message error)))))

(defn- run-editor! [^TerminalScreen screen state argv editing]
  (try
    (.stopScreen screen)
    (println "Opening external editor:" (pr-str argv))
    (println "Finish the editor session to return to CSB. Press s there to save the project source.")
    (flush)
    (let [result (editor/launch! editing argv)
          draft (when (= :ok (:status result)) (editor/read-draft editing))]
      (if (and (= :ok (:status result)) (= :ok (:status draft)))
        (do (session/edit! state (:value draft))
            (editor/discard! editing)
            (swap! state assoc :editor-error nil
                   :status "Draft loaded from editor. Press s to save, d to discard."))
        (editor-error! state (:error (if (= :error (:status result)) result draft)) argv (:temp editing))))
    (catch Exception e
      (editor-error! state {:type :editor :message (ex-message e)} argv (:temp editing)))
    (finally (.startScreen screen) (.setCursorPosition screen nil))))

(defn- edit-external! [^TerminalScreen screen state]
  (cond
    (= :requires (:category @state))
    (swap! state assoc :status "Requires are read-only.")
    (not (get-in @state [:definition :writable?]))
    (swap! state assoc :status (str "This definition is read-only: "
                                   (get-in @state [:definition :read-only-reason])))
    :else
    (try
      ;; Resolve configuration while the screen is active, before creating a temporary.
      (let [argv (cli/editor-argv)
            started (editor/begin! (assoc (:definition @state) :source (:draft @state)))]
        (if (= :error (:status started))
          (editor-error! state (:error started) argv nil)
          (run-editor! screen state argv (:value started))))
      (catch Exception e
        (editor-error! state {:type (get (ex-data e) :type :invalid-editor) :message (ex-message e)} nil nil)))))

(defn- extension! [state kind]
  (if-let [c (when-let [system (:system @state)] (first (service/contributions system kind)))]
    (session/extension! state kind (:id c))
    (swap! state assoc :status "No extension loaded. Use --example-extension for the bundled example.")))

(defn- retain-draft! [state]
  (let [recovered (editor/begin! (assoc (:definition @state) :source (:draft @state)))]
    (swap! state assoc :recovery (if (= :ok (:status recovered))
                                  (str "Unsaved draft retained at " (get-in recovered [:value :temp]))
                                  (str "Could not retain unsaved draft: " (get-in recovered [:error :message]))))))

(defn- edit-repl! [state ^KeyStroke key field]
  (let [s @state
        [row column] (get-in s [:repl-caret field] [0 0])
        [first-line & more] (str/split (get-in s [:repl field] (if (= :host field) repl/default-host "")) #"\n" -1)
        widget (TextBox. ^String first-line
                         (if (= field :input) TextBox$Style/MULTI_LINE TextBox$Style/SINGLE_LINE))]
    ;; TextBox.setText drops trailing empty lines. Add them explicitly.
    (doseq [line more] (.addLine widget line))
    (.setCaretWarp widget true)
    (.setCaretPosition widget (int row) (int column))
    (.handleKeyStroke widget key)
    (let [caret (.getCaretPosition widget)
          text (.getText widget)]
      (swap! state (fn [s]
                     (assoc-in (if (= field :port)
                                 (update s :repl repl/edit-port text)
                                 (assoc-in s [:repl :input] text))
                               [:repl-caret field] [(.getRow caret) (.getColumn caret)]))))))

(defn- inspection-move! [state size direction]
  (let [snapshot @state
        rows (repl/inspection-rows snapshot)
        page (view/inspection-page-size (.getRows ^TerminalSize size))]
    (if (seq rows)
      (let [idx (.indexOf ^java.util.List rows (repl/inspection-selection snapshot rows))
            next-idx (max 0 (min (dec (count rows)) (+ idx direction)))]
        (repl/select-inspection! state (:name (nth rows next-idx))))
      (when-let [lines (view/inspection-lines snapshot (.getColumns ^TerminalSize size))]
        (swap! state update :inspection-scroll
               #(max 0 (min (- (count lines) page) (+ (or % 0) direction))))))))

(defn- inspection-key! [state type size]
  (let [target (get-in @state [:repl :inspection :target])
        perspective (get-in @state [:repl :inspection :perspective])
        page (view/inspection-page-size (.getRows ^TerminalSize size))]
    (condp = type
      KeyType/F4 (repl/hide-inspection! state)
      KeyType/F5 (repl/refresh-inspection! state)
      KeyType/F7 (repl/interrupt! state)
      KeyType/F8 (repl/disconnect! state)
      KeyType/Tab (do (repl/set-perspective! state (if (= :combined perspective) :live :combined))
                      (swap! state assoc :inspection-scroll 0))
      KeyType/Enter (do (repl/open-inspection! state) (swap! state assoc :inspection-scroll 0))
      KeyType/Escape (if (seq target)
                       (do (repl/back-inspection! state) (swap! state assoc :inspection-scroll 0))
                       (repl/hide-inspection! state))
      KeyType/ArrowUp (inspection-move! state size -1)
      KeyType/ArrowDown (inspection-move! state size 1)
      KeyType/PageUp (inspection-move! state size (- page))
      KeyType/PageDown (inspection-move! state size page)
      nil)))

(defn- repl-key! [state ^KeyStroke key ^TerminalSize size]
  (let [type (.getKeyType key)
        phase (get-in @state [:repl :phase])
        connected? (= :connected phase)
        disconnected? (contains? #{nil :disconnected} phase)
        scroll-step (view/repl-page-size (.getRows size))]
    (cond
      (:inspection-open? @state) (inspection-key! state type size)
      (= type KeyType/Escape) (swap! state assoc :repl-open? false)
      (= type KeyType/F8) (repl/disconnect! state)
      (= type KeyType/PageUp)
      (swap! state (fn [s]
                     (let [line-count (count (view/repl-lines (:repl s) (.getColumns size)))]
                       (assoc s :repl-scroll (min (max 0 (- line-count scroll-step))
                                                  (+ scroll-step (get s :repl-scroll 0)))))))
      (= type KeyType/PageDown) (swap! state update :repl-scroll #(max 0 (- (or % 0) scroll-step)))
      (and disconnected? (= type KeyType/Enter))
      (do (swap! state assoc :repl-scroll 0) (repl/connect! state))
      (and disconnected? (= type KeyType/Tab))
      (swap! state update :repl-address-field #(if (= :host %) :port :host))
      (and connected? (= type KeyType/F4))
      (do (swap! state assoc :inspection-scroll 0) (repl/show-inspection! state))
      (and connected? (= type KeyType/F5))
      (do (repl/evaluate-input! state)
          (when (empty? (get-in @state [:repl :input]))
            (swap! state assoc :repl-scroll 0 :repl-caret (dissoc (:repl-caret @state) :input))))
      (and connected? (= type KeyType/F6))
      (do (repl/evaluate-definition! state) (swap! state assoc :repl-scroll 0))
      (and connected? (= type KeyType/F7)) (repl/interrupt! state)
      (and (or disconnected? connected?) (not (.isCtrlDown key)) (not (.isAltDown key)))
      (edit-repl! state key (if connected? :input (get @state :repl-address-field :port))))))

(defn- show-repl! [state]
  (repl/show-panel! state)
  (swap! state #(assoc % :repl-address-field :port
                       :repl-caret
                       (merge (:repl-caret %)
                              {:port [0 (count (get-in % [:repl :port]))]
                               :host [0 (count (get-in % [:repl :host] repl/default-host))]}))))

(defn- java-vm-key! [state ^KeyStroke key]
  (let [{:keys [input caret installed choice-index]} (:java-vm-editor @state)
        type (.getKeyType key)]
    (if (some? choice-index)
      (cond
        (#{KeyType/Escape KeyType/Tab} type) (swap! state update :java-vm-editor dissoc :choice-index)
        (= KeyType/Enter type) (session/set-java-home-input! state (:home (nth installed choice-index)))
        (#{KeyType/ArrowDown KeyType/ArrowUp} type)
        (let [delta (if (= KeyType/ArrowDown type) 1 -1)]
          (swap! state assoc-in [:java-vm-editor :choice-index]
                 (max 0 (min (dec (count installed)) (+ choice-index delta))))))
      (condp = type
        KeyType/Escape (swap! state dissoc :java-vm-editor)
        KeyType/Enter (session/apply-java-vm! state)
        KeyType/Tab (when (seq installed)
                      (swap! state assoc-in [:java-vm-editor :choice-index]
                             (max 0 (.indexOf ^java.util.List (mapv :home installed) input))))
        (let [widget (TextBox. ^String input TextBox$Style/SINGLE_LINE)]
          (.setCaretPosition widget 0 (int (or caret (count input))))
          (.handleKeyStroke widget key)
          (swap! state update :java-vm-editor assoc
                 :input (.getText widget) :caret (.getColumn (.getCaretPosition widget)) :error nil))))))

(defn- tests-key! [state ^KeyStroke key ^TerminalSize size]
  (let [s @state
        type (.getKeyType key)
        tree (tests/targets s)
        ns-sym (tests/selected-namespace s tree)
        definitions (get tree ns-sym)
        selected (tests/selected-test s definitions)
        test-focus? (get-in s [:tests-view :test-focus?])]
    (cond
      (or (= type KeyType/Escape) (= type KeyType/F3))
      (swap! state assoc-in [:tests-view :open?] false)

      (#{KeyType/Tab KeyType/ArrowLeft KeyType/ArrowRight} type)
      (swap! state update-in [:tests-view :test-focus?] not)

      (#{KeyType/ArrowDown KeyType/ArrowUp} type)
      (let [items (if test-focus? definitions (vec (sort-by str (keys tree))))
            idx (.indexOf ^java.util.List (vec items) (if test-focus? selected ns-sym))
            next-idx (+ idx (if (= type KeyType/ArrowUp) -1 1))]
        (when-let [item (get items next-idx)]
          (if test-focus? (tests/select-test! state item) (tests/select-namespace! state item))))

      (= type KeyType/Enter) (tests/open-source! state selected)
      (= type KeyType/F4) (tests/run! state nil nil)
      (= type KeyType/F5) (when ns-sym (tests/run! state ns-sym nil))
      (= type KeyType/F6) (when selected (tests/run! state ns-sym (:name selected)))
      (= type KeyType/F7) (tests/cancel! state)
      (= \j (.getCharacter key)) (session/show-java-vms! state)
      (#{KeyType/PageDown KeyType/PageUp} type)
      (let [page (view/test-detail-page-size (.getRows size))
            total (count (view/test-detail-lines (.getColumns size) (tests/result s)))
            delta (if (= type KeyType/PageUp) page (- page))]
        (swap! state update-in [:tests-view :detail-scroll]
               #(min (max 0 (- total page)) (max 0 (+ (or % 0) delta)))))
      (= \p (.getCharacter key)) (show-repl! state))))

(defn- step! [^TerminalScreen screen state last-frame]
  (render-if-changed! screen @state last-frame)
  (let [^KeyStroke key (.pollInput screen)
        type (when key (.getKeyType key))
        ch (when key (.getCharacter key))
        size (.getTerminalSize screen)
        page-size (when (#{KeyType/PageDown KeyType/PageUp} type)
                    (view/page-size (.getRows size) (.getColumns size) @state))
        repl-open? (:repl-open? @state)
        tests-open? (get-in @state [:tests-view :open?])
        java-vm-open? (:java-vm-editor @state)
        eof? (= type KeyType/EOF)
        quit? (and (not (or repl-open? tests-open? java-vm-open?)) (= ch \q))]
    (when (and eof? (:dirty? @state)) (retain-draft! state))
    (if (or eof? (and quit? (not (:dirty? @state))))
      false
      (do
        (cond
          java-vm-open? (when key (java-vm-key! state key))
          repl-open? (when key (repl-key! state key size))
          tests-open? (when key (tests-key! state key size))
          quit? (swap! state assoc :status "Unsaved changes: s to Save or d to Discard, then q")
          (= type KeyType/F3) (when (:system @state) (swap! state assoc-in [:tests-view :open?] true))
          (= ch \j) (session/show-java-vms! state)
          (= type KeyType/Tab) (swap! state update :focus #(mod (inc (or % 0)) 3))
          (= type KeyType/ArrowRight) (swap! state update :focus #(mod (inc (or % 0)) 3))
          (= type KeyType/ArrowLeft) (swap! state update :focus #(mod (+ 2 (or % 0)) 3))
          (= type KeyType/ArrowDown) (move! state 1)
          (= type KeyType/ArrowUp) (move! state -1)
          (= type KeyType/PageDown) (swap! state update :scroll #(+ page-size (or % 0)))
          (= type KeyType/PageUp) (swap! state update :scroll #(max 0 (- (or % 0) page-size)))
          (= ch \?) (swap! state #(assoc % :help? (not (:help? %)) :diagnostics? false :scroll 0))
          (= ch \!) (swap! state #(assoc % :diagnostics? (not (:diagnostics? %)) :help? false :scroll 0))
          (= ch \o) (swap! state #(assoc % :source-order? (not (:source-order? %))
                                           :help? false :diagnostics? false :scroll 0))
          (= ch \p) (show-repl! state)
          (= type KeyType/Escape) (swap! state assoc :editor-error nil :help? false :diagnostics? false
                                         :source-order? false :scroll 0)
          (= ch \s) (session/save! state)
          (= ch \d) (do (session/discard! state) (swap! state assoc :editor-error nil))
          (= ch \r) (do (session/reload! state) (swap! state assoc :editor-error nil))
          (= ch \e) (do (edit-external! screen state)
                          ;; Returning from an editor invalidates the terminal front buffer,
                          ;; even if the editor left the draft/status unchanged.
                          (vreset! last-frame nil))
          (= ch \t) (swap! state update :theme #(if (= % :light) :dark :light))
          (= ch \x) (extension! state :commands)
          (= ch \v) (extension! state :views))
        (if key
          (render-if-changed! screen @state last-frame)
          (Thread/sleep 30))
        true))))

(defn- run-screen! [^TerminalScreen screen state]
  (try
    (.startScreen screen)
    (.setCursorPosition screen nil)
    (let [last-frame (volatile! nil)]
      (loop [] (when (step! screen state last-frame) (recur))))
    (finally (tests/cancel! state) (repl/disconnect! state) (.close screen))))

(defn -main [& args]
  (try
    (let [{:keys [project help?] :as options} (cli/arguments args)]
      (if help?
        (println (str "clojure-system-browser " cli/version
                      "\nclojure -M:tui --project PATH [--source-root DIR ...] [--example-extension]"
                      "\nPress ? inside the browser for keyboard help."))
        (terminal/with-input-settings
          (fn []
            ;; Screen.close stops drawing; close the terminal too, before the
            ;; outer settings restore, so its shutdown hook cannot undo it.
            (with-open [device (.createTerminal (doto (DefaultTerminalFactory.) (.setForceTextTerminal true)))]
              (let [state (session/create)
                    screen (TerminalScreen. device)]
                (session/open! state project options)
                (run-screen! screen state)
                (when-let [recovery (:recovery @state)] (binding [*out* *err*] (println recovery)))))))))
    (catch Exception e
      (binding [*out* *err*] (println (ex-message e)))
      (System/exit 1))))
