(ns csb.desktop.code-editor
  (:require [cljfx.component :as component]
            [cljfx.lifecycle :as lifecycle]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [csb.syntax :as syntax])
  (:import [java.util Collections]
           [java.util.function Consumer]
           [javafx.event EventHandler]
           [org.fxmisc.flowless VirtualizedScrollPane]
           [org.fxmisc.richtext CodeArea]
           [org.fxmisc.richtext.model StyleSpansBuilder]
           [org.reactfx Subscription]))

(defn- display-text [text]
  ;; RichTextFX uses LF between paragraphs. Compare with that representation so
  ;; an unrelated render never replaces the text or moves the caret for CRLF/CR.
  (str/replace (or text "") #"\r\n?" "\n"))

(defn- highlight! [^CodeArea area]
  (when (pos? (.getLength area))
    (let [builder (StyleSpansBuilder.)]
      (doseq [{:keys [start end kind]} (syntax/spans (.getText area))]
        (.add builder (Collections/singleton (str "syntax-" (name kind))) (int (- end start))))
      (.setStyleSpans area (int 0) (.create builder)))))

(defrecord Editor [area container subscription props updating?]
  component/Component
  (instance [_] container))

(defn- sync-editor! [{:keys [^CodeArea area props updating?] :as editor} desc]
  (let [text (display-text (:text desc))
        changed-document? (not= (:document-key @props) (:document-key desc))]
    (reset! updating? true)
    (try
      (.setEditable area (boolean (:editable desc)))
      (when (or changed-document? (not= text (.getText area)))
        (.replaceText area ^String text)
        (.moveTo area (int 0))
        (.forgetHistory (.getUndoManager area))
        (highlight! area))
      (reset! props desc)
      (finally (reset! updating? false))))
  editor)

(def lifecycle
  (reify lifecycle/Lifecycle
    (create [_ desc _]
      (let [area (doto (CodeArea.)
                   (.setId "definition-source")
                   (.setWrapText false))
            container (VirtualizedScrollPane. area)
            props (atom {})
            updating? (atom false)
            subscription (.subscribe (.plainTextChanges area)
                                     (reify Consumer
                                       (accept [_ _]
                                         (when-not @updating?
                                           (highlight! area)
                                           (when-let [edit (:on-text-changed @props)]
                                             (let [text (.getText area)
                                                   saved (:saved-text @props)]
                                               ;; Undo to the original display also restores
                                               ;; its exact CRLF/CR text in the session.
                                               (edit (if (= text (display-text saved)) saved text))))))))
            editor (->Editor area container subscription props updating?)]
        (.setOnContextMenuRequested area
                                    (reify EventHandler
                                      (handle [_ event]
                                        (when-let [handler (:on-context-menu-requested @props)]
                                          (handler area event)))))
        (.add (.getStylesheets area)
              (.toExternalForm (io/resource "csb/desktop/code-editor.css")))
        (sync-editor! editor desc)))
    (advance [_ editor desc _]
      (sync-editor! editor desc))
    (delete [_ {:keys [^CodeArea area ^Subscription subscription]} _]
      (.unsubscribe subscription)
      (.dispose area))))
