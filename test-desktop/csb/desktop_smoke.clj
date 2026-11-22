(ns csb.desktop-smoke
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is testing]]
            [csb.desktop :as desktop]
            [csb.session :as session]
            [csb.source :as source]
            [csb.ui-fixture :as fixture]
            [nrepl.server :as server])
  (:import [java.nio.file Files]
           [javafx.application Platform]
           [javafx.event Event]
           [javafx.scene.control Button CheckBox ComboBox ContextMenu Label ListCell ListView MenuItem TextArea TextField TreeItem TreeView]
           [javafx.scene.layout Region]
           [javafx.scene.input ContextMenuEvent KeyCode KeyEvent]
           [javafx.scene.paint Color]
           [javafx.scene.shape SVGPath]
           [javafx.scene.text Text]
           [javafx.stage Stage Window WindowEvent]
           [org.fxmisc.richtext CodeArea TextExt]))

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
    ;; Barrier: a round trip through the FX thread drains any render that a
    ;; state swap scheduled after the forced one above.
    (fx! #(fx/instance component))))

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
  ;; Retries absorb a stale render occasionally winning the race against the
  ;; forced one after async state changes; each attempt re-syncs first.
  (let [attempt! (fn []
                   (let [stage (sync! ui)]
                     (fx! #(let [^ListView list-view (node stage selector)]
                             (when list-view
                               (let [index (first (keep-indexed
                                                   (fn [i item]
                                                     (when (str/starts-with?
                                                            (if (map? item) (:text item) item) text) i))
                                                   (.getItems list-view)))]
                                 (when index
                                   (.select (.getSelectionModel list-view) (int index))
                                   true)))))))
        deadline (+ (System/nanoTime) (long 5e9))]
    (loop []
      (or (attempt!)
          (if (< (System/nanoTime) deadline)
            (do (Thread/sleep 50) (recur))
            (throw (ex-info "Missing list or item" {:selector selector :text text}))))))
  (sync! ui))

(defn- open! [{:keys [state submit!] :as ui} root]
  (submit! #(session/open! state root {:example-extension? true}))
  (sync! ui))

(defn- total! [ui]
  (select! ui "#namespaces" "shop.orders")
  (select! ui "#categories" "functions")
  (select! ui "#definitions" "total ·"))

(defn- context-action! [ui selector title]
  (let [stage (sync! ui)
        fired? (fx! (fn []
                      (Event/fireEvent (node stage selector)
                                       (ContextMenuEvent. ContextMenuEvent/CONTEXT_MENU_REQUESTED
                                                          10 10 120 120 true nil))
                      (when-let [^ContextMenu menu (some #(when (instance? ContextMenu %) %) (Window/getWindows))]
                        (try
                          (when-let [^MenuItem item (some #(when (= title (.getText ^MenuItem %)) %) (.getItems menu))]
                            (.fire item)
                            true)
                          (finally (.hide menu))))))]
    (sync! ui)
    fired?))

(deftest contextual-symbol-navigation
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (open! ui root)
         (total! ui)
         (when (is (context-action! ui "#definitions" "Find related tests"))
           (select! ui "#symbol-results" "shop.orders-test/total-test")
           (fire! ui "Open definition")
           (is (= 'shop.orders-test (:namespace @state)))
           (is (str/starts-with? (source ui) "(deftest total-test"))
           (let [stage (sync! ui)]
             (fx! #(let [^CodeArea area (node stage "#definition-source")]
                     (.moveTo area (int (+ 2 (str/index-of (.getText area) "orders/total")))))))
           (is (context-action! ui "#definition-source" "Go to definition"))
           (is (= 'total (get-in @state [:definition :name])))
           (is (= 'shop.orders (:namespace @state))))
         (total! ui)
         (edit! ui fixture/total-draft)
         (when (is (true? (context-action! ui "#definition-source" "Find symbol uses")))
           (select! ui "#symbol-results" "shop.orders-test/total-test")
           (fire! ui "Open definition")
           (is (= 'total (get-in @state [:definition :name]))))
         (is (= fixture/total-draft (source ui)))
         (is (:dirty? @state)))))))

(deftest project-java-vm-can-be-saved-and-reopened
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (let [home (System/getProperty "java.home")
               input #(let [stage (sync! ui)]
                        (fx! (fn [] (.setText ^TextField (node stage "#java-home") %))))
               selected-home #(let [stage (sync! ui)]
                                (fx! (fn [] (.getText ^TextField (node stage "#java-home")))))]
           (open! ui root)
           (total! ui)
           (edit! ui fixture/total-draft)
           (fire! ui "Java VMs…")
           (let [stage (sync! ui)
                 selected (fx! #(when-let [^ComboBox box (node stage "#installed-java-vms")]
                                  (when-let [item (some (fn [label] (when (str/ends-with? label home) label))
                                                        (.getItems box))]
                                    (.setValue box item)
                                    item)))]
             (is (some? selected))
             (is (= home (selected-home))))
           (input "missing-jdk")
           (fire! ui "Apply Java VM")
           (let [stage (sync! ui)]
             (is (str/includes? (fx! #(.getText ^Label (node stage "#java-vm-error"))) "absolute")))
           (input home)
           (fire! ui "Apply Java VM")
           (is (= fixture/total-draft (source ui)))
           (session/discard! state)
           (fixture/with-example
            (fn [other-root]
              (open! ui other-root)
              (fire! ui "Java VMs…")
              (is (= "" (selected-home)))))
           (open! ui root)
           (fire! ui "Java VMs…")
           (is (= home (selected-home)))
           (input "")
           (fire! ui "Apply Java VM")
           (is (str/includes? (status ui) "Inherit launcher environment"))))))))

(deftest tests-view-runs-a-suite-and-colours-individual-tests
  (fixture/with-test-project
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (open! ui root)
         (fire! ui "Java VMs…")
         (let [home (or (System/getenv "CSB_TEST_JAVA_HOME") (System/getProperty "java.home"))
               stage (sync! ui)]
           (fx! #(.setText ^TextField (node stage "#java-home") home)))
         (fire! ui "Apply Java VM")
         (fire! ui "Tests")
         (let [led (fn [selector label]
                     (let [stage (sync! ui)]
                       (fx! (fn []
                              (let [root (.getRoot (.getScene stage))]
                                (.applyCss root)
                                (.layout root)
                                (let [^ListView list (node stage selector)]
                                  (some (fn [^ListCell cell]
                                          (when (= label (.getText cell))
                                            (when-let [graphic (.getGraphic cell)]
                                              [(some-> ^SVGPath (.lookup graphic ".icon-stroke") .getStroke)
                                               (.getAccessibleText cell)])))
                                        (.lookupAll list ".list-cell"))))))))]
           (is (= [(Color/web "#51645f") "passing-test · Not run"] (led "#test-definitions" "passing-test")))
           (fire! ui "Run suite")
           (is (= [(Color/web "#754500") "shop.orders-test · Running"] (led "#test-namespaces" "shop.orders-test")))
           (is (= [(Color/web "#754500") "passing-test · Running"] (led "#test-definitions" "passing-test")))
           (fixture/await-state! state #(= :failed (get-in % [:test-runner :last-run :status])))
           (let [home (or (System/getenv "CSB_TEST_JAVA_HOME") (System/getProperty "java.home"))
                 output (get-in @state [:test-runner :last-run :output])]
             (is (some #{(str "Project JAVA_HOME " home)} output))
             (is (some #{(str "Project Java runtime " home)} output)))
           (is (= [(Color/web "#236541") "passing-test · Passed"] (led "#test-definitions" "passing-test")))
           (is (= [(Color/web "#9e2d35") "failing-test · Failed"] (led "#test-definitions" "failing-test")))
           (is (= [(Color/web "#9e2d35") "shop.orders-test · Failed"] (led "#test-namespaces" "shop.orders-test")))
           (let [stage (sync! ui)
                 output (fx! #(.getText ^CodeArea (node stage "#test-details")))]
             (is (str/includes? output "Configured suite command"))
             (is (str/includes? output "Suite stderr"))
             (is (str/includes? output "1 failures, 0 errors"))
             (fx! (fn []
                    (let [root (.getRoot (.getScene stage))
                          log (node stage "#test-details")]
                      (.applyCss root)
                      (.layout root)
                      (is (> (.getHeight ^Region log) (.getHeight ^ListView (node stage "#test-definitions"))))
                      (is (some #(and (instance? Text %)
                                      (str/includes? (.getText ^Text %) "shop.orders-test/failing-test")
                                      (= (Color/web "#9e2d35") (.getFill ^Text %)))
                                (.lookupAll log ".test-log-error .text")))))))
           (select! ui "#test-definitions" "failing-test")
           (fire! ui "Open test source")
           (is (str/includes? (source ui) "deftest failing-test"))
           (let [stage (sync! ui)]
             (fx! (fn []
                    (let [notice ^Label (node stage "#test-failure")
                          editor ^CodeArea (node stage "#definition-source")]
                      (is (some-> notice .getText (str/includes? "Totals should match")))
                      (is (some-> notice .getText (str/includes? "Expected: (= 1 2)")))
                      (is (some #{"test-failure-line"} (.getParagraphStyle (.getParagraph editor 1))))
                      (doto (.getRoot (.getScene stage)) .applyCss .layout)
                      (is (some #(and (instance? TextExt %)
                                      (= (Color/web "#fde9ea") (.getBackgroundColor ^TextExt %)))
                                (.lookupAll editor ".test-failure-line .text")))))))
           (fire! ui "Tests")
           (select! ui "#test-definitions" "passing-test")
           (fire! ui "Run test")
           (fixture/await-state! state #(= :passed (get-in % [:test-runner :last-run :status])))
           (is (= #{['shop.orders-test 'passing-test]}
                  (get-in @state [:test-runner :last-run :targets])))
           (fire! ui "Open test source")
           (is (= 'shop.orders-test (:namespace @state)))
           (is (str/includes? (source ui) "deftest passing-test"))
           (let [stage (sync! ui)]
             (is (nil? (fx! #(node stage "#test-failure")))))
           (is (= [(Color/web "#9e2d35") "shop.orders-test · Failed"] (led "#namespaces" "shop.orders-test")))))))))

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

(deftest runtime-inspector-navigates-vars-and-retains-source-drafts
  (with-open [running (server/start-server :bind "127.0.0.1" :port 0)]
    (fixture/with-example
     (fn [root]
       (with-window
         (fn [{:keys [state] :as ui}]
           (fixture/write! (.resolve root ".nrepl-port") (str (:port running)))
           (open! ui root)
           (total! ui)
           (edit! ui fixture/total-draft)
           (fire! ui "REPL")
           (fire! ui "Connect")
           (fixture/await-state! state #(= :connected (get-in % [:repl :phase])))
           (fire! ui "Inspect runtime")
           (fixture/await-state! state #(get-in % [:repl :inspection :observation]))
           (select! ui "#runtime-entries" "clojure.string")
           (fire! ui "Open")
           (fixture/await-state! state #(get-in % [:repl :inspection :observation]))
           (select! ui "#runtime-entries" "upper-case")
           (fire! ui "Open")
           (fixture/await-state! state #(get-in % [:repl :inspection :observation]))
           (let [stage (sync! ui)]
             (is (str/includes? (fx! #(.getText ^TextArea (node stage "#runtime-metadata")))
                                "clojure.string/upper-case")))
           (fire! ui "Console")
           (is (= fixture/total-draft (source ui)))
           (is (:dirty? @state))))))))

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
           (is (fx! #(let [^Button open (node stage ".button-primary")]
                       (and open (= "Open project…" (.getText open)) (some? (.getGraphic open))))))
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

(deftest namespace-filter-narrows-and-navigates
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (open! ui root)
         (let [stage (sync! ui)]
           (is (fx! #(let [column (.getParent (.getParent ^TextField (node stage "#namespace-filter")))]
                       (some? (.lookup column "#namespaces")))))
           (is (fx! #(let [column (.getParent (.getParent ^TextField (node stage "#definition-filter")))]
                       (some? (.lookup column "#definitions")))))
           (is (= 2 (fx! #(count (.getItems ^ListView (node stage "#namespaces"))))))
           (fx! #(.setText ^TextField (node stage "#namespace-filter") "test"))
           (sync! ui)
           (is (= ["shop.orders-test"]
                  (fx! #(mapv identity (.getItems ^ListView (node stage "#namespaces"))))))
           (select! ui "#namespaces" "shop.orders-test")
           (is (= 'shop.orders-test (:namespace @state)))
           (fx! #(.setText ^TextField (node stage "#namespace-filter") ""))
           (sync! ui)
           (is (= 2 (fx! #(count (.getItems ^ListView (node stage "#namespaces"))))))))))))

(deftest namespaces-switch-between-flat-and-tree-views
  (fixture/with-example
    (fn [root]
      (fixture/write! (.resolve root "src/shop/orders/extra.clj")
                      "(ns shop.orders.extra)\n(defn extra [] :extra)\n")
      (with-window
        (fn [{:keys [state] :as ui}]
          (let [stage (sync! ui)
                toggle! #(do (fx! (fn [] (.fire ^CheckBox (node stage "#namespace-tree-toggle"))))
                             (sync! ui))]
            (is (some? (fx! #(node stage "#namespaces"))))
            (when (is (fx! #(some? (node stage "#namespace-tree-toggle"))))
              (is (false? (fx! #(.isSelected ^CheckBox (node stage "#namespace-tree-toggle")))))
              (toggle!)
              (is (fx! #(let [^TreeView tree (node stage "#namespaces-tree")]
                          (empty? (.getChildren (.getRoot tree))))))
              (toggle!)
              (open! ui root)
              (total! ui)
              (edit! ui fixture/total-draft)
              (toggle!)
              (is (= 'total (get-in @state [:definition :name])))
              (is (= fixture/total-draft (source ui)))
              (is (fx! #(.isDisabled ^TreeView (node stage "#namespaces-tree"))))
              (session/discard! state)
              (sync! ui)
              (fx! #(let [^TreeView tree (node stage "#namespaces-tree")
                          ^TreeItem shop (first (.getChildren (.getRoot tree)))
                          ^TreeItem orders (first (.getChildren shop))
                          ^TreeItem extra (first (.getChildren orders))
                          selection (.getSelectionModel tree)]
                      (is (= orders (.getSelectedItem selection)))
                      (.select selection shop)
                      (is (= 'shop.orders (:namespace @state)))
                      (.setExpanded orders false)
                      (is (= -1 (.getRow tree extra)))
                      (.setExpanded orders true)
                      (.select selection extra)))
              (is (= 'shop.orders.extra (:namespace @state)))
              (is (str/includes? (source ui) "(defn extra"))
              (fx! #(.setText ^TextField (node stage "#namespace-filter") "ORDERS-TEST"))
              (sync! ui)
              (fx! #(let [^TreeView tree (node stage "#namespaces-tree")
                          ^TreeItem shop (first (.getChildren (.getRoot tree)))
                          selection (.getSelectionModel tree)]
                      (is (= 1 (count (.getChildren shop))))
                      (is (nil? (.getSelectedItem selection)))
                      (.select selection ^TreeItem (first (.getChildren shop)))))
              (is (= 'shop.orders-test (:namespace @state)))
              (toggle!)
              (is (= ["shop.orders-test"]
                     (fx! #(vec (.getItems ^ListView (node stage "#namespaces"))))))
              (is (= "shop.orders-test"
                     (fx! #(.getSelectedItem (.getSelectionModel ^ListView (node stage "#namespaces")))))))))))))

(defn- press-shortcut! [ui code character]
  (let [stage (sync! ui)
        mac? (str/includes? (System/getProperty "os.name") "Mac")]
    (fx! #(Event/fireEvent (.getScene ^Stage stage)
                           (KeyEvent. KeyEvent/KEY_PRESSED "" character code
                                      false (not mac?) false mac?)))
    (sync! ui)))

(defn- press-key! [ui selector code]
  (let [stage (sync! ui)]
    (fx! #(Event/fireEvent (node stage selector)
                           (KeyEvent. KeyEvent/KEY_PRESSED "" "" code false false false false)))
    (sync! ui)))

(defn- type-search! [ui text]
  (let [stage (sync! ui)]
    (fx! #(.setText ^TextField (node stage "#search-query") text)))
  (sync! ui))

(deftest uses-follow-the-visible-definition
  (fixture/with-example
    (fn [root]
      (with-window
        (fn [{:keys [state] :as ui}]
            ;; Reads that expect content retry briefly: the panel derives from
            ;; render-time state and the forced render can lag one frame
            ;; behind the selection under suite load.
            (let [items (fn []
                          (let [stage (sync! ui)]
                            (fx! #(when-let [^ListView results (node stage "#definition-usage-results")]
                                    (vec (.getItems results))))))
                  items-with-retry! (fn []
                                      (let [deadline (+ (System/nanoTime) (long 2e9))]
                                        (loop [v (items)]
                                          (if (or (and (vector? v) (seq v)) (>= (System/nanoTime) deadline))
                                            v
                                            (do (Thread/sleep 50) (recur (items)))))))]
              (is (nil? (items)))
              (open! ui root)
              (is (= ["shop.orders/receipt"] (mapv :text (items-with-retry!))))
              (total! ui)
              (when (is (= ["shop.orders/receipt" "shop.orders-test/total-test"] (mapv :text (items-with-retry!))))
              (is (= ["src/shop/orders.clj:16" "test/shop/orders_test.clj:6"] (mapv :detail (items-with-retry!))))
              (select! ui "#definition-usage-results" "shop.orders-test/total-test")
              (edit! ui fixture/total-draft)
              (press-key! ui "#definition-usage-results" KeyCode/ENTER)
              (is (= 'total (get-in @state [:definition :name])))
              (is (= fixture/total-draft (source ui)))
              (fire! ui "Discard")
              (press-key! ui "#definition-usage-results" KeyCode/ENTER)
              (is (= 'total-test (get-in @state [:definition :name])))
              (is (nil? (items)))

              (press-shortcut! ui KeyCode/F "f")
              (type-search! ui "shop.orders/total")
              (press-key! ui "#search-query" KeyCode/ENTER)
              (is (= ["shop.orders/receipt" "shop.orders-test/total-test"] (mapv :text (items-with-retry!))))
              (is (context-action! ui "#definitions" "Find related tests"))
              (is (nil? (items)))
              (press-key! ui "#symbol-results" KeyCode/ESCAPE)
              (is (= 2 (count (items-with-retry!))))

              (select! ui "#definitions" "receipt ·")
              (let [stage (sync! ui)]
                (is (= "No uses found in the indexed project."
                       (fx! #(.getText ^Label (node stage "#definition-uses-status"))))))
              (select! ui "#definitions" "discount ·")
              (select! ui "#definitions" "total ·")
              (is (= 2 (count (items-with-retry!))))
              (fixture/with-example
                (fn [other]
                  (fixture/write! (.resolve other "src/shop/orders.clj") "(ns shop.orders)\n(defn total [] 1)")
                  (fixture/write! (.resolve other "test/shop/orders_test.clj") "(ns shop.orders-test)")
                  (open! ui other)
                  (is (nil? (items)))
                  (let [stage (sync! ui)]
                    (is (= "No uses found in the indexed project."
                           (fx! #(.getText ^Label (node stage "#definition-uses-status")))))))))))))))

(deftest shortcut-search-opens-categorized-results-and-navigates
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [{:keys [state] :as ui}]
         (open! ui root)
         (total! ui)
         (press-shortcut! ui KeyCode/F "f")
         (let [stage (sync! ui)]
           (is (fx! #(some? (node stage "#search-query"))))
           (is (fx! #(.isFocused ^TextField (node stage "#search-query")))))
         (testing "results group namespaces first and definitions by category"
           (type-search! ui "orders")
           (let [stage (sync! ui)
                 texts (fx! #(mapv :text (.getItems ^ListView (node stage "#search-results"))))]
             (is (= "Namespaces" (first texts)))
             (is (= "shop.orders" (second texts)))
             (is (some #(str/starts-with? % "shop.orders/total") texts)))
           (testing "Enter opens the selected namespace row and closes the overlay"
             (select! ui "#search-results" "shop.orders-test")
             (press-key! ui "#search-query" KeyCode/ENTER)
             (is (= 'shop.orders-test (:namespace @state)))
             (is (nil? (:search @state)))))
         (testing "Esc closes the overlay without navigating"
           (press-shortcut! ui KeyCode/F "f")
           (is (some? (:search @state)))
           (press-key! ui "#search-query" KeyCode/ESCAPE)
           (is (nil? (:search @state))))
         (testing "Enter with a dirty draft warns and keeps the search open"
           (total! ui)
           (edit! ui "(defn total [lines] :retained)")
           (press-shortcut! ui KeyCode/F "f")
           (type-search! ui "orders")
           (sync! ui)
           (select! ui "#search-results" "shop.orders/total")
           (press-key! ui "#search-query" KeyCode/ENTER)
           (is (str/includes? (status ui) "Unsaved changes"))
           (is (some? (:search @state)))
           (session/discard! state)
           (press-key! ui "#search-query" KeyCode/ENTER)
           (is (= 'total (get-in @state [:definition :name])))
           (is (nil? (:search @state)))))))))

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
             (is (fx! #(let [^Button save (node stage ".button-primary")]
                         (and save (= "Save" (.getText save)) (not (.isDisabled save))))))
             (is (fx! #(every? (fn [selector] (.isDisabled ^ListView (node stage selector)))
                               ["#namespaces" "#categories" "#definitions"]))))
           (fire! ui "Save")
           (is (fixture/bytes= (str/replace original before replacement) file))
           (is (= "Saved" (status ui)))
           (is (false? (:dirty? @(:state ui))))
           (let [stage (sync! ui)]
             (is (fx! #(some? (node stage "#status.tone-success"))))
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
             (let [stage (sync! ui)]
               (is (fx! #(and (node stage "#status.tone-error")
                              (node stage "#draft-status.tone-warning")))))
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
               (let [stage (sync! ui)]
                 (is (fx! #(and (node stage "#status.tone-warning")
                                (node stage "#draft-status.tone-warning")))))
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
             (fx! #(.setText ^TextField (node stage "#definition-filter") "total"))
             (fx! #(.setText ^TextField (node stage "#namespace-filter") "shop")))
           ;; Replace only the native chooser, then fire the production Open button.
           (with-redefs-fn {#'desktop/choose-project (constantly (str other))}
             #(fire! ui "Open project…"))
           (is (= 'demo.dart (:namespace @state)))
           (is (= "" (:filter @state)))
           (is (= "" (:namespace-filter @state)))
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
           (is (= (Color/web "#244f9e")
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

(deftest requires-selector-shows-namespace-declarations
  (fixture/with-example
   (fn [root]
     (with-window
       (fn [ui]
         (open! ui root)
         (select! ui "#namespaces" "shop.orders-test")
         (let [stage (sync! ui)
               categories (fx! #(vec (.getItems ^ListView (node stage "#categories"))))]
           (is (some #{"(requires)"} categories))
           (select! ui "#categories" "(requires)")
           (is (str/includes? (source ui) "[shop.orders :as orders]"))
           (is (false? (fx! #(.isEditable ^CodeArea (node stage "#definition-source")))))
           (select! ui "#categories" "(all)")
           (is (str/includes? (source ui) "(deftest total-test"))))))))

(defn- menu-disabled? [ui selector title]
  (let [stage (sync! ui)]
    (fx! (fn []
           (Event/fireEvent (node stage selector)
                            (ContextMenuEvent. ContextMenuEvent/CONTEXT_MENU_REQUESTED
                                               10 10 120 120 true nil))
           (when-let [^ContextMenu menu (some #(when (instance? ContextMenu %) %) (Window/getWindows))]
             (let [item (some #(when (= title (.getText ^MenuItem %)) %) (.getItems menu))]
               (.hide menu)
               (boolean (and item (.isDisable item)))))))))

(defn- repl-output [ui]
  (let [stage (sync! ui)]
    (fx! #(.getText ^TextArea (node stage "#repl-output")))))

(deftest comment-blocks-and-eval-actions-reach-the-repl-console
  (with-open [running (server/start-server :bind "127.0.0.1" :port 0)]
    (fixture/with-example
     (fn [root]
       (with-window
        (fn [{:keys [state] :as ui}]
          (fixture/write!
           (.resolve root "src/shop/orders.clj")
           (str (slurp (.toFile (.resolve root "src/shop/orders.clj")))
                "\r\n(comment\r\n  (println \"block output\")\r\n  (total [{:amount 2}]))\r\n"))
          (let [original (slurp (.toFile (.resolve root "src/shop/orders.clj")))]
            (fixture/write! (.resolve root ".nrepl-port") (str (:port running)))
            (open! ui root)
            ;; The (comment) facet lists the blocks and the viewer shows the
            ;; whole block read-only, even with the REPL disconnected. The
            ;; example now ships its own block, so the test finds its own by
            ;; its body.
            (select! ui "#categories" "(comment)")
            (is (pos? (count (session/definitions @state))))
            (is (true? (get-in @state [:definition :block?])))
            (is (str/includes? (source ui) "(comment"))
            (let [stage (sync! ui)]
              (is (false? (fx! #(.isEditable ^CodeArea (node stage "#definition-source"))))))
            ;; Load the namespace in the runtime, then evaluate the block:
            ;; the console shows the execution, its output and its value.
            (fire! ui "REPL")
            (fire! ui "Connect")
            (fixture/await-state! state #(= :connected (get-in % [:repl :phase])))
            (let [stage (sync! ui)]
              (fx! #(.setText ^TextArea (node stage "#repl-input")
                              "(do (clojure.core/create-ns 'shop.orders) (binding [*ns* (the-ns 'shop.orders)] (clojure.core/refer-clojure)) (clojure.core/intern 'shop.orders 'total (fn [lines] (reduce + 0 (map :amount lines)))))")))
            (fire! ui "Evaluate")
            (fixture/await-state! state #(and (nil? (get-in % [:repl :active]))
                                              (str/includes? (or (get-in % [:repl :status]) "") "finished")))
            (let [block (some #(when (some (fn [f] (str/includes? (str (:source f)) "block output"))
                                           (:body-forms %)) %)
                              (session/definitions @state))]
              (is (some? block))
              (session/select-definition! state (:id block)))
            (fire! ui "Evaluate block")
            (fixture/await-state! state #(and (nil? (get-in % [:repl :active]))
                                              (str/includes? (or (get-in % [:repl :status]) "") "finished")))
            (is (str/includes? (repl-output ui) "out: block output"))
            (is (str/includes? (repl-output ui) "=> 2"))
            ;; Evaluating leaves the file untouched.
            (is (fixture/bytes= original (.resolve root "src/shop/orders.clj")))
            ;; Eval in REPL on the drafted row evaluates the visible draft
            ;; and keeps the draft intact.
            (select! ui "#categories" "functions")
            (select! ui "#definitions" "total ·")
            (edit! ui fixture/total-draft)
            (is (context-action! ui "#definitions" "Eval in REPL"))
            (fixture/await-state! state #(str/includes? (or (get-in % [:repl :status]) "") "finished"))
            (is (str/includes? (repl-output ui) "#'shop.orders/total"))
            (is (= fixture/total-draft (source ui)))
            (is (:dirty? @state))
            (fire! ui "Discard")
            ;; A selection in the code panel is sent exactly as selected.
            (select! ui "#definitions" "total ·")
            (let [stage (sync! ui)]
              (fx! (fn []
                     (let [^CodeArea area (node stage "#definition-source")
                           selection "\"Calculate the total of a collection of order lines.\""
                           start (str/index-of (.getText area) selection)]
                       (.selectRange area (int start) (int (+ start (count selection))))))))
            (is (context-action! ui "#definition-source" "Eval in REPL"))
            (fixture/await-state! state #(str/includes? (or (get-in % [:repl :status]) "") "finished"))
            (is (str/includes? (repl-output ui) "=> \"Calculate the total"))
            ;; A blank selection disables the editor action.
            (let [stage (sync! ui)]
              (fx! #(.moveTo ^CodeArea (node stage "#definition-source") (int 0))))
            (is (menu-disabled? ui "#definition-source" "Eval in REPL")))))))))

(deftest eval-actions-stay-inactive-in-the-initial-view
  (with-window
   (fn [ui]
     ;; No editor menu and no Definitions menu without a definition.
     (is (nil? (context-action! ui "#definition-source" "Eval in REPL")))
     (is (nil? (context-action! ui "#definitions" "Eval in REPL")))
     ;; The Evaluate definition button exists but stays disabled.
     (fire! ui "REPL")
     (let [stage (sync! ui)]
       (is (fx! #(let [^Button button (some (fn [^Button x] (when (= "Evaluate definition" (.getText x)) x))
                                            (.lookupAll (.getRoot (.getScene stage)) ".button"))]
                   (and button (.isDisable button)))))))))

(deftest settings-gear-saves-project-and-global-layers-and-reopens
  (fixture/with-example
   (fn [^java.nio.file.Path root]
     (let [cfg (str (.resolve root "config-home"))
           settings-file (.resolve root ".csb/settings.edn")
           global-file (.resolve (.resolve root "config-home")
                                 "clojure-system-browser/settings.edn")]
       (with-window {:config-dir cfg}
         (fn [{:keys [state submit!] :as ui}]
           (submit! #(session/open! state (str root)
                                    {:example-extension? true :config-dir cfg}))
           (sync! ui)
           (total! ui)
           ;; The gear opens the overlay with one row per setting.
           (fire! ui "Settings")
           (let [stage (sync! ui)]
             (is (some? (node stage "#settings-theme")))
             (is (some? (node stage "#settings-namespace-tree")))
             (is (some? (node stage "#settings-scope"))))
           ;; Edit theme and the tree default, save to this project.
           (let [stage (sync! ui)]
             (fx! (fn []
                    (.select (.getSelectionModel ^ComboBox (node stage "#settings-theme")) 1)
                    (.fire ^CheckBox (node stage "#settings-namespace-tree"))
                    (.setValue ^ComboBox (node stage "#settings-scope") "This project"))))
           (fire! ui "Save settings")
           (is (nil? (:settings-editor @state)))
           (is (str/includes? (status ui) "Settings saved · project"))
           ;; The view defaults and theme now come from the project layer.
           (is (= :light (:theme @state)))
           (is (true? (:namespace-tree? @state)))
           (is (str/includes? (slurp (str settings-file)) ":theme :light"))
           ;; Reopening the gear shows the persisted values and origin.
           (fire! ui "Settings")
           (sync! ui)
           (is (= :light (get-in @state [:settings-editor :values :theme])))
           (is (= :project (get-in @state [:settings-origins :theme])))
           ;; Switching the scope to global writes the other layer.
           (let [stage (sync! ui)]
             (fx! #(.setValue ^ComboBox (node stage "#settings-scope") "Global")))
           (fire! ui "Save settings")
           (is (str/includes? (slurp (str global-file)) ":namespace-tree? true"))
           ;; A strict failure keeps the overlay open with the reason.
           (fire! ui "Settings")
           (let [stage (sync! ui)]
             (fx! (fn []
                    (.setValue ^ComboBox (node stage "#settings-scope") "This project")
                    (.setText ^TextField (node stage "#settings-editor-command")
                              "[\"emacsclient\" 7]"))))
           (fire! ui "Save settings")
           (let [stage (sync! ui)]
             (is (some? (:settings-editor @state)))
             (is (str/includes? (fx! #(.getText ^Label (node stage "#settings-error")))
                                "editor-command")))
           (press-key! ui "#settings-theme" KeyCode/ESCAPE)
           (is (nil? (:settings-editor @state)))))))))

(defn -main [& _]
  (try
    (let [r (test/run-tests 'csb.desktop-smoke)]
      (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))
    (finally (Platform/exit) (shutdown-agents))))
