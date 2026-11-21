(ns csb.desktop
  (:require [cljfx.api :as fx]
            [cljfx.ext.list-view :as list-view]
            [clojure.string :as str]
            [csb.cli :as cli]
            [csb.desktop.code-editor :as code-editor]
            [csb.service :as service]
            [csb.session :as session])
  (:import [javafx.application Platform]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.stage DirectoryChooser WindowEvent]
           [java.util.concurrent Executors ExecutorService]))

(defn- selection-list [id title values selected label on-change disabled?]
  {:fx/type :v-box :spacing 6 :h-box/hgrow :always :pref-width 280
   :children
   [{:fx/type :label :text title :style "-fx-font-weight: bold;"}
    {:fx/type list-view/with-selection-props :v-box/vgrow :always
     :props {:selected-index (.indexOf ^java.util.List (vec values) selected)
             :on-selected-index-changed
             (fn [i] (when (and (<= 0 i) (< i (count values))) (on-change (nth values i))))}
     :desc {:fx/type :list-view :id id :disable (boolean disabled?)
            :items (mapv label values)}}]})

(defn- button [text action disabled?]
  {:fx/type :button :text text :disable (boolean disabled?) :on-action (fn [_] (action))})

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
    {:fx/type :v-box :spacing 6 :padding 14
     :children
     [{:fx/type :label :wrap-text true
       :text (str "Source order · " (or (:namespace state) "no namespace")
                  " · real file order of top-level forms · Esc or the button returns to source")}
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

(defn- view [state actions]
  (let [{:keys [definition draft dirty? system busy?]} state
        defs (filterv #(str/includes? (str/lower-case (str (:name %)))
                                     (str/lower-case (get state :filter "")))
                      (session/definitions state))
        extensions (when system
                     (concat (map #(assoc % :kind :commands) (service/contributions system :commands))
                             (map #(assoc % :kind :views) (service/contributions system :views))))]
    {:fx/type :stage :title (str (when dirty? "* ") "Clojure System Browser · Source World")
     :showing true :width 1100 :height 780 :min-width 760 :min-height 540
     :on-close-request (:close actions)
     :scene
     {:fx/type :scene
      :on-key-pressed (fn [^KeyEvent e]
                       (cond
                         (and (.isShortcutDown e) (= KeyCode/S (.getCode e)))
                         (do (.consume e) ((:save actions)))
                         (and (:source-order? state) (= KeyCode/ESCAPE (.getCode e)))
                         (do (.consume e) ((:toggle-order actions)))))
      :root
      {:fx/type :border-pane :style "-fx-font-family: sans-serif; -fx-font-size: 13;"
       :top {:fx/type :v-box :spacing 10 :padding 14
             :children
             [{:fx/type :label :text "CLOJURE SYSTEM BROWSER" :style "-fx-font-size: 18; -fx-font-weight: bold;"}
              {:fx/type :h-box :spacing 8
               :children (into [(button "Open project…" (:open actions) busy?)
                                (button "Save" (:save actions) (or busy? (not dirty?) (not (:writable? definition))))
                                (button "Discard" (:discard actions) (or busy? (not dirty?)))
                                (button "Reload" (:reload actions) (or busy? (nil? system)))
                                (button "Source order" (:toggle-order actions) (or busy? (nil? system)))
                                {:fx/type :text-field :id "definition-filter" :prompt-text "Filter definitions…"
                                 :text (get state :filter "") :h-box/hgrow :always
                                 :on-text-changed (:filter actions)}]
                               (map (fn [c] (button (:title c) #((:extension actions) (:kind c) (:id c)) busy?)) extensions))}]}
       :center
       {:fx/type :split-pane :orientation :vertical :divider-positions [0.34]
        :items
        [{:fx/type :h-box :padding 14 :spacing 12
          :children
          [(selection-list "namespaces" "Namespaces" (vec (session/namespaces state)) (:namespace state)
                           str (:namespace actions) (or busy? dirty?))
           (let [cats (session/categories state)]
             (selection-list "categories"
                             (str "Categories"
                                  (when cats
                                    (str " · " (:total cats) " defs · " (:files cats)
                                         (if (= 1 (:files cats)) " file" " files"))))
                             (:entries cats) (:category state)
                             (fn [entry]
                               (if (:category entry)
                                 (str (name (:category entry)) " · " (:count entry))
                                 "(all)"))
                             (fn [entry] ((:category actions) (:category entry)))
                             (or busy? dirty?)))
           (selection-list "definitions" "Definitions" (mapv :id defs) (:id definition)
                           (fn [id]
                             (let [d (some #(when (= id (:id %)) %) defs)]
                               (str (:name d) (when-not (:writable? d) " [read only]")
                                    " · " (get-in d [:location :row]))))
                           (:definition actions) (or busy? dirty?))]}
         (if (:source-order? state)
           (order-panel state actions)
           {:fx/type :v-box :spacing 6 :padding 14
            :children
            [{:fx/type :label :text (if definition
                                    (str (:namespace definition) "/" (:name definition)
                                         " · " (:file definition) ":" (get-in definition [:location :row]))
                                    "Select a definition") :wrap-text true}
             {:fx/type code-editor/lifecycle :v-box/vgrow :always :text (or draft "")
              :document-key [system (:id definition)]
              :saved-text (:source definition)
              :editable (and (not busy?) (boolean (:writable? definition)))
              :on-text-changed (:edit actions)}
             {:fx/type :label :wrap-text true
              :text (or (:read-only-reason definition) "Save changes the source file. No code is evaluated.")}]})]}
       :bottom
       {:fx/type :v-box :padding 12 :spacing 4
        :children
        [{:fx/type :label :id "status" :wrap-text true
          :text (str (when busy? "Working… ") (when dirty? "Unsaved changes · ") (:status state))}
         {:fx/type :label :wrap-text true
          :text (str/join "\n" (map #(str (:file %) ": " (get-in % [:error :message]))
                                     (when system (service/diagnostics system))))}]}}}}))

(defn- choose-project []
  (some-> (.showDialog (doto (DirectoryChooser.) (.setTitle "Open Clojure project")) nil)
          .getAbsolutePath))

(defn- controller [state options]
  (let [worker (Executors/newSingleThreadExecutor)
        stop! #(.shutdownNow ^ExecutorService worker)
        submit (fn [f]
                 (when-not (:busy? @state)
                   (swap! state assoc :busy? true)
                   (.submit ^ExecutorService worker
                            ^Runnable (fn []
                                        (try (f)
                                             (catch Exception e (swap! state assoc :status (ex-message e)))
                                             (finally (swap! state assoc :busy? false)))))))]
    {:submit! submit :stop! stop!
     :actions
     {:open (fn []
              (if (:dirty? @state)
                (swap! state assoc :status "Unsaved changes: Save or Discard before opening a project")
                (when-let [root (choose-project)]
                  (submit #(session/open! state root (dissoc options :roots :project))))))
      :save #(submit (fn [] (session/save! state)))
      :discard #(session/discard! state)
      :reload #(submit (fn [] (session/reload! state)))
      :namespace #(session/select-namespace! state %)
      :category #(session/select-category! state %)
      :definition #(session/select-definition! state %)
      :edit #(session/edit! state %)
      :filter #(swap! state assoc :filter %)
      :toggle-order #(swap! state update :source-order? not)
      :extension (fn [kind id] (submit #(session/extension! state kind id)))
      :close (fn [^WindowEvent e]
               (if (:dirty? @state)
                 (do (.consume e) (swap! state assoc :status "Save or Discard changes before closing"))
                 (do (stop!) (Platform/exit))))}}))

(defn -main [& args]
  (try
    (let [{:keys [project help?] :as options} (cli/arguments args)]
      (if help?
        (do (println "clojure -M:desktop --project PATH [--source-root DIR ...] [--example-extension]") (Platform/exit))
        (let [state (session/create)
              {:keys [actions submit!]} (controller state options)
              renderer (fx/create-renderer :middleware (fx/wrap-map-desc #(view % actions)))]
          (fx/mount-renderer state renderer)
          (submit! #(session/open! state project options)))))
    (catch Exception e
      (binding [*out* *err*] (println (ex-message e)))
      (Platform/exit))))
