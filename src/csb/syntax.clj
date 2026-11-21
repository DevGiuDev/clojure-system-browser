(ns csb.syntax
  "Lexical colours for display only. Never reads, resolves or evaluates source."
  (:require [clojure.string :as str]))

(def ^:private special-forms
  #{"ns" "in-ns" "def" "defonce" "defn" "defn-" "defmacro" "defmulti"
    "defmethod" "defprotocol" "defrecord" "deftype" "declare" "extend-type"
    "extend-protocol" "reify" "proxy" "fn" "fn*" "let" "let*" "letfn"
    "loop" "loop*" "recur" "if" "if-not" "if-let" "if-some" "when"
    "when-not" "when-let" "when-some" "cond" "condp" "case" "do" "doseq"
    "for" "dotimes" "binding" "with-open" "try" "catch" "finally" "throw"
    "new" "set!" "quote" "var" "monitor-enter" "monitor-exit"})

;; Order matters: strings and characters consume embedded reader punctuation.
;; The last alternatives cover whitespace and unfinished/unknown input as well.
(def ^:private token-pattern
  #";[^\r\n]*|#?\"(?:\\[\s\S]|[^\"\\])*+(?:\"|\\?\z)|\\(?:[^\s,\[\]{}()\";]+|[\s\S])?|[\[\]{}()]|~@|#\?@?|#[_'=]|['`~@^]|[^\s,\[\]{}()\";\\'`~@^]+|[\s,]+|[\s\S]")

(def ^:private number-pattern
  #"[+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+[rR][0-9a-zA-Z]+|[0-9]+/[0-9]+|(?:[0-9]+(?:\.[0-9]*)?)(?:[eE][+-]?[0-9]+)?)[MN]?")

(defn- kind [token]
  (cond
    (str/starts-with? token ";") :comment
    (or (str/starts-with? token "\"") (str/starts-with? token "#\"")) :string
    (str/starts-with? token "\\") :character
    (str/starts-with? token ":") :keyword
    (or (#{"nil" "true" "false"} token)
        (#{"##NaN" "##Inf" "##-Inf"} token)) :literal
    (re-matches number-pattern token) :number
    (special-forms token) :special
    (re-matches #"[\[\]{}()]" token) :delimiter
    (or (str/starts-with? token "#") (re-matches #"['`~@^]+" token)) :reader
    :else :plain))

(defn spans
  "Contiguous UTF-16 ranges covering all text, including incomplete forms."
  [text]
  (let [matcher (re-matcher token-pattern text)]
    (loop [result []]
      (if (.find matcher)
        (recur (conj result {:start (.start matcher) :end (.end matcher)
                            :kind (kind (.group matcher))}))
        result))))
