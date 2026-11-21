(ns csb.service
  "Public headless facade. All operations return result data; no UI dependencies."
  (:require [csb.extensions :as ext]
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
