(ns csb.service
  "Public headless facade. All operations return result data; no UI dependencies."
  (:require [clojure.string :as str]
            [csb.extensions :as ext]
            [csb.model :as m]
            [csb.source :as source]))

(defn open-project
  ([project] (open-project project {}))
  ([project options]
   (m/attempt
    #(let [store (source/open-store project options)
           snapshot (m/scan-project store)
           registry (ext/registry)]
       (when (:example-extension? options)
         (let [r (ext/register! registry ext/example)]
           (when (= :error (:status r))
             (m/fail! :extension-invalid "Cannot register example extension" (:error r)))))
       {:store store :snapshot (atom snapshot) :extensions registry
        :events (atom []) :operation-lock (Object.)}))))

(defn refresh! [system]
  (let [lock (:operation-lock system)]
    (m/attempt #(locking lock
                 (let [snapshot (m/scan-project (:store system))]
                   (reset! (:snapshot system) snapshot)
                   snapshot)))))

(defn- source-world! [options]
  (when-not (= :source (get options :world :source))
    (m/fail! :unsupported "This build supports Source World only" {})))

(defn list-namespaces
  ([system] (list-namespaces system {}))
  ([system options]
   (m/attempt #(do (source-world! options)
                  (:namespaces @(:snapshot system))))))

(defn list-definitions
  ([system ns-sym] (list-definitions system ns-sym {}))
  ([system ns-sym options]
   (m/attempt #(do (source-world! options)
                  (->> (:definitions @(:snapshot system))
                       (filter (fn [d] (= ns-sym (:namespace d))))
                       (sort-by (juxt (comp str :name) :file (comp :row :location))) vec)))))

(defn list-tests [system]
  (m/attempt
   #(->> (:definitions @(:snapshot system))
         (filter (fn [{:keys [kind file]}]
                   (and (= kind :test) (or (str/ends-with? file ".clj")
                                         (str/ends-with? file ".cljc")))))
         (sort-by (juxt (comp str :namespace) (comp str :name) :file (comp :row :location)))
         (group-by :namespace))))

(defn list-categories
  "Counted category facets plus a namespace summary: {:total :files :facets}.
   Facets appear only for kinds with at least one definition of the namespace,
   sorted by name; files counts the distinct definition files."
  ([system ns-sym] (list-categories system ns-sym {}))
  ([system ns-sym options]
   (m/attempt #(do (source-world! options)
                  (let [defs (->> (:definitions @(:snapshot system))
                                  (filter (fn [d] (= ns-sym (:namespace d)))))]
                    {:total (count defs)
                     :files (count (distinct (map :file defs)))
                     :facets (->> (frequencies (map :category defs))
                                  (map (fn [[category count]] {:category category :count count}))
                                  (sort-by (comp str :category)) vec)})))))

(defn search
  "Case-insensitive substring search over indexed namespace and definition names.
   Returns {:query :namespaces [symbols] :definitions [maps]}. Definitions match
   on the qualified \\\"namespace/name\\\" string, so any name substring also matches;
   each result carries identity, kind, category and location only. Blank queries
   return empty results."
  ([system query] (search system query {}))
  ([system query options]
   (m/attempt
    (fn []
      (source-world! options)
      (let [q (str/lower-case (str query))]
        (if (str/blank? q)
          {:query query :namespaces [] :definitions []}
          (let [{:keys [namespaces definitions]} @(:snapshot system)]
            {:query query
             :namespaces (filterv #(str/includes? (str/lower-case (str %)) q) namespaces)
             :definitions (->> definitions
                               (filterv #(str/includes?
                                          (str/lower-case (str (some-> (:namespace %) str) "/" (:name %))) q))
                               (sort-by (juxt (comp str :category) (comp str :namespace)
                                              (comp str :name) :file (comp :row :location)))
                               (mapv #(assoc (select-keys % [:id :namespace :name :kind :category
                                                              :file :location])
                                             :result/type :definition))
                               vec)})))))))

(defn list-requires
  "Return the indexed require declarations for `ns-sym`, grouped by source file.

   Each entry contains `:namespace`, `:file` and `:declarations` in source order.
   The declaration text preserves libspecs and reader conditionals."
  [system ns-sym]
  (m/attempt #(get-in @(:snapshot system) [:requires ns-sym] [])))

(defn list-source-forms
  "All significant top-level forms per file of a namespace, in real file order.
   Includes forms that are not definitions (ns, declare, comment, discarded).
   Structural data only; the project source is never evaluated."
  ([system ns-sym] (list-source-forms system ns-sym {}))
  ([system ns-sym options]
   (m/attempt #(do (source-world! options)
                  (->> (:source-forms @(:snapshot system))
                       (filter (fn [f] (= ns-sym (:namespace f))))
                       (sort-by :file) vec)))))

(defn get-definition-source [system id]
  (let [lock (:operation-lock system)]
    (m/attempt #(locking lock (m/definition-source (:store system) id)))))

(defn- qualify-symbol [{:keys [aliases refers] home :namespace} sym]
  (let [target (if-let [prefix (some-> sym namespace symbol)]
                 (get aliases prefix prefix)
                 (get refers sym home))]
    (symbol (some-> target str) (name sym))))

(defn- definition-symbol [{:keys [namespace name]}]
  (symbol (some-> namespace str) (clojure.core/name name)))

(defn lookup-symbol
  "Find `:definition`, `:usages` or `:tests` for `sym` relative to the indexed `context-id`.
   A nil `sym` targets the selected definition. Usages are structural matches in indexed forms."
  [system context-id sym action]
  (m/attempt
   (fn []
     (let [{:keys [definitions symbol-contexts]} @(:snapshot system)
           context (some #(when (= context-id (:id %)) %) definitions)]
       (when-not context
         (m/fail! :not-found "Definition no longer exists; reload the project" {:id context-id}))
       (when-not (and (contains? #{:definition :usages :tests} action) (or (nil? sym) (symbol? sym)))
         (m/fail! :unsupported "Expected a symbol and a source lookup action" {:action action}))
       (let [target (if sym (qualify-symbol (symbol-contexts (:file context)) sym)
                        (definition-symbol context))
             usage? (fn [d]
                      (let [bindings (symbol-contexts (:file d))]
                        (and bindings (str/includes? (:source d) (name target))
                             (some #(= target (qualify-symbol bindings %)) (source/definition-symbols d)))))
             matches? (case action
                        :definition #(and (not (#{:method :extension} (:kind %)))
                                          (= target (definition-symbol %)))
                        :usages usage?
                        :tests #(and (= :test (:kind %)) (usage? %)))]
         {:symbol target
          :matches (->> definitions (filter matches?)
                        (sort-by (juxt (comp str :namespace) (comp str :name) :file (comp :row :location)))
                        (mapv #(select-keys % [:id :namespace :name :file :location])))})))))

(defn save-definition! [system id new-source options]
  (let [lock (:operation-lock system)]
    (m/attempt
   #(locking lock
      (let [result (m/save-source! (:store system) id new-source options)]
        (when-not (:reindex-error result)
          (reset! (:snapshot system) (:snapshot result)))
        (swap! (:events system)
               (fn [events]
                 (vec (take-last 100 (conj events {:type :source/saved :change (:change result)})))))
        (dissoc result :snapshot))))))

(defn diagnostics [system] (:diagnostics @(:snapshot system)))

(defn project-info [system]
  (select-keys @(:snapshot system) [:root :roots :namespaces :diagnostics]))

(defn contributions [system kind] (ext/contributions (:extensions system) kind))

(defn invoke-extension [system kind id args]
  (ext/invoke (:extensions system) kind id
              {:capabilities #{:source/read}
               :definitions #(-> system :snapshot deref :definitions)} args))
