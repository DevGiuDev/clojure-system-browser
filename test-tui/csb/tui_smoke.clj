(ns csb.tui-smoke
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is testing]]
            [csb.cli :as cli]
            [csb.editor :as editor]
            [csb.repl :as repl]
            [csb.session :as session]
            [csb.source :as source]
            [csb.tui :as tui]
            [csb.ui-fixture :as fixture]
            [nrepl.server :as server])
  (:import [com.googlecode.lanterna TerminalSize TextColor TextColor$Indexed]
           [com.googlecode.lanterna.input KeyStroke KeyType]
           [com.googlecode.lanterna.screen TerminalScreen]
           [com.googlecode.lanterna.terminal.virtual DefaultVirtualTerminal]
           [java.nio.file Files Path]))

(defn- with-terminal [root f]
  (let [private-mode? (atom false)
        output (atom {:flushes 0 :clears 0})
        terminal (proxy [DefaultVirtualTerminal] [(TerminalSize. 120 35)]
                   (enterPrivateMode [] (proxy-super enterPrivateMode) (reset! private-mode? true))
                   (exitPrivateMode [] (proxy-super exitPrivateMode) (reset! private-mode? false))
                   (clearScreen [] (proxy-super clearScreen) (swap! output update :clears inc))
                   (flush [] (proxy-super flush) (swap! output update :flushes inc)))
        screen (TerminalScreen. terminal)
        last-frame (volatile! nil)
        state (session/create)]
    (when root (session/open! state root {:example-extension? true}))
    (try
      (.startScreen screen)
      (.setCursorPosition screen nil)
      (#'tui/render-if-changed! screen @state last-frame)
      (f {:terminal terminal :screen screen :state state :last-frame last-frame
          :output output :private-mode? private-mode?})
      (finally (.close screen)))))

(defn- frame [{:keys [^TerminalScreen screen]}]
  (let [size (.getTerminalSize screen)]
    (str/join "\n" (for [y (range (.getRows size))]
                    (apply str (for [x (range (.getColumns size))]
                                 (.getCharacterString (.getFrontCharacter screen x y))))))))

(defn- key-stroke [key]
  (if (char? key) (KeyStroke. ^Character key false false) (KeyStroke. ^KeyType key)))

(defn- press! [{:keys [^DefaultVirtualTerminal terminal screen state last-frame]} key]
  (.addInput terminal (if (instance? KeyStroke key) key (key-stroke key)))
  ;; The production editor handoff prints instructions to the real terminal.
  (binding [*out* (java.io.StringWriter.)]
    (#'tui/step! screen state last-frame)))

(defn- navigate! [ui key selected?]
  (loop [remaining 16]
    (when-not (selected?)
      (when (zero? remaining) (throw (ex-info "TUI navigation did not reach its target" {:key key})))
      (press! ui key)
      (recur (dec remaining)))))

(defn- total! [{:keys [state] :as ui}]
  ;; Navigate with the same input queue and iteration used by the terminal loop.
  (navigate! ui KeyType/ArrowUp #(= 'shop.orders (:namespace @state)))
  (press! ui KeyType/Tab)
  (navigate! ui KeyType/ArrowUp #(= :functions (:category @state)))
  (press! ui KeyType/Tab)
  (navigate! ui KeyType/ArrowDown #(= 'total (get-in @state [:definition :name]))))

(deftest project-search-opens-namespaces-and-definitions
  (let [shortcut \f]
    (with-terminal nil
      (fn [ui]
        (press! ui shortcut)
        (is (str/includes? (frame ui) "Open a project to search"))
        (press! ui KeyType/Escape)
        (is (str/includes? (frame ui) "CLOJURE SYSTEM BROWSER"))))
    (fixture/with-example
     (fn [root]
       (with-terminal root
         (fn [{:keys [state] :as ui}]
           (is (str/includes? (frame ui) "f: search"))
           (press! ui shortcut)
           (is (str/includes? (frame ui) "Project search"))
           (press! ui \f)
           (is (press! ui \q) "Typing in the search box does not quit")
           (is (str/includes? (frame ui) "fq") "The shortcut can be typed in the query")
           (is (str/includes? (frame ui) "No matches"))
           (press! ui KeyType/Enter)
           (is (str/includes? (frame ui) "Project search"))
           (press! ui KeyType/Backspace)
           (press! ui KeyType/Backspace)
           (doseq [ch "ORDERS"] (press! ui ch))
           (let [screen (frame ui)]
             (is (when-let [namespaces (str/index-of screen "Namespaces")]
                   (< namespaces (or (str/index-of screen "Functions") -1))))
             (is (str/includes? screen (str "shop.orders/total · " (java.io.File. "src/shop/orders.clj") ":")))
             (is (str/includes? screen "Private functions")))
           (press! ui KeyType/ArrowDown)
           (press! ui KeyType/Enter)
           (is (= 'shop.orders-test (:namespace @state)))
           (is (str/includes? (frame ui) "(deftest total-test"))
           (press! ui shortcut)
           (doseq [ch "shop.orders/totalx"] (press! ui ch))
           (press! ui KeyType/ArrowLeft)
           (press! ui KeyType/Delete)
           (press! ui KeyType/Enter)
           (is (= 'shop.orders (:namespace @state)))
           (is (= 'total (get-in @state [:definition :name])))
           (is (str/includes? (frame ui) "(defn total"))))))))

(deftest project-search-preserves-pending-drafts
  (fixture/with-example
   (fn [root]
     (with-terminal root
       (fn [{:keys [state] :as ui}]
         (total! ui)
         (session/edit! state fixture/total-draft)
         (press! ui \f)
         (doseq [ch "orders"] (press! ui ch))
         (press! ui KeyType/Enter)
         (is (str/includes? (frame ui) "Unsaved changes: Save or Discard"))
         (is (str/includes? (frame ui) "Project search"))
         (press! ui KeyType/ArrowDown)
         (press! ui KeyType/ArrowDown)
         (press! ui KeyType/Enter)
         (is (str/includes? (frame ui) "Project search"))
         (is (= fixture/total-draft (:draft @state)))
         (press! ui KeyType/Escape)
         (is (= 'total (get-in @state [:definition :name])))
         (is (str/includes? (frame ui) "(+ 10")))))))

(deftest repl-panel-keeps-port-input-separate-from-source-shortcuts
  (fixture/with-example
   (fn [root]
     (with-terminal root
       (fn [{:keys [state] :as ui}]
         (total! ui)
         (session/edit! state fixture/total-draft)
         (press! ui \p)
         (is (str/includes? (frame ui) "nREPL"))
         (is (press! ui \q) "A character in the panel does not quit")
         (press! ui \s)
         (is (:dirty? @state) "A character in the panel does not save")
         (doseq [key "65536"] (press! ui key))
         (press! ui KeyType/Enter)
         (is (str/includes? (frame ui) "Enter a port"))
         (press! ui KeyType/Escape)
         (is (str/includes? (frame ui) "(defn total"))
         (is (= fixture/total-draft (:draft @state))))))))

(deftest repl-input-preserves-newlines-and-ignores-source-commands
  (with-terminal nil
    (fn [{:keys [state] :as ui}]
      (swap! state assoc :repl {:phase :connected :namespace "user"})
      (press! ui \p)
      (doseq [key "(str \"q\""] (press! ui key))
      (press! ui KeyType/Enter)
      (doseq [key " \"s\")"] (press! ui key))
      (is (= "(str \"q\"\n \"s\")" (get-in @state [:repl :input])))
      (press! ui KeyType/ArrowLeft)
      (press! ui KeyType/Backspace)
      (is (= "(str \"q\"\n \"s)" (get-in @state [:repl :input])))
      (press! ui KeyType/Escape)
      (is (= "Open a project" (:status @state))))))

(deftest repl-port-is-read-from-the-project-and-can-be-edited
  (fixture/with-example
   (fn [root]
     (let [port-file (.resolve root ".nrepl-port")]
       (fixture/write! port-file "65536\n")
       (with-terminal root
         (fn [{:keys [state] :as ui}]
           (press! ui \p)
           (is (str/blank? (get-in @state [:repl :port])))
           (press! ui KeyType/Escape)
           (fixture/write! port-file " 41234\n")
           (press! ui \p)
           (is (str/includes? (frame ui) "41234"))
           (press! ui KeyType/Backspace)
           (press! ui \5)
           (press! ui KeyType/Escape)
           (press! ui \p)
           (is (str/includes? (frame ui) "41235"))))))))

(deftest runtime-inspector-has-independent-key-navigation
  (with-open [running (server/start-server :bind "127.0.0.1" :port 0)]
    (fixture/with-example
     (fn [root]
       (fixture/write! (.resolve root ".nrepl-port") (str (:port running)))
       (with-terminal root
         (fn [{:keys [state] :as ui}]
           (total! ui)
           (session/edit! state fixture/total-draft)
           (try
             (press! ui \p)
             (press! ui KeyType/Enter)
             (fixture/await-state! state #(= :connected (get-in % [:repl :phase])))
             (press! ui KeyType/F4)
             (is (str/includes? (frame ui) "Live · Namespaces"))
             (when (:inspection-open? @state)
               (fixture/await-state! state #(get-in % [:repl :inspection :observation]))
               (press! ui KeyType/Tab)
               (is (str/includes? (frame ui) "Combined · Namespaces"))
               (press! ui KeyType/Enter)
               (fixture/await-state! state #(get-in % [:repl :inspection :observation]))
               (press! ui KeyType/ArrowDown)
               (is (str/includes? (frame ui) "Combined · Vars"))
               (press! ui KeyType/F4))
             (press! ui KeyType/Escape)
             (is (= fixture/total-draft (:draft @state)))
             (finally (repl/disconnect! state)))))))))

(defn- editor-argv [^Path root draft exit-code]
  (let [script (.resolve root "editor fixture.clj")
        replacement (.resolve root "replacement.txt")
        log (.resolve root "editor-log.edn")]
    (fixture/write! replacement draft)
    (fixture/write!
     script
     (str "(let [[replacement log exit-code temp] *command-line-args*]\n"
          "  (spit log (pr-str {:path temp :before (slurp temp :encoding \"UTF-8\")}))\n"
          "  (spit temp (slurp replacement :encoding \"UTF-8\") :encoding \"UTF-8\")\n"
          "  (System/exit (Integer/parseInt exit-code)))\n"))
    [(str (System/getProperty "java.home") "/bin/java") "-cp" (System/getProperty "java.class.path")
     "clojure.main" (str script) (str replacement) (str log) (str exit-code)]))

(defn- edit! [ui root draft exit-code]
  (let [argv (editor-argv root draft exit-code)]
    (with-redefs [cli/editor-argv (constantly argv)] (press! ui \e))))

(defn- cells [^TerminalScreen screen]
  (let [size (.getTerminalSize screen)]
    (for [y (range (.getRows size)) x (range (.getColumns size))]
      (.getFrontCharacter screen x y))))

(defn- luminance [^TextColor colour]
  (let [linear (fn [n]
                 (let [s (/ n 255.0)]
                   (if (<= s 0.04045) (/ s 12.92) (Math/pow (/ (+ s 0.055) 1.055) 2.4))))]
    (+ (* 0.2126 (linear (.getRed colour)))
       (* 0.7152 (linear (.getGreen colour)))
       (* 0.0722 (linear (.getBlue colour))))))

(defn- readable? [cell]
  (let [foreground (luminance (.getForegroundColor cell))
        background (luminance (.getBackgroundColor cell))]
    (>= (/ (+ 0.05 (max foreground background)) (+ 0.05 (min foreground background))) 4.5)))

(defn- check-colours! [{:keys [screen]}]
  (let [all (cells screen)
        text (remove #(or (str/blank? (.getCharacterString %))
                          (= "│" (.getCharacterString %))) all)]
    (is (every? #(and (instance? TextColor$Indexed (.getForegroundColor %))
                     (instance? TextColor$Indexed (.getBackgroundColor %))) all)
        "Every cell has an explicit foreground and background, including blank space")
    (is (seq text))
    (let [unreadable (remove readable? text)]
      (is (empty? unreadable)
          (str "Text needs at least 4.5:1 contrast: " (pr-str (take 3 unreadable)))))))

(defn- colour-at [{:keys [^TerminalScreen screen] :as ui} fragment]
  (or (some (fn [[y line]]
              (when-let [x (str/index-of line fragment)]
                (.getForegroundColor (.getFrontCharacter screen x y))))
            (map-indexed vector (str/split-lines (frame ui))))
      (throw (ex-info "Text is not visible" {:fragment fragment}))))

(deftest project-java-vm-editor-isolates-source-shortcuts
  (fixture/with-example
   (fn [root]
     (with-terminal root
       (fn [{:keys [state] :as ui}]
         (total! ui)
         (session/edit! state fixture/total-draft)
         (press! ui \j)
         (is (str/includes? (frame ui) "Java VMs"))
         (press! ui KeyType/Tab)
         (is (str/includes? (frame ui) "Installed Java VMs"))
         (is (str/includes? (frame ui) (str "Java " (.feature (Runtime/version)))))
         (press! ui KeyType/Enter)
         (is (not (str/blank? (get-in @state [:java-vm-editor :input]))))
         (press! ui KeyType/Escape)
         (press! ui \j)
         (is (true? (press! ui \q)))
         (press! ui KeyType/Enter)
         (is (str/includes? (frame ui) "absolute"))
         (press! ui KeyType/Backspace)
         (doseq [ch (System/getProperty "java.home")] (press! ui ch))
         (press! ui KeyType/Enter)
         (is (str/includes? (frame ui) "Java VM saved"))
         (is (= fixture/total-draft (:draft @state)))
         (press! ui \j)
         (is (str/includes? (frame ui) (System/getProperty "java.home")))
         (doseq [_ (System/getProperty "java.home")] (press! ui KeyType/Backspace))
         (press! ui KeyType/Enter)
         (is (str/includes? (frame ui) "Inherit launcher environment")))))))

(deftest tests-view-executes-suites-and-namespaces-without-a-repl
  (fixture/with-test-project
   (fn [root]
     (with-terminal root
       (fn [{:keys [state screen last-frame] :as ui}]
         (let [draw! #(#'tui/render-if-changed! screen @state last-frame)
               led #(colour-at ui (str "● " %))]
           (press! ui \?)
           (press! ui KeyType/F3)
           (is (str/includes? (frame ui) "Test namespaces"))
           (is (= (TextColor$Indexed. 248) (led "shop.orders-test")))
           (press! ui KeyType/F4)
           (is (= (TextColor$Indexed. 215) (led "passing-test")))
           (is (str/includes? (frame ui) "Running"))
           (fixture/await-state! state #(= :failed (get-in % [:test-runner :last-run :status])))
           (draw!)
           (is (= (TextColor$Indexed. 114) (led "passing-test")))
           (is (= (TextColor$Indexed. 210) (led "failing-test")))
           (is (str/includes? (frame ui) "Configured suite command"))
           (is (= (TextColor$Indexed. 210) (colour-at ui "FAIL shop.orders-test/failing-test")))
           (press! ui KeyType/F5)
           (fixture/await-state! state #(= :failed (get-in % [:test-runner :last-run :status])))
           (draw!)
           (is (str/includes? (frame ui) "Ran 2 tests"))
           (is (str/includes? (frame ui) "expected:"))
           (press! ui KeyType/Enter)
           (is (= 'shop.orders-test (:namespace @state)))
           (is (str/includes? (frame ui) "deftest failing-test"))
           (is (= (TextColor$Indexed. 210) (colour-at ui "Totals should match")))
           (is (str/includes? (frame ui) "Expected: (= 1 2)"))
           (is (= (TextColor$Indexed. 210) (colour-at ui "! ")))
           (is (= (TextColor$Indexed. 210) (led "shop.orders-test")))))))))

(deftest source-syntax-is-coloured-in-both-themes-without-changing-the-draft
  (fixture/with-example
   (fn [root]
     (with-terminal root
       (fn [{:keys [state screen last-frame output] :as ui}]
         (total! ui)
         (let [draft (str "(defn total [café]\r\n"
                          "  ; comment :not-a-keyword\r\n"
                          "  {:key \"first\r\nsecond :inside\" :pattern #\"[()]+\"\r\n"
                          "   :amount 42 :flag true :char \\newline}\r\n"
                          "  #_ :ignored)\r\n")]
           (session/edit! state draft)
           (doseq [[theme special keyword string comment number literal reader delimiter]
                   [[:dark 81 183 150 246 215 209 180 248]
                    [:light 24 90 22 240 94 124 94 240]]]
             (#'tui/render-if-changed! screen @state last-frame)
             (doseq [[fragment expected]
                     [["defn total" special] [":key" keyword] ["\"first" string]
                      ["second :inside" string] ["#\"[()]+\"" string]
                      ["\\newline" string] ["; comment" comment] ["42" number]
                      ["true" literal] ["#_" reader] ["[café]" delimiter]]]
               (is (= (TextColor$Indexed. expected) (colour-at ui fragment))
                   (str theme " colours " fragment)))
             (check-colours! ui)
             (is (= draft (:draft @state)) "CRLF and Unicode remain exact in the session")
             (let [before @output]
               (#'tui/step! screen state last-frame)
               (is (= before @output) "A coloured frame also stays quiet while idle"))
             (when (= theme :dark) (press! ui \t)))
           (let [multiline (str "(defn total []\n  \"start\n"
                                (str/join "\n" (repeat 12 ":still-a-string")))]
             (session/edit! state multiline)
             (press! ui KeyType/PageDown)
             (is (= (TextColor$Indexed. 22) (colour-at ui ":still-a-string"))
                 "An unfinished multiline string retains its colour when its opening is offscreen")
             (is (= multiline (:draft @state)))
             (check-colours! ui))
           (press! ui \?)
           (is (= (colour-at ui "Tab/Left") (colour-at ui "$EDITOR"))
               "Help is plain text, not source code")
           (press! ui \!)
           (is (str/includes? (frame ui) "No source errors"))
           (check-colours! ui)))))))

(deftest explicit-themes-remain-readable-with-drafts-and-errors
  (with-terminal nil
    (fn [ui]
      (check-colours! ui)
      (press! ui \t)
      (is (str/includes? (frame ui) "Theme: light"))
      (check-colours! ui)))
  (fixture/with-example
   (fn [root]
     (with-terminal root
       (fn [{:keys [screen state] :as ui}]
         (let [active (.getBackgroundColor (.getFrontCharacter ^TerminalScreen screen 2 4))]
           (press! ui KeyType/Tab)
           (is (not= active (.getBackgroundColor (.getFrontCharacter ^TerminalScreen screen 2 4)))
               "Selected rows distinguish the focused list from the other lists"))
         (press! ui KeyType/ArrowLeft)
         (total! ui)
         (session/edit! state "(defn total [")
         (let [definition (:definition @state)]
           (doseq [theme [:light :dark]]
             (press! ui \t)
             (is (= theme (:theme @state)))
             (is (= "(defn total [" (:draft @state)))
             (is (= definition (:definition @state)))
             (is (:dirty? @state))
             (is (str/includes? (frame ui) "UNSAVED"))
             (press! ui \s)
             (is (str/includes? (frame ui) "parse:"))
             (check-colours! ui))))))))

(deftest idle-input-does-not-write-to-the-terminal-and-resize-still-renders
  (with-terminal nil
    (fn [{:keys [screen terminal state last-frame output] :as ui}]
      (let [before @output]
        (dotimes [_ 8] (#'tui/step! screen state last-frame))
        (press! ui \z)
        (is (= before @output) "Idle polling and an unbound key emit no terminal output")
        (press! ui KeyType/Tab)
        (is (> (:flushes @output) (:flushes before)))
        (is (= (:clears before) (:clears @output)) "Focus changes use a delta, without clearing the terminal"))
      (let [before (:flushes @output)]
        (.setTerminalSize ^DefaultVirtualTerminal terminal (TerminalSize. 40 10))
        (#'tui/step! screen state last-frame)
        (is (> (:flushes @output) before))
        (is (str/includes? (frame ui) "Resize terminal"))
        (check-colours! ui)
        (.setTerminalSize ^DefaultVirtualTerminal terminal (TerminalSize. 120 35))
        (#'tui/step! screen state last-frame)
        (is (str/includes? (frame ui) "CLOJURE SYSTEM BROWSER"))
        (is (str/includes? (frame ui) "Open a project"))))))

(deftest initial-state-navigation-resize-and-extension-keys
  (with-terminal nil
    (fn [ui]
      (is (str/includes? (frame ui) "Open a project"))
      (is (false? (press! ui \q)))))
  (fixture/with-example
   (fn [root]
     (with-terminal
       root
       (fn [{:keys [terminal screen state last-frame] :as ui}]
         (press! ui KeyType/ArrowDown)
         (is (= 'shop.orders-test (:namespace @state)))
         (total! ui)
         (is (str/includes? (frame ui) "(defn total"))
         (is (not (str/includes? (frame ui) "(ns shop.orders)")))
         (press! ui \?)
         (is (str/includes? (frame ui) "e: edit definition in $EDITOR"))
         (press! ui \!)
         (is (str/includes? (frame ui) "No source errors"))
         (press! ui \!)
         (press! ui \x)
         (is (str/includes? (frame ui) "Hello from the Source World extension"))
         (press! ui \v)
         (is (str/includes? (frame ui) ":world :source"))
         (.setTerminalSize ^DefaultVirtualTerminal terminal (TerminalSize. 40 10))
         (#'tui/step! screen state last-frame)
         (is (str/includes? (frame ui) "Resize terminal"))
         (.setTerminalSize ^DefaultVirtualTerminal terminal (TerminalSize. 120 35))
         (#'tui/step! screen state last-frame)
         (is (str/includes? (frame ui) "(defn total"))
         (press! ui KeyType/PageDown)
         (is (= 8 (:scroll @state)))
         (press! ui KeyType/PageUp)
         (is (zero? (:scroll @state)))
         (is (false? (press! ui \q))))))))

(deftest external-editor-and-save-use-the-shared-service
  (fixture/with-example
   (fn [root]
     (with-terminal
       root
       (fn [{:keys [state terminal] :as ui}]
         (total! ui)
         (let [file (.resolve root "src/shop/orders.clj")
               original (Files/readString file)
               before (:draft @state)]
           (edit! ui root fixture/total-draft 0)
           (is (= "C" (.getCharacterString (.getCharacter ^DefaultVirtualTerminal terminal 1 0)))
               "The physical terminal, not only the screen buffer, is restored after the editor")
           (let [{:keys [path] editor-before :before}
                 (edn/read-string (slurp (str (.resolve root "editor-log.edn"))))]
             (is (= before editor-before))
             (is (not= (str file) path))
             (is (not (Files/exists (Path/of path (make-array String 0)) (make-array java.nio.file.LinkOption 0)))))
           (is (= fixture/total-draft (:draft @state)))
           (is (:dirty? @state))
           (is (fixture/bytes= original file))
           (is (str/includes? (frame ui) "UNSAVED"))
           (let [before @state]
             (edit! ui root fixture/total-draft 0)
             (is (= before @state) "Returning an identical draft need not change session state")
             (is (= "C" (.getCharacterString (.getCharacter ^DefaultVirtualTerminal terminal 1 0)))
                 "An editor return repaints even when the state is identical"))
           (is (true? (press! ui \q)))
           (is (str/includes? (frame ui) "Unsaved changes"))
           (press! ui KeyType/ArrowUp)
           (is (= 'total (get-in @state [:definition :name])))
           (press! ui \r)
           (is (= fixture/total-draft (:draft @state)))
           (press! ui (KeyStroke. (Character/valueOf \s) true false))
           (is (= "Saved" (:status @state)))
           (is (false? (:dirty? @state)))
           (is (fixture/bytes= (str/replace original before fixture/total-draft) file))
           (press! ui \r)
           (is (str/includes? (:status @state) "Reloaded"))
           (is (false? (press! ui \q)))))))))

(deftest rejected-saves-preserve-drafts-and-disk
  (fixture/with-example
   (fn [root]
     (with-terminal
       root
       (fn [{:keys [state] :as ui}]
         (total! ui)
         (let [file (.resolve root "src/shop/orders.clj") original (Files/readString file)]
           (testing "reader syntax errors"
             (edit! ui root "(defn total [" 0)
             (press! ui \s)
             (is (str/includes? (frame ui) "parse:"))
             (is (= "(defn total [" (:draft @state)))
             (is (:dirty? @state))
             (is (fixture/bytes= original file)))
           (testing "external changes"
             (edit! ui root fixture/total-draft 0)
             (let [external (str original "\r\n;; external edit\r\n")]
               (fixture/write! file external)
               (press! ui \s)
               (is (str/includes? (frame ui) "conflict:"))
               (is (= fixture/total-draft (:draft @state)))
               (is (:dirty? @state))
               (is (fixture/bytes= external file))
               (press! ui \d)
               (press! ui \r)
               (is (false? (:dirty? @state)))
               (is (fixture/bytes= external file))))
           (testing "I/O errors"
             ;; Reload selects the first definition; edit that selected form.
             (let [draft (str (get-in @state [:definition :source]) "\r\n;; io draft\r\n")]
               (edit! ui root draft 0)
               (with-redefs-fn {#'source/replace-atomically!
                               (fn [& _] (throw (java.io.IOException. "simulated disk write failure")))}
                 #(press! ui \s))
               (is (str/includes? (frame ui) "io:"))
               (is (fixture/bytes= (str original "\r\n;; external edit\r\n") file))
               (is (= draft (:draft @state)))
               (is (:dirty? @state))))))))))

(deftest failed-editor-retains-its-temporary-file-and-restarts-screen
  (fixture/with-example
   (fn [root]
     (with-terminal
       root
       (fn [{:keys [state] :as ui}]
         (total! ui)
         (let [before (:draft @state) file (.resolve root "src/shop/orders.clj") original (Files/readString file)]
           (edit! ui root "(defn total [lines] :recover-me)" 7)
           (let [{:keys [path]} (edn/read-string (slurp (str (.resolve root "editor-log.edn"))))
                 temp (Path/of path (make-array String 0))]
             (try
               (is (str/includes? (:status @state) path))
               (is (= "(defn total [lines] :recover-me)" (Files/readString temp)))
               (is (= before (:draft @state)))
               (is (fixture/bytes= original file))
               (is (str/includes? (frame ui) "Editor failed; draft retained"))
               (is (str/includes? (frame ui) "Editor exited unsuccessfully"))
               (is (str/includes? (frame ui) "exit 7"))
               (press! ui \?)
               (is (str/includes? (frame ui) "e: edit definition in $EDITOR"))
               (finally (Files/deleteIfExists temp))))))))))

(deftest missing-editor-explains-the-failure-and-retains-the-current-draft
  (fixture/with-example
   (fn [root]
     (with-terminal root
       (fn [{:keys [state terminal private-mode?] :as ui}]
         (total! ui)
         (session/edit! state fixture/total-draft)
         (let [file (.resolve root "src/shop/orders.clj") original (Files/readString file)
               argv [(str (.resolve root "missing editor"))]]
           (.setTerminalSize ^DefaultVirtualTerminal terminal (TerminalSize. 80 20))
           (with-redefs [cli/editor-argv (constantly argv)] (press! ui \e))
           (let [temp (Path/of (get-in @state [:editor-error :temp]) (make-array String 0))]
             (try
               (is (= :io (get-in @state [:editor-error :error :type])))
               (is (= argv (get-in @state [:editor-error :argv])))
               (is (str/includes? (frame ui) "Cannot run program"))
               (is (str/includes? (frame ui) "Esc: source"))
               (is (= fixture/total-draft (Files/readString temp)))
               (is (= fixture/total-draft (:draft @state)))
               (is (:dirty? @state))
               (is @private-mode?)
               (is (fixture/bytes= original file))
               (check-colours! ui)
               (press! ui KeyType/PageDown)
               (is (= 6 (:scroll @state)) "A short panel does not skip any lines of error details")
               (press! ui KeyType/PageUp)
               (is (zero? (:scroll @state)))
               (press! ui KeyType/Escape)
               (is (nil? (:editor-error @state)))
               (is (str/includes? (frame ui) "(+ 10"))
               (edit! ui root fixture/total-draft 0)
               (is (= "Draft loaded from editor. Press s to save, d to discard." (:status @state)))
               (is (fixture/bytes= original file))
               (finally (Files/deleteIfExists temp))))))))))

(deftest invalid-editor-settings-are-reported-before-a-temporary-or-process-is-created
  (fixture/with-example
   (fn [root]
     (with-terminal root
       (fn [{:keys [state private-mode?] :as ui}]
         (total! ui)
         (session/edit! state fixture/total-draft)
         (with-redefs [cli/editor-argv #(editor/command {"EDITOR" "vim 'unclosed"})
                       editor/begin! (fn [& _] (throw (AssertionError. "Do not create a temporary for invalid configuration")))]
           (press! ui \e))
         (is (= :invalid-editor (get-in @state [:editor-error :error :type])))
         (is (nil? (get-in @state [:editor-error :temp])))
         (is (str/includes? (frame ui) "Unclosed quote"))
         (is (= fixture/total-draft (:draft @state)))
         (is (:dirty? @state))
         (is @private-mode?)
         (press! ui KeyType/Escape)
         (is (str/includes? (frame ui) "(+ 10")))))))

(deftest eof-recovers-the-draft-and-the-loop-closes-cleanly
  (fixture/with-example
   (fn [root]
     (with-terminal
       root
       (fn [{:keys [^DefaultVirtualTerminal terminal screen state private-mode?] :as ui}]
         (total! ui)
         (edit! ui root fixture/total-draft 0)
         (.addInput terminal (KeyStroke. KeyType/EOF))
         (#'tui/run-screen! screen state)
         (let [recovery (:recovery @state)
               temp (Path/of (subs recovery (count "Unsaved draft retained at ")) (make-array String 0))]
           (try
             (is (= fixture/total-draft (Files/readString temp)))
             (is (:dirty? @state))
             (is (false? @private-mode?))
             (finally (Files/deleteIfExists temp)))))))))

(deftest clojuredart-is-visible-and-cannot-launch-an-editor-or-save
  (fixture/with-example
   (fn [root]
     (let [file (.resolve root "src/dart/core.cljd")
           code "(ns aaa.dart) (defn widget [] (#/(identity :ok)))"]
       (fixture/write! file code)
       (with-terminal
         root
         (fn [{:keys [state] :as ui}]
           (is (= 'aaa.dart (:namespace @state)))
           (is (str/includes? (frame ui) "(defn widget"))
           (is (= (TextColor$Indexed. 183) (colour-at ui ":ok"))
               "Read-only ClojureDart is highlighted too")
           (with-redefs [cli/editor-argv (fn [] (throw (AssertionError. "Read-only source must not launch an editor")))]
             (press! ui \e))
           (is (str/includes? (frame ui) "This definition is read-only"))
           (press! ui \s)
           (is (str/includes? (:status @state) "ClojureDart (.cljd) is read-only"))
           (is (false? (:dirty? @state)))
           (is (fixture/bytes= code file))))))))

(deftest source-order-panel-shows-the-real-file-order
  (with-terminal nil
    (fn [ui]
      (press! ui \o)
      (is (str/includes? (frame ui) "No project open"))))
  (fixture/with-example
   (fn [root]
     (with-terminal
      root
      (fn [{:keys [state] :as ui}]
        (total! ui)
        ;; Opening another panel first proves the toggle is exclusive.
        (press! ui \?)
        (is (str/includes? (frame ui) "o: source order"))
        (press! ui \o)
        (let [f (frame ui)]
          (is (str/includes? f "Source order · Esc: source"))
          (is (str/includes? f "src/shop/orders.clj"))
          ;; Every significant top-level form in real order, ns included.
          (is (str/includes? f "0 ns shop.orders · row 2"))
          ;; total precedes discount in the file, unlike the alphabetical list.
          (is (str/includes? f "> 1 defn total · row 5"))
          (is (str/includes? f "2 defn- discount · row 11"))
          (is (str/includes? f "3 defn receipt · row 14"))
          (is (< (str/index-of f "defn total") (str/index-of f "defn- discount"))
              "The panel shows file order, not alphabetical order")
          (is (not (str/includes? f "Tab/Left/Right")) "The order panel replaces help")
          (check-colours! ui))
        ;; Navigating moves the current marker without closing the panel.
        (press! ui KeyType/ArrowUp)
        (let [f (frame ui)]
          (is (str/includes? f "> 3 defn receipt · row 14"))
          (is (str/includes? f "  1 defn total · row 5"))
          (is (true? (:source-order? @state))))
        (press! ui KeyType/Escape)
        (is (str/includes? (frame ui) "(defn receipt"))
        (is (false? (:source-order? @state)))
        (session/edit! state "(defn total [")
        (press! ui \o)
        (is (:dirty? @state))
        (is (= "(defn total [" (:draft @state)))
        (is (str/includes? (frame ui) "UNSAVED"))
        (press! ui KeyType/Escape)
        (is (= "(defn total [" (:draft @state)))
        (is (false? (:source-order? @state))))))))

(deftest category-facets-show-counts-and-all-clears-the-filter
  (fixture/with-example
   (fn [root]
     (with-terminal
      root
      (fn [{:keys [state] :as ui}]
        ;; Counted facet rows, the summary in the column header, and the sentinel.
        (let [f (frame ui)]
          (is (str/includes? f "functions · 2"))
          (is (str/includes? f "private-functions · 1"))
          (is (str/includes? f "(all)"))
          (is (str/includes? f "Categories · 3 defs · 1 file")))
        (is (nil? (:category @state)))
        ;; The unfiltered default lists every kind; discount (private) is first.
        (is (= 'discount (get-in @state [:definition :name])))
        ;; Focus the categories column and select the functions facet.
        ;; "(all)" starts selected, so the facets are reached going up.
        (press! ui KeyType/Tab)
        (navigate! ui KeyType/ArrowUp #(= :functions (:category @state)))
        (is (= #{'receipt 'total} (set (map :name (session/definitions @state)))))
        ;; ArrowDown again lands on private-functions, then on "(all)".
        (press! ui KeyType/ArrowDown)
        (is (= :private-functions (:category @state)))
        (press! ui KeyType/ArrowDown)
        (is (nil? (:category @state)))
        (is (= #{'discount 'receipt 'total} (set (map :name (session/definitions @state))))))))))

(deftest requires-selector-shows-namespace-declarations
  (fixture/with-example
   (fn [root]
     (with-terminal
      root
      (fn [{:keys [state] :as ui}]
        (press! ui KeyType/ArrowDown)
        (is (= 'shop.orders-test (:namespace @state)))
        (is (str/includes? (frame ui) "(requires)"))
        (press! ui KeyType/Tab)
        (press! ui KeyType/ArrowDown)
        (is (= :requires (:category @state)))
        (is (str/includes? (frame ui) "[shop.orders :as orders]"))
        (press! ui \e)
        (is (str/includes? (frame ui) "Requires are read-only."))
        (press! ui KeyType/ArrowUp)
        (is (str/includes? (frame ui) "(deftest total-test")))))))

(defn- fresh-frame [{:keys [^TerminalScreen screen state last-frame] :as ui}]
  ;; Background REPL updates do not drive the terminal loop; render the
  ;; current state the way the next key press would before reading the frame.
  (#'tui/render-if-changed! screen @state last-frame)
  (frame ui))

(deftest comment-blocks-show-and-evaluate-from-the-repl-panel
  (with-open [running (server/start-server :bind "127.0.0.1" :port 0)]
    (fixture/with-example
     (fn [root]
       (fixture/write!
        (.resolve root "src/shop/orders.clj")
        (str (slurp (.toFile (.resolve root "src/shop/orders.clj")))
             "\r\n(comment\r\n  (println \"block output\")\r\n  (total [{:amount 2}]))\r\n"))
       (fixture/write! (.resolve root ".nrepl-port") (str (:port running)))
       (with-terminal root
         (fn [{:keys [state] :as ui}]
           (try
             (total! ui)
             (session/edit! state fixture/total-draft)
             ;; F6 on a definition still evaluates the visible draft.
           (press! ui \p)
           (press! ui KeyType/Enter)
           (fixture/await-state! state #(= :connected (get-in % [:repl :phase])))
           (doseq [key "(do (clojure.core/create-ns 'shop.orders) (binding [*ns* (the-ns 'shop.orders)] (clojure.core/refer-clojure)) (clojure.core/intern 'shop.orders 'total (fn [lines] (reduce + 0 (map :amount lines)))))"]
             (press! ui key))
           (press! ui KeyType/F5)
           (fixture/await-state! state #(and (nil? (get-in % [:repl :active]))
                                             (str/includes? (or (get-in % [:repl :status]) "") "finished")))
             (press! ui KeyType/F6)
             (fixture/await-state! state #(and (nil? (get-in % [:repl :active]))
                                               (str/includes? (or (get-in % [:repl :status]) "") "finished")))
             (is (str/includes? (frame ui) "#'shop.orders/total"))
             ;; Returning to the source keeps the draft.
             (press! ui KeyType/Escape)
             (is (= fixture/total-draft (:draft @state)))
             (is (:dirty? @state))
             (press! ui \d)
             (is (false? (:dirty? @state)))
             ;; Navigate to the (comment) category and select the appended
             ;; block by its body; the example ships its own block first.
             (navigate! ui KeyType/Tab #(= 1 (:focus @state)))
             (navigate! ui KeyType/ArrowUp #(= :comment (:category @state)))
             (navigate! ui KeyType/Tab #(= 2 (:focus @state)))
             (navigate! ui KeyType/ArrowDown
                        #(and (true? (get-in @state [:definition :block?]))
                              (str/includes? (str (get-in @state [:definition :source])) "block output")))
             (is (true? (get-in @state [:definition :block?])))
             (is (str/includes? (frame ui) "(comment"))
             ;; F6 in the panel evaluates the whole block.
             (press! ui \p)
             (press! ui KeyType/F6)
             (fixture/await-state! state #(and (nil? (get-in % [:repl :active]))
                                               (str/includes? (or (get-in % [:repl :status]) "") "finished")))
             (is (str/includes? (fresh-frame ui) "out: block output"))
             ;; The block sees the redefinition the draft evaluation made above.
             (is (str/includes? (fresh-frame ui) "=> 12"))
             ;; Returning to the source keeps the block selection.
             (press! ui KeyType/Escape)
             (is (true? (get-in @state [:definition :block?])))
             (is (str/includes? (frame ui) "(comment"))
             (finally (repl/disconnect! state)))))))))

(defn -main [& _]
  (let [r (test/run-tests 'csb.tui-smoke)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
