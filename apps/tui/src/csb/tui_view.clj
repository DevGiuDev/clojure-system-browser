(ns csb.tui-view
  "Terminal presentation with explicit colours, independent of the shell theme."
  (:require [clojure.string :as str]
            [csb.repl :as repl]
            [csb.service :as service]
            [csb.session :as session]
            [csb.syntax :as syntax])
  (:import [com.googlecode.lanterna TerminalPosition TerminalSize TerminalTextUtils TextColor$Indexed]
           [com.googlecode.lanterna.graphics TextGraphics]
           [com.googlecode.lanterna.screen TerminalScreen Screen$RefreshType]))

;; Indices above the terminal's configurable ANSI 0–15 palette. No default colours.
(def ^:private themes
  {:dark {:background 234 :surface 235 :raised 237 :text 253 :muted 248
          :accent 81 :selection 24 :selected-text 231 :border 240 :warning 221 :error 210
          :special 81 :keyword 183 :string 150 :comment 246 :number 215
          :literal 209 :character 150 :delimiter 248 :reader 180}
   :light {:background 255 :surface 254 :raised 253 :text 234 :muted 240
           :accent 24 :selection 24 :selected-text 231 :border 245 :warning 58 :error 124
           :special 24 :keyword 90 :string 22 :comment 240 :number 94
           :literal 124 :character 22 :delimiter 240 :reader 94}})

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

(defn- lists! [g palette width rows state]
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
                y (+ 4 i)]
            (style! g palette (if (and selected? focused?) :selected-text :text)
                    (cond (and selected? focused?) :selection selected? :raised :else :surface))
            (rectangle! g x y (- right x) 1)
            (text! g right (inc x) y
                   (str (if selected? "> " "  ")
                        (cond (= c 2) (str (:name item) (when-not (:writable? item) " [RO]"))
                              (= c 1) (if (:category item)
                                        (str (name (:category item)) " · " (:count item))
                                        "(all)")
                              :else item)))))
        (when (pos? c)
          (style! g palette :border :background)
          (.drawLine ^TextGraphics g (dec x) 3 (dec x) (+ 3 rows) \│))))))

(def ^:private help
  (str "Tab/Left/Right: focus a list\nUp/Down: select · PgUp/PgDn: scroll source\n"
       "e: edit definition in $EDITOR (external editor)\ns / Ctrl+S: Save · d: Discard · r: Reload\n"
       "t: toggle dark/light theme\nx: example command · v: extension view\n"
       "o: source order (real file order of top-level forms)\n"
       "p: nREPL panel\n"
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

(defn- repl-input! [^TerminalScreen screen g palette width x y rows text [row column]]
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
  (let [{:keys [phase namespace status port input] :as panel} (:repl state)
        connected? (= :connected phase)
        disconnected? (contains? #{nil :disconnected} phase)
        rows (repl-page-size height)
        lines (repl-lines panel width)
        start (max 0 (- (count lines) rows (get state :repl-scroll 0)))]
    (bar! g palette width 0 :accent :raised
          (str "nREPL · 127.0.0.1" (when-not disconnected? (str ":" port))
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
        (repl-input! screen g palette width 1 (- height 7) 4 input (get-in state [:repl-caret :input])))
      disconnected?
      (do
        (bar! g palette width (- height 7) :accent :surface "Port:")
        (repl-input! screen g palette width 7 (- height 7) 1 port (get-in state [:repl-caret :port]))))
    (bar! g palette width (- height 2) :text :background
          (if connected?
            "F5: evaluate · F6: evaluate definition · F7: interrupt"
            (if disconnected? "Enter: connect" "F8: disconnect")))
    (bar! g palette width (dec height) :muted :background
          (if connected? "Esc: source · F8: disconnect" "Esc: source"))))

(defn draw! [^TerminalScreen screen state full?]
  (let [size (.getTerminalSize screen)
        width (.getColumns size) height (.getRows size)
        g (.newTextGraphics screen)
        palette (colours state)
        rows (list-rows height)
        status (:status state)]
    ;; Paint only the back buffer. Screen.clear requests a full terminal erase.
    (style! g palette :text :background)
    (.fill g \space)
    (.setCursorPosition screen nil)
    (cond
      (or (< width 60) (< height 16))
      (do (text! g width 0 0 "Resize terminal to at least 60 × 16")
          (when (> height 1) (text! g width 0 1 (if (:repl-open? state) "Esc: source" "q: quit · t: theme"))))
      (:repl-open? state) (repl-panel! screen g palette width height state)
      :else
      (do
        (bar! g palette width 0 :accent :raised "CLOJURE SYSTEM BROWSER · Source World")
        (style! g palette :muted :background)
        (text! g width 1 1 (str (or (:namespace state) "No project open")
                                     " / " (or (:category state) "—")
                                     "    Theme: " (name (get state :theme :dark)) " · t to change"))
        (lists! g palette width rows state)
        (source! g palette width height rows state)
        (bar! g palette width (- height 2)
              (cond (:editor-error state) :error
                    (re-find #"^[\w-]+:" (or status "")) :error
                    (:dirty? state) :warning :else :text)
              :raised status)
        (bar! g palette width (dec height) :muted :background
              "q: quit · ?: help · p: REPL · !: errors · o: order · Tab: focus · ↑↓: select · e: edit · s: Save · t: theme")))
    ;; A full repaint is necessary when the physical terminal has been reset.
    ;; Otherwise even large changes (such as themes) can be sent without an erase.
    (.refresh screen (if full? Screen$RefreshType/COMPLETE Screen$RefreshType/DELTA))))
