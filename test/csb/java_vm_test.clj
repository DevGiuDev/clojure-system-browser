(ns csb.java-vm-test
  (:require [clojure.test :refer [deftest is]]
            [csb.java-vm :as vm]
            [csb.source-test :refer [with-project]])
  (:import [java.io File]
           [java.nio.file Files LinkOption Path]))

(deftest local-java-discovery
  (with-project {}
    (fn [^Path root]
      (let [java (if (= File/separatorChar \\) "java.exe" "java")
            fake-home (fn [name release]
                        (let [home (.resolve root ^String name)
                              bin (.resolve home "bin")]
                          (.mkdirs (.toFile bin))
                          (let [executable (.toFile (.resolve bin ^String java))]
                            (spit executable "This fixture must never be run.")
                            (.setExecutable executable true))
                          (spit (.toFile (.resolve home "release")) release)
                          (str (.toRealPath home (make-array LinkOption 0)))))
            managed (fake-home ".jdks/local 21" "JAVA_VERSION=\"21.0.2\"\nIMPLEMENTOR=\"Example Vendor\"\n")
            on-path (fake-home "tools/java-27" "JAVA_VERSION=\"27.0.1\"\n")
            bundled (fake-home ".jdks/Legacy.jdk/Contents/Home" "JAVA_VERSION=\"1.8.0_442\"\n")
            wrapper (.resolve root "unrelated-bin")]
        (.mkdir (.toFile wrapper))
        (doto (.toFile (.resolve wrapper ^String java))
          (spit "A wrapper is not a Java home.")
          (.setExecutable true))
        (Files/createDirectory (.resolve root ".jdks/incomplete")
                               (make-array java.nio.file.attribute.FileAttribute 0))
        (let [vms (vm/discover {"JAVA_HOME" managed
                               "PATH" (str wrapper File/pathSeparator on-path File/separator "bin")}
                              {"java.home" managed "user.home" (str root) "os.name" "Fixture OS"})]
          (is (= [{:home on-path :version "27.0.1"}
                  {:home managed :version "21.0.2" :vendor "Example Vendor"}
                  {:home bundled :version "1.8.0_442"}]
                 vms)))))))
