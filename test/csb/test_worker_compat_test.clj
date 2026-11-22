(ns csb.test-worker-compat-test
  (:require [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]])
  (:import (java.io PushbackReader StringReader)))

;; The worker loads inside the project's own JVM, so its source must stay
;; within Clojure core available since 1.8 (see the Test Runner section of
;; README.md). The aliases here (str, test) must mirror the worker's, so its
;; namespaced symbols resolve.
(def ^:private minimum-version "1.8")
(def ^:private worker-file "extensions/test-runner/src/csb/test_worker.clj")

(defn- read-all-forms [source]
  (let [reader (PushbackReader. (StringReader. source))]
    (loop [forms []]
      (let [form (try (read reader false ::eof false) (catch Exception _ ::unreadable))]
        (cond
          (= ::eof form) forms
          (= ::unreadable form) (conj forms ::unreadable)
          :else (recur (conj forms form)))))))

(defn- var-added [sym]
  (when-let [v (try (resolve sym) (catch Exception _ nil))]
    (when (var? v) (:added (meta v)))))

(defn- version-order [a b]
  (let [parse (fn [v] (mapv #(Integer/parseInt %) (take 2 (str/split (str v) #"\."))))]
    (compare (parse a) (parse b))))

(deftest worker-loads-on-oldest-supported-clojure
  (let [symbols (->> (read-all-forms (slurp worker-file))
                     (mapcat #(tree-seq coll? seq %))
                     (filter symbol?)
                     (remove #(str/starts-with? (str %) "."))
                     distinct)
        offenders (into {}
                        (keep (fn [sym]
                                (when-let [added (var-added sym)]
                                  (when (pos? (version-order added minimum-version))
                                    [(str sym) added]))))
                        symbols)]
    (is (seq symbols) "the worker source was read and parsed")
    (is (empty? offenders)
        (str "core vars newer than Clojure " minimum-version
             " referenced by " worker-file ": " (pr-str offenders)))))
