(ns csb.desktop
  (:require [cljfx.api :as fx]
            [cljfx.composite :as composite]
            [cljfx.ext.list-view :as list-view]
            [cljfx.ext.tree-view :as tree-view]
            [cljfx.fx.list-cell :as list-cell]
            [cljfx.fx.tree-cell :as tree-cell]
            [cljfx.lifecycle :as lifecycle]
            [cljfx.mutator :as mutator]
            [cljfx.prop :as prop]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [csb.cli :as cli]
            [csb.desktop.code-editor :as code-editor]
            [csb.desktop.icons :as icons]
            [csb.desktop.symbol-menu :as symbol-menu]
            [csb.java-vm :as java-vm]
            [csb.repl :as repl]
            [csb.runtime-inspection :as inspection]
            [csb.service :as service]
            [csb.session :as session]
            [csb.tests :as tests])
  (:import [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.scene.control ListCell TextArea TextField TreeCell TreeItem TreeView]
           [javafx.scene.input KeyCode KeyEvent MouseEvent]
           [javafx.stage DirectoryChooser WindowEvent]
           [java.util.concurrent Executors ExecutorService]))

(defn- selectable-items [id values selected label on-change disabled?]
  {:fx/type list-view/with-selection-props :v-box/vgrow :always
   :props {:selected-index (.indexOf ^java.util.List (vec values) selected)
           :on-selected-index-changed
           (fn [i] (when (and (<= 0 i) (< i (count values))) (on-change (nth values i))))}
   :desc {:fx/type :list-view :id id :disable (boolean disabled?)
          :items (mapv label values)}})

(defn- heading [text icon]
  {:fx/type :label :text text :style-class ["label" "panel-heading"]
   :graphic (icons/graphic icon)})

(defn- selection-list [id title icon values selected label on-change disabled?]
  {:fx/type :v-box :spacing 6 :h-box/hgrow :always :pref-width 280
   :children [(heading title icon)
              (selectable-items id values selected label on-change disabled?)]})

(defn- button
  ([text action disabled?] (button text action disabled? {}))
  ([text action disabled? {:keys [icon primary? active?]}]
   (cond-> {:fx/type :button :text text :disable (boolean disabled?)
            :style-class (cond-> ["button"]
                           primary? (conj "button-primary")
                           active? (conj "button-active"))
            :on-action (fn [_] (action))}
     icon (assoc :graphic (icons/graphic icon))
     active? (assoc :accessible-help "Active view"))))

(defn- filter-box [id prompt text on-text-changed]
  {:fx/type :stack-pane :style-class ["filter-box"] :alignment :center-left
   :children [{:fx/type :text-field :id id :prompt-text prompt
               :accessible-help prompt :pref-column-count 16
               :text text :on-text-changed on-text-changed}
              (update (icons/graphic :search) :style-class conj "filter-icon")]})

(def ^:private test-appearance
  {:passed [:success :success] :failed [:error :error] :error [:error :error]
   :running [:warning :clock] :cancelled [:warning :clock] :unavailable [:warning :warning]})

(defn- tone-class [tone]
  (str "tone-" (name (or tone :neutral))))

(defn- status-label [id text tone icon]
  (cond-> {:fx/type :label :id id :text text :wrap-text true
           :style-class ["label" "status-message" (tone-class tone)]}
    icon (assoc :graphic (icons/graphic icon))))

(def ^:private accessible-cell
  {:ctor #(ListCell.)
   :props (merge list-cell/props
                 (composite/props ListCell :accessible-text [:setter lifecycle/scalar]))})

(defn- test-status-cell [text accessible-text status]
  (if status
    (let [[tone icon] (get test-appearance status [:neutral :idle])
          label (tests/status-label status)]
      {:text text :accessible-text (str accessible-text " · " label)
       :tooltip {:fx/type :tooltip :text label}
       :graphic (update (icons/graphic icon) :style-class conj (tone-class tone))})
    {:text text :accessible-text accessible-text}))

(defn- with-test-status [items-desc item-status]
  (assoc-in
   items-desc
   [:desc :cell-factory]
   {:fx/cell-type accessible-cell :describe
    (fn [item]
      (test-status-cell item item (some-> item item-status)))}))

(defn- namespace-status [state test-tree ns-sym]
  (when-let [definitions (get test-tree ns-sym)]
    (tests/namespace-status state ns-sym definitions)))

(defn- namespace-items [id values selected on-change disabled? state test-tree]
  (with-test-status (selectable-items id values selected str on-change disabled?)
    #(namespace-status state test-tree (symbol %))))

(def ^:private accessible-tree-cell
  {:ctor #(TreeCell.)
   :props (merge tree-cell/props
                 (composite/props TreeCell :accessible-text [:setter lifecycle/scalar]))})

(defn- namespace-tree-item [segment {:keys [children namespace]} status]
  {:fx/type :tree-item :expanded true
   :value {:text segment :namespace namespace :status (when namespace (status namespace))}
   :children (mapv (fn [[label node]]
                     (assoc (namespace-tree-item label node status) :fx/key label))
                   (sort-by key children))})

(defn- namespace-branches [namespaces]
  (reduce (fn [root ns-sym]
            (let [segments (str/split (str ns-sym) #"\." -1)]
              (update-in root (interleave (repeat :children) segments) assoc :namespace ns-sym)))
          {} namespaces))

(defn- select-tree-namespace! [^TreeView tree [namespaces selected]]
  (let [model (.getSelectionModel tree)
        ^TreeItem item (when (some #{selected} namespaces)
                         (some #(when (= selected (:namespace (.getValue ^TreeItem %))) %)
                               (tree-seq (fn [^TreeItem item] (not (.isLeaf item)))
                                         (fn [^TreeItem item] (.getChildren item))
                                         (.getRoot tree))))]
    (if item
      (do
        (doseq [^TreeItem parent (take-while some? (iterate #(.getParent ^TreeItem %) (.getParent item)))]
          (.setExpanded parent true))
        (.select model item)
        (.scrollTo tree (.getRow tree item)))
      (.clearSelection model))))

(def ^:private namespace-tree-selection
  (fx/make-ext-with-props
   {:selection (prop/make (mutator/setter select-tree-namespace!) lifecycle/scalar)}))

(defn- namespaces-tree [namespaces state actions disabled? test-tree]
  {:fx/type namespace-tree-selection :v-box/vgrow :always
   :props {:selection [namespaces (:namespace state)]}
   :desc {:fx/type tree-view/with-selection-props
          :props {:on-selected-item-changed
                  (fn [^TreeItem item]
                    (when-let [ns-sym (some-> item .getValue :namespace)]
                      ((:namespace actions) ns-sym)))}
          :desc {:fx/type :tree-view :id "namespaces-tree" :show-root false
                 :disable (boolean disabled?)
                 :root (namespace-tree-item nil (namespace-branches namespaces)
                                            #(namespace-status state test-tree %))
                 :cell-factory {:fx/cell-type accessible-tree-cell
                                :describe (fn [{:keys [text namespace status]}]
                                            (test-status-cell text (if namespace (str namespace) text) status))}}}})

(defn- namespaces-panel [namespaces state actions disabled? test-tree]
  {:fx/type :v-box :spacing 6 :pref-width 280 :h-box/hgrow :always
   :children [(filter-box "namespace-filter" "Filter namespaces…"
                          (get state :namespace-filter "") (:namespace-filter actions))
              {:fx/type :h-box :spacing 8 :alignment :center-left
               :children [(heading "Namespaces" :layers)
                          {:fx/type :check-box :id "namespace-tree-toggle" :text "Tree"
                           :accessible-help "Show namespaces as a tree"
                           :selected (boolean (:namespace-tree? state))
                           :on-selected-changed (:namespace-tree actions)}]}
              (if (:namespace-tree? state)
                (namespaces-tree namespaces state actions disabled? test-tree)
                (namespace-items "namespaces" namespaces (:namespace state)
                                 (:namespace actions) disabled? state test-tree))]})

(def ^:private definition-icons
  {:function :function :macro :macro :value :value :test :tests})

(defn- definition-item [{:keys [id name writable? kind location block?]}]
  {:id id
   ;; Blocks already identify file and line in their label.
   :text (if block?
           name
           (str name (when-not writable? " [read only]") " · " (:row location)))
   :kind kind :read-only? (not writable?)})

(defn- definition-cell [{:keys [text kind read-only?]}]
  (if text
    {:text text :accessible-text (str text " · " (name (or kind :definition)))
     :graphic {:fx/type :h-box :alignment :center-left :spacing 3
               :mouse-transparent true
               :children (cond-> [(icons/graphic (get definition-icons kind :definition))]
                           read-only? (conj (icons/graphic :lock)))}}
    {:text nil}))

(defn- definition-list [definitions selected on-change disabled? on-context-menu]
  (update-in (selection-list "definitions" "Definitions" :definition definitions selected
                             definition-item on-change disabled?)
             [:children 1 :desc] assoc
             :cell-factory {:fx/cell-type accessible-cell :describe definition-cell}
             :on-context-menu-requested on-context-menu))

(defn- follow-transcript! [^TextArea area]
  (let [scroll! #(Platform/runLater
                  (fn []
                    (.positionCaret area (.getLength area))
                    (.setScrollTop area Double/MAX_VALUE)))]
    (.addListener (.textProperty area)
                  (reify ChangeListener
                    (changed [_ _ _ _] (scroll!))))
    (scroll!)))

(defn- tests-panel [state actions tree]
  (let [namespaces (vec (sort-by str (keys tree)))
        ns-sym (tests/selected-namespace state tree)
        definitions (get tree ns-sym)
        selected (tests/selected-test state definitions)
        test-sym (:name selected)
        result (tests/result state)
        lines (tests/log-lines result)
        running? (tests/active? state)
        [tone icon] (test-appearance (:status result))]
    {:fx/type :v-box :style-class ["workspace-panel"] :spacing 10
     :children
     [{:fx/type :split-pane :orientation :vertical :divider-positions [0.38] :v-box/vgrow :always
       :items
       [{:fx/type :h-box :spacing 12 :min-height 90
         :children [{:fx/type :v-box :spacing 6 :h-box/hgrow :always :pref-width 280
                     :children [(heading "Test namespaces" :layers)
                                (namespace-items "test-namespaces" namespaces ns-sym
                                                 (:test-namespace actions) false state tree)]}
                    (update-in (selection-list "test-definitions" "Tests" :tests definitions selected
                                               (comp str :name) (:test-definition actions) false)
                               [:children 1] with-test-status #(tests/case-status state ns-sym (symbol %)))]}
        {:fx/type :v-box :spacing 8 :min-height 100
         :children
         [{:fx/type :h-box :spacing 8
           :children [(button "Run suite" #((:run-tests actions) nil nil) running? {:icon :tests :primary? (not running?)})
                      (button "Run namespace" #((:run-tests actions) ns-sym nil) (or running? (nil? ns-sym)))
                      (button "Run test" #((:run-tests actions) ns-sym test-sym) (or running? (nil? selected)))
                      (button "Stop tests" (:cancel-tests actions) (not running?))
                      (button "Open test source" #((:open-test actions) selected)
                              (or (nil? selected) (:busy? state) (:dirty? state)))]}
          (status-label "test-summary" (tests/result-label result) tone icon)
          {:fx/type code-editor/lifecycle :id "test-details" :log? true :v-box/vgrow :always
           :text (str/join "\n" (map :text lines))
           :error-rows (into #{} (keep-indexed #(when (:error? %2) %1)) lines)}]}]}]}))

(defn- order-entries [state]
  (when-let [system (:system state)]
    (mapcat (fn [{:keys [file forms]}]
              (into [{:header file}]
                    (map #(assoc % :file file) forms)))
            (:value (service/list-source-forms system (:namespace state))))))

(defn- order-label [entry definition]
  (if (:header entry)
    (:header entry)
    (str (when (= (:definition-id entry) (:id definition)) "▶ ")
         (:ordinal entry) " · " (:label entry)
         " " (or (some-> entry :name str) (:preview entry))
         " · " (:file entry) ":" (:row entry))))

(defn- order-panel [state actions]
  (let [{:keys [definition busy? dirty?]} state
        entries (vec (order-entries state))]
    {:fx/type :v-box :spacing 6 :style-class ["workspace-panel"]
     :children
     [(assoc (heading (str "Source order · " (or (:namespace state) "no namespace")
                           " · Esc returns to source") :order) :wrap-text true)
      {:fx/type list-view/with-selection-props :v-box/vgrow :always
       :props {:selected-index -1
               :on-selected-index-changed
               (fn [i]
                 (when (and (<= 0 i) (< i (count entries)))
                   (when-let [id (:definition-id (nth entries i))]
                     ((:definition actions) id))))}
       :desc {:fx/type :list-view :id "source-order"
              :disable (boolean (or busy? dirty?))
              :items (mapv #(order-label % definition) entries)}}]}))

(defn- search-items
  "Interleave section headers and result rows for the search results list.
   Namespaces come first, then definition categories in name order."
  [{:keys [namespaces definitions]}]
  (vec
   (concat
    (when (seq namespaces)
      (into [{:header "Namespaces"}] (map #(array-map :result/type :namespace :namespace %)) namespaces))
    (mapcat (fn [[category defs]] (into [{:header (str/capitalize (str/replace (name category) "-" " "))}] defs))
            (group-by :category definitions)))))

(defn- search-rows
  "Result rows of a search, without section headers."
  [results]
  (filterv (complement :header) (search-items results)))

(defn- search-item [item root]
  (if (:header item)
    {:text (:header item) :header? true}
    (let [namespace? (= :namespace (:result/type item))]
      (cond-> {:text (if namespace?
                       (str (:namespace item))
                       (str (:namespace item) "/" (:name item) " · "
                            (str/replace-first (:file item) (str root java.io.File/separator) "")
                            ":" (get-in item [:location :row])))}
        namespace? (assoc :icon :layers)
        (not namespace?) (assoc :icon (get definition-icons (:kind item) :definition)
                                :accessible-text (str (name (or (:kind item) :definition)) " "
                                                      (:namespace item) "/" (:name item)))))))

(defn- search-cell [{:keys [text header? icon accessible-text]}]
  (if header?
    {:text text :style-class ["list-cell" "search-header"]}
    (cond-> {:text text :graphic (icons/graphic icon)}
      accessible-text (assoc :accessible-text accessible-text))))

(defn- search-overlay
  "Centered quick-open panel over the workspace: type to search, arrows move,
   Enter opens the selection, Esc or a click on the scrim closes."
  [{:keys [search system]} actions]
  (let [items (search-items (:results search))
        root (some-> system service/project-info :root)
        rows (search-rows (:results search))]
    {:fx/type :stack-pane :style-class ["search-scrim"]
     :on-mouse-pressed (fn [_] ((:close-search actions)))
     :children
     [{:fx/type :v-box :style-class ["search-overlay" "desktop"] :spacing 8
       :on-mouse-pressed (fn [^MouseEvent e] (.consume e))
       :children
       (into
        [{:fx/type fx/ext-on-instance-lifecycle
          :on-created (fn [^TextField field] (Platform/runLater #(.requestFocus field)))
          :desc {:fx/type :text-field :id "search-query"
                 :prompt-text "Search namespaces and definitions…"
                 :accessible-help "Search namespaces and definitions"
                 :text (or (:query search) "") :on-text-changed (:search-query actions)
                 :on-key-pressed (fn [^KeyEvent e]
                                   (cond
                                     (= KeyCode/ENTER (.getCode e))
                                     (do (.consume e) ((:open-search-result actions)))
                                     (= KeyCode/UP (.getCode e))
                                     (do (.consume e) ((:move-search-selection actions) -1))
                                     (= KeyCode/DOWN (.getCode e))
                                     (do (.consume e) ((:move-search-selection actions) 1))))}}]
        (if (empty? rows)
          [{:fx/type :label :id "search-empty" :wrap-text true
            :style-class ["label" "secondary-text"]
            :text (if-not system
                    "Open a project to search."
                    (if (str/blank? (:query search))
                      "Type to search namespaces and definitions"
                      "No matches in the indexed project."))}]
          [(update (selectable-items "search-results" items (:selection search)
                                     #(search-item % root)
                                     (fn [item] ((:select-search-result actions) item))
                                     false)
                   :desc assoc
                   :pref-height 320
                   :cell-factory {:fx/cell-type accessible-cell
                                  :describe search-cell}
                   :on-key-pressed (fn [^KeyEvent e]
                                     (when (= KeyCode/ENTER (.getCode e))
                                       (.consume e)
                                       ((:open-search-result actions)))))
           {:fx/type :h-box :spacing 8 :alignment :center-left
            :children [{:fx/type :label :style-class ["label" "secondary-text"]
                        :text "Enter opens · ↑ ↓ navigates · Esc closes"}
                       (button "Close" (:close-search actions) false)]}]))}]}))

(defn- automatic-usages [{:keys [system definition category source-order? tests-view symbol-results usage-selection]}]
  (when (and system definition (not= :requires category)
             (not (or source-order? (:open? tests-view) symbol-results)))
    (when-let [{:keys [matches index-version] :as result}
               (:value (service/definition-usages system (:id definition)))]
      (let [context [index-version (:id definition)]
            [selected-context selected-id] usage-selection
            selected (when (= context selected-context)
                       (some #(when (= selected-id (:id %)) %) matches))]
        (assoc result :usage-context context :selection (or selected (first matches)))))))

(defn- usage-cell [{:keys [text detail]}]
  (let [full-text (str text " · " detail)]
    {:accessible-text full-text
     :tooltip {:fx/type :tooltip :text full-text}
     :graphic {:fx/type :v-box
               :children [{:fx/type :label :text text :max-width 204}
                          {:fx/type :label :text detail :max-width 204
                           :style-class ["label" "secondary-text"]}]}}))

(defn- symbol-panel [{:keys [system busy? dirty?]} actions results]
  (let [{:keys [action symbol matches selection availability usage-context]} results
        root (:root (service/project-info system))
        prefix (if (str/ends-with? root java.io.File/separator) root (str root java.io.File/separator))
        label (fn [{:keys [namespace name file location usage-location usage-count]}]
                (let [title (str namespace "/" name)
                      row (:row (if usage-context usage-location location))
                      detail (str (str/replace-first file prefix "") ":" row)]
                  (if usage-context
                    {:text title :detail (cond-> detail (> usage-count 1) (str " · " usage-count " uses"))}
                    (str title " · " detail))))
        blocked? (or busy? dirty? (nil? selection))
        select (if usage-context #((:select-definition-usage actions) usage-context %) (:select-symbol-result actions))
        open (if usage-context #((:open-definition-usage actions) usage-context) (:open-symbol-result actions))
        title (if usage-context "Uses" ({:definition "Definitions" :usages "Symbol uses" :tests "Related tests"} action))
        message (case availability
                  :stale "Reload the project to refresh its uses."
                  :ambiguous "Multiple definitions share this name."
                  :ready "No uses found in the indexed project."
                  "No matches in the indexed project.")]
    {:fx/type :v-box :spacing 8 :style-class ["workspace-panel" "symbol-panel"]
     :pref-width (if usage-context 248 350)
     :children
     (cond-> [(assoc (heading (str title " · " symbol) :search) :wrap-text true)
              (if (seq matches)
                (cond-> (assoc-in (selectable-items (if usage-context "definition-usage-results" "symbol-results")
                                                  matches selection label select busy?)
                                  [:desc :on-key-pressed]
                                  (fn [^KeyEvent event]
                                    (cond
                                      (and (not blocked?) (= KeyCode/ENTER (.getCode event)))
                                      (do (.consume event) (open))

                                      (and (nil? usage-context) (= KeyCode/ESCAPE (.getCode event)))
                                      (do (.consume event) ((:close-symbol-results actions))))))
                  usage-context (assoc-in [:desc :cell-factory] {:fx/cell-type accessible-cell :describe usage-cell}))
                {:fx/type :label :id (when usage-context "definition-uses-status") :text message :wrap-text true})]
       (or (seq matches) (nil? usage-context))
       (conj {:fx/type :h-box :spacing 8
              :children (cond-> [(button "Open definition" open blocked? {:icon :definition :primary? (not blocked?)})]
                          (nil? usage-context) (conj (button "Close" (:close-symbol-results actions) false)))}))}))

(defn- inspection-panel [state actions]
  (let [{:keys [phase active inspection interrupt?]} (:repl state)
        {:keys [target perspective]} inspection
        rows (repl/inspection-rows state)
        pending? (or (not= :connected phase) active)]
    {:fx/type :v-box :spacing 6 :v-box/vgrow :always
     :children
     [{:fx/type :h-box :spacing 8
       :children [(button "Live" #((:perspective actions) :live) (not= :combined perspective))
                  (button "Combined" #((:perspective actions) :combined) (= :combined perspective))
                  (button "Refresh" (:refresh-inspection actions) pending?)
                  (button "Back" (:back-inspection actions) (or pending? (empty? target)))
                  (button "Open" (:open-inspection actions) (or pending? (empty? rows)))
                  (button "Interrupt" (:repl-interrupt actions)
                          (or (not interrupt?) (nil? active) (:stop active)))]}
      {:fx/type :label :id "runtime-status" :text (inspection/status (:repl state)) :wrap-text true}
      (heading (inspection/heading inspection) :layers)
      (if-let [details (inspection/details state)]
        {:fx/type :text-area :id "runtime-metadata" :text details :editable false
         :wrap-text true :v-box/vgrow :always}
        (selectable-items "runtime-entries" rows (repl/inspection-selection state rows)
                          inspection/label #((:select-inspection actions) (:name %)) pending?))]}))

(defn- repl-panel [state actions]
  (let [{:keys [phase host port input active interrupt? namespace status] :as panel} (:repl state)
        disconnected? (contains? #{nil :disconnected} phase)
        connected? (= :connected phase)
        pending? (or (not connected?) active)
        working? (or active (contains? #{:connecting :disconnecting} phase))
        inspector? (:inspection-open? state)]
    {:fx/type :v-box :spacing 6 :style-class ["workspace-panel"]
     :children
     (into
      [{:fx/type :h-box :spacing 8 :alignment :center-left
        :children
        [(heading "nREPL" :terminal)
         {:fx/type :text-field :id "repl-host" :pref-column-count 9 :prompt-text "Host"
          :disable (not disconnected?) :text (or host repl/default-host)
          :on-text-changed (:repl-host actions)
          :on-action (fn [_] ((:repl-connect actions)))}
         {:fx/type :text-field :id "repl-port" :pref-column-count 5 :prompt-text "Port"
          :disable (not disconnected?) :text (or port "") :on-text-changed (:repl-port actions)
          :on-action (fn [_] ((:repl-connect actions)))}
         (button "Connect" (:repl-connect actions) (or (not disconnected?) (:busy? state))
                 {:primary? (and disconnected? (not (:busy? state)))})
         (button "Disconnect" (:repl-disconnect actions) (or disconnected? (= :disconnecting phase)))
         (if inspector?
           (button "Console" (:hide-inspection actions) false)
           (button "Inspect runtime" (:show-inspection actions) pending?))
         (status-label "repl-status" (str (when namespace (str namespace " · ")) (or status "Disconnected"))
                       (when working? :warning) (when working? :clock))]}]
      (if inspector?
        [(inspection-panel state actions)]
        [{:fx/type fx/ext-on-instance-lifecycle :on-created follow-transcript! :v-box/vgrow :always
          :desc {:fx/type :text-area :id "repl-output" :editable false :wrap-text true
                 :pref-row-count 4 :text (repl/transcript panel) :style-class ["text-area" "monospace"]}}
         {:fx/type :text-area :id "repl-input" :pref-row-count 2 :prompt-text "Clojure expression"
          :text (or input "") :on-text-changed (:repl-input actions) :style-class ["text-area" "monospace"]}
         {:fx/type :h-box :spacing 8
          :children [(button "Evaluate" (:repl-evaluate actions) (or pending? (str/blank? input)))
                     (button (if (get-in state [:definition :block?])
                               "Evaluate block" "Evaluate definition")
                             (:repl-definition actions)
                             (or pending? (:busy? state) (not (repl/evaluable? state))))
                     (button "Interrupt" (:repl-interrupt actions)
                             (or (not interrupt?) (nil? active) (:stop active)))]}]))}))

(defn- java-vm-panel [{:keys [input installed error]} busy? actions]
  (let [options (mapv java-vm/label installed)
        homes (zipmap options (map :home installed))]
    {:fx/type :v-box :spacing 6 :style-class ["java-vm-panel"]
     :children
     (cond->
      [{:fx/type :combo-box :id "installed-java-vms" :max-width Double/MAX_VALUE
        :disable (boolean (or busy? (empty? options))) :items options
        :prompt-text (if (seq options) "Select an installed Java VM" "No Java VMs detected")
        :value (some #(when (= input (:home %)) (java-vm/label %)) installed)
        :on-value-changed (fn [choice]
                            (when-let [home (get homes choice)]
                              ((:edit-java-home actions) home)))}
       {:fx/type :h-box :spacing 8 :alignment :center-left
        :children
        [{:fx/type :label :text "JDK directory"}
         {:fx/type :text-field :id "java-home" :h-box/hgrow :always
          :disable (boolean busy?) :text input
          :prompt-text "Leave blank to inherit the launcher environment"
          :on-text-changed (:edit-java-home actions)
          :on-action (fn [_] ((:apply-java-vm actions)))}
         (button "Apply Java VM" (:apply-java-vm actions) busy? {:primary? (not busy?)})
         (button "Cancel" (:close-java-vms actions) busy?)]}]
       error (conj (status-label "java-vm-error" error :error :error)))}))

(defn- toolbar [state actions extensions]
  (let [{:keys [system busy? dirty? definition]} state
        save? (and (not busy?) dirty? (:writable? definition))]
    {:fx/type :flow-pane :style-class ["toolbar"]
     :pref-wrap-length 1050 :hgap 10 :vgap 7
     :children
     (into
      [{:fx/type :h-box :spacing 6
        :children [(button "Open project…" (:open actions) busy?
                           {:icon :folder :primary? (and (nil? system) (not busy?))})
                   (button "Save" (:save actions) (not save?) {:icon :save :primary? save?})
                   (button "Discard" (:discard actions) (or busy? (not dirty?)) {:icon :undo})
                   (button "Reload" (:reload actions) (or busy? (nil? system)) {:icon :reload})]}
       {:fx/type :h-box :spacing 6
        :children [(button "Source order" (:toggle-order actions) (or busy? (nil? system))
                           {:icon :order :active? (:source-order? state)})
                   (button "Tests" (:toggle-tests actions) (or busy? (nil? system))
                           {:icon :tests :active? (get-in state [:tests-view :open?])})
                   (button "Java VMs…" (:show-java-vms actions) (or busy? (nil? system) (:java-vm-editor state))
                           {:active? (:java-vm-editor state)})
                   (button "REPL" (:toggle-repl actions) busy? {:icon :terminal :active? (:repl-open? state)})]}]
      (map (fn [{:keys [title kind id]}]
             (button title #((:extension actions) kind id) busy?)))
      extensions)}))

(def ^:private status-icons {:warning :warning :error :error :success :success})

(defn- status-bar [{:keys [status status-tone busy? dirty? system]}]
  {:fx/type :v-box :spacing 5 :style-class ["status-bar"]
   :children
   (into
    [{:fx/type :h-box :spacing 8 :alignment :center-left
      :children (cond-> []
                  busy? (conj (status-label "busy-status" "Working…" nil :clock))
                  dirty? (conj (status-label "draft-status" "Unsaved changes" :warning :pencil))
                  true (conj (status-label "status" status status-tone (status-icons status-tone))))}]
    (map (fn [{:keys [file error]}]
           (status-label nil (str file ": " (:message error)) :error :error)))
    (when system (service/diagnostics system)))})

(defn- view [state actions]
  (let [{:keys [definition draft dirty? system busy?]} state
        requires? (= :requires (:category state))
        failure (when-not requires? (tests/source-failure state definition))
        symbol-results (or (:symbol-results state) (automatic-usages state))
        test-tree (tests/targets state)
        tests-open? (get-in state [:tests-view :open?])
        defs (filterv #(str/includes? (str/lower-case (str (:name %)))
                                      (str/lower-case (get state :filter "")))
                      (session/definitions state))
        nss (filterv #(str/includes? (str/lower-case (str %))
                                     (str/lower-case (get state :namespace-filter "")))
                     (vec (session/namespaces state)))
        extensions (when system
                     (concat (map #(assoc % :kind :commands) (service/contributions system :commands))
                             (map #(assoc % :kind :views) (service/contributions system :views))))]
    {:fx/type :stage :title (str (when dirty? "* ") "Clojure System Browser " cli/version " · Source World")
     :showing true :width 1100 :height 780 :min-width 760 :min-height 540
     :on-close-request (:close actions)
     :scene
     {:fx/type :scene
      :stylesheets [(-> "csb/desktop/theme.css" io/resource .toExternalForm)]
      :on-key-pressed (fn [^KeyEvent e]
                        (cond
                          (and (.isShortcutDown e) (= KeyCode/F (.getCode e)))
                          (do (.consume e) ((:open-search actions)))
                          (and (:search state) (= KeyCode/ESCAPE (.getCode e)))
                          (do (.consume e) ((:close-search actions)))
                          (and (.isShortcutDown e) (= KeyCode/S (.getCode e)))
                          (do (.consume e) ((:save actions)))
                          (and (:java-vm-editor state) (= KeyCode/ESCAPE (.getCode e)))
                          (do (.consume e) ((:close-java-vms actions)))
                          (and tests-open? (= KeyCode/ESCAPE (.getCode e)))
                          (do (.consume e) ((:toggle-tests actions)))
                          (and (:symbol-results state) (= KeyCode/ESCAPE (.getCode e)))
                          (do (.consume e) ((:close-symbol-results actions)))
                          (and (:source-order? state) (= KeyCode/ESCAPE (.getCode e)))
                          (do (.consume e) ((:toggle-order actions)))))
      :root
      (let [desktop {:fx/type :border-pane :style-class ["desktop"]
                     :top {:fx/type :v-box :spacing 8 :style-class ["desktop-header"]
                           :children
                           (cond-> [(assoc (heading "Clojure System Browser" :definition)
                                           :style-class ["label" "app-title"])
                                    (toolbar state actions extensions)]
                             (:java-vm-editor state) (conj (java-vm-panel (:java-vm-editor state) busy? actions)))}
                     :center
                     {:fx/type :split-pane :orientation :vertical
                      :divider-positions (if tests-open? [0.65] (if (:repl-open? state) [0.25 0.54] [0.34]))
                      :items
                      (if tests-open?
                        (cond-> [(tests-panel state actions test-tree)]
                          (:repl-open? state) (conj (repl-panel state actions)))
                        (cond->
                         [{:fx/type :h-box :spacing 12 :min-height 140 :style-class ["workspace-panel"]
                           :children
                          [(namespaces-panel nss state actions (or busy? dirty?) test-tree)
                            (let [cats (session/categories state)]
                              (selection-list "categories"
                                              (str "Categories"
                                                   (when cats
                                                     (str " · " (:total cats) " defs · " (:files cats)
                                                          (if (= 1 (:files cats)) " file" " files"))))
                                              :categories
                                              (:entries cats) (:category state)
                                              session/category-label
                                              (fn [entry] ((:category actions) (:category entry)))
                                              (or busy? dirty?)))
                            {:fx/type :v-box :spacing 6 :pref-width 280 :h-box/hgrow :always
                             :children [(filter-box "definition-filter" "Filter definitions…"
                                                    (get state :filter "") (:filter actions))
                                        (dissoc (definition-list defs (some #(when (= (:id %) (:id definition)) %) defs)
                                                                  #((:definition actions) (:id %)) (or busy? dirty?)
                                                                  (symbol-menu/on-definitions defs (:lookup-symbol actions)
                                                                                              (:repl-row actions) repl/row-evaluable?))
                                                :h-box/hgrow)]}]}
                          (cond->
                           {:fx/type :border-pane
                            :center
                            (if (:source-order? state)
                              (order-panel state actions)
                              {:fx/type :v-box :spacing 6 :style-class ["workspace-panel" "editor-panel"]
                               :children
                               (cond-> [(cond-> (assoc (heading (cond requires? (str "Requires · " (:namespace state))
                                                                      definition (str (:namespace definition) "/" (:name definition))
                                                                      :else "Select a definition")
                                                                (if failure :error :file))
                                                       :wrap-text true)
                                          failure (update :style-class conj "test-failure-heading" "tone-error"))]
                                 failure
                                 (conj (status-label "test-failure"
                                                     (str (:heading failure) " · " (:description failure))
                                                     :error nil))
                                 true
                                 (conj {:fx/type code-editor/lifecycle :v-box/vgrow :always
                                         :text (if requires? (session/requires-text state) (or draft ""))
                                         :document-key (if requires? [system :requires (:namespace state)] [system (:id definition)])
                                         :error-rows (when-let [row (:row failure)] #{row})
                                         :saved-text (:source definition)
                                         :editable (and (not busy?) (boolean (:writable? definition)))
                                         :on-text-changed (:edit actions)
                                         :on-context-menu-requested (when-not busy?
                                                                       (symbol-menu/on-editor definition (:lookup-symbol actions)
                                                                                              (:repl-selection actions) repl/selection-evaluable?))})
                                 (or requires? definition)
                                 (conj (if requires?
                                         (status-label "editor-access" "Read only" nil :lock)
                                         (let [{:keys [file location writable? read-only-reason]} definition
                                               path (str file ":" (:row location))]
                                           (if writable?
                                             {:fx/type :label :style-class ["label" "secondary-text"] :wrap-text true :text path}
                                             (status-label "editor-access"
                                                           (str "Read only" (when read-only-reason (str " · " read-only-reason)) " · " path)
                                                           nil :lock))))))})}
                            symbol-results (assoc :right (symbol-panel state actions symbol-results)))]
                          (:repl-open? state) (conj (repl-panel state actions))))}
                     :bottom (status-bar state)}]
        (if (:search state)
          {:fx/type :stack-pane :children [desktop (search-overlay state actions)]}
          desktop))}}))

(defn- choose-project []
  (some-> (.showDialog (doto (DirectoryChooser.) (.setTitle "Open Clojure project")) nil)
          .getAbsolutePath))

(defn- controller [state options]
  (let [worker (Executors/newSingleThreadExecutor)
        close-results! #(swap! state dissoc :symbol-results)
        open-result! (fn [id]
                       (when (session/select-definition! state id {:reveal? true})
                         (close-results!)))
        stop! (fn []
                (tests/cancel! state)
                (repl/disconnect! state)
                (.shutdownNow ^ExecutorService worker))
        submit (fn [f]
                 (when-not (:busy? @state)
                   (swap! state assoc :busy? true)
                   (.submit ^ExecutorService worker
                            ^Runnable (fn []
                                        (try (f)
                                             (catch Exception e (swap! state assoc :status (ex-message e) :status-tone :error))
                                             (finally (swap! state assoc :busy? false)))))))]
    {:submit! submit :stop! stop!
     :actions
     {:open (fn []
              (if (:dirty? @state)
                (swap! state assoc :status "Unsaved changes: Save or Discard before opening a project" :status-tone :warning)
                (when-let [root (choose-project)]
                  (submit (fn []
                            (let [previous (:system @state)]
                              (session/open! state root (dissoc options :roots :project))
                              (when-not (identical? previous (:system @state))
                                (tests/cancel! state)
                                (repl/disconnect! state)
                                (swap! state dissoc :repl-open? :inspection-open? :symbol-results :tests-view :test-runner))))))))
      :save #(submit (fn [] (session/save! state) (when-not (:dirty? @state) (close-results!))))
      :discard #(session/discard! state)
      :reload #(submit (fn [] (session/reload! state) (when-not (:dirty? @state) (close-results!))))
      :namespace #(session/select-namespace! state %)
      :category #(session/select-category! state %)
      :definition #(session/select-definition! state %)
      :lookup-symbol (fn [id sym action]
                       (submit
                        (fn []
                          (let [result (service/lookup-symbol (:system @state) id sym action)
                                {:keys [matches] :as value} (:value result)]
                            (cond
                              (= :error (:status result))
                              (swap! state assoc :status (get-in result [:error :message]) :status-tone :error)

                              (and (= :definition action) (= 1 (count matches)) (not (:dirty? @state)))
                              (open-result! (:id (first matches)))

                              :else (swap! state assoc :symbol-results
                                           (assoc value :action action :selection (first matches))))))))
      :close-symbol-results close-results!
      :select-symbol-result #(swap! state assoc-in [:symbol-results :selection] %)
      :open-symbol-result #(when-let [id (get-in @state [:symbol-results :selection :id])]
                             (when-not (:busy? @state) (open-result! id)))
      :select-definition-usage (fn [context row]
                                 (swap! state (fn [s]
                                                (if (= context (:usage-context (automatic-usages s)))
                                                  (assoc s :usage-selection [context (:id row)])
                                                  s))))
      :open-definition-usage (fn [context]
                               (let [s @state
                                     {:keys [usage-context selection]} (automatic-usages s)]
                                 (when (and (not (:busy? s)) (= context usage-context) selection)
                                   (open-result! (:id selection)))))
      :edit #(session/edit! state %)
      :filter #(swap! state assoc :filter %)
      :namespace-filter #(swap! state assoc :namespace-filter %)
      :namespace-tree #(swap! state assoc :namespace-tree? %)
      :open-search #(when-not (:search @state)
                      (swap! state assoc :search {:open? true :query ""
                                                  :results {:namespaces [] :definitions []}
                                                  :selection nil}))
      :close-search #(swap! state dissoc :search)
      :search-query (fn [query]
                      (swap! state
                             (fn [s]
                               (if-let [search (:search s)]
                                 (let [results (or (some-> (:system s) (service/search query) :value)
                                                   {:namespaces [] :definitions []})]
                                   (assoc s :search (assoc search :query query :results results
                                                           :selection (first (search-rows results)))))
                                 s))))
      :select-search-result #(when-not (:header %) (swap! state assoc-in [:search :selection] %))
      :move-search-selection (fn [delta]
                               (swap! state
                                      (fn [s]
                                        (if-let [search (:search s)]
                                          (let [rows (search-rows (:results search))
                                                idx (.indexOf ^java.util.List (vec rows) (:selection search))
                                                next (max 0 (min (dec (count rows))
                                                                 (if (neg? idx) 0 (+ idx delta))))]
                                            (assoc-in s [:search :selection] (get rows next)))
                                          s))))
      :open-search-result (fn []
                            (when-not (:busy? @state)
                              (let [selection (get-in @state [:search :selection])]
                                (when (some? selection)
                                  (let [opened? (if (= :namespace (:result/type selection))
                                                  (or (= (:namespace selection) (:namespace @state))
                                                      (session/select-namespace! state (:namespace selection)))
                                                  (when-let [id (:id selection)] (open-result! id)))]
                                    (when opened?
                                      (swap! state dissoc :search)))))))
      :toggle-order #(swap! state update :source-order? not)
      :toggle-tests #(swap! state update-in [:tests-view :open?] not)
      :show-java-vms #(submit (fn [] (session/show-java-vms! state)))
      :edit-java-home #(session/set-java-home-input! state %)
      :apply-java-vm #(submit (fn [] (session/apply-java-vm! state)))
      :close-java-vms #(when-not (:busy? @state) (swap! state dissoc :java-vm-editor))
      :test-namespace #(tests/select-namespace! state %)
      :test-definition #(tests/select-test! state %)
      :run-tests #(tests/run! state %1 %2)
      :cancel-tests #(tests/cancel! state)
      :open-test #(tests/open-source! state %)
      :toggle-repl #(if (:repl-open? @state)
                      (swap! state assoc :repl-open? false)
                      (repl/show-panel! state))
      :repl-port #(swap! state update :repl repl/edit-port %)
      :repl-host #(swap! state assoc-in [:repl :host] %)
      :repl-input #(swap! state assoc-in [:repl :input] %)
      :repl-connect #(when-not (:busy? @state) (repl/connect! state))
      :repl-disconnect #(repl/disconnect! state)
      :repl-evaluate #(repl/evaluate-input! state)
      :repl-definition #(repl/evaluate-definition! state)
      :repl-row (fn [entry]
                  (repl/evaluate-row! state entry)
                  (repl/hide-inspection! state)
                  (repl/show-panel! state))
      :repl-selection (fn [definition text]
                        (repl/evaluate-selection! state definition text)
                        (repl/hide-inspection! state)
                        (repl/show-panel! state))
      :repl-interrupt #(repl/interrupt! state)
      :show-inspection #(repl/show-inspection! state)
      :hide-inspection #(repl/hide-inspection! state)
      :perspective #(repl/set-perspective! state %)
      :select-inspection #(repl/select-inspection! state %)
      :open-inspection #(repl/open-inspection! state)
      :back-inspection #(repl/back-inspection! state)
      :refresh-inspection #(repl/refresh-inspection! state)
      :extension (fn [kind id] (submit #(session/extension! state kind id)))
      :close (fn [^WindowEvent e]
               (if (:dirty? @state)
                 (do (.consume e) (swap! state assoc :status "Save or Discard changes before closing" :status-tone :warning))
                 (do (stop!) (Platform/exit))))}}))

(defn -main [& args]
  (try
    (let [{:keys [project help?] :as options} (cli/arguments args)]
      (if help?
        (do (println (str "clojure-system-browser " cli/version
                          "\nclojure -M:desktop --project PATH [--source-root DIR ...] [--example-extension]"))
            (Platform/exit))
        (let [state (session/create)
              {:keys [actions submit!]} (controller state options)
              renderer (fx/create-renderer :middleware (fx/wrap-map-desc #(view % actions)))]
          (fx/mount-renderer state renderer)
          (submit! #(session/open! state project options)))))
    (catch Exception e
      (binding [*out* *err*] (println (ex-message e)))
      (Platform/exit))))
