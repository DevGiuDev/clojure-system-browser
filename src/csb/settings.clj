(ns csb.settings
  "Application settings as EDN: a global file plus an optional per-project
   override. Layers merge built-in defaults, then global, then the project.
   Reading is tolerant: a broken layer becomes a warning and is skipped, so
   opening a project never fails because of settings. Saving is strict:
   unknown keys or unsupported values are rejected and nothing is written.
   Settings are data only; nothing in them is evaluated."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [csb.model :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException Files LinkOption Path Paths
            StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFileAttributeView]))

(def ^:private no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private empty-attrs (make-array FileAttribute 0))

(defn- valid-theme [v] (contains? #{:dark :light} v))

(defn- valid-port [v] (and (int? v) (pos? v) (<= v 65535)))

(defn- valid-editor-command
  [v]
  (or (nil? v)
      (and (vector? v) (seq v) (every? string? v) (not (str/blank? (first v))))))

(defn- valid-repl
  [v]
  (or (nil? v)
      (and (map? v)
           (every? #(contains? #{:host :port} %) (keys v))
           (or (nil? (:host v)) (and (string? (:host v)) (not (str/blank? (:host v)))))
           (or (nil? (:port v)) (valid-port (:port v))))))

(def schema
  "Known settings with their predicate and built-in default. Unknown keys and
   unsupported values warn on load (the layer is skipped) and are rejected on
   save. Optional entries (:editor-command, :repl) also accept nil, meaning
   unset."
  {:theme           {:pred valid-theme :default :dark
                     :description "UI theme, :dark or :light (TUI; Desktop ships one theme)"}
   :editor-command  {:pred valid-editor-command :default nil
                     :description "Editor argv vector; overrides CSB_EDITOR_ARGS, VISUAL and EDITOR"}
   :namespace-tree? {:pred boolean? :default false
                     :description "Namespaces panel starts as a tree (Desktop)"}
   :source-order?   {:pred boolean? :default false
                     :description "Start in the source order view (Desktop and TUI)"}
   :repl            {:pred valid-repl :default {}
                     :description "REPL panel defaults: {:host string, :port integer}"}})

(defn- reject-tag [tag _value]
  (m/fail! :unsupported "Unsupported tagged literal in settings" {:tag tag}))

(defn global-file
  "Path of the global settings file: $XDG_CONFIG_HOME or ~/.config, then
   clojure-system-browser/settings.edn. :config-dir overrides the directory
   (used by tests and embedders)."
  ([] (global-file {}))
  ([{:keys [config-dir]}]
   (let [dir (or (not-empty config-dir)
                 (not-empty (System/getenv "XDG_CONFIG_HOME"))
                 (some-> (System/getProperty "user.home") not-empty (str "/.config")))]
     (when dir
       (.toAbsolutePath (Paths/get ^String dir
                                   (into-array String
                                     ["clojure-system-browser" "settings.edn"])))))))

(defn project-file
  "Per-project settings: <project-root>/.csb/settings.edn."
  [project-root]
  (when project-root
    (.toAbsolutePath (Paths/get (str project-root)
                                (into-array String [".csb" "settings.edn"])))))

(defn- read-layer [^Path file]
  (when (Files/isRegularFile file no-links)
    (try
      {:value (edn/read-string {:readers {} :default reject-tag}
                               (Files/readString file StandardCharsets/UTF_8))}
      (catch Exception e
        {:problems [(str "unreadable or invalid EDN: "
                         (or (ex-message e) (str (class e))))]}))))

(defn- layer-problems
  "Strict validation of one settings map: unknown keys and unsupported values."
  [value]
  (if (not (map? value))
    [(str "expected a map of settings, got: " (pr-str value))]
    (concat
     (for [k (remove #(contains? schema %) (keys value))]
       (str "unknown setting " (pr-str k) " (known keys: "
            (pr-str (vec (sort (map str (keys schema)))) ")")))
     (for [[k v] (sort-by (comp str key) value)
           :when (and (contains? schema k)
                      (not ((:pred (get schema k)) v)))]
       (str (pr-str k) " does not accept " (pr-str v))))))

(defn- merge-value [old new]
  (if (and (map? old) (map? new)) (merge old new) new))

(defn load-settings
  "Effective settings: built-in defaults, then the global file, then the
   project file when :project-root is given. Returns
   {:settings … :origins … :warnings …} where origins maps each key to the
   layer that last set it and each warning identifies a skipped layer with its
   problems."
  ([] (load-settings {}))
  ([{:keys [project-root config-dir]}]
   (m/attempt
    #(let [layers (cond-> [[:global (global-file {:config-dir config-dir})]]
                    project-root (conj [:project (project-file project-root)]))
           warnings (volatile! [])
           settings (volatile! (into {} (map (fn [[k e]] [k (:default e)])) schema))
           origins (volatile! {})]
       (doseq [[layer ^Path file] layers
               :when file
               :let [read (read-layer file)
                     problems (when read
                                (or (seq (:problems read))
                                    (seq (layer-problems (:value read)))))]
               :when read]
         (if problems
           (vswap! warnings conj {:layer layer :file (str file) :problems (vec problems)})
           (doseq [[k v] (:value read)]
             (vswap! settings update k merge-value v)
             (vswap! origins assoc k layer))))
       {:settings @settings :origins @origins :warnings @warnings}))))

(defn- writable
  "Drop unset entries so hand-edited files stay meaningful: nil and empty maps
   are omitted (an omitted key is the same as unset for merging)."
  [values]
  (into {} (remove (fn [[_ v]] (or (nil? v) (and (map? v) (empty? v)))) values)))

(defn- edn-text [values]
  (if (empty? values)
    "{}\n"
    (str "{\n"
         (str/join "\n"
                   (for [k (sort-by str (keys values))]
                     (str " " (pr-str k) " " (pr-str (get values k)))))
         "\n}\n")))

(defn- replace-atomically! [^Path file ^String text]
  (let [tmp (Files/createTempFile (.getParent file) ".csb-" ".tmp" empty-attrs)]
    (try
      (when (and (Files/isRegularFile file no-links)
                 (Files/getFileAttributeView file PosixFileAttributeView no-links))
        (Files/setPosixFilePermissions tmp (Files/getPosixFilePermissions file no-links)))
      (Files/write tmp (.getBytes text StandardCharsets/UTF_8)
                   (into-array StandardOpenOption [StandardOpenOption/TRUNCATE_EXISTING]))
      (try
        (Files/move tmp file (into-array StandardCopyOption
                                        [StandardCopyOption/ATOMIC_MOVE
                                         StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (m/fail! :atomic-move-unsupported
                   "Filesystem does not support atomic replacement; settings left unchanged"
                   {:file (str file)})))
      (finally (Files/deleteIfExists tmp)))))

(defn save-settings!
  "Merge `values` into the settings file of `scope` (:global or :project) and
   replace it atomically; :project requires :project-root and creates its
   .csb directory. Strict: an unreadable existing file, or unknown keys and
   unsupported values in the merged result, fail without writing."
  ([scope values] (save-settings! scope values {}))
  ([scope values {:keys [project-root config-dir]}]
   (m/attempt
    #(let [^Path file (case scope
                  :global (or (global-file {:config-dir config-dir})
                              (m/fail! :settings-invalid
                                       "Cannot resolve a global settings directory" {}))
                  :project (or (when project-root (project-file project-root))
                               (m/fail! :settings-invalid
                                        "Project settings require a project root" {}))
                  (m/fail! :settings-invalid "Unknown settings scope" {:scope scope}))
           _ (when-not (map? values)
               (m/fail! :settings-invalid "Settings must be a map" {:value (pr-str values)}))
           read (read-layer file)
           _ (when (:problems read)
               (m/fail! :settings-invalid
                        "Existing settings file is invalid; fix or remove it first"
                        {:file (str file) :problems (:problems read)}))
           merged (reduce-kv (fn [acc k v] (update acc k merge-value v))
                             (or (:value read) {}) values)
           problems (vec (layer-problems merged))]
       (when (seq problems)
         (m/fail! :settings-invalid "Settings contain unsupported keys or values"
                  {:file (str file) :problems problems}))
       (Files/createDirectories (.getParent file) empty-attrs)
       (replace-atomically! file (edn-text (writable merged)))
       {:file (str file) :settings merged}))))


