(ns csb.desktop-launcher
  "Check Java before resolving any JavaFX classes. No GUI dependencies at load time.")

(defn check-runtime!
  ([] (check-runtime! (.feature (Runtime/version))))
  ([feature]
   (when (< feature 25)
     (throw (ex-info
             (str "Desktop necesita Java 25 o posterior; Clojure está usando Java " feature ".\n"
                  "Selecciona un JDK compatible antes de arrancar. Ejemplo en Linux/macOS:\n"
                  "  JAVA_CMD=/ruta/al/jdk-27/bin/java "
                  "clojure -Srepro -M:desktop --project examples/sample-project\n"
                  "Consulta README.md para configurar el JDK de tu instalación.")
             {:type :unsupported-java :required 25 :actual feature})))))

(defn -main [& args]
  (try
    (check-runtime!)
    (apply (requiring-resolve 'csb.desktop/-main) args)
    (catch clojure.lang.ExceptionInfo e
      (if (= :unsupported-java (:type (ex-data e)))
        (do (binding [*out* *err*] (println (ex-message e))) (System/exit 1))
        (throw e)))))
