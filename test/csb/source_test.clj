(ns csb.source-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [csb.editor :as editor]
            [csb.service :as service]
            [csb.source :as source])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Arrays]))

(defn with-project [files f]
  (let [root (Files/createTempDirectory "csb-test-" (make-array FileAttribute 0))]
    (try
      (doseq [[name text] files]
        (let [file (.resolve root name)]
          (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
          (Files/writeString file text (make-array java.nio.file.OpenOption 0))))
      (f root)
      (finally
        (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
          (doseq [file (reverse (sort (iterator-seq (.iterator stream))))]
            (Files/deleteIfExists ^Path file)))))))

(defn opened [root]
  (let [r (service/open-project root)]
    (is (= :ok (:status r)) (pr-str r))
    (:value r)))

(defn definition [system]
  (first (:definitions @(:snapshot system))))

(defn save [system draft]
  (let [d (definition system)]
    (service/save-definition! system (:id d) draft {:expected-revision (:revision d)})))

(deftest navigation-without-execution
  (with-project
    {"src/demo.clj" "(ns demo)\n;; intro\n(defn first-one [x] x)\n(defn- secret [] :ok)\n#_(defn discarded [] nil)\n(comment (defn nested [] nil))\n(defmacro twice [x] `(do ~x ~x))\n(def answer 42)"}
    (fn [root]
      (let [system (opened root)
            defs (:value (service/list-definitions system 'demo))]
        (is (= ['demo] (:value (service/list-namespaces system))))
        (is (= #{'first-one 'secret 'twice 'answer} (set (map :name defs))))
        (is (= :private-functions (:category (some #(when (= 'secret (:name %)) %) defs))))
        (is (= "(defn first-one [x] x)" (:source (some #(when (= 'first-one (:name %)) %) defs))))
        (is (= :unsupported (get-in (service/list-namespaces system {:world :live}) [:error :type])))))))

(deftest symbol-lookup-in-indexed-definitions
  (with-project
    {"src/cart.clj" "(ns cart)\n(defn total [] 1)\n(defn receipt [] (total))"
     "src/pay.clj" "(ns pay (:require [cart :as c :refer [total]]))
                  (defn pay [] [(c/total) (total)])
                  (defn ignored [] [\"c/total\" 'c/total #_(c/total) :c/total])"
     "src/other.clj" "(ns other)\n(defn total [] 0)\n(defn unrelated [] (total))"
     "test/cart_test.clj" "(ns cart-test (:require [cart :as c]))\n(deftest check-total (c/total))"}
    (fn [root]
      (let [system (opened root)
            context (first (:value (service/list-definitions system 'pay)))
            names-for (fn [action sym]
                        (mapv :name (get-in (service/lookup-symbol system (:id context) sym action)
                                           [:value :matches])))]
        (is (= ['total] (names-for :definition 'c/total)))
        (is (= ['check-total] (names-for :tests 'total)))
        (is (= ['receipt 'check-total 'pay] (names-for :usages 'cart/total)))
        (is (empty? (names-for :definition 'missing)))))))

(deftest uses-of-an-indexed-definition
  (with-project
    {"src/cart.clj" "\uFEFF(ns cart)\r\n(def tax 2)\r\n(defn total [xs]\r\n  (if (seq xs) (+ tax (total (next xs))) 0))"
     "src/pay.clj" "(ns pay (:require [cart :as c :refer [total]]))
(defn pay []
  [(c/total []) total #'cart/total ^:private c/total])
(defn ignored []
  [\"c/total\" 'c/total #_(c/total) :c/total (comment (total []))])"
     "src/other.clj" "(ns other) (defn total [] 0) (defn unrelated [] (total))"
     "test/cart_test.clj" "(ns cart-test (:require [cart :as c]))\n(deftest check-total (c/total []))"}
    (fn [root]
      (let [system (opened root)
            find-uses (fn [sym]
                        (let [d (some #(when (= sym (:name %)) %)
                                      (:value (service/list-definitions system 'cart)))]
                          (get-in (service/definition-usages system (:id d)) [:value :matches])))]
        (is (= [['cart 'total 4 1] ['cart-test 'check-total 2 1] ['pay 'pay 3 4]]
               (mapv (juxt :namespace :name (comp :row :usage-location) :usage-count)
                     (find-uses 'total))))
        (is (= [['total 4]]
               (mapv (juxt :name (comp :row :usage-location)) (find-uses 'tax))))))))

(deftest uses-of-ambiguous-or-unsupported-definitions
  (with-project
    {"src/cart.clj" "(ns cart) (defn total [] 1) (defmulti price identity) (defmethod price :default [_] 0)"
     "src/cart_extra.clj" "(ns cart) (defn total [] 2) (defn invoice [] (total))"
     "src/pay.clj" "(ns pay (:require [cart :as c])) (defmethod c/price :online [_] 1)"}
    (fn [root]
      (let [system (opened root)
            defs (:value (service/list-definitions system 'cart))
            lookup (fn [kind sym]
                     (let [d (some #(when (= [kind sym] ((juxt :kind :name) %)) %) defs)]
                       (service/definition-usages system (:id d))))]
        (is (= :ambiguous (get-in (lookup :function 'total) [:value :availability])))
        (is (empty? (get-in (lookup :function 'total) [:value :matches])))
        (is (= :ready (get-in (lookup :multimethod 'price) [:value :availability])))
        (is (= ['price 'c/price] (mapv :name (get-in (lookup :multimethod 'price) [:value :matches]))))
        (is (= :unsupported (get-in (lookup :method 'price) [:error :type])))))))

(deftest indexed-uses-change-after-saving-and-reloading
  (with-project {"src/demo.clj" "(ns demo)\n(defn caller [] 0)\n(def given 1)"}
    (fn [root]
      (let [system (opened root)
            file (.resolve root "src/demo.clj")
            names (fn []
                    (let [d (second (:value (service/list-definitions system 'demo)))]
                      (mapv :name (get-in (service/definition-usages system (:id d)) [:value :matches]))))]
        (is (empty? (names)))
        (is (= :ok (:status (save system "(defn caller [] given)"))))
        (is (= ['caller] (names)))
        (Files/writeString file "(ns demo)\n(defn caller [] 0)\n(def given 1)"
                           (make-array java.nio.file.OpenOption 0))
        (is (= :ok (:status (service/refresh! system))))
        (is (empty? (names)))))))

(deftest search-finds-namespaces-and-definitions-by-name
  (with-project
    {"src/cart.clj" "(ns cart)\n(defn total [] 1)\n(defn- receipt [] :ok)"
     "src/pay.clj" "(ns pay)\n(def payment 3)\n(deftest check-total (total))"}
    (fn [root]
      (let [system (opened root)
            results (fn [query] (:value (service/search system query)))
            names (fn [query] (mapv (juxt :namespace :name) (:definitions (results query))))]
        (is (= {:query "" :namespaces [] :definitions []} (results "")))
        (is (= [] (:namespaces (results "tot"))))
        (is (= '[[cart total] [pay check-total]] (names "tot")))
        (is (= '[[cart total] [pay check-total]] (names "TOT")))
        (is (= '[[cart total]] (names "cart/tot")))
        (is (= '[cart pay] (:namespaces (results "a"))))
        (let [row (first (:definitions (results "receipt")))]
          (is (= :definition (:result/type row)))
          (is (= :private-functions (:category row)))
          (is (nil? (:source row))))
        (is (= :unsupported (get-in (service/search system "x" {:world :live}) [:error :type])))))))

(deftest browse-does-not-evaluate-reader-forms-or-project-code
  (with-project {"src/demo.clj" "(ns demo)\n#=(throw (Exception. \"MUST NOT RUN\"))\n(throw (Exception. \"MUST NOT RUN\"))\n(defn f [] 1)"}
    (fn [root]
      (let [system (opened root)]
        (is (= 'f (:name (definition system))))
        (is (= :parse (get-in (save system "(defn f [] 2)") [:error :type])))))))

(deftest exact-preservation-and-repeated-save
  (doseq [newline ["\n" "\r\n" "\r"] bom ["" "\uFEFF"]]
    (let [prefix (str bom "(ns demo)" newline ";; café 😀" newline)
          form (str "(defn f" newline "  \"héllo\" [x]" newline "  x)")
          suffix (str newline ";; other" newline "(defn g [] :untouched)")
          draft "(defn f [x] (+ x 1))"]
      (with-project {"src/demo.clj" (str prefix form suffix)}
        (fn [root]
          (let [system (opened root)
                file (.resolve root "src/demo.clj")
                result (save system draft)]
            (is (= form (-> (source/document file (.getBytes (str prefix form suffix) StandardCharsets/UTF_8))
                             :definitions first :source)))
            (is (= :ok (:status result)) (pr-str result))
            (is (Arrays/equals (.getBytes (str prefix draft suffix) StandardCharsets/UTF_8)
                               (Files/readAllBytes file)))
            (is (= :ok (:status (save system "(defn f [x] (+ x 2))"))))
            (is (= 2 (count @(:events system))))))))))

(deftest invalid-replacements-never-write
  (doseq [draft ["(defn f []" "(defn f [] {:a})" "(defn f [] {:a 1 :a 2})"
                 "(defn f [] #{1 1})" "(defn f [] \"\\x\")"
                 "(defn f [] 2) (defn g [] 3)" "#_(defn f [] 2)"
                 "(defn f 42)" "(defn f [1] :bad)" "(defn f [x &] x)" "(defn f [] #=(+ 1 2))"
                 "(defn other [] 2)" "(defmacro f [] 2)" "(defn f [] #custom/value 1)"
                 "(defn f [] #?(:clj 1))"]]
    (with-project {"src/demo.clj" "(ns demo)\n(defn f [] 1)\n"}
      (fn [root]
        (let [system (opened root)
              file (.resolve root "src/demo.clj")
              original (Files/readAllBytes file)
              r (save system draft)]
          (is (= :error (:status r)) (str draft " => " (pr-str r)))
          (is (Arrays/equals original (Files/readAllBytes file)))
          (is (empty? @(:events system))))))))

(deftest conflict-preserves-external-changes
  (with-project {"src/demo.clj" "(ns demo)\n(defn f [] 1)"}
    (fn [root]
      (let [system (opened root)
            file (.resolve root "src/demo.clj")
            external "(ns demo)\n;; external\n(defn f [] 7)"]
        (Files/writeString file external (make-array java.nio.file.OpenOption 0))
        (is (= :conflict (get-in (save system "(defn f [] 2)") [:error :type])))
        (is (= external (Files/readString file)))
        (is (empty? @(:events system)))
        (is (= :ok (:status (service/refresh! system))))
        (is (= :ok (:status (save system "(defn f [] 3)"))))))))

(deftest stale-revision-and-missing-file
  (with-project {"src/demo.clj" "(ns demo)\n(defn f [] 1)"}
    (fn [root]
      (let [system (opened root) d (definition system)]
        (is (= :conflict (get-in (service/save-definition! system (:id d) "(defn f [] 2)" {}) [:error :type])))
        (Files/delete (.resolve root "src/demo.clj"))
        (is (= :error (:status (save system "(defn f [] 2)"))))))))

(deftest ambiguous-and-unsupported-definitions-are-read-only
  (doseq [files [{"src/demo.clj" "(ns demo) (defn f [] 1) (defn f [] 2)"}
                 {"src/a.clj" "(ns demo) (defn f [] 1)" "src/b.clj" "(ns demo) (defn f [] 2)"}
                 {"src/demo.cljc" "(ns demo) (defn f [] 1)"}
                 {"src/demo.clj" "(ns demo) (defn f [] 1) (ns other)"}
                 {"src/demo.clj" "(defn f [] 1)"}]]
    (with-project files
      (fn [root]
        (let [system (opened root)]
          (is (every? (comp not :writable?) (:definitions @(:snapshot system))))
          (is (= :unsupported (get-in (save system "(defn f [] 3)") [:error :type]))))))))

(deftest partial-project-errors-stay-visible
  (with-project {"src/good.clj" "(ns good) (defn f [] 1)" "src/bad.clj" "(ns bad) (defn broken ["}
    (fn [root]
      (let [system (opened root)]
        (is (= ['good] (:value (service/list-namespaces system))))
        (is (= 1 (count (service/diagnostics system))))
        (is (= :parse (get-in (first (service/diagnostics system)) [:error :type])))))))

(deftest explicit-source-roots-and-no-deps-execution
  (with-project {"deps.edn" "{:paths [\"code\"] :aliases {:evil {:exec-fn never/run}}}"
                 "code/demo.clj" "(ns demo) (defn f [] 1)"
                 "src/ignored.clj" "(ns ignored) (defn g [] 2)"}
    (fn [root]
      (is (= ['demo] (:value (service/list-namespaces (opened root)))))))
  (with-project {"deps.edn" "{:paths [\"../outside\"]}"}
    (fn [root] (is (= :unsupported (get-in (service/open-project root) [:error :type]))))))

(deftest test-alias-paths-are-indexed-without-running-the-alias
  (with-project
    {"deps.edn" "{:paths [\"src\"] :aliases {:test {:extra-paths [\"spec/jvm\"] :main-opts [\"-m\" \"never.run\"]}}}"
     "src/demo.clj" "(ns demo) (defn f [] 1)"
     "spec/jvm/demo_test.clj" "(ns demo-test (:require [clojure.test :refer [deftest]])) (deftest check-f)"}
    (fn [root]
      (is (= ['check-f] (mapv :name (get (:value (service/list-tests (opened root))) 'demo-test))))
      (let [system (:value (service/open-project root {:roots ["src"]}))]
        (is (empty? (:value (service/list-tests system))))))))

(deftest lazytest-suites-are-indexed-without-loading-the-project
  (with-project
    {"deps.edn" "{:paths [\"src\"] :aliases {:lazytest {:extra-paths [\"spec\"] :main-opts [\"-m\" \"lazytest.main\"]}}}"
     "src/demo.clj" "(ns demo) (defn total [] 42)"
     "spec/demo_test.clj" "(ns demo-test (:require [lazytest.core :as lt :refer [defdescribe it]] [demo :as d]))
                           (throw (Exception. \"must not run while indexing\"))
                           (lt/defdescribe aliased (it \"nested\" (d/total)))
                           (lazytest.core/defdescribe qualified)
                           (defdescribe referred)
                           (other/defdescribe unrelated)
                           #_(defdescribe discarded)"
     "spec/renamed_test.clj" "(ns renamed-test (:require [lazytest.core :refer [defdescribe] :rename {defdescribe suite}]))
                              (suite renamed)
                              (defdescribe no-longer-referred)"
     "spec/shadowed_test.clj" "(ns shadowed-test
                                (:refer-clojure :exclude [defn])
                                (:require [lazytest.core :refer [defdescribe] :rename {defdescribe defn}]))
                               (defn named-like-a-function)"
     "spec/excluded_test.clj" "(ns excluded-test (:require [lazytest.core :refer :all :exclude [defdescribe]]))
                               (defmacro defdescribe [& _] nil)
                               (defdescribe excluded)"}
    (fn [root]
      (let [system (opened root)
            suites (:value (service/list-tests system))
            aliased (first (suites 'demo-test))
            definition (first (:value (service/list-definitions system 'demo)))]
        (is (= {'demo-test ['aliased 'qualified 'referred]
                'renamed-test ['renamed]
                'shadowed-test ['named-like-a-function]}
               (update-vals suites #(mapv :name %))))
        (is (false? (:writable? aliased)))
        (is (false? (:writable? (first (suites 'shadowed-test)))))
        (is (= ['aliased] (mapv :name (get-in (service/lookup-symbol system (:id definition) nil :tests)
                                              [:value :matches]))))))))

(deftest polylith-workspace-is-discovered-by-convention
  (with-project
    {"deps.edn" "{:aliases {:dev {:extra-paths [\"components/shop/src\" \"bases/worker/src\"]}}}"
     "components/shop/src/shop/interface.clj" "(ns shop.interface (:require [shop.components.invoice :as invoice]))"
     "components/shop/src/shop/components/invoice.clj" "(ns shop.components.invoice)\n(defn total [items] (reduce + 0 (map :price items)))"
     "components/shop/test/shop/components/invoice_test.clj" "(ns shop.components.invoice-test)\n(deftest total-test (is true))"
     "bases/worker/src/worker/bases/cli.clj" "(ns worker.bases.cli)\n(defn start [opts] opts)"
     "development/src/dev/scratch.clj" "(ns dev.scratch)\n(defonce seed 42)"
     "projects/app/src/app/core.clj" "(ns app.core)\n(def version \"0.1.0\")"}
    (fn [root]
      (let [system (opened root)
            real-root (.toRealPath root (make-array java.nio.file.LinkOption 0))]
        (is (= #{'shop.interface 'shop.components.invoice 'shop.components.invoice-test
                 'worker.bases.cli 'dev.scratch 'app.core}
               (set (:value (service/list-namespaces system)))))
        (is (= #{"components/shop/src" "components/shop/test" "bases/worker/src"
                 "development/src" "projects/app/src"}
               (set (map #(str (.relativize real-root (source/path %)))
                         (:roots (service/project-info system))))))
        (let [d (->> (:value (service/list-definitions system 'shop.components.invoice)) first)]
          (is (true? (:writable? d)))
          (is (= :ok (:status (service/save-definition! system (:id d) "(defn total [items] 0)"
                                                        {:expected-revision (:revision d)}))))
          (is (.contains (Files/readString
                          (.resolve root "components/shop/src/shop/components/invoice.clj"))
                         "(defn total [items] 0)")))))))

(deftest polylith-workspace-edn-is-enough-to-detect-the-layout
  (with-project {"workspace.edn" "{:top-namespace \"poly.sample\"}"
                 "components/shop/src/shop/interface.clj" "(ns shop.interface)"
                 "bases/worker/src/worker/bases/cli.clj" "(ns worker.bases.cli) (defn -main [& _] nil)"}
    (fn [root]
      (is (= #{'shop.interface 'worker.bases.cli}
             (set (:value (service/list-namespaces (opened root)))))))))

(deftest polylith-detection-respects-explicit-configuration
  (with-project {"deps.edn" "{:paths [\"src\"]}"
                 "src/demo.clj" "(ns demo) (defn f [] 1)"
                 "components/shop/src/shop/invoice.clj" "(ns shop.invoice) (defn total [] 1)"
                 "bases/worker/src/worker/cli.clj" "(ns worker.cli) (defn start [] 1)"}
    (fn [root]
      (is (= ['demo] (:value (service/list-namespaces (opened root)))))))
  (with-project {"components/shop/src/shop/invoice.clj" "(ns shop.invoice) (defn total [] 1)"
                 "bases/worker/src/worker/cli.clj" "(ns worker.cli) (defn start [] 1)"
                 "development/src/dev/scratch.clj" "(ns dev.scratch) (defonce seed 42)"}
    (fn [root]
      (let [r (service/open-project root {:roots ["development/src"]})]
        (is (= :ok (:status r)) (pr-str r))
        (is (= ['dev.scratch] (:value (service/list-namespaces (:value r)))))))))

(deftest lone-components-directory-is-not-a-polylith-workspace
  (with-project {"components/shop/src/shop/invoice.clj" "(ns shop.invoice) (defn total [] 1)"
                 "src/demo.clj" "(ns demo) (defn f [] 1)"}
    (fn [root]
      (is (= ['demo] (:value (service/list-namespaces (opened root))))))))

(deftest clojuredart-sources-are-discovered-without-loading-the-project
  (let [code (str "(ns ^{:no-dynamic true} demo.dart (:require [\"dart:io\" :as io]))\n"
                  "(defonce handle (#/(io/File \"example\")))\n"
                  "(defn f [value] (#/(identity value)))\n"
                  "(defn- secret [] :hidden)\n"
                  "#=(throw (Exception. \"MUST NOT RUN\"))\n"
                  "(throw (Exception. \"MUST NOT RUN\"))\n")]
    (with-project {"deps.edn" "{:paths [\"code\"] :deps {missing/dependency {:mvn/version \"not-real\"}}}"
                   "code/demo.cljd" code}
      (fn [root]
        (let [system (opened root)
              defs (:value (service/list-definitions system 'demo.dart))
              f (some #(when (= 'f (:name %)) %) defs)]
          (is (= ['demo.dart] (:value (service/list-namespaces system))))
          (is (= #{'handle 'f 'secret} (set (map :name defs))))
          (is (= "(defn f [value] (#/(identity value)))" (:source f)))
          (is (= :private-functions (:category (some #(when (= 'secret (:name %)) %) defs))))
          (is (every? (comp false? :writable?) defs))
          (is (re-find #"ClojureDart.*read-only" (:read-only-reason f)))
          (is (= :unsupported
                 (get-in (service/save-definition! system (:id f) "(defn f [value] value)"
                                                  {:expected-revision (:revision f)}) [:error :type])))
          (is (= code (Files/readString (.resolve root "code/demo.cljd"))))
          (is (empty? (service/diagnostics system)))
          (is (nil? (find-ns 'demo.dart))))))))

(deftest clojuredart-parse-errors-do-not-hide-other-files
  (with-project {"src/good.cljd" "(ns good.dart) (defn f [] 1)"
                 "src/bad.cljd" "(ns bad.dart) (defn broken ["}
    (fn [root]
      (let [system (opened root)]
        (is (= ['good.dart] (:value (service/list-namespaces system))))
        (is (= :parse (get-in (first (service/diagnostics system)) [:error :type])))))))

(deftest editor-uses-temporary-definition-and-retains-conflicted-draft
  (with-project {"src/demo.clj" "(ns demo)\n(defn f [] 1)"}
    (fn [root]
      (let [system (opened root)
            file (.resolve root "src/demo.clj")
            session (:value (editor/begin! (definition system)))]
        (try
          (is (not= file (:temp session)))
          (Files/writeString ^Path (:temp session) "(defn f [] 9)" (make-array java.nio.file.OpenOption 0))
          (is (= "(ns demo)\n(defn f [] 1)" (Files/readString file)))
          (Files/writeString file "(ns demo)\n(defn f [] :external)" (make-array java.nio.file.OpenOption 0))
          (is (= :conflict (get-in (editor/save! system session) [:error :type])))
          (is (= "(defn f [] 9)" (:value (editor/read-draft session))))
          (finally (editor/discard! session)))))))

(deftest metadata-destructuring-and-safe-namespace-aliases
  (with-project {"src/demo.clj" "(ns demo (:require [not.installed :as other]))\n(defn ^:private f [x] ::other/value)"}
    (fn [root]
      (let [system (opened root)]
        (is (= 'f (:name (definition system))))
        (is (:private? (definition system)))
        (is (= :ok (:status (save system "(defn ^:private f [{:keys [x]} & xs] [::other/value x xs])"))))
        (is (nil? (find-ns 'not.installed)))))))

(deftest source-on-same-line-after-unicode
  (with-project {"src/demo.clj" "(ns demo) \"😀é\" (defn f [] 1) ;; tail"}
    (fn [root]
      (let [system (opened root)]
        (is (= "(defn f [] 1)" (:source (definition system))))
        (is (= :ok (:status (save system "(defn f [] 2)"))))
        (is (= "(ns demo) \"😀é\" (defn f [] 2) ;; tail"
               (Files/readString (.resolve root "src/demo.clj"))))))))

(deftest simultaneous-buffers-do-not-silently-overwrite
  (with-project {"src/demo.clj" "(ns demo) (defn f [] 1)"}
    (fn [root]
      (let [system (opened root)
            old (definition system)]
        (is (= :ok (:status (save system "(defn f [] :first)"))))
        (is (= :conflict (get-in (service/save-definition! system (:id old) "(defn f [] :second)"
                                                          {:expected-revision (:revision old)}) [:error :type])))
        (is (= "(ns demo) (defn f [] :first)" (Files/readString (.resolve root "src/demo.clj"))))))))

(deftest invalid-unicode-is-never-silently-replaced
  (with-project {"src/demo.clj" "(ns demo) (defn f [] 1)"}
    (fn [root]
      (let [system (opened root)]
        (is (= :parse (get-in (save system (str "(defn f [] \"" (char 0xD800) "\")")) [:error :type])))
        (is (= "(ns demo) (defn f [] 1)" (Files/readString (.resolve root "src/demo.clj"))))))))

(deftest invalid-utf8-is-reported
  (with-project {"src/demo.clj" ""}
    (fn [root]
      (Files/write (.resolve root "src/demo.clj") (byte-array [(unchecked-byte 0xff)])
                   (make-array java.nio.file.OpenOption 0))
      (let [system (opened root)]
        (is (= :unsupported (get-in (first (service/diagnostics system)) [:error :type])))))))

(deftest symbolic-source-files-are-not-followed
  (with-project {"src/demo.clj" "(ns demo) (defn f [] 1)"}
    (fn [root]
      (when (Files/getFileAttributeView root java.nio.file.attribute.PosixFileAttributeView
                                        (make-array java.nio.file.LinkOption 0))
        (Files/createSymbolicLink (.resolve root "src/link.clj") (.resolve root "src/demo.clj")
                                 (make-array FileAttribute 0))
        (let [system (opened root)]
          (is (= 1 (count (:definitions @(:snapshot system))))))))))

(deftest write-failure-does-not-destroy-the-file
  (with-project {"src/demo.clj" "(ns demo) (defn f [] 1)"}
    (fn [root]
      (let [dir (.resolve root "src") file (.resolve root "src/demo.clj")
            system (opened root) no-links (make-array java.nio.file.LinkOption 0)]
        (when (Files/getFileAttributeView dir java.nio.file.attribute.PosixFileAttributeView no-links)
          (let [permissions (Files/getPosixFilePermissions dir no-links)]
            (try
              (Files/setPosixFilePermissions dir (java.nio.file.attribute.PosixFilePermissions/fromString "r-x------"))
              (is (= :io (get-in (save system "(defn f [] 2)") [:error :type])))
              (is (= "(ns demo) (defn f [] 1)" (Files/readString file)))
              (finally (Files/setPosixFilePermissions dir permissions)))))))))

(deftest committed-save-is-distinguished-from-a-reindex-error
  (with-project {"src/demo.clj" "(ns demo) (defn f [] given) (def given 1)"}
    (fn [root]
      (let [system (opened root)
            id (:id (second (:value (service/list-definitions system 'demo))))
            result (with-redefs-fn {#'source/source-files (fn [_] (throw (java.io.IOException. "index failed")))}
                     #(save system "(defn f [] 2)"))]
        (is (= :ok (:status result)))
        (is (true? (get-in result [:value :written?])))
        (is (= "index failed" (get-in result [:value :reindex-error])))
        (is (= "(ns demo) (defn f [] 2) (def given 1)" (Files/readString (.resolve root "src/demo.clj"))))
        (is (= (get-in result [:value :revision]) (get-in (last @(:events system)) [:change :after])))
        (is (= :stale (get-in (service/definition-usages system id) [:value :availability])))
        (is (empty? (get-in (service/definition-usages system id) [:value :matches])))
        (is (= :stale-index (get-in (service/lookup-symbol system id nil :usages) [:error :type])))
        (is (= :ok (:status (service/refresh! system))))
        (is (= :ready (get-in (service/definition-usages system id) [:value :availability])))
        (is (empty? (get-in (service/definition-usages system id) [:value :matches])))))))

(deftest privacy-is-read-from-headers-not-function-bodies
  (with-project {"src/demo.clj" "(ns demo) (defn f [] \"^:private\") (defn g {:private true} [] 1)"}
    (fn [root]
      (let [defs (:value (service/list-definitions (opened root) 'demo))]
        (is (false? (:private? (first defs))))
        (is (true? (:private? (second defs))))))))

(deftest ambiguous-namespaces-are-never-guessed
  (with-project {"src/demo.clj" "(ns a) (defn f [] 1) (ns b) (defn g [] 2)"}
    (fn [root]
      (let [system (opened root)]
        (is (empty? (:value (service/list-namespaces system))))
        (is (= :unsupported (get-in (first (service/diagnostics system)) [:error :type])))
        (is (every? (comp nil? :namespace) (:definitions @(:snapshot system))))))))

(deftest category-facets-count-and-summarize-a-namespace
  (with-project {"src/demo.clj" (str "(ns demo)\n"
                                     "(defn f [] 1)\n"
                                     "(defn- g [] 2)\n"
                                     "(defmacro m [x] x)\n"
                                     "(def v 42)\n")
                 "test/demo_test.clj" "(ns demo)\n(deftest f-test (is true))"}
    (fn [root]
      (let [system (opened root)
            {:keys [total files facets]} (:value (service/list-categories system 'demo))]
        (is (= 5 total))
        (is (= 2 files))
        (is (= [{:category :functions :count 1}
                {:category :macros :count 1}
                {:category :private-functions :count 1}
                {:category :tests :count 1}
                {:category :values :count 1}]
               facets))
        (is (= :unsupported
               (get-in (service/list-categories system 'demo {:world :live}) [:error :type])))))))

(deftest indexed-requires-preserve-declarations-per-file
  (let [require-clause "(:require plain [missing.lib :as l :refer [f]] (my.prefix [a :as a] b))"
        conditional-clause "#?@(:clj [(:require [jvm.lib :as j])] :cljs [(:require-macros [macros.lib :as m])])"
        macros-clause "#?(:cljs (:require-macros [macros.extra :as m]))"
        dart-clause "(:require [\"dart:io\" :as io])"]
    (with-project {"src/a.cljc" (str "(ns demo #_(:require ignored) (:import java.io.File) "
                                    require-clause "\n" conditional-clause "\n" macros-clause ")")
                   "src/b.cljd" (str "(ns demo " dart-clause ")")
                   "src/c.clj" "(ns other (:require unrelated))"
                   "src/d.clj" "(ns empty)"
                   "src/e.cljc" "(ns feature (:require #?(:clj jvm.lib :cljs js.lib)))"}
      (fn [root]
        (let [system (:value (service/open-project root))]
          (is (= [["a.cljc" [require-clause conditional-clause macros-clause]]
                  ["b.cljd" [dart-clause]]]
                 (mapv (fn [{:keys [file declarations]}]
                         [(last (str/split file #"/")) declarations])
                       (:value (service/list-requires system 'demo)))))
          (is (= ["(:require #?(:clj jvm.lib :cljs js.lib))"]
                 (-> (service/list-requires system 'feature) :value first :declarations)))
          (is (= [] (:value (service/list-requires system 'empty)))))))))

(deftest source-forms-show-real-file-order
  (with-project
    {"src/demo.clj" (str "(ns demo)\n"
                         "(defn first-one [x] x)\n"
                         "(declare later)\n"
                         "(comment (defn nested [] nil))\n"
                         "#_(defn discarded [] nil)\n"
                         "#=(+ 1 2)\n"
                         "(start-server)\n"
                         "(defmacro twice [x] x)\n")}
    (fn [root]
      (let [system (opened root)
            files (:value (service/list-source-forms system 'demo))
            forms (mapcat :forms files)]
        ;; The adapter real-resolves the project root, so compare against
        ;; real paths (macOS temp dirs live behind the /var symlink).
        (is (= [(str (.toRealPath (.resolve root "src/demo.clj")
                                  (make-array java.nio.file.LinkOption 0)))]
               (mapv :file files)))
        ;; Every significant top-level form, in real file order, including the
        ;; forms the definition index skips.
        (is (= ["ns" "defn" "declare" "comment" "#_" "#=" "start-server" "defmacro"]
               (mapv :label forms)))
        (is (= (vec (range 8)) (mapv :ordinal forms)))
        (is (= (mapv :id (:definitions @(:snapshot system))) (keep :definition-id forms)))
        (is (nil? (:definition-id (nth forms 3))))
        (is (true? (:discarded? (nth forms 4))))
        (is (= 'later (:name (nth forms 2))))
        (is (= :macro (:kind (nth forms 7))))
        (is (= "(start-server)" (:preview (nth forms 6))))
        (is (= 8 (:row (nth forms 7))))
        (is (= :unsupported
               (get-in (service/list-source-forms system 'demo {:world :live}) [:error :type])))))))

(deftest source-forms-span-files-and-skip-parse-errors
  (let [crlf "\uFEFF(ns demo)\r\n(defn f [] 1)\r\n;; tail\r\n(defn g [] 2)\r\n"]
    (with-project {"src/a.clj" crlf
                   "src/b.clj" "(ns demo) (declare helper)"
                   "src/broken.clj" "(ns demo) (defn broken ["}
      (fn [root]
        (let [system (opened root)
              files (:value (service/list-source-forms system 'demo))]
          (is (= (mapv #(str (.toRealPath % (make-array java.nio.file.LinkOption 0)))
                       [(.resolve root "src/a.clj") (.resolve root "src/b.clj")])
                 (mapv :file files)))
          (is (= ["ns" "defn" "defn"] (mapv :label (:forms (first files)))))
          ;; BOM stays out of previews; rows are 1-based file lines with CRLF intact.
          (is (= "(defn f [] 1)" (:preview (nth (:forms (first files)) 1))))
          (is (= [1 2 4] (mapv :row (:forms (first files)))))
          (is (= ["ns" "declare"] (mapv :label (:forms (second files)))))
          ;; A parse error keeps the file out of the view without hiding others.
          (is (= 1 (count (service/diagnostics system))))
          (is (= :parse (get-in (first (service/diagnostics system)) [:error :type]))))))))
