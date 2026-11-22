(ns csb.desktop
  (:require [cljfx.api :as fx]
            [cljfx.composite :as composite]
            [cljfx.ext.list-view :as list-view]
            [cljfx.fx.list-cell :as list-cell]
            [cljfx.lifecycle :as lifecycle]
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
           [javafx.scene.control ListCell TextArea]
           [javafx.scene.input KeyCode KeyEvent]
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

(defn- with-test-status [list-desc item-status]
  (assoc-in
   list-desc
   [:children 1 :desc :cell-factory]
   {:fx/cell-type accessible-cell :describe
    (fn [item]
      (if-let [status (some-> item item-status)]
        (let [[tone icon] (get test-appearance status [:neutral :idle])
              label (tests/status-label status)]
          {:text item :accessible-text (str item " · " label)
           :tooltip {:fx/type :tooltip :text label}
           :graphic (update (icons/graphic icon) :style-class conj (tone-class tone))})
        {:text item}))}))

(defn- namespace-list [id title values selected on-change disabled? state test-tree]
  (with-test-status (selection-list id title :layers values selected str on-change disabled?)
    (fn [item]
      (let [ns-sym (symbol item)]
        (when-let [definitions (get test-tree ns-sym)]
          (tests/namespace-status state ns-sym definitions))))))

(def ^:private definition-icons
  {:function :function :macro :macro :value :value :test :tests})

(defn- definition-item [{:keys [id name writable? kind location]}]
  {:id id :text (str name (when-not writable? " [read only]") " · " (:row location))
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
        running? (tests/active? state)
        [tone icon] (test-appearance (:status result))]
    {:fx/type :v-box :style-class ["workspace-panel"] :spacing 10
     :children
     [{:fx/type :h-box :spacing 12 :v-box/vgrow :always
       :children [(namespace-list "test-namespaces" "Test namespaces" namespaces ns-sym
                                  (:test-namespace actions) false state tree)
                  (with-test-status (selection-list "test-definitions" "Tests" :tests definitions selected
                                                    (comp str :name) (:test-definition actions) false)
                    #(tests/case-status state ns-sym (symbol %)))]}
      {:fx/type :h-box :spacing 8
       :children [(button "Run suite" #((:run-tests actions) nil nil) running? {:icon :tests :primary? (not running?)})
                  (button "Run namespace" #((:run-tests actions) ns-sym nil) (or running? (nil? ns-sym)))
                  (button "Run test" #((:run-tests actions) ns-sym test-sym) (or running? (nil? selected)))
                  (button "Stop tests" (:cancel-tests actions) (not running?))
                  (button "Open test source" #((:open-test actions) selected)
                          (or (nil? selected) (:busy? state) (:dirty? state)))]}
      (status-label "test-summary" (tests/result-label result) tone icon)
      {:fx/type fx/ext-on-instance-lifecycle
       :on-created follow-transcript!
       :desc {:fx/type :text-area :id "test-details" :editable false :wrap-text true :pref-row-count 6
              :text (str/join "\n" (:output result))}}]}))

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

(defn- symbol-panel [{:keys [system symbol-results busy? dirty?]} actions]
  (let [{:keys [action symbol matches selection]} symbol-results
        root (:root (service/project-info system))
        label (fn [{:keys [namespace name file location]}]
                (str namespace "/" name " · "
                     (str/replace-first file (str root java.io.File/separator) "") ":" (:row location)))
        blocked? (or busy? dirty? (nil? selection))
        open (:open-symbol-result actions)]
    {:fx/type :v-box :spacing 8 :style-class ["workspace-panel" "symbol-panel"] :pref-width 350
     :children
     [(assoc (heading (str ({:definition "Definitions" :usages "Symbol uses" :tests "Related tests"} action)
                           " · " symbol) :search) :wrap-text true)
      (if (seq matches)
        (assoc-in (selectable-items "symbol-results" matches selection label (:select-symbol-result actions) busy?)
                  [:desc :on-key-pressed]
                  (fn [^KeyEvent event]
                    (when (and (not blocked?) (= KeyCode/ENTER (.getCode event)))
                      (.consume event)
                      (open))))
        {:fx/type :label :text "No matches in the indexed project."})
      {:fx/type :h-box :spacing 8
       :children [(button "Open definition" open blocked? {:icon :definition :primary? (not blocked?)})
                  (button "Close" (:close-symbol-results actions) false)]}]}))

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
                     (button "Evaluate definition" (:repl-definition actions)
                             (or pending? (:busy? state) (not (repl/jvm-definition? state))))
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
                   (button "REPL" (:toggle-repl actions) busy? {:icon :terminal :active? (:repl-open? state)})]}
       {:fx/type :stack-pane :style-class ["filter-box"] :alignment :center-left
        :children [{:fx/type :text-field :id "definition-filter" :prompt-text "Filter definitions…"
                    :accessible-help "Filter definitions" :pref-column-count 16
                    :text (get state :filter "") :on-text-changed (:filter actions)}
                   (update (icons/graphic :search) :style-class conj "filter-icon")]}]
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
        test-tree (tests/targets state)
        tests-open? (get-in state [:tests-view :open?])
        defs (filterv #(str/includes? (str/lower-case (str (:name %)))
                                     (str/lower-case (get state :filter "")))
                      (session/definitions state))
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
      {:fx/type :border-pane :style-class ["desktop"]
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
          [(namespace-list "namespaces" "Namespaces" (vec (session/namespaces state)) (:namespace state)
                           (:namespace actions) (or busy? dirty?) state test-tree)
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
           (definition-list defs (some #(when (= (:id %) (:id definition)) %) defs)
                            #((:definition actions) (:id %)) (or busy? dirty?)
                            (symbol-menu/on-definitions defs (:lookup-symbol actions)))]}
         (cond->
          {:fx/type :border-pane
          :center
          (if (:source-order? state)
            (order-panel state actions)
            {:fx/type :v-box :spacing 6 :style-class ["workspace-panel" "editor-panel"]
             :children
             (cond-> [(assoc (heading (cond requires? (str "Requires · " (:namespace state))
                                            definition (str (:namespace definition) "/" (:name definition))
                                            :else "Select a definition") :file) :wrap-text true)
                      {:fx/type code-editor/lifecycle :v-box/vgrow :always
                       :text (if requires? (session/requires-text state) (or draft ""))
                       :document-key (if requires? [system :requires (:namespace state)] [system (:id definition)])
                       :saved-text (:source definition)
                       :editable (and (not busy?) (boolean (:writable? definition)))
                       :on-text-changed (:edit actions)
                       :on-context-menu-requested (when-not busy? (symbol-menu/on-editor definition (:lookup-symbol actions)))}]
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
          (:symbol-results state) (assoc :right (symbol-panel state actions)))]
          (:repl-open? state) (conj (repl-panel state actions))))}
       :bottom (status-bar state)}}}))

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
      :edit #(session/edit! state %)
      :filter #(swap! state assoc :filter %)
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
