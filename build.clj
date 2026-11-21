(ns build
  "Release build tasks (ADR 012): one uberjar of the TUI/CLI. Desktop is not
   packaged here — JavaFX 27 natives are platform-specific and jpackage is out
   of scope; the Desktop keeps the documented repo workflow."
  (:require [clojure.tools.build.api :as b]))

(def ^:private main 'csb.tui)
(def ^:private class-dir "target/classes")
(def ^:private basis (b/create-basis {:project "deps.edn" :aliases [:tui]}))

(defn- read-version []
  (or (second (re-find #"\(def version \"([^\"]+)\"\)" (slurp "src/csb/cli.clj")))
      (throw (ex-info "Cannot read the version from src/csb/cli.clj" {}))))

(defn version
  "Print the release version, read from src/csb/cli.clj without evaluating it."
  [_]
  (println (read-version)))

(defn uber
  "Build target/clojure-system-browser-<version>.jar with csb.tui as main.
   Only the core sources and the TUI enter the jar; no JavaFX or cljfx."
  [_]
  (let [v (read-version)]
    (b/copy-dir {:src-dirs ["src" "apps/tui/src"] :target-dir class-dir})
    (b/compile-clj {:basis basis :class-dir class-dir :ns-compile [main]})
    (b/uber {:basis basis :class-dir class-dir :main main
             :uber-file (str "target/clojure-system-browser-" v ".jar")})))
