(ns csb.tui-view
  "Terminal presentation with explicit colours, independent of the shell theme."
  (:require [clojure.string :as str]
            [csb.java-vm :as java-vm]
            [csb.repl :as repl]
            [csb.runtime-inspection :as inspection]
            [csb.service :as service]
            [csb.session :as session]
            [csb.syntax :as syntax]
            [csb.tests :as tests])
  (:import [com.googlecode.lanterna TerminalPosition TerminalSize TerminalTextUtils TextColor$Indexed]
           [com.googlecode.lanterna.graphics TextGraphics]
           [com.googlecode.lanterna.screen TerminalScreen Screen$RefreshType]))

;; Indices above the terminal's configurable ANSI 0–15 palette. No default colours.
(def ^:private themes
  {:dark {:background 234 :surface 235 :raised 237 :text 253 :muted 248
          :accent 81 :selection 24 :selected-text 231 :border 240 :warning 221 :error 210
          :special 81 :keyword 183 :string 150 :comment 246 :number 215
          :literal 209 :character 150 :delimiter 248 :reader 180
          :test-idle 248 :test-passed 114 :test-failed 210 :test-running 215}
   :light {:background 255 :surface 254 :raised 253 :text 234 :muted 240
           :accent 24 :selection 24 :selected-text 231 :border 245 :warning 58 :error 124
           :special 24 :keyword 90 :string 22 :comment 240 :number 94
           :literal 124 :character 22 :delimiter 240 :reader 94
           :test-idle 240 :test-passed 28 :test-failed 124 :test-running 130}})

(defn- colours [state]
  (into {} (map (fn [[k index]] [k (TextColor$Indexed. index)]))
        (get themes (get state :theme :dark) (:dark themes))))

(defn- style! [^TextGraphics g palette foreground background]
  (.setForegroundColor g (get palette foreground))
  (.setBackgroundColor g (get palette background)))

(defn- text! [^TextGraphics g right x y text]
  (when (and (<= 0 x) (< x right) (<= 0 y))
    (let [clean (str/replace (str text) #"[\p{Cntrl}]" " ")
          ;; Keep the final terminal cell free of text to avoid automatic wrapping.
          clean (TerminalTextUtils/fitString clean (max 0 (- right x 1)))]
      (.putString g (int x) (int y) ^String clean)
      (TerminalTextUtils/getColumnWidth clean))))

(defn- rectangle! [^TextGraphics g x y width height]
  (when (and (pos? width) (pos? height))
    (.fillRectangle g (TerminalPosition. x y) (TerminalSize. width height) \space)))

(defn- bar! [g palette width y foreground background text]
  (style! g palette foreground background)
  (rectangle! g 0 y width 1)
  (text! g width 1 y text))

(defn- test-led! [g palette right x y background status]
  (let [kind (case status
               :passed :test-passed
               (:failed :error) :test-failed
               :running :test-running
               :test-idle)
        palette (if (= background :selection)
                  (assoc palette kind (TextColor$Indexed. (get-in themes [:dark kind])))
                  palette)]
    (style! g palette kind background)
    (text! g right x y "●")))

(defn- lists! [g palette width rows state tree]
  (let [focus (get state :focus 0)
        cats (session/categories state)
        values [(vec (session/namespaces state)) (:entries cats)
                (session/definitions state)]
        selected [(:namespace state) (:category state) (get-in state [:definition :id])]
        column (quot width 3)]
    (doseq [c (range 3)]
      (let [x (* c column)
            right (if (= c 2) width (+ x column))
            items (nth values c)
            item-id (cond (= c 2) :id (= c 1) :category :else identity)
            idx (.indexOf ^java.util.List (mapv item-id items) (nth selected c))
            start (max 0 (- idx (dec rows)))
            focused? (= focus c)
            title (nth ["Namespaces" "Categories" "Definitions"] c)
            title (if (and (= c 1) cats)
                    (str title " · " (:total cats) " defs · " (:files cats)
                         (if (= 1 (:files cats)) " file" " files"))
                    title)]
        (style! g palette :text :surface)
        (rectangle! g x 3 (- right x) (inc rows))
        (style! g palette (if focused? :accent :muted) :raised)
        (rectangle! g x 3 (- right x) 1)
        (text! g right (inc x) 3
               (str (when focused? "[ ") title
                    (when-not (and (= c 1) cats) (str " · " (count items)))
                    (when focused? " ]")))
        (doseq [[i item] (map-indexed vector (take rows (drop start items)))]
          (let [selected? (= (item-id item) (nth selected c))
                y (+ 4 i)
                background (cond (and selected? focused?) :selection selected? :raised :else :surface)
                foreground (if (and selected? focused?) :selected-text :text)
                led? (and (zero? c) (contains? tree item))]
            (style! g palette foreground background)
            (rectangle! g x y (- right x) 1)
            (text! g right (inc x) y
                   (if selected? ">" " "))
            (when led?
              (test-led! g palette right (+ x 3) y background (tests/namespace-status state item (get tree item)))
              (style! g palette foreground background))
            (text! g right (+ x (if led? 5 3)) y
                   (cond (= c 2) (str (:name item) (when-not (:writable? item) " [RO]"))
                         (= c 1) (session/category-label item)
                         :else item))))
        (when (pos? c)
          (style! g palette :border :background)
          (.drawLine ^TextGraphics g (dec x) 3 (dec x) (+ 3 rows) \│))))))

(def ^:private help
  (str "Tab/Left/Right: focus a list\nUp/Down: select · PgUp/PgDn: scroll source\n"
       "e: edit definition in $EDITOR (external editor)\ns / Ctrl+S: Save · d: Discard · r: Reload\n"
       "t: toggle dark/light theme\nx: example command · v: extension view\n"
       "o: source order (real file order of top-level forms)\n"
       "p: nREPL panel · F3: Tests · j: Java VMs\n"
       "!: source diagnostics · q: quit · ?: help · Esc: source\nAfter an external edit, press s to save explicitly."))

(defn- editor-error-text [{:keys [error argv temp]} width]
  (str/join "\n"
            (TerminalTextUtils/getWordWrappedText
             (max 1 (- width 4))
             (into-array String
                         [(str "Reason: " (:message error))
                          (str "Error: " (name (:type error :editor))
                               (when-let [code (get-in error [:details :exit])] (str " (exit " code ")")))
                          (str "Editor command: " (if argv (pr-str argv) "check VISUAL / EDITOR / CSB_EDITOR_ARGS"))
                          (if temp (str "Temporary draft retained: " temp) "Your current draft is unchanged.")
                          "Configure CSB_EDITOR_ARGS as an argument vector, for example [\"vim\" \"-f\"]."
                          "Esc: return to source. e: retry the editor. PgUp/PgDn: scroll details."]))))

(defn- source-order-text [files current-id]
  (str/join "\n"
            (mapcat (fn [{:keys [file forms]}]
                      (into [file]
                            (map (fn [{:keys [ordinal label name preview row definition-id]}]
                                   (str (if (= definition-id current-id) "> " "  ")
                                        ordinal " " label
                                        (if name (str " " name) (str " " preview))
                                        " · row " row)))
                            forms))
                    files)))

(defn- content [state width]
  (cond
    (:help? state) help
    (:diagnostics? state)
    (if-let [system (:system state)]
      (let [errors (service/diagnostics system)]
        (if (seq errors)
          (str/join "\n\n" (map #(str (:file %) "\n" (get-in % [:error :message])) errors))
          "No source errors"))
      "No project open")
    (:source-order? state)
    (if-let [system (:system state)]
      (let [files (:value (service/list-source-forms system (:namespace state)))]
        (if (seq files)
          (source-order-text files (get-in state [:definition :id]))
          "No source forms"))
      "No project open")
    (:editor-error state) (editor-error-text (:editor-error state) width)
    (= :requires (:category state))
    (str/join "\n"
              (TerminalTextUtils/getWordWrappedText
               (- width 3) (into-array String (str/split-lines (session/requires-text state)))))
    :else (:draft state)))

(defn- code-lines [text]
  ;; Normalise only the displayed copy. Tokenise the whole definition before
  ;; clipping, so multiline strings retain their style when scrolling into them.
  (let [text (str/replace text #"\r\n?" "\n")]
    (reduce (fn [lines {:keys [start end kind]}]
              (let [parts (str/split (subs text start end) #"\n" -1)]
                (into (update lines (dec (count lines)) conj {:text (first parts) :kind kind})
                      (map #(vector {:text % :kind kind}) (rest parts)))))
            [[]] (syntax/spans text))))

(defn- code-line! [^TextGraphics g palette width y segments]
  (loop [x 2 segments (seq segments)]
    (when (and segments (< x (dec width)))
      (let [{:keys [text kind]} (first segments)]
        (.setForegroundColor g (get palette kind (:text palette)))
        (recur (+ x (text! g width x y text)) (next segments))))))

(defn- list-rows [height]
  (max 1 (min 9 (quot (- height 10) 2))))

(defn page-size [height]
  ;; A page must not skip details when the source panel is shorter than eight rows.
  (max 1 (min 8 (- height (+ 6 (list-rows height)) 3))))

(defn- source! [g palette width height rows state]
  (let [top (+ 6 rows)
        title (cond (:help? state) "Help" (:diagnostics? state) "Source diagnostics"
                    (:source-order? state) "Source order · Esc: source"
                    (:editor-error state) "External editor failed · Esc: source"
                    (= :requires (:category state)) "Requires · read only"
                    :else (or (get-in state [:definition :name]) "Source"))]
    (bar! g palette width (dec top)
          (cond (:editor-error state) :error (:dirty? state) :warning :else :accent) :raised
          (str title
               (when (get-in state [:definition :writable?]) " · e: external editor")
               (when (:dirty? state) " · UNSAVED")))
    (style! g palette :text :background)
    (let [code? (not (or (:help? state) (:diagnostics? state) (:source-order? state) (:editor-error state)))
          text (or (content state width) "")
          lines (if code? (code-lines text) (str/split-lines text))]
      (doseq [[i line] (map-indexed vector
                                   (take (max 0 (- height top 3))
                                         (drop (get state :scroll 0) lines)))]
        (if code?
          (code-line! g palette width (+ top i) line)
          (text! g width 2 (+ top i) line))))))

(defn repl-page-size [height] (max 1 (- height 11)))

(defn repl-lines [panel width]
  (vec (TerminalTextUtils/getWordWrappedText
        (max 1 (- width 3)) (into-array String (str/split-lines (repl/transcript panel))))))

(defn- input! [^TerminalScreen screen g palette width x y rows text [row column]]
  (let [lines (str/split (or text "") #"\n" -1)
        row (min (or row 0) (dec (count lines)))
        line (nth lines row)
        column (min (or column 0) (count line))
        caret (TerminalTextUtils/getColumnWidth (subs line 0 column))
        first-row (max 0 (- row (dec rows)))
        space (- width x 1)
        left (max 0 (- caret (dec space)))]
    (style! g palette :text :surface)
    (rectangle! g x y (- width x) rows)
    (doseq [[index text] (map-indexed vector (take rows (drop first-row lines)))]
      (text! g width x (+ y index) (TerminalTextUtils/fitString text left space)))
    (.setCursorPosition screen (TerminalPosition. (+ x (- caret left)) (+ y (- row first-row))))))

(defn- repl-panel! [^TerminalScreen screen g palette width height state]
  (let [{:keys [phase namespace status host port input] :as panel} (:repl state)
        host (or host repl/default-host)
        connected? (= :connected phase)
        disconnected? (contains? #{nil :disconnected} phase)
        rows (repl-page-size height)
        lines (repl-lines panel width)
        start (max 0 (- (count lines) rows (get state :repl-scroll 0)))]
    (bar! g palette width 0 :accent :raised
          (str "nREPL" (when-not disconnected? (str " · " host " / " port))
               (when namespace (str " · " namespace)) (when (:dirty? state) " · UNSAVED SOURCE")))
    (bar! g palette width 1 :text :background (or status "Disconnected"))
    (bar! g palette width 2 :muted :raised "Transcript · PgUp/PgDn")
    (style! g palette :text :background)
    (doseq [[index text] (map-indexed vector (take rows (drop start lines)))]
      (text! g width 1 (+ 3 index) text))
    (cond
      connected?
      (do
        (bar! g palette width (- height 8) :accent :raised "Clojure expression · Enter: newline")
        (input! screen g palette width 1 (- height 7) 4 input (get-in state [:repl-caret :input])))
      disconnected?
      (let [focus (get state :repl-address-field :port)
            other (if (= :host focus) :port :host)]
        (doseq [field [other focus]
                :let [host? (= :host field)
                      y (- height (if host? 6 7))]]
          (bar! g palette width y (if (= field focus) :accent :muted) :surface (if host? "Host:" "Port:"))
          (input! screen g palette width 7 y 1 (if host? host port)
                  (get-in state [:repl-caret field])))))
    (bar! g palette width (- height 2) :text :background
          (if connected?
            "F5: evaluate · F6: evaluate definition · F7: interrupt"
            (if disconnected? "Enter: connect · Tab: host/port" "F8: disconnect")))
    (bar! g palette width (dec height) :muted :background
          (if connected? "Esc: source · F4: inspect runtime · F8: disconnect" "Esc: source"))))

(defn inspection-page-size [height] (max 1 (- height 5)))

(defn inspection-lines [state width]
  (when-let [details (inspection/details state)]
    (vec (TerminalTextUtils/getWordWrappedText
          (max 1 (- width 3)) (into-array String (str/split-lines details))))))

(defn- inspection-panel! [g palette width height state]
  (let [panel (:repl state)
        page (inspection-page-size height)
        entries (repl/inspection-rows state)
        selected (repl/inspection-selection state entries)]
    (bar! g palette width 0 :accent :raised
          (str (inspection/heading (:inspection panel))
               (when (:dirty? state) " · UNSAVED SOURCE")))
    (bar! g palette width 1 (if (get-in panel [:inspection :error]) :error :text) :background
          (inspection/status panel))
    (if-let [lines (inspection-lines state width)]
      (let [start (max 0 (min (- (count lines) page) (get state :inspection-scroll 0)))]
        (style! g palette :text :background)
        (doseq [[i line] (map-indexed vector (take page (drop start lines)))]
          (text! g width 1 (+ i 2) line)))
      (let [index (.indexOf ^java.util.List entries selected)
            start (* page (quot (max index 0) page))]
        (doseq [[i entry] (map-indexed vector (take page (drop start entries)))]
          (let [selected? (= selected entry)]
            (bar! g palette width (+ i 2)
                  (if selected? :selected-text :text) (if selected? :selection :background)
                  (str (if selected? "> " "  ") (inspection/label entry)))))
        (when (> (count entries) page)
          (bar! g palette width (- height 3) :muted :background
                (str (inc index) "/" (count entries) " · PgUp/PgDn")))))
    (bar! g palette width (- height 2) :text :background
          "Tab: Live/Combined · ↑↓: navigate · Enter: open · Esc: back")
    (bar! g palette width (dec height) :muted :background
          "F4: console · F5: refresh · F7: interrupt · F8: disconnect")))

(defn- test-list! [g palette left right rows title items selected label status focused?]
  (let [idx (.indexOf ^java.util.List (vec items) selected)
        start (max 0 (- idx (dec rows)))]
    (style! g palette (if focused? :accent :muted) :raised)
    (rectangle! g left 2 (- right left) 1)
    (text! g right (inc left) 2 (if focused? (str "[ " title " ]") title))
    (doseq [[row item] (map-indexed vector (take rows (drop start items)))]
      (let [y (+ 3 row)
            current? (= selected item)
            bg (cond (and current? focused?) :selection current? :raised :else :surface)
            fg (if (and current? focused?) :selected-text :text)]
        (style! g palette fg bg)
        (rectangle! g left y (- right left) 1)
        (text! g right (inc left) y (if current? ">" " "))
        (when status
          (test-led! g palette right (+ left 3) y bg (status item))
          (style! g palette fg bg))
        (text! g right (+ left (if status 5 3)) y (label item))))))

(defn- test-list-rows [height] (max 2 (quot (- height 9) 2)))

(defn test-detail-page-size [height] (max 1 (- height (test-list-rows height) 8)))

(defn test-detail-lines [width result]
  (vec (TerminalTextUtils/getWordWrappedText
        (max 1 (- width 2))
        (into-array String (mapcat #(str/split (str/replace % "\r" "") #"\n" -1)
                                  (:output result))))))

(defn- tests-panel! [g palette width height state tree]
  (let [rows (test-list-rows height)
        mid (quot width 2)
        ns-sym (tests/selected-namespace state tree)
        definitions (get tree ns-sym)
        selected (tests/selected-test state definitions)
        test-focus? (get-in state [:tests-view :test-focus?])
        result (tests/result state)
        summary-y (+ 4 rows)
        page (test-detail-page-size height)
        lines (test-detail-lines width result)
        start (max 0 (- (count lines) page (get-in state [:tests-view :detail-scroll] 0)))]
    (bar! g palette width 0 :accent :raised "CLOJURE SYSTEM BROWSER · Tests")
    (test-list! g palette 0 mid rows "Test namespaces" (sort-by str (keys tree))
                ns-sym str #(tests/namespace-status state % (get tree %)) (not test-focus?))
    (test-list! g palette mid width rows "Tests" definitions
                selected (comp str :name) #(tests/case-status state ns-sym (:name %)) test-focus?)
    (bar! g palette width summary-y :text :raised
          (tests/result-label result))
    (style! g palette :text :background)
    (doseq [[row line] (map-indexed vector (take page (drop start lines)))]
      (text! g width 1 (+ summary-y row 1) line))
    (when (:dirty? state)
      (bar! g palette width (- height 3) :warning :background
            "Unsaved changes · Save or Discard before opening test source"))
    (bar! g palette width (- height 2) :text :background
          "F4: suite · F5: namespace · F6: test · F7: stop · j: Java VMs")
    (bar! g palette width (dec height) :muted :background
          "Tab: focus · ↑↓: select · PgUp/PgDn: details · Enter: source · Esc: back")))

(defn- java-vm-panel! [^TerminalScreen screen g palette width height
                       {:keys [input caret error installed choice-index]}]
  (bar! g palette width 0 :accent :raised "Java VMs")
  (bar! g palette width 2 :text :background "JDK directory for project commands")
  (input! screen g palette width 1 3 1 input [0 (or caret (count input))])
  (if (some? choice-index)
    (let [rows (- height 7)
          start (max 0 (- (inc choice-index) rows))]
      (.setCursorPosition screen nil)
      (bar! g palette width 5 :accent :raised "Installed Java VMs · ↑↓: select · Enter: use · Esc: back")
      (doseq [[row vm] (map-indexed vector (take rows (drop start installed)))]
        (let [selected? (= choice-index (+ start row))]
          (bar! g palette width (+ 6 row)
                (if selected? :selected-text :text) (if selected? :selection :surface)
                (str (if selected? "> " "  ") (java-vm/label vm))))))
    (do
      (bar! g palette width 5 :muted :background "Leave blank to inherit the launcher environment")
      (bar! g palette width 6 :text :background "Enter: apply · Esc: cancel")
      (bar! g palette width 7 :muted :background
            (if (seq installed) "Tab: installed Java VMs" "No Java VMs detected"))
      (when error
        (style! g palette :error :background)
        (let [lines (TerminalTextUtils/getWordWrappedText (max 1 (- width 2)) (into-array String [error]))]
          (doseq [[i line] (map-indexed vector (take (- height 9) lines))]
            (text! g width 1 (+ i 9) line)))))))

(defn draw! [^TerminalScreen screen state full?]
  (let [size (.getTerminalSize screen)
        width (.getColumns size) height (.getRows size)
        g (.newTextGraphics screen)
        palette (colours state)
        rows (list-rows height)
        status (:status state)
        tree (tests/targets state)]
    ;; Paint only the back buffer. Screen.clear requests a full terminal erase.
    (style! g palette :text :background)
    (.fill g \space)
    (.setCursorPosition screen nil)
    (cond
      (or (< width 60) (< height 16))
      (do (text! g width 0 0 "Resize terminal to at least 60 × 16")
          (when (> height 1) (text! g width 0 1 (if (or (:java-vm-editor state) (:repl-open? state)
                                                       (get-in state [:tests-view :open?]))
                                                "Esc: back" "q: quit · t: theme"))))
      (:java-vm-editor state) (java-vm-panel! screen g palette width height (:java-vm-editor state))
      (:repl-open? state) (if (:inspection-open? state)
                            (inspection-panel! g palette width height state)
                            (repl-panel! screen g palette width height state))
      (get-in state [:tests-view :open?]) (tests-panel! g palette width height state tree)
      :else
      (do
        (bar! g palette width 0 :accent :raised "CLOJURE SYSTEM BROWSER · Source World")
        (style! g palette :muted :background)
        (text! g width 1 1 (str (or (:namespace state) "No project open")
                                     " / " (case (:category state) :requires "(requires)"
                                                 (or (:category state) "—"))
                                     "    Theme: " (name (get state :theme :dark)) " · t to change"))
        (lists! g palette width rows state tree)
        (source! g palette width height rows state)
        (bar! g palette width (- height 2)
              (cond (:editor-error state) :error
                    (re-find #"^[\w-]+:" (or status "")) :error
                    (:dirty? state) :warning :else :text)
              :raised status)
        (bar! g palette width (dec height) :muted :background
              "q: quit · ?: help · F3: Tests · j: Java VMs · p: REPL · !: errors · o: order · Tab: focus · e: edit · s: Save · t: theme")))
    ;; A full repaint is necessary when the physical terminal has been reset.
    ;; Otherwise even large changes (such as themes) can be sent without an erase.
    (.refresh screen (if full? Screen$RefreshType/COMPLETE Screen$RefreshType/DELTA))))
