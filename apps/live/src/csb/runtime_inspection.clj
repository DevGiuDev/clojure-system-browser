(ns csb.runtime-inspection
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [csb.model :as model]
            [csb.service :as service])
  (:import [java.io PushbackReader StringReader]))

(defn request-code [[ns-name var-name]]
  (cond
    var-name
    (format
     "(if-let [ns (find-ns (symbol %s))]
        (if-let [v (get (ns-interns ns) (symbol %s))]
          (let [m (meta v) args (:arglists m)
                bounded (fn [s] (subs s 0 (min (count s) 4096)))
                plain-arity? (fn [arity]
                               (and (vector? arity) (<= (count arity) 32)
                                    (every? (fn [arg] (and (symbol? arg) (<= (count (str arg)) 128)))
                                            arity)))]
            (cond-> {:private (boolean (:private m)) :macro (boolean (:macro m))}
              (string? (:doc m)) (assoc :doc (bounded (:doc m)))
              (string? (:file m)) (assoc :file (bounded (:file m)))
              (integer? (:line m)) (assoc :line (:line m))
              (integer? (:column m)) (assoc :column (:column m))
              (and (or (list? args) (vector? args)) (<= (count args) 32)
                   (every? plain-arity? args))
              (assoc :arglists (bounded (binding [*print-length* 32 *print-level* 8 *print-meta* false]
                                          (pr-str args))))))
          :missing)
        :missing)"
     (pr-str ns-name) (pr-str var-name))

    ns-name
    (format
     "(if-let [ns (find-ns (symbol %s))]
        (mapv (fn [[sym v]] [(str sym) (boolean (:private (meta v)))]) (ns-interns ns))
        :missing)"
     (pr-str ns-name))

    :else
    "(mapv (comp str ns-name) (all-ns))"))

(defn- var-entry? [entry]
  (and (vector? entry) (= 2 (count entry))
       (string? (first entry)) (boolean? (second entry))))

(defn- metadata? [value]
  (and (map? value) (boolean? (:private value)) (boolean? (:macro value))
       (every? (fn [[k v]]
                 (case k
                   (:private :macro) (boolean? v)
                   (:doc :file :arglists) (string? v)
                   (:line :column) (integer? v)
                   false))
               value)))

(defn read-observation [target text]
  (let [reader (PushbackReader. (StringReader. (or text "")))
        eof (Object.)
        opts {:eof eof}
        value (edn/read opts reader)]
    (if (and (case (count target)
               0 (and (vector? value) (every? string? value))
               1 (or (= :missing value) (and (vector? value) (every? var-entry? value)))
               2 (or (= :missing value) (metadata? value)))
             (identical? eof (edn/read opts reader)))
      value
      (throw (ex-info "Unexpected data from nREPL inspection" {})))))

(defn- presence [known? source? runtime? ambiguous?]
  (cond
    (not known?) :unknown
    (and source? runtime?) (if ambiguous? :unknown :present-in-both)
    source? :source-only
    :else :runtime-only))

(def ^:private var-kinds #{:function :macro :value :protocol :multimethod :test})

(defn- source-definitions [system ns-name]
  (:value (service/list-definitions system (symbol ns-name))))

(defn- clear-var-match? [definitions]
  (let [{:keys [kind file name]} (first definitions)]
    (and (= 1 (count definitions)) (var-kinds kind)
         (nil? (namespace name)) (str/ends-with? file ".clj"))))

(defn rows [{:keys [system repl]}]
  (let [{:keys [target perspective observation]} (:inspection repl)
        combined? (= :combined perspective)
        known? (and system (some? observation))]
    (case (count target)
      0 (let [source (if (and combined? system)
                       (into #{} (map str) (:value (service/list-namespaces system)))
                       #{})
              runtime (set observation)]
          (for [name (sort (into source runtime))]
            {:name name :presence (when combined?
                                     (presence known? (source name) (runtime name) false))}))
      1 (let [source (if (and combined? system)
                       (group-by (comp str :name) (source-definitions system (first target)))
                       {})
              runtime (if (vector? observation) (into {} observation) {})]
          (for [name (sort (into (set (keys source)) (keys runtime)))
                :let [definitions (get source name)]]
            {:name name :private? (get runtime name)
             :presence (when combined?
                         (presence known? (seq definitions) (contains? runtime name)
                                   (not (clear-var-match? definitions))))}))
      [])))

(defn label [{:keys [name private? presence]}]
  (str name
       (case private? true " · private" false " · public" "")
       (when presence
         (str " · " (case presence
                      :present-in-both "Present in both"
                      :source-only "Indexed source only"
                      :runtime-only "Runtime only"
                      :unknown "Unknown")))))

(defn details [{:keys [system repl]}]
  (let [{:keys [target perspective observation]} (:inspection repl)
        [ns-name var-name] target]
    (when var-name
      (let [{:keys [private macro arglists doc file line column]} observation
            definitions (when (and system (= :combined perspective))
                          (filter #(= var-name (str (:name %))) (source-definitions system ns-name)))]
        (str/join "\n"
                  (concat
                   [(str ns-name "/" var-name)]
                   (when (map? observation)
                     [(str "Runtime var · " (if private "private" "public") (when macro " macro"))])
                   (when file [(str "Runtime location · " file (when line (str ":" line))
                                    (when (and line column) (str ":" column)))])
                   (when arglists [arglists])
                   (when doc ["" doc])
                   (for [definition definitions]
                     (str "Indexed source · " (:file definition) ":" (get-in definition [:location :row])))))))))

(defn heading [{:keys [target perspective]}]
  (let [[ns-name var-name] target]
    (str (if (= :combined perspective) "Combined" "Live") " · "
         (cond var-name "Var metadata" ns-name "Vars" :else "Namespaces")
         (when ns-name (str " · " ns-name)))))

(defn status [{:keys [active phase inspection status]}]
  (let [{:keys [observation error target]} inspection]
    (cond
      (not= :connected phase) (or status "Connect to nREPL to inspect the runtime")
      (:inspection-target active) "Inspecting runtime…"
      error (:message error)
      (= :missing observation) (if (= 1 (count target)) "Namespace is not loaded" "Runtime var is absent")
      (= :combined (:perspective inspection)) "Indexed source and runtime observations · presence by name"
      :else "Runtime observations · Refresh to update")))

(defn failure [status]
  (cond
    (some status ["forbidden" "unauthorized" "permission-denied"])
    (:error (model/error :forbidden "The server denied runtime inspection"))
    (some status ["unknown-op" "unknown-code-type" "namespace-not-found"])
    (:error (model/error :unsupported "Runtime inspection is unavailable on this server"))
    (status "nrepl.middleware.print/truncated")
    (:error (model/error :truncated "Runtime inspection exceeded the response limit"))
    (some status ["eval-error" "server-error" "error"])
    (:error (model/error :inspection "Runtime inspection failed"))))
