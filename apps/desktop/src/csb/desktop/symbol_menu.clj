(ns csb.desktop.symbol-menu
  (:require [clojure.string :as str]
            [csb.syntax :as syntax])
  (:import [javafx.event EventHandler]
           [javafx.scene Node]
           [javafx.scene.control ContextMenu ListCell ListView MenuItem SeparatorMenuItem]
           [javafx.scene.input ContextMenuEvent]
           [org.fxmisc.richtext CodeArea]))

(def actions
  [[:definition "Go to definition"] [:usages "Find symbol uses"] [:tests "Find related tests"]])

(defn- show! [^Node owner ^ContextMenuEvent event items]
  (let [menu (ContextMenu.)]
    (.setAll (.getItems menu)
             (into-array
              MenuItem
              (map (fn [[label action]]
                     (if label
                       (doto (MenuItem. label)
                         (.setDisable (nil? action))
                         (.setOnAction (reify EventHandler (handle [_ _] (when action (action))))))
                       (SeparatorMenuItem.)))
                   items)))
    (.consume event)
    (.show menu owner (.getScreenX event) (.getScreenY event))))

(defn- lookup-items [lookup definition sym]
  (into [[(str (or sym (:name definition))) nil] nil]
        (map (fn [[action label]] [label #(lookup (:id definition) sym action)]))
        actions))

(defn- row-index [^ContextMenuEvent event]
  (loop [node (.getTarget event)]
    (cond
      (instance? ListCell node) (when-not (.isEmpty ^ListCell node) (.getIndex ^ListCell node))
      (instance? ListView node) nil
      (instance? Node node) (recur (.getParent ^Node node)))))

(defn on-definitions [definitions lookup eval-row evaluable?]
  (fn [^ContextMenuEvent event]
    (let [^ListView view (.getSource event)
          index (if (.isKeyboardTrigger event)
                  (.getSelectedIndex (.getSelectionModel view))
                  (row-index event))]
      (when-let [definition (when index (get definitions index))]
        (show! view event (into (lookup-items lookup definition nil)
                                (when eval-row
                                  [nil
                                   ["Eval in REPL"
                                    (when (evaluable? definition) #(eval-row definition))]])))))))

(defn- editor-symbol [^CodeArea area ^ContextMenuEvent event]
  (let [keyboard? (.isKeyboardTrigger event)
        offset (if keyboard?
                 (if (.isEmpty (.getSelectedText area)) (.getCaretPosition area) (.getStart (.getSelection area)))
                 (let [point (.screenToLocal area (.getScreenX event) (.getScreenY event))]
                   (.orElse (.getCharacterIndex (.hit area (.getX point) (.getY point))) -1)))]
    (syntax/symbol-at (.getText area) offset keyboard?)))

(defn on-editor [definition lookup eval-selection evaluable?]
  (when definition
    (fn [^CodeArea area ^ContextMenuEvent event]
      ;; The selection is captured when the menu opens and sent exactly as it
      ;; was selected, even if the definition changed in between.
      (let [selected-text (str (.getSelectedText area))
            selected? (not (.isEmpty (.getSelectedText area)))
            editable? (.isEditable area)
            sym (editor-symbol area event)]
        (show! area event
               (into (lookup-items lookup definition sym)
                     [nil
                      ["Eval in REPL"
                       (when (and eval-selection
                                  (evaluable? definition)
                                  (not (str/blank? selected-text)))
                         #(eval-selection definition selected-text))]
                      ["Cut" (when (and editable? selected?) #(.cut area))]
                      ["Copy" (when selected? #(.copy area))]
                      ["Paste" (when editable? #(.paste area))]
                      ["Select all" #(.selectAll area)]]))))))
