(ns csb.session-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [csb.editor :as editor]
            [csb.service :as service]
            [csb.session :as session]
            [csb.source-test :refer [with-project]])
  (:import [java.nio.file Files]))

(deftest shared-draft-lifecycle
  (with-project {"src/a.clj" "(ns a) (defn f [] 1) (defn g [] 2)"
                 "src/b.clj" "(ns b) (defn other [] 3)"}
    (fn [root]
      (let [state (session/create)]
        (session/open! state root {})
        (is (= 'a (:namespace @state)))
        (is (= 'f (get-in @state [:definition :name])))
        (session/edit! state "(defn f [] 9)")
        (session/select-namespace! state 'b)
        (is (= 'a (:namespace @state)))
        (is (:dirty? @state))
        (session/reload! state)
        (is (= "(defn f [] 9)" (:draft @state)))
        (session/save! state)
        (is (false? (:dirty? @state)))
        (is (= "Saved" (:status @state)))
        (session/select-namespace! state 'b)
        (is (= 'other (get-in @state [:definition :name])))
        (session/edit! state "(defn other [] 10)")
        (session/discard! state)
        (is (= "(defn other [] 3)" (:draft @state)))
        (is (false? (:dirty? @state)))))))

(deftest conflicts-and-parse-errors-retain-client-drafts
  (with-project {"src/a.clj" "(ns a) (defn f [] 1)"}
    (fn [root]
      (let [state (session/create)]
        (session/open! state root {})
        (session/edit! state "(defn f [")
        (session/save! state)
        (is (:dirty? @state))
        (is (= "(defn f [" (:draft @state)))
        (session/edit! state "(defn f [] 2)")
        (Files/writeString (.resolve root "src/a.clj") "(ns a) (defn f [] :external)"
                           (make-array java.nio.file.OpenOption 0))
        (session/save! state)
        (is (:dirty? @state))
        (is (= "(defn f [] 2)" (:draft @state)))
        (is (re-find #"conflict" (:status @state)))
        (session/discard! state)
        (session/reload! state)
        (is (= "(defn f [] :external)" (:draft @state)))))))

(deftest empty-namespaces-remain-navigable
  (with-project {"src/empty.clj" "(ns empty)"}
    (fn [root]
      (let [state (session/create)]
        (session/open! state root {})
        (is (= ['empty] (session/namespaces @state)))
        (is (empty? (session/definitions @state)))
        (is (nil? (:definition @state)))))))

(deftest opening-another-project-resets-client-filters-and-preserves-dirty-drafts
  (with-project {"src/a.clj" "(ns a) (defn f [] 1)"}
    (fn [first-root]
      (with-project {"src/b.cljd" "(ns b) (defn g [] 2)"}
        (fn [second-root]
          (let [state (session/create)]
            (session/open! state first-root {})
            (swap! state assoc :filter "f" :focus 2 :scroll 24 :help? true :diagnostics? true :source-order? true)
            (session/edit! state "(defn f [] 9)")
            (session/open! state second-root {})
            (is (= 'a (:namespace @state)))
            (is (= "(defn f [] 9)" (:draft @state)))
            (is (= "f" (:filter @state)))
            (session/discard! state)
            (session/open! state second-root {})
            (is (= 'b (:namespace @state)))
            (is (= 'g (get-in @state [:definition :name])))
            (is (= "" (:filter @state)))
            (is (= 0 (:focus @state) (:scroll @state)))
            (is (false? (:help? @state)))
            (is (false? (:diagnostics? @state)))
            (is (false? (:source-order? @state)))))))))

(deftest categories-are-counted-facets-and-default-to-all-definitions
  (with-project {"src/a.clj" "(ns a) (defn f [] 1) (defn- g [] 2) (defmacro m [x] x)"}
    (fn [root]
      (let [state (session/create)]
        (session/open! state root {})
        ;; No category is pre-selected: every definition of the namespace is visible.
        (is (nil? (:category @state)))
        (is (= #{'f 'g 'm} (set (map :name (session/definitions @state)))))
        (let [{:keys [total files entries]} (session/categories @state)]
          (is (= 3 total))
          (is (= 1 files))
          (is (= [{:category :functions :count 1}
                  {:category :macros :count 1}
                  {:category :private-functions :count 1}
                  {:category nil :count 3}
                  {:category :requires}]
                 entries)))
        ;; Selecting a facet narrows the list.
        (session/select-category! state :functions)
        (is (= #{'f} (set (map :name (session/definitions @state)))))
        ;; The "(all)" sentinel restores the full list.
        (session/select-category! state nil)
        (is (= #{'f 'g 'm} (set (map :name (session/definitions @state)))))
        ;; Re-selecting the sentinel keeps the unfiltered state.
        (session/select-category! state nil)
        (is (nil? (:category @state)))
        (is (= #{'f 'g 'm} (set (map :name (session/definitions @state)))))))))

(deftest an-empty-project-explains-where-sources-were-searched
  (with-project {"deps.edn" "{:paths [\"code\"]}"}
    (fn [root]
      (let [state (session/create)]
        (session/open! state root {})
        (is (empty? (session/namespaces @state)))
        (is (str/includes? (:status @state) "No namespaces found"))
        (is (str/includes? (:status @state) (str (.resolve root "code"))))
        (is (str/includes? (:status @state) ".cljd"))
        (session/reload! state)
        (is (str/includes? (:status @state) "No namespaces found"))))))

(deftest requires-is-a-read-only-view-of-the-selected-namespace
  (with-project {"src/a.clj" "(ns a (:require [external.lib :as lib])) (defn f [] 1)"
                 "src/b.clj" "(ns b)"}
    (fn [root]
      (let [state (session/create)]
        (session/open! state root {})
        (is (= :requires (:category (last (:entries (session/categories @state))))))
        (session/edit! state "(defn f [] 2)")
        (session/select-category! state :requires)
        (is (nil? (:category @state)))
        (is (= "(defn f [] 2)" (:draft @state)))
        (session/discard! state)
        (session/select-category! state :requires)
        (is (empty? (session/definitions @state)))
        (is (nil? (:definition @state)))
        (is (= "(:require [external.lib :as lib])" (session/requires-text @state)))
        (session/select-category! state nil)
        (is (= 'f (get-in @state [:definition :name])))
        (session/select-namespace! state 'b)
        (session/select-category! state :requires)
        (is (= "No requires declared." (session/requires-text @state)))))))

(deftest editor-commits-via-the-service
  (with-project {"src/a.clj" "(ns a) (defn f [] 1)"}
    (fn [root]
      (let [system (:value (service/open-project root))
            definition (first (:value (service/list-definitions system 'a)))
            session (:value (editor/begin! definition))]
        (try
          (Files/writeString (:temp session) "(defn f [] 9)" (make-array java.nio.file.OpenOption 0))
          (is (= :ok (:status (editor/save! system session))))
          (is (= "(ns a) (defn f [] 9)" (Files/readString (.resolve root "src/a.clj"))))
          (is (= 1 (count @(:events system))))
          (finally (editor/discard! session)))))))
