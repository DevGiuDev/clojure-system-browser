(ns csb.settings-test
  "Settings layering: defaults, global file and project override, tolerant
   reads with strict saves, atomic replacement without leftovers."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [csb.session :as session]
            [csb.settings :as settings]
            [csb.source-test :refer [with-project]])
  (:import [java.nio.file Files]))

(defn- global-dir [root]
  (str (.resolve ^java.nio.file.Path root "config-home")))

(defn- write-project-settings [root text]
  (let [file (.resolve ^java.nio.file.Path root ".csb/settings.edn")]
    (Files/createDirectories (.getParent file) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString file text (make-array java.nio.file.OpenOption 0))))

(defn- read-project-settings [root]
  (edn/read-string (Files/readString (.resolve ^java.nio.file.Path root ".csb/settings.edn"))))

(defn- warnings-for [result]
  (-> result :value :warnings))

(deftest missing-files-fall-back-to-defaults
  (with-project {}
    (fn [root]
      (let [r (settings/load-settings {:project-root (str root)
                                       :config-dir (global-dir root)})]
        (is (= :ok (:status r)) (pr-str r))
        (is (= {:theme :dark :editor-command nil :namespace-tree? false
                :source-order? false :repl {}}
               (-> r :value :settings)))
        (is (= [] (-> r :value :warnings)))
        (is (= {} (-> r :value :origins)))))))

(deftest layers-merge-and-track-origins
  (with-project {".csb/settings.edn" "{:theme :light :repl {:port 4748}}"}
    (fn [root]
      (let [dir (global-dir root)
            saved (settings/save-settings! :global {:repl {:host "127.0.0.1"}
                                                   :namespace-tree? true}
                                           {:config-dir dir})]
        (is (= :ok (:status saved)) (pr-str saved))
        (let [r (:value (settings/load-settings {:project-root (str root)
                                                 :config-dir dir}))]
          ;; :repl merges key by key across layers
          (is (= {:host "127.0.0.1" :port 4748} (-> r :settings :repl)))
          (is (= :light (-> r :settings :theme)))
          (is (true? (-> r :settings :namespace-tree?)))
          (is (= {:theme :project :repl :project :namespace-tree? :global}
                 (:origins r))))))))

(deftest broken-layers-warn-and-are-skipped
  (with-project {".csb/settings.edn" "{:theme "}
    (fn [root]
      (let [dir (global-dir root)
            r (:value (settings/load-settings {:project-root (str root)
                                               :config-dir dir}))]
        (is (= [{:layer :project :file (str (settings/project-file (str root)))
                 :problems ["unreadable or invalid EDN: EOF while reading"]}]
               (:warnings r)))
        ;; defaults still hold for the broken layer's keys
        (is (= :dark (-> r :settings :theme))))))
  (with-project {".csb/settings.edn" "{:colour :red}"}
    (fn [root]
      (is (some #(re-find #"unknown setting :colour" %)
                (-> (warnings-for (settings/load-settings {:project-root (str root)}))
                    first :problems)))))
  (with-project {".csb/settings.edn" "{:theme #foo/bar}"}
    (fn [root]
      (is (= 1 (count (warnings-for
                       (settings/load-settings {:project-root (str root)})))))))
  (with-project {".csb/settings.edn" "[1 2]"}
    (fn [root]
      (is (= ["expected a map of settings, got: [1 2]"]
             (-> (warnings-for (settings/load-settings {:project-root (str root)}))
                 first :problems)))))
  (with-project {".csb/settings.edn" "{:theme :pink :repl {:port \"x\"}}"}
    (fn [root]
      (is (some #(re-find #":theme does not accept :pink" %)
                (-> (warnings-for (settings/load-settings {:project-root (str root)}))
                    first :problems)))
      (is (some #(re-find #":repl does not accept" %)
                (-> (warnings-for (settings/load-settings {:project-root (str root)}))
                    first :problems))))))

(deftest save-merges-writes-and-drops-unset
  (with-project {".csb/settings.edn" "{:theme :light :repl {:host \"localhost\"}}"}
    (fn [root]
      (let [r (settings/save-settings! :project {:repl {:port 4000}}
                                       {:project-root (str root)})]
        (is (= :ok (:status r)) (pr-str r))
        (is (= {:theme :light :repl {:host "localhost" :port 4000}}
               (read-project-settings root)))
        (is (= {:theme :light :repl {:host "localhost" :port 4000}}
               (-> r :value :settings)))
        ;; the atomic replacement leaves no temporaries behind
        (is (= ["settings.edn"]
               (mapv #(str (.getFileName ^java.nio.file.Path %))
                     (iterator-seq (.iterator
                                    (Files/newDirectoryStream
                                     (.resolve ^java.nio.file.Path root ".csb"))))))))))
  (with-project {}
    (fn [root]
      (settings/save-settings! :project {:editor-command nil :repl {} :theme :dark}
                               {:project-root (str root)})
      ;; unset entries are omitted; an omitted key merges as unset
      (is (= {:theme :dark} (read-project-settings root))))))

(deftest save-is-strict
  (with-project {".csb/settings.edn" "{:theme :light}"}
    (fn [root]
      (doseq [values [{:colour :red} {:theme :pink} {:repl {:port 0}}
                      {:editor-command ["ok" 5]} {:editor-command []}]]
        (is (= :error (:status (settings/save-settings! :project values
                                                        {:project-root (str root)})))
            (pr-str values))
        ;; nothing was written
        (is (= {:theme :light} (read-project-settings root))))))
  (with-project {".csb/settings.edn" "{{{"}
    (fn [root]
      (let [r (settings/save-settings! :project {:theme :dark}
                                       {:project-root (str root)})]
        (is (= :settings-invalid (get-in r [:error :type])))
        (is (= "Existing settings file is invalid; fix or remove it first"
               (some-> r :error :message)))
        ;; the broken file is left untouched for the user to fix
        (is (= "{{{" (Files/readString (.resolve ^java.nio.file.Path root ".csb/settings.edn")))))))
  (with-project {}
    (fn [root]
      (is (= :settings-invalid
             (get-in (settings/save-settings! :project {} {}) [:error :type])))
      (is (= :settings-invalid
             (get-in (settings/save-settings! :nope {} {:project-root (str root)})
                     [:error :type])))
      (is (false? (Files/exists (.resolve ^java.nio.file.Path root ".csb")
                                (make-array java.nio.file.LinkOption 0)))))))

(deftest paths-follow-the-decided-layout
  (with-project {}
    (fn [root]
      (let [dir (global-dir root)
            dir-path (.resolve ^java.nio.file.Path root "config-home")]
        (is (= (str (.resolve dir-path "clojure-system-browser/settings.edn"))
               (str (settings/global-file {:config-dir dir}))))
        (is (= (str (.resolve ^java.nio.file.Path root ".csb/settings.edn"))
               (str (settings/project-file (str root)))))
        ;; default resolution works on this machine even when XDG is unset
        (is (string? (re-find #"clojure-system-browser/settings\.edn$"
                              (str (settings/global-file)))))))))

(deftest session-settings-round-trip
  (with-project {"src/a.clj" "(ns a) (defn f [] 1)"}
    (fn [root]
      (let [state (session/create)
            cfg (global-dir root)]
        (session/open! state (str root) {:config-dir cfg})
        ;; defaults seed the initial view
        (is (false? (:namespace-tree? @state)))
        (is (= :dark (:theme @state)))
        (is (= [] (:settings-warnings @state)))
        ;; the gear round-trip: show, edit, save to the project layer
        (session/show-settings! state)
        (is (= {:values (:settings @state)
                :origins (:settings-origins @state)
                :scope :global :field 0 :caret 0 :error nil}
               (:settings-editor @state)))
        (session/set-settings-scope! state :project)
        (session/set-setting! state :theme :light)
        (session/set-setting! state :namespace-tree? true)
        (session/apply-settings! state)
        (is (nil? (:settings-editor @state)))
        (is (= "Settings saved · project" (:status @state)))
        (is (= :light (:theme @state)))
        (is (true? (:namespace-tree? @state)))
        ;; the gear saves the whole effective view, so booleans come along
        (is (= {:namespace-tree? true :source-order? false :theme :light}
               (read-project-settings root)))
        ;; strict failures surface inside the editor and keep it open
        (session/show-settings! state)
        (session/set-setting! state :theme :pink)
        (session/apply-settings! state)
        (is (re-find #":theme does not accept :pink"
                     (:error (:settings-editor @state))))
        (session/close-settings! state)
        (is (nil? (:settings-editor @state)))
        ;; a hand-edited broken file warns on the next open; browsing continues
        (write-project-settings root "{:theme ")
        (session/open! state (str root) {:config-dir cfg})
        (is (= :dark (:theme @state)))
        ;; the broken project layer is skipped, so its keys fall back to defaults
        (is (false? (:namespace-tree? @state)))
        (is (= 1 (count (:settings-warnings @state))))
        (is (re-find #"settings warning" (:status @state)))))))
