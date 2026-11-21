(ns csb.tui-terminal
  "Local POSIX terminal settings; no changes to the user's shell configuration."
  (:require [clojure.java.io :as io])
  (:import [java.lang ProcessBuilder]))

(defn- stty! [arguments]
  (let [process (.start (doto (ProcessBuilder. ^java.util.List (into ["stty"] arguments))
                         (.redirectInput (io/file "/dev/tty"))
                         (.redirectErrorStream true)))
        output (slurp (.getInputStream process))
        code (.waitFor process)]
    (when-not (zero? code)
      (throw (ex-info "Could not configure the current terminal"
                      {:exit code :details output})))
    (.trim ^String output)))

(defn with-input-settings
  "Let Ctrl+S reach Lanterna instead of XOFF; restore the exact prior settings.
   Call before constructing the terminal, and close it inside f before returning."
  [f]
  (if (.startsWith (System/getProperty "os.name") "Windows")
    (f)
    (let [original (stty! ["-g"])]
      (try
        (stty! ["-ixon"])
        (f)
        (finally (stty! [original]))))))
