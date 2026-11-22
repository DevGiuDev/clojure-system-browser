(ns csb.java-vm
  (:require [clojure.string :as str]
            [csb.model :as model])
  (:import [java.io File IOException UncheckedIOException]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files InvalidPathException LinkOption Path]
           [java.util UUID]
           [java.util.prefs BackingStoreException Preferences]))

(def ^:private node-path "csb/java-vms")

(defn- project-key [^String root]
  (str (UUID/nameUUIDFromBytes (.getBytes root StandardCharsets/UTF_8))))

(defn project-home [root]
  (let [prefs (Preferences/userRoot)]
    (when (.nodeExists prefs node-path)
      (let [project-prefs (.node prefs node-path)]
        (locking project-prefs
          (.sync project-prefs)
          (.get project-prefs (project-key root) nil))))))

(defn- java-executable ^Path [^Path home]
  (.resolve (.resolve home "bin")
            ^String (if (= File/separatorChar \\) "java.exe" "java")))

(defn- absolute-path ^Path [value]
  (when-not (str/blank? value)
    (try
      (let [path (Path/of ^String value (make-array String 0))]
        (when (.isAbsolute path) (.normalize path)))
      (catch InvalidPathException _ nil))))

(defn- executable? [^Path path]
  (and (Files/isRegularFile path (make-array LinkOption 0))
       (Files/isExecutable path)))

(defn- validate-home ^Path [value]
  (let [home (absolute-path value)]
    (when-not (and home (executable? (java-executable home)))
      (model/fail! :invalid-java-home
                   "Choose an absolute JDK directory with an executable Java in bin."
                   {:java-home value}))
    home))

(defn- real-path ^Path [^Path path]
  (try (.toRealPath path (make-array LinkOption 0))
       (catch IOException _ nil)
       (catch SecurityException _ nil)))

(defn- children [^Path dir]
  (try
    (with-open [entries (Files/list dir)] (.toList entries))
    (catch IOException _ [])
    (catch UncheckedIOException _ [])
    (catch SecurityException _ [])))

(defn- install-folders [env properties]
  (let [os (str/lower-case (get properties "os.name" ""))
        home (absolute-path (get properties "user.home"))
        program-files (keep #(absolute-path (get env %))
                            ["ProgramFiles" "ProgramW6432" "ProgramFiles(x86)"])]
    (concat
     (cond
       (str/includes? os "linux") (keep absolute-path ["/usr/lib/jvm" "/usr/java"])
       (str/includes? os "mac") (cond-> [(absolute-path "/Library/Java/JavaVirtualMachines")]
                                home (conj (.resolve home "Library/Java/JavaVirtualMachines")))
       (str/starts-with? os "win") (for [^Path folder program-files
                                          vendor ["Java" "Eclipse Adoptium" "Microsoft" "Amazon Corretto"]]
                                      (.resolve folder ^String vendor)))
     (when home
       (map #(.resolve home ^String %) [".jdks" ".sdkman/candidates/java"
                                       ".asdf/installs/java" ".local/share/mise/installs/java"])))))

(defn- path-home [^Path java]
  (when (executable? java)
    (when-let [^Path home (some-> (real-path java) .getParent .getParent)]
      (when (Files/isRegularFile (.resolve home "release") (make-array LinkOption 0))
        home))))

(defn- release-info [^Path home]
  (let [file (.resolve home "release")]
    (try
      (when (and (Files/isRegularFile file (make-array LinkOption 0))
                 (<= (Files/size file) 65536))
        (into {}
              (map (fn [[_ key value]] [({"JAVA_VERSION" :version "IMPLEMENTOR" :vendor} key) value]))
              (re-seq #"(?m)^(JAVA_VERSION|IMPLEMENTOR)=\"([^\"\r\n]+)\"\r?$" (Files/readString file))))
      (catch IOException _ nil)
      (catch SecurityException _ nil))))

(defn discover
  ([] (discover (System/getenv) (System/getProperties)))
  ([env properties]
   (let [path (or (get env "PATH") (get env "Path") "")
         separator (re-pattern (java.util.regex.Pattern/quote File/pathSeparator))
         path-folders (keep absolute-path (str/split path separator))
         java-name (if (= File/separatorChar \\) "java.exe" "java")
         path-vms (keep #(path-home (.resolve ^Path % ^String java-name)) path-folders)
         folders (mapcat children (distinct (install-folders env properties)))
         homes (concat (keep absolute-path [(get properties "java.home") (get env "JAVA_HOME")])
                       path-vms
                       (mapcat (fn [^Path folder] [folder (.resolve folder "Contents/Home")]) folders))]
     (->> homes
          (filter #(executable? (java-executable %)))
          (keep real-path)
          distinct
          (map #(assoc (release-info %) :home (str %)))
          (sort-by (fn [{:keys [home version]}]
                     (let [[_ major] (when version (re-find #"^(?:1\.)?(\d+)" version))]
                       [(- (or (some-> major parse-long) 0)) home])))
          vec))))

(defn label [{:keys [home version vendor]}]
  (str "Java" (when version (str " " version)) (when vendor (str " · " vendor)) " · " home))

(defn save-home! [root value]
  (let [home (when-not (str/blank? value) (str (validate-home (str/trim value))))
        prefs (.node (Preferences/userRoot) node-path)
        key (project-key root)
        set-home! (fn [setting] (if setting (.put prefs key setting) (.remove prefs key)))]
    (locking prefs
      (.sync prefs)
      (let [previous (.get prefs key nil)]
        (set-home! home)
        (try (.flush prefs)
             (catch BackingStoreException e
               (set-home! previous)
               (throw e)))))
    home))

(defn configure-process! [^ProcessBuilder builder home]
  (when home
    (let [home (validate-home home)
          java (java-executable home)
          env (.environment builder)
          path (.get env "PATH")]
      (.putAll env {"JAVA_HOME" (str home)
                    "JAVA_CMD" (str java)
                    "LEIN_JAVA_CMD" (str java)
                    "PATH" (str (.getParent java)
                                (when-not (str/blank? path) (str File/pathSeparator path)))}))))
