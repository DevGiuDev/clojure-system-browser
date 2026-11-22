(ns csb.source
  "Source-only adapter. Never requires or evaluates the browsed project."
  (:require [clojure.edn :as edn]
            [clojure.core.specs.alpha :as core-specs]
            [clojure.spec.alpha :as spec]
            [clojure.string :as str]
            [csb.model :as m]
            [csb.test-config :as test-config]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p])
  (:import [java.nio ByteBuffer CharBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException Files LinkOption Path Paths
            FileVisitResult SimpleFileVisitor StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFileAttributeView]
           [java.security MessageDigest]))

(def ^:private no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private empty-attrs (make-array FileAttribute 0))
(def ^:private ignored-dirs #{".git" ".cache" ".cpcache" ".clj-kondo" ".lsp"
                             "target" "node_modules" ".idea"})
(def ^:private writable-heads #{'defn 'defn- 'clojure.core/defn 'clojure.core/defn-})
(def ^:private known-heads
  {'defn :function 'defn- :function 'defmacro :macro 'def :value
   'defonce :value 'defprotocol :protocol 'defrecord :type 'deftype :type
   'defmulti :multimethod 'defmethod :method 'extend-type :extension
   'extend-protocol :extension 'deftest :test})

(defn path ^Path [x]
  (if (instance? Path x) x (Paths/get (str x) (make-array String 0))))

(defn revision [^bytes bytes]
  (str "sha256:" (.formatHex (java.util.HexFormat/of)
                            (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn- decode [^bytes bytes]
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap bytes)))
    (catch java.nio.charset.CharacterCodingException _
      (m/fail! :unsupported "Only valid UTF-8 source files are supported" {}))))

(defn- encode [^String text]
  (try
    (let [buffer (.encode (doto (.newEncoder StandardCharsets/UTF_8)
                            (.onMalformedInput CodingErrorAction/REPORT)
                            (.onUnmappableCharacter CodingErrorAction/REPORT))
                          (CharBuffer/wrap text))
          bytes (byte-array (.remaining buffer))]
      (.get buffer bytes)
      bytes)
    (catch java.nio.charset.CharacterCodingException _
      (m/fail! :parse "Draft contains invalid Unicode" {}))))

(defn- significant [node]
  (remove #(contains? #{:whitespace :newline :comment :comma} (n/tag %))
          (n/children node)))

(defn- leaf-symbol [node]
  (if (and node (= :meta (n/tag node)))
    (recur (last (significant node)))
    (when (and node (= :token (n/tag node)))
      (let [s (n/string node)]
        (try
          (let [v (edn/read-string {:readers {} :default (fn [_ _] nil)} s)]
            (when (symbol? v) v))
          (catch Exception _ nil))))))

(defn- parse [text]
  (try
    (p/parse-string-all text)
    (catch Exception e
      (m/fail! :parse (or (ex-message e) "Cannot parse source") {}))))

(defn- bare-node [node]
  (if (= :meta (n/tag node)) (recur (last (significant node))) node))

(defn- list-parts [node]
  (let [node (bare-node node)]
    (when (= :list (n/tag node)) (vec (significant node)))))

(defn- offset
  "Map rewrite-clj's 1-based row/column to original UTF-16 text, preserving CRLF."
  [^String text row col]
  (loop [i 0 r 1]
    (cond
      (= r row) (+ i (dec col))
      (>= i (.length text)) (m/fail! :parse "Invalid parser position" {})
      (= \return (.charAt text i))
      (recur (+ i (if (and (< (inc i) (.length text))
                                (= \newline (.charAt text (inc i)))) 2 1)) (inc r))
      (= \newline (.charAt text i)) (recur (inc i) (inc r))
      :else (recur (inc i) r))))

(defn- node-range [text node]
  (let [{:keys [row col end-row end-col]} (meta node)]
    {:start (offset text row col) :end (offset text end-row end-col)
     :row row :col col :end-row end-row :end-col end-col}))

(defn- read-safe-forms!
  "Reader validation without evaluation, custom data readers or namespace loading.
   Unsupported tagged literals and alias-dependent forms fail conservatively."
  [text namespace-symbol aliases]
  (binding [*read-eval* false
            *data-readers* {}
            *default-data-reader-fn* (fn [tag _]
                                      (m/fail! :unsupported "Tagged literals are read-only"
                                               {:tag (str tag)}))
            *reader-resolver*
            (reify clojure.lang.LispReader$Resolver
              (currentNS [_] (or namespace-symbol 'user))
              (resolveClass [_ sym] sym)
              (resolveAlias [_ sym] (get aliases sym))
              (resolveVar [_ sym] sym))]
    (try
      (let [reader (java.io.PushbackReader. (java.io.StringReader. text))
            eof (Object.)]
        (loop [forms []]
          (let [form (read {:eof eof} reader)]
            (if (identical? eof form) forms (recur (conj forms form))))))
      (catch clojure.lang.ExceptionInfo e (throw e))
      (catch Exception e (m/fail! :parse (ex-message e) {})))))

(defn- function-shape! [form]
  (when-not (and (contains? writable-heads (first form))
                (spec/valid? ::core-specs/defn-args (rest form)))
    (m/fail! :parse "Expected one defn/defn- with a name and parameter vector" {})))

(defn- reader-children [node]
  (remove #(= :uneval (n/tag %)) (significant node)))

(defn- requires-declaration? [node]
  (let [node (bare-node node)]
    (case (n/tag node)
      :list (contains? #{":require" ":require-macros"} (some-> node reader-children first n/string))
      :reader-macro
      (let [[marker branches] (reader-children node)
            marker (some-> marker n/string)]
        (when (and (#{"?" "?@"} marker) branches (= :list (n/tag branches)))
          (some (fn [[_ branch]]
                  (if (= "?@" marker)
                    (when (#{:list :vector} (n/tag branch))
                      (some requires-declaration? (reader-children branch)))
                    (requires-declaration? branch)))
                (partition 2 (reader-children branches)))))
      false)))

(defn- requires-declarations [body ns-node]
  (into [] (comp (filter requires-declaration?)
                 (map (fn [node]
                        (let [{:keys [start end]} (node-range body node)]
                          (subs body start end)))))
        (drop 2 (when ns-node (reader-children (bare-node ns-node))))))

(defn- namespace-libspecs [ns-node]
  (for [clause (drop 2 (when ns-node (list-parts ns-node)))
        :let [parts (list-parts clause)]
        :when (= ":require" (some-> parts first n/string))
        libspec (rest parts)
        :when (= :vector (n/tag libspec))
        :let [[target & options] (reader-children libspec)
              target (leaf-symbol target)]
        :when target]
    [target (map (fn [[key value]] [(n/string key) value]) (partition 2 options))]))

(defn- lazytest-refer [libspecs]
  (some (fn [[target options]]
          (when (= 'lazytest.core target)
            (let [options (into {} options)
                  refer (get options ":refer")
                  rename (get options ":rename")
                  mentions-macro? (fn [node]
                                    (and (#{:vector :list :set} (some-> node n/tag))
                                         (some #{'defdescribe} (map leaf-symbol (reader-children node)))))]
              (when (and (not (mentions-macro? (get options ":exclude")))
                         (or (= ":all" (some-> refer n/string)) (mentions-macro? refer)))
                (or (when (= :map (some-> rename n/tag))
                      (some (fn [[original local]]
                              (when (= 'defdescribe (leaf-symbol original))
                                (leaf-symbol local)))
                            (partition 2 (reader-children rename))))
                    'defdescribe)))))
        libspecs))

(defn- namespace-bindings [ns-node]
  (let [libspecs (namespace-libspecs ns-node)]
    (reduce (fn [bindings [target option value]]
              (case option
                ":as" (if-let [alias (leaf-symbol value)]
                        (-> bindings (assoc-in [:aliases alias] target)
                            (assoc-in [:reader-aliases alias] target))
                        bindings)
                ":as-alias" (if-let [alias (leaf-symbol value)]
                              (assoc-in bindings [:reader-aliases alias] target)
                              bindings)
                ":refer" (if (= :vector (n/tag value))
                           (update bindings :refers into
                                   (for [node (reader-children value) :let [sym (leaf-symbol node)] :when sym]
                                     [sym target]))
                           bindings)
                bindings))
            {:aliases {} :reader-aliases {} :refers {} :lazytest-refer (lazytest-refer libspecs)}
            (for [[target options] libspecs [option value] options]
              [target option value]))))

(defn- lazytest-head? [{:keys [aliases lazytest-refer]} head]
  (when head
    (if-let [prefix (some-> head namespace symbol)]
      (and (= "defdescribe" (name head)) (= 'lazytest.core (get aliases prefix prefix)))
      (= head lazytest-refer))))

(defn- symbol-children [node]
  (case (n/tag node)
    (:uneval :quote :syntax-quote :eval) nil
    :meta [(bare-node node)]
    :list (let [parts (significant node)]
            (when-not (#{'quote 'comment 'clojure.core/comment} (leaf-symbol (first parts)))
              parts))
    (when (n/inner? node) (significant node))))

(defn qualify-symbol
  "Qualify `sym` using the indexed namespace, aliases and explicit refers for a file."
  [{:keys [aliases refers] home :namespace} sym]
  (let [target (if-let [prefix (some-> sym namespace symbol)]
                 (get aliases prefix prefix)
                 (get refers sym home))]
    (symbol (some-> target str) (name sym))))

(defn- definition-usages [context node kind]
  (let [parts (drop (if (= :method kind) 1 2) (list-parts node))
        tokens (mapcat #(tree-seq n/inner? symbol-children %) parts)]
    (reduce (fn [usages token]
              (if-let [sym (when (= :token (n/tag token)) (leaf-symbol token))]
                (let [target (qualify-symbol context sym)]
                  (if (contains? usages target)
                    (update-in usages [target :usage-count] inc)
                    (assoc usages target {:usage-location (select-keys (meta token) [:row :col])
                                          :usage-count 1})))
                usages))
            {} tokens)))

(defn- private-map? [node]
  (and node (= :map (n/tag node))
       (boolean (some (fn [[k v]] (and (= ":private" (n/string k)) (= "true" (n/string v))))
                      (partition 2 (significant node))))))

(defn- private-meta? [node]
  (when (and node (= :meta (n/tag node)))
    (let [[metadata target] (significant node)]
      (or (= ":private" (n/string metadata)) (private-map? metadata) (private-meta? target)))))

(defn- private-definition? [head parts]
  (let [tail (drop 2 parts)
        tail (if (some-> tail first n/string (str/starts-with? "\"")) (rest tail) tail)]
    (boolean (or (= head 'defn-) (private-meta? (second parts)) (private-map? (first tail))))))

(defn- preview-line [text start]
  (let [stop (or (str/index-of text "\n" start) (count text))
        stop (if (and (pos? stop) (= \return (.charAt text (dec stop)))) (dec stop) stop)]
    (subs text start stop)))

(defn- form-label [node head simple-head kind]
  (cond
    (= :uneval (n/tag node)) "#_"
    (= :eval (n/tag node)) "#="
    kind (str simple-head)
    (= head 'ns) "ns"
    (= head 'comment) "comment"
    (= head 'declare) "declare"
    head (str head)
    :else "form"))

(defn- form-entry
  "Structural description of one significant top-level form, in file order.
   Comments and blank lines are not significant, so the ordinal matches the
   definition ids of the same revision. Never evaluates or resolves anything."
  [body bindings ordinal node definition-id]
  (let [parts (list-parts node)
        head (some-> parts first leaf-symbol)
        simple-head (when head (symbol (name head)))
        kind (if (lazytest-head? bindings head) :test (get known-heads simple-head))
        {:keys [row col start]} (node-range body node)]
    {:ordinal ordinal :row row :col col
     :head head :name (some-> parts second leaf-symbol)
     :label (form-label node head simple-head kind) :kind kind
     :definition-id definition-id
     :discarded? (= :uneval (n/tag node))
     :preview (preview-line body start)}))

(defn document
  "Index supported top-level constructs. Nested and discarded forms are never writable.

   | Option | Description |
   | --- | --- |
   | `:index-usages?` | Collect structural references for the project scan. |"
  [file ^bytes bytes & [{:keys [index-usages?]}]]
  (let [text (decode bytes)
        bom? (str/starts-with? text "\uFEFF")
        body (if bom? (subs text 1) text)
        tree (parse body)
        nodes (vec (significant tree))
        ns-nodes (filter #(= 'ns (some-> (list-parts %) first leaf-symbol)) nodes)
        ns-sym (when (= 1 (count ns-nodes))
                 (some-> ns-nodes first list-parts second leaf-symbol))
        ns-node (when ns-sym (first ns-nodes))
        bindings (namespace-bindings ns-node)
        symbol-context (assoc (select-keys bindings [:aliases :refers]) :namespace ns-sym)
        editable-document? (and (= 1 (count ns-nodes)) ns-sym)
        rev (revision bytes)
        definitions
        (keep-indexed
         (fn [ordinal node]
           (let [parts (list-parts node)
                 head (some-> parts first leaf-symbol)
                 simple-head (when head (symbol (name head)))
                 lazytest? (lazytest-head? bindings head)
                 kind (if lazytest? :test (get known-heads simple-head))
                 sym (some-> parts second leaf-symbol)]
             (when (and kind sym)
               (let [r (node-range body node)
                     start (+ (:start r) (if bom? 1 0))
                     end (+ (:end r) (if bom? 1 0))
                     source (subs text start end)
                     private? (private-definition? (if lazytest? 'defdescribe simple-head) parts)
                     writable? (and (not lazytest?) editable-document? (str/ends-with? (str file) ".clj")
                                    (contains? writable-heads head))]
                 (cond-> {:id [(str file) ordinal] :file (str file) :namespace ns-sym
                          :name sym :kind kind :private? private?
                          :category (keyword (str (if private? "private-" "") (name kind) "s"))
                          :location (select-keys r [:row :col :end-row :end-col])
                          :range {:start start :end end} :source source :revision rev
                          :writable? (boolean writable?)
                          :read-only-reason (when-not writable?
                                              (if (str/ends-with? (str file) ".cljd")
                                                "ClojureDart (.cljd) is read-only; Save supports only top-level defn/defn- in .clj"
                                                "Only unambiguous top-level defn/defn- in .clj are editable"))}
                   lazytest? (assoc :test-framework :lazytest))))))
         nodes)
        counts (frequencies (map (juxt :namespace :name) definitions))
        def-ordinals (set (map (comp second :id) definitions))
        forms (keep-indexed
               (fn [ordinal node]
                 (form-entry body bindings ordinal node
                             (when (contains? def-ordinals ordinal) [(str file) ordinal])))
               nodes)]
    {:file (str file) :namespace ns-sym :aliases (:reader-aliases bindings)
     :symbol-context symbol-context
     :symbol-usages (when (and index-usages? ns-sym)
                      (into []
                            (for [{:keys [id kind]} definitions
                                  [sym usage] (definition-usages symbol-context (nodes (second id)) kind)]
                              [sym (assoc usage :id id)])))
     :requires (requires-declarations body ns-node)
     :revision rev :text text :bytes bytes
     :error (when-not ns-sym
              {:type :unsupported :message "File must declare exactly one identifiable top-level namespace"
               :details {:file (str file)}})
     :definitions
     (mapv #(if (> (get counts [(:namespace %) (:name %)]) 1)
              (assoc % :writable? false :read-only-reason "Duplicate definition name in file") %)
           definitions)
     :forms (vec forms)}))

(defn- check-file! [^Path file]
  (when-not (Files/isRegularFile file no-links)
    (m/fail! :unsupported "Source must be a regular file, not a symbolic link" {:file (str file)})))

(defn- replace-atomically! [^Path file ^bytes bytes expected-revision]
  (check-file! file)
  (let [tmp (Files/createTempFile (.getParent file) ".csb-" ".tmp" empty-attrs)]
    (try
      (when (Files/getFileAttributeView file PosixFileAttributeView no-links)
        (Files/setPosixFilePermissions tmp (Files/getPosixFilePermissions file no-links)))
      (Files/write tmp bytes (into-array StandardOpenOption [StandardOpenOption/TRUNCATE_EXISTING]))
      ;; Narrow the external-writer race. This is NOT filesystem compare-and-swap.
      (check-file! file)
      (when-not (= expected-revision (revision (Files/readAllBytes file)))
        (m/fail! :conflict "File changed outside the browser; reload before saving" {:file (str file)}))
      (try
        (Files/move tmp file (into-array StandardCopyOption
                                        [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (m/fail! :atomic-move-unsupported "Filesystem does not support atomic replacement; file left unchanged"
                   {:file (str file)})))
      (finally (Files/deleteIfExists tmp)))))

(defn- subdirectories [^Path dir]
  (with-open [stream (Files/newDirectoryStream dir)]
    (->> (iterator-seq (.iterator stream))
         (filter #(Files/isDirectory ^Path % no-links))
         (sort-by str)
         vec)))

(defn- existing-roots [^Path dir subs]
  (->> subs
       (map #(.resolve ^Path dir (str %)))
       (filter #(Files/isDirectory % no-links))
       (mapv str)))

(defn- polylith? [^Path root]
  (or (Files/isRegularFile (.resolve root "workspace.edn") no-links)
      (Files/isRegularFile (.resolve root ".polylith/workspace.edn") no-links)
      (and (Files/isDirectory (.resolve root "components") no-links)
           (Files/isDirectory (.resolve root "bases") no-links))))

(defn- polylith-roots
  "Conventional Polylith layout, derived from the filesystem only and never from
   executing the workspace: every brick under components/ and bases/ contributes
   its src and test, development contributes src and test, and a project
   contributes its rare src. Explicit roots and deps.edn :paths take precedence."
  [^Path root]
  (let [brick-family (fn [^Path dir subs]
                       (when (Files/isDirectory dir no-links)
                         (mapcat #(existing-roots % subs) (subdirectories dir))))
        projects (.resolve root "projects")]
    (vec (concat
          (mapcat #(brick-family (.resolve root %) ["src" "test"]) ["components" "bases"])
          (existing-roots (.resolve root "development") ["src" "test"])
          (brick-family projects ["src"])))))

(defn- source-paths [^Path root roots]
  (let [deps-file (.resolve root "deps.edn")
        deps (when (and (nil? roots) (Files/isRegularFile deps-file no-links))
               (edn/read-string {:readers {} :default (fn [tag _]
                                                       (m/fail! :unsupported "Unsupported deps.edn tag" {:tag tag}))}
                                (decode (Files/readAllBytes deps-file))))
        configured (or roots (:paths deps))
        base (cond
               (seq configured) configured
               (polylith? root) (polylith-roots root)
               :else ["src" "test"])]
    (when-not (and (sequential? base) (every? string? base))
      (m/fail! :invalid-project "Source roots must be a sequence of strings" {}))
    (->> (concat base (when (nil? roots) (test-config/test-paths root deps)))
         (map (fn [s]
                (let [p (.normalize (.resolve root ^String s))]
                  (when-not (.startsWith p root)
                    (m/fail! :unsupported "Source roots outside the project need a separate project" {:root s}))
                  p)))
         distinct vec)))

(defn- source-files [roots]
  (->> roots
       (mapcat (fn [^Path source-root]
                 (when (and (Files/isDirectory source-root no-links)
                            (= source-root (.toRealPath source-root (make-array LinkOption 0))))
                   (let [found (atom [])]
                     (Files/walkFileTree
                      source-root
                      (proxy [SimpleFileVisitor] []
                        (preVisitDirectory [dir _attrs]
                          (if (and (not= dir source-root) (ignored-dirs (str (.getFileName ^Path dir))))
                            FileVisitResult/SKIP_SUBTREE FileVisitResult/CONTINUE))
                        (visitFile [file _attrs]
                          (when (and (Files/isRegularFile ^Path file no-links)
                                     (or (str/ends-with? (str file) ".clj")
                                         (str/ends-with? (str file) ".cljc")
                                         (str/ends-with? (str file) ".cljd")))
                            (swap! found conj file))
                          FileVisitResult/CONTINUE)))
                     @found))))
       distinct (sort-by str) vec))

(defn- indexed-usages [documents definitions-by-id]
  (let [order (juxt (comp str :namespace) (comp str :name) :file (comp :row :location))]
    (-> (reduce (fn [usages [sym {:keys [id] :as usage}]]
                  (let [definition (definitions-by-id id)
                        entry (merge (select-keys definition [:namespace :name :file :location :kind]) usage)]
                    (update usages sym (fnil conj []) entry)))
                {} (mapcat :symbol-usages documents))
        (update-vals #(vec (sort-by order %))))))

(defrecord DiskSource [root roots index lock]
  m/SourceStore
  (scan-project [_]
    (locking lock
      (let [files (source-files roots)
            results (mapv (fn [^Path file]
                            (let [result (m/attempt #(document file (Files/readAllBytes file) {:index-usages? true}))]
                              (if (= :ok (:status result)) (:value result)
                                  {:file (str file) :error (:error result)}))) files)
            defs (vec (mapcat :definitions results))
            duplicates (frequencies (map (juxt :namespace :name) defs))
            defs (mapv #(if (> (get duplicates [(:namespace %) (:name %)]) 1)
                          (assoc % :writable? false :read-only-reason "Ambiguous definition across source roots") %) defs)
            definitions-by-id (into {} (map (juxt :id identity) defs))
            snapshot
            {:root (str root) :roots (mapv str roots) :definitions defs
             :index-version (random-uuid)
             :definitions-by-id definitions-by-id
             :definitions-by-name (group-by (juxt :namespace (comp name :name)) defs)
             :namespaces (->> results (keep :namespace) distinct (sort-by str) vec)
             :requires (->> results
                            (keep (fn [{:keys [file namespace requires]}]
                                    (when (seq requires)
                                      {:namespace namespace :file file :declarations requires})))
                            (group-by :namespace))
             :symbol-contexts (into {} (keep (fn [{:keys [file symbol-context]}]
                                               (when (:namespace symbol-context) [file symbol-context])))
                                    results)
             :symbol-usages (indexed-usages results definitions-by-id)
             :diagnostics (vec (keep #(when (:error %) (select-keys % [:file :error])) results))
             ;; Parse-failed results have no :forms and stay in :diagnostics.
             :source-forms (vec (keep #(when (contains? % :forms)
                                         (select-keys % [:file :namespace :forms]))
                                      results))}]
        (reset! index definitions-by-id)
        snapshot)))
  (definition-source [_ id]
    (or (get @index id) (m/fail! :not-found "Definition no longer exists; reload the project" {:id id})))
  (save-source! [this id new-source {:keys [expected-revision]}]
    (locking lock
      (let [base (m/definition-source this id)
            file (path (:file base))]
        (when-not (:writable? base)
          (m/fail! :unsupported (:read-only-reason base) {:id id}))
        (when-not (= expected-revision (:revision base))
          (m/fail! :conflict "Editor revision is stale or missing; reload before saving" {:id id}))
        (check-file! file)
        (let [current (Files/readAllBytes file)]
          (when-not (= expected-revision (revision current))
            (m/fail! :conflict "File changed outside the browser; reload before saving" {:file (str file)}))
          (let [doc (document file current)
                replacement-tree (parse new-source)
                nodes (vec (significant replacement-tree))
                _ (when-not (and (= 1 (count nodes)) (list-parts (first nodes)))
                    (m/fail! :parse "Replacement must contain exactly one definition" {}))
                forms (read-safe-forms! new-source (:namespace base) (:aliases doc))
                _ (when-not (= 1 (count forms)) (m/fail! :parse "Expected exactly one readable form" {}))
                form (first forms)
                _ (function-shape! form)
                _ (when-not (= (:name base) (second form))
                    (m/fail! :unsupported "Renaming definitions is not supported in this MVP" {}))
                original-head (some-> (:source base) parse significant first list-parts first leaf-symbol)
                _ (when-not (= original-head (first form))
                    (m/fail! :unsupported "Changing definition kind requires a separate operation" {}))
                {:keys [start end]} (:range base)
                text (:text doc)
                ;; Keep original prefix and suffix byte-for-byte, including BOM and CRLF.
                prefix (.getBytes (subs text 0 start) StandardCharsets/UTF_8)
                suffix (.getBytes (subs text end) StandardCharsets/UTF_8)
                output (byte-array (concat prefix (encode new-source) suffix))
                output-doc (document file output)
                _ (read-safe-forms! (str/replace-first (:text output-doc) #"^\uFEFF" "")
                                    (:namespace base) (:aliases doc))]
            (replace-atomically! file output expected-revision)
            (let [new-revision (revision output)]
              ;; A committed write must not be reported as failed if reindexing subsequently fails.
              (try
                (let [snapshot (m/scan-project this)]
                  {:definition (get @index id) :revision new-revision :written? true :snapshot snapshot
                   :change {:definition-id id :before expected-revision :after new-revision}})
                (catch Exception e
                  {:revision new-revision :written? true :reindex-error (ex-message e)
                   :change {:definition-id id :before expected-revision :after new-revision}})))))))))

(defn open-store [project {:keys [roots]}]
  (let [root (.toRealPath (.toAbsolutePath (path project)) (make-array LinkOption 0))]
    (when-not (Files/isDirectory root no-links)
      (m/fail! :invalid-project "Project must be a directory" {:project (str project)}))
    (->DiskSource root (source-paths root roots) (atom {}) (Object.))))
