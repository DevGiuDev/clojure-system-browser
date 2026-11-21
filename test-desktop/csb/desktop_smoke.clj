(ns csb.desktop-smoke
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is testing]]
            [csb.desktop :as desktop]
            [csb.session :as session]
            [csb.source :as source]
            [csb.ui-fixture :as fixture])
  (:import [java.nio.file Files]
           [javafx.application Platform]
           [javafx.event Event]
           [javafx.scene.control Button Label ListView TextArea TextField]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.paint Color]
           [javafx.scene.text Text]
           [javafx.stage Stage WindowEvent]
           [org.fxmisc.richtext CodeArea]))

(defn- await! [pending]
  (let [value (deref pending 15000 ::timeout)]
    (when (= ::timeout value) (throw (ex-info "Timed out waiting for Desktop" {})))
    value))

(defn- fx! [f]
  (let [done (promise)]
    (Platform/runLater (bound-fn []
                         (try (deliver done [:ok (f)])
                              (catch Throwable e (deliver done [:error e])))))
    (let [[status value] (await! done)]
      (if (= :error status) (throw value) value))))

(defn- idle! [state]
  (let [done (promise) key (Object.) check #(when-not (:busy? %) (deliver done true))]
    (add-watch state key (fn [_ _ _ s] (check s)))
    (try (check @state) (await! done) (finally (remove-watch state key)))))

(defn- sync! [{:keys [state renderer errors]}]
  (idle! state)
  (let [component (await! (renderer @state))]
    (when-let [e (first @errors)] (throw e))
    (fx/instance component)))

(defn- with-window
  ([f] (with-window {:example-extension? true} f))
  ([options f]
  (let [state (session/create)
        {:keys [actions stop!] :as controller} (#'desktop/controller state options)
        errors (atom [])
        renderer (fx/create-renderer :middleware (fx/wrap-map-desc #(#'desktop/view % actions))
                                     :error-handler #(swap! errors conj %))
        ui (assoc controller :state state :renderer renderer :errors errors)]
    (try
      (await! (fx/mount-renderer state renderer))
      (f ui)
      (finally
        (stop!)
        (await! (fx/unmount-renderer state renderer)))))))

(defn- node [^Stage stage selector] (.lookup (.getScene stage) selector))
(defn- source [ui] (let [stage (sync! ui)] (fx! #(.getText ^CodeArea (node stage "#definition-source")))))
(defn- status [ui] (let [stage (sync! ui)] (fx! #(.getText ^Label (node stage "#status")))))

(defn- fire! [ui title]
  (let [stage (sync! ui)]
    (fx! #(let [button (some (fn [^Button b] (when (= title (.getText b)) b))
                             (.lookupAll (.getRoot (.getScene stage)) ".button"))]
            (when-not button (throw (ex-info "Missing button" {:title title})))
            (.fire ^Button button)))
    (sync! ui)))

(defn- edit! [ui text]
  (let [stage (sync! ui)]
    (fx! #(.replaceText ^CodeArea (node stage "#definition-source") ^String text)))
  (sync! ui))

(defn- select! [ui selector text]
  (let [stage (sync! ui)]
    (fx! #(let [^ListView list-view (node stage selector)
                index (first (keep-indexed (fn [i item] (when (str/starts-with? item text) i))
                                           (.getItems list-view)))]
            (when-not index (throw (ex-info "Missing list item" {:selector selector :text text})))
            (.select (.getSelectionModel list-view) (int index)))))
  (sync! ui))

(defn- open! [{:keys [state submit!] :as ui} root]
  (submit! #(session/open! state root {:example-extension? true}))
  (sync! ui))

(defn- total! [ui]
  (select! ui "#namespaces" "shop.orders")
  (select! ui "#categories" "functions")
  (select! ui "#definitions" "total ·"))

(deftest repl-panel-preserves-the-source-draft-and-validates-the-port
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (open! ui root)
         (total! ui)
         (edit! ui fixture/total-draft)
         (fire! ui "REPL")
         (let [stage (sync! ui)]
           (fx! (fn []
                  (.setText ^TextField (node stage "#repl-port") "65536")
                  (.setText ^TextArea (node stage "#repl-input") "(+ 1\n 2)"))))
         (fire! ui "Connect")
         (let [stage (sync! ui)]
           (is (str/includes? (fx! #(.getText ^Label (node stage "#repl-status"))) "port")))
         (fire! ui "REPL")
         (is (= fixture/total-draft (source ui)))
         (is (:dirty? @state))
         (fire! ui "REPL")
         (let [stage (sync! ui)]
           (is (= "(+ 1\n 2)" (fx! #(.getText ^TextArea (node stage "#repl-input")))))))))))

(deftest repl-port-default-follows-the-open-project
  (fixture/with-example
   (fn [root]
     (let [port-file (.resolve root ".nrepl-port")
           other (.resolve root "other-project")]
       (fixture/write! port-file " 41234\r\n")
       (fixture/write! (.resolve other "deps.edn") "{:paths [\"src\"]}")
       (fixture/write! (.resolve other ".nrepl-port") "42345\n")
       (with-window
         (fn [ui]
           (let [port (fn []
                        (let [stage (sync! ui)]
                          (fx! #(.getText ^TextField (node stage "#repl-port")))))]
             (open! ui root)
             (fire! ui "REPL")
             (is (= "41234" (port)))
             (fire! ui "REPL")
             (fixture/write! port-file "41235\n")
             (fire! ui "REPL")
             (is (= "41235" (port)))
             (let [stage (sync! ui)]
               (fx! #(.setText ^TextField (node stage "#repl-port") "45678")))
             (fire! ui "REPL")
             (fire! ui "REPL")
             (is (= "45678" (port)))
             (with-redefs-fn {#'desktop/choose-project (constantly (str other))}
               #(fire! ui "Open project…"))
             (fire! ui "REPL")
             (is (= "42345" (port))))))))))

(deftest initial-window-and-example-navigation
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [ui]
         (let [stage (sync! ui)]
           (is (fx! #(.isShowing ^Stage stage)))
           (is (= "" (source ui)))
           (is (fx! #(.isDisabled ^Button
                                  (some (fn [^Button b] (when (= "Save" (.getText b)) b))
                                        (.lookupAll (.getRoot (.getScene ^Stage stage)) ".button"))))))
         (open! ui root)
         (total! ui)
         (is (= 'total (get-in @(:state ui) [:definition :name])))
         (is (str/starts-with? (source ui) "(defn total"))
         (is (not (str/includes? (source ui) "(ns shop.orders)")))
         (select! ui "#namespaces" "shop.orders-test")
         (is (= 'shop.orders-test (:namespace @(:state ui))))
         (total! ui)
         (let [stage (sync! ui)]
           (fx! #(.setText ^TextField (node stage "#definition-filter") "ToTaL"))
           (sync! ui)
           (is (= 1 (fx! #(count (.getItems ^ListView (node stage "#definitions"))))))))))))

(deftest save-through-controls-preserves-surrounding-bytes
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [ui]
         (open! ui root)
         (total! ui)
         (let [file (.resolve root "src/shop/orders.clj")
               original (Files/readString file)
               before (get-in @(:state ui) [:definition :source])
               replacement fixture/total-draft]
           (edit! ui replacement)
           (is (:dirty? @(:state ui)))
           (let [stage (sync! ui)]
             (is (fx! #(every? (fn [selector] (.isDisabled ^ListView (node stage selector)))
                               ["#namespaces" "#categories" "#definitions"]))))
           (fire! ui "Save")
           (is (fixture/bytes= (str/replace original before replacement) file))
           (is (= "Saved" (status ui)))
           (is (false? (:dirty? @(:state ui))))
           (let [stage (sync! ui)]
             (is (fx! #(not (.isDisabled ^ListView (node stage "#definitions")))))))
         (fire! ui "Reload")
         (total! ui)
         (is (= fixture/total-draft (source ui)))
         (fire! ui "Example: hello")
         (is (str/includes? (status ui) "Hello"))
         (fire! ui "Project summary")
         (is (str/includes? (status ui) ":world :source")))))))

(deftest failed-saves-and-navigation-retain-the-visible-draft
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (open! ui root)
         (total! ui)
         (let [file (.resolve root "src/shop/orders.clj") original (Files/readString file)]
           (testing "invalid syntax is rejected by the Save button"
             (edit! ui "(defn total [")
             (fire! ui "Save")
             (is (str/includes? (status ui) "parse:"))
             (is (= "(defn total [" (source ui)))
             (is (:dirty? @state))
             (is (fixture/bytes= original file)))
           (testing "reload and close leave the dirty editor open"
             (with-redefs-fn {#'desktop/choose-project
                             (fn [] (throw (AssertionError. "Dirty drafts must block the project chooser")))}
               #(fire! ui "Open project…"))
             (is (str/includes? (status ui) "before opening a project"))
             (fire! ui "Reload")
             (is (str/includes? (status ui) "Unsaved changes"))
             (let [stage (sync! ui)]
               (fx! #(Event/fireEvent stage (WindowEvent. stage WindowEvent/WINDOW_CLOSE_REQUEST)))
               (is (fx! #(.isShowing ^Stage stage))))
             (is (= "(defn total [" (source ui)))
             (is (str/includes? (status ui) "before closing")))
           (testing "a disk change causes a visible conflict without overwriting either text"
             (edit! ui fixture/total-draft)
             (let [external (str original "\r\n;; external edit\r\n")]
               (fixture/write! file external)
               (fire! ui "Save")
               (is (str/includes? (status ui) "conflict:"))
               (is (= fixture/total-draft (source ui)))
               (is (:dirty? @state))
               (is (fixture/bytes= external file))
               (fire! ui "Discard")
               (fire! ui "Reload")
               (total! ui)
               (is (false? (:dirty? @state)))
               (is (fixture/bytes= external file))))))))))

(deftest shortcut-save-and-io-errors
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (open! ui root)
         (total! ui)
         (edit! ui fixture/total-draft)
         (let [stage (sync! ui)
               mac? (str/includes? (System/getProperty "os.name") "Mac")]
           (fx! #(Event/fireEvent (.getScene ^Stage stage)
                                 (KeyEvent. KeyEvent/KEY_PRESSED "" "s" KeyCode/S
                                            false (not mac?) false mac?))))
         (sync! ui)
         (is (= "Saved" (status ui)))
         (is (false? (:dirty? @state)))
         (edit! ui "(defn total [lines] :retained)")
         (let [file (.resolve root "src/shop/orders.clj") original (Files/readString file)]
           (with-redefs-fn {#'source/replace-atomically!
                           (fn [& _] (throw (java.io.IOException. "simulated disk write failure")))}
             #(fire! ui "Save"))
           (is (fixture/bytes= original file)))
         (is (str/includes? (status ui) "io:"))
         (is (= "(defn total [lines] :retained)" (source ui)))
         (is (:dirty? @state)))))))

(deftest open-project-button-discovers-clojuredart-and-clears-the-previous-filter
  (fixture/with-example
   (fn [root]
     (let [other (.resolve root "other-project")
           dart-source "(ns ^{:no-dynamic true} demo.dart)\n(defn widget [] (#/(identity :ok)))"]
       (fixture/write! (.resolve other "deps.edn") "{:paths [\"code\"]}")
       (fixture/write! (.resolve other "code/demo.cljd") dart-source)
       (with-window
         {:roots ["src"]}
         (fn [{:keys [state] :as ui}]
           (open! ui root)
           (let [stage (sync! ui)]
             (fx! #(.setText ^TextField (node stage "#definition-filter") "total")))
           ;; Replace only the native chooser, then fire the production Open button.
           (with-redefs-fn {#'desktop/choose-project (constantly (str other))}
             #(fire! ui "Open project…"))
           (is (= 'demo.dart (:namespace @state)))
           (is (= "" (:filter @state)))
           (is (= "(defn widget [] (#/(identity :ok)))" (source ui)))
           (is (str/includes? (status ui) "1 namespaces"))
           (let [stage (sync! ui)]
             (is (fx! #(not (.isEditable ^CodeArea (node stage "#definition-source")))))
             (is (= #{"syntax-special"}
                    (fx! #(set (.getStyleOfChar ^CodeArea (node stage "#definition-source") (int 1)))))))
           (is (false? (get-in @state [:definition :writable?])))
           (fire! ui "Save")
           (is (fixture/bytes= dart-source (.resolve other "code/demo.cljd")))
           (with-redefs-fn {#'desktop/choose-project (constantly nil)}
             #(fire! ui "Open project…"))
           (is (= 'demo.dart (:namespace @state)))))))))

(deftest syntax-colours-follow-edits-without-changing-selection-or-undo
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (open! ui root)
         (total! ui)
         (let [stage (sync! ui)
               ^CodeArea area (node stage "#definition-source")
               original (source ui)
               draft "(defn total [lines]\n  ;; café 😀\n  {:amount 42 :label \"hello\" :ok true})"
               style-at (fn [text]
                          (fx! #(set (.getStyleOfChar area (int (str/index-of (.getText area) text))))))]
           (is (false? (:dirty? @state)))
           (is (= #{"syntax-special"} (style-at "defn")))
           ;; A status-only render on a CRLF source must preserve caret/selection.
           (fx! #(.selectRange area (int 2) (int 6)))
           (swap! state assoc :status "Selection retained")
           (sync! ui)
           (is (= [2 6] (fx! #(vector (.getAnchor area) (.getCaretPosition area)))))
           (edit! ui draft)
           (is (= draft (:draft @state)))
           (is (= #{"syntax-comment"} (style-at ";;")))
           (is (= #{"syntax-keyword"} (style-at ":amount")))
           (is (= #{"syntax-number"} (style-at "42")))
           (is (= #{"syntax-string"} (style-at "hello")))
           (is (= #{"syntax-literal"} (style-at "true")))
           (is (= (Color/web "#0550ae")
                  (fx! #(do (.applyCss area)
                            (.layout area)
                            (.getFill ^Text (.lookup area ".syntax-keyword"))))))
           (fx! #(.undo area))
           (sync! ui)
           (is (= original (source ui)))
           (is (false? (:dirty? @state)))
           (is (= (get-in @state [:definition :source]) (:draft @state)))
           (fx! #(.redo area))
           (sync! ui)
           (is (= draft (source ui)))
           (edit! ui "(defn total [] \"unfinished")
           (is (= #{"syntax-string"} (style-at "unfinished")))
           (is (:dirty? @state))
           (fire! ui "Discard")
           (is (= original (source ui)))
           (is (false? (:dirty? @state)))
           (select! ui "#namespaces" "shop.orders-test")
           (is (fx! #(not (.isUndoAvailable area))))))))))

(deftest source-order-panel-shows-real-file-order-and-navigates
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (open! ui root)
         (total! ui)
         (fire! ui "Source order")
         (is (true? (:source-order? @state)))
         (let [stage (sync! ui)
               ^ListView list-view (node stage "#source-order")
               items (fx! #(vec (.getItems list-view)))
               idx (fn [text]
                     (first (keep-indexed (fn [i item] (when (str/includes? ^String item text) i)) items)))]
           (is (fx! #(.isVisible list-view)))
           (is (some #(str/starts-with? ^String % "0 · ns shop.orders") items)
               "The ns form is part of the order view")
           ;; Real file order: the current definition is marked and total
           ;; (ordinal 1) precedes discount (ordinal 2), unlike the alphabetical list.
           (is (str/starts-with? ^String (nth items (idx "defn total")) "▶ 1 · defn total"))
           (is (str/starts-with? ^String (nth items (idx "defn- discount")) "2 · defn- discount"))
           (is (< (idx "defn total") (idx "defn- discount")) "File order, not alphabetical order"))
         ;; Selecting a definition in the view navigates and moves the marker.
         (select! ui "#source-order" "2 · defn- discount")
         (is (= 'discount (get-in @state [:definition :name])))
         (is (true? (:source-order? @state)))
         (let [stage (sync! ui)
               items (fx! #(vec (.getItems ^ListView (node stage "#source-order"))))]
           (is (some #(str/starts-with? ^String % "▶ 2 · defn- discount") items)))
         ;; Esc returns to the editor with the selected definition.
         (let [stage (sync! ui)]
           (fx! #(Event/fireEvent (.getScene ^Stage stage)
                                  (KeyEvent. KeyEvent/KEY_PRESSED "" "" KeyCode/ESCAPE
                                             false false false false))))
         (sync! ui)
         (is (false? (:source-order? @state)))
         (is (str/starts-with? (source ui) "(defn- discount"))
         ;; A pending draft keeps the view available but its list disabled.
         (edit! ui "(defn total [")
         (fire! ui "Source order")
         (let [stage (sync! ui)]
           (is (fx! #(.isDisabled ^ListView (node stage "#source-order")))))
         (is (= "(defn total [" (:draft @state)))
         (is (:dirty? @state)))))))

(deftest category-facets-show-counts-and-all-restores-everything
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (open! ui root)
         ;; No category is pre-selected and every definition is listed.
         (is (nil? (:category @state)))
         (let [stage (sync! ui)]
           (is (= 3 (fx! #(count (.getItems ^ListView (node stage "#definitions")))))))
         (let [stage (sync! ui)
               cats (fx! #(vec (.getItems ^ListView (node stage "#categories"))))]
           (is (some #(str/starts-with? ^String % "functions · 2") cats))
           (is (some #(str/starts-with? ^String % "private-functions · 1") cats))
           (is (some #(= "(all)" ^String %) cats))
           (is (fx! #(some (fn [^Label l]
                             (when (str/includes? (str (.getText l)) "3 defs · 1 file") l))
                           (.lookupAll (.getRoot (.getScene ^Stage stage)) ".label")))
               "The categories panel title carries the namespace summary"))
         ;; Selecting a facet narrows; the sentinel restores everything.
         (select! ui "#categories" "functions · 2")
         (is (= :functions (:category @state)))
         (let [stage (sync! ui)]
           (is (= 2 (fx! #(count (.getItems ^ListView (node stage "#definitions")))))))
         (select! ui "#categories" "(all)")
         (is (nil? (:category @state)))
         (let [stage (sync! ui)]
           (is (= 3 (fx! #(count (.getItems ^ListView (node stage "#definitions"))))))))))))

(defn -main [& _]
  (try
    (let [r (test/run-tests 'csb.desktop-smoke)]
      (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))
    (finally (Platform/exit) (shutdown-agents))))
