(ns csb.test-config
  (:require [clojure.edn :as edn])
  (:import [java.nio.file Files LinkOption Path]))

(def ^:private no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn file? [^Path root filename]
  (Files/isRegularFile (.resolve root ^String filename) no-links))

(defn read-config [^Path root filename]
  (when (file? root filename)
    (try
      (edn/read-string {:readers {'kaocha/v1 identity}}
                       (Files/readString (.resolve root ^String filename)))
      (catch Exception _ nil))))

(defn test-aliases [deps]
  (let [aliases (:aliases deps)]
    (filterv #(map? (get aliases %)) [:test :tests :kaocha :lazytest])))

(defn- paths [values]
  (when (sequential? values) values))

(defn test-paths [^Path root deps]
  (let [aliases (:aliases deps)
        kaocha (read-config root "tests.edn")]
    (->> (concat
          (when (Files/isDirectory (.resolve root "test") no-links) ["test"])
          (mapcat (fn [id]
                    (let [opts (get aliases id)]
                      (concat (paths (:extra-paths opts)) (paths (:replace-paths opts)))))
                  (test-aliases deps))
          (mapcat #(paths (or (:test-paths %) (:kaocha/test-paths %)))
                  (paths (or (:tests kaocha) (:kaocha/tests kaocha)))))
         (filter string?)
         (filter (fn [p]
                   (try
                     (.startsWith (.normalize (.resolve root ^String p)) root)
                     (catch java.nio.file.InvalidPathException _ false))))
         distinct
         vec)))
