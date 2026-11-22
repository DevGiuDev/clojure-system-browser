(ns csb.editor-test
  (:require [clojure.test :refer [deftest is]]
            [csb.editor :as editor])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest editor-configuration-priority-and-empty-values
  (is (= ["vi"] (editor/command {})))
  (is (= ["vim"] (editor/command {"VISUAL" "  " "EDITOR" "vim"})))
  (is (= ["emacsclient" "-c"] (editor/command {"VISUAL" "emacsclient -c" "EDITOR" "vim -f"})))
  (is (= ["vim" "-f"] (editor/command {"CSB_EDITOR_ARGS" "[\"vim\" \"-f\"]"
                                       "VISUAL" "'invalid"}))))

(deftest settings-editor-command-takes-precedence-over-the-environment
  (is (= ["nano" "-w"] (editor/command {"VISUAL" "vim" "EDITOR" "vi"}
                                       {:editor-command ["nano" "-w"]})))
  (is (= ["emacsclient" "-c"] (editor/command {"CSB_EDITOR_ARGS" "[\"vim\" \"-f\"]"}
                                              {:editor-command ["emacsclient" "-c"]})))
  ;; unset in settings falls back to the environment chain
  (is (= ["vim"] (editor/command {"EDITOR" "vim"} {:editor-command nil})))
  (is (= ["vi"] (editor/command {} {:editor-command nil})))
  ;; invalid settings argv fails like any other source
  (is (thrown? clojure.lang.ExceptionInfo
               (editor/command {} {:editor-command ["nano" 5]})))
  (is (thrown? clojure.lang.ExceptionInfo
               (editor/command {} {:editor-command ["emacsclient" "-n"]}))))

(deftest editor-arguments-are-grouped-without-shell-expansion
  (doseq [[command expected]
          [["emacsclient -c -a \"\"" ["emacsclient" "-c" "-a" ""]]
           ["'editor with spaces' -f" ["editor with spaces" "-f"]]
           ["editor\\ with\\ spaces --title='hello world'" ["editor with spaces" "--title=hello world"]]
           ["\"C:\\Program Files\\Vim\\vim.exe\" -f" ["C:\\Program Files\\Vim\\vim.exe" "-f"]]
           ["vim \"a\\\"b\" 'a\\b'" ["vim" "a\"b" "a\\b"]]
           ["vim $HOME/*.clj ; echo $(whoami)" ["vim" "$HOME/*.clj" ";" "echo" "$(whoami)"]]]]
    (is (= expected (editor/command {"EDITOR" command}))))
  (is (= ["emacsclient" "-nw"] (editor/command {"EDITOR" "emacsclient -nw"}))
      "-nw requests a terminal frame; it is not the nonwaiting -n option"))

(deftest invalid-editor-configuration-is-an-actionable-error
  (doseq [environment [{"EDITOR" "vim 'unfinished"}
                       {"CSB_EDITOR_ARGS" "not-edn ["}
                       {"CSB_EDITOR_ARGS" "[]"}
                       {"CSB_EDITOR_ARGS" "[\"vim\"] [\"extra\"]"}
                       {"CSB_EDITOR_ARGS" "[\"vim\" 123]"}
                       {"EDITOR" (str "vim " \u0000)}
                       {"EDITOR" "emacsclient -n -c"}
                       {"CSB_EDITOR_ARGS" "[\"/usr/bin/emacsclient\" \"--no-wait\"]"}]]
    (let [error (try (editor/command environment) nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-editor (:type (ex-data error))))
      (is (seq (ex-message error))))))

(deftest a-literal-executable-path-with-spaces-remains-supported
  (let [file (Files/createTempFile "csb editor with spaces " ".exe" (make-array FileAttribute 0))]
    (try
      (is (= [(str file)] (editor/command {"EDITOR" (str file)})))
      (finally (Files/deleteIfExists file)))))
