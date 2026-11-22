(ns csb.editor
  "External editors only see a temporary definition, never the original file."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [csb.model :as m]
            [csb.service :as service])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- validate-argv [argv]
  (when-not (and (vector? argv) (seq argv) (every? string? argv)
                 (not (str/blank? (first argv)))
                 (not-any? #(str/includes? % "\u0000") argv))
    (m/fail! :invalid-editor "Editor must be a non-empty vector of string arguments" {}))
  (when (and (re-find #"(?i)(^|[/\\])emacsclient(?:\.exe)?$" (first argv))
             (some #{"-n" "--no-wait"} (rest argv)))
    (m/fail! :invalid-editor "emacsclient must wait for editing to finish; remove -n / --no-wait" {}))
  argv)

(defn- split-command [command]
  ;; Quotes and escaped spaces only group arguments. No shell, expansion or pipes.
  (loop [chars (seq command) quote-char nil token "" started? false argv []]
    (if-let [ch (first chars)]
      (cond
        (and (= ch \\) (not= quote-char \')
             (let [following (second chars)]
               (or (= following \\) (= following \")
                   (and (nil? quote-char)
                        (or (= following \') (and following (Character/isWhitespace ^char following)))))))
        (recur (nnext chars) quote-char (str token (second chars)) true argv)

        quote-char
        (if (= ch quote-char)
          (recur (next chars) nil token true argv)
          (recur (next chars) quote-char (str token ch) true argv))

        (#{\' \"} ch) (recur (next chars) ch token true argv)
        (Character/isWhitespace ^char ch)
        (recur (next chars) nil "" false (if started? (conj argv token) argv))
        :else (recur (next chars) nil (str token ch) true argv))
      (do
        (when quote-char
          (m/fail! :invalid-editor "Unclosed quote in editor command; use CSB_EDITOR_ARGS for an explicit argument vector" {}))
        (validate-argv (if started? (conj argv token) argv))))))

(defn command
  "Resolve the settings argv first (:editor-command), then CSB_EDITOR_ARGS,
   then VISUAL, EDITOR or vi. Never invokes a shell."
  ([environment] (command environment {}))
  ([environment {:keys [editor-command]}]
   (cond
     editor-command (validate-argv editor-command)
     :else (if-let [explicit (get environment "CSB_EDITOR_ARGS")]
             (validate-argv
              (try
                (with-open [reader (java.io.PushbackReader. (java.io.StringReader. explicit))]
                  (let [eof (Object.)
                        argv (edn/read {:eof eof} reader)]
                    (when-not (identical? eof (edn/read {:eof eof} reader))
                      (throw (ex-info "Trailing input" {})))
                    argv))
                (catch Exception _
                  (m/fail! :invalid-editor "CSB_EDITOR_ARGS must contain one EDN vector of string arguments" {}))))
             (let [selected (or (some #(when-not (str/blank? %) %)
                                      [(get environment "VISUAL") (get environment "EDITOR")]) "vi")]
               ;; Preserve the previous support for literal executable paths containing spaces.
               (if (.isFile (io/file selected))
                 (validate-argv [selected])
                 (split-command selected)))))))

(defn begin! [definition]
  (m/attempt
   #(let [temp (Files/createTempFile "csb-definition-" ".clj" (make-array FileAttribute 0))]
      (try
        (Files/write temp (.getBytes ^String (:source definition) StandardCharsets/UTF_8)
                     (make-array java.nio.file.OpenOption 0))
        {:temp temp :definition-id (:id definition) :revision (:revision definition)}
        (catch Exception e (Files/deleteIfExists temp) (throw e))))))

(defn read-draft [session]
  (m/attempt #(Files/readString ^Path (:temp session) StandardCharsets/UTF_8)))

(defn save! [system session]
  (let [draft (read-draft session)]
    (if (= :ok (:status draft))
      (service/save-definition! system (:definition-id session) (:value draft)
                                {:expected-revision (:revision session)})
      draft)))

(defn discard! [session]
  (m/attempt #(Files/deleteIfExists ^Path (:temp session))))

(defn launch!
  "Launch an argv vector, appending only the temporary path. No shell interpolation."
  [session argv]
  (m/attempt
   #(do
      (validate-argv argv)
      (let [process (.start (doto (ProcessBuilder. ^java.util.List
                                                  (conj argv (str (:temp session))))
                             (.inheritIO)))
            code (.waitFor process)]
        (when-not (zero? code)
          (m/fail! :editor "Editor exited unsuccessfully; draft retained" {:exit code :temp (str (:temp session))}))
        {:temp (str (:temp session))}))))
