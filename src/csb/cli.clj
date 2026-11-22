(ns csb.cli
  (:require [csb.editor :as editor]
            [csb.service :as service]))

;; Application version, kept in sync with CHANGELOG.md (ADR 011).
(def version "0.3.1")

(defn arguments [args]
  (loop [args args options {:project "."}]
    (if (empty? args) options
        (case (first args)
          "--project" (if (second args) (recur (nnext args) (assoc options :project (second args)))
                          (throw (ex-info "--project needs a directory" {})))
          "--example-extension" (recur (next args) (assoc options :example-extension? true))
          "--source-root" (if (second args)
                            (recur (nnext args) (update options :roots (fnil conj []) (second args)))
                            (throw (ex-info "--source-root needs a relative directory" {})))
          "--help" (assoc options :help? true)
          (throw (ex-info (str "Unknown argument: " (first args)) {}))))))

(defn editor-argv []
  (editor/command (System/getenv)))

(defn -main [& args]
  (try
    (let [{:keys [project help?] :as options} (arguments args)]
      (if help?
        (println (str "clojure-system-browser " version
                      "\nclojure -M:cli --project PATH [--source-root DIR ...] [--example-extension]"
                      "\nPrint Source World index as EDN. No project code is executed."))
        (let [result (service/open-project project options)]
          (if (= :ok (:status result))
            (let [system (:value result)]
              (prn {:status :ok :namespaces (:value (service/list-namespaces system))
                    :definitions (mapv #(dissoc % :source :range) (:definitions @(:snapshot system)))
                    :diagnostics (service/diagnostics system)}))
            (do (prn result) (System/exit 1))))))
    (catch Exception e
      (binding [*out* *err*] (println (ex-message e)))
      (System/exit 1))))
