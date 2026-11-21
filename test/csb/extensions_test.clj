(ns csb.extensions-test
  (:require [clojure.test :refer [deftest is]]
            [csb.extensions :as ext]
            [csb.service :as service]
            [csb.source-test :refer [with-project]]))

(deftest version-collision-registration-and-unregistration
  (let [registry (ext/registry)]
    (is (= :extension-invalid (get-in (ext/register! registry (assoc ext/example :api-version 2)) [:error :type])))
    (is (= :ok (:status (ext/register! registry ext/example))))
    (is (= 1 (count (ext/contributions registry :commands))))
    (is (= :extension-collision (get-in (ext/register! registry ext/example) [:error :type])))
    (ext/unregister! registry :csb.ext/example)
    (is (empty? (ext/contributions registry :views)))))

(deftest explicit-trusted-extension-without-runtime
  (with-project {"src/demo.clj" "(ns demo) (defn f [] 1)"}
    (fn [root]
      (let [plain (:value (service/open-project root))
            system (:value (service/open-project root {:example-extension? true}))]
        (is (empty? (service/contributions plain :commands)))
        (is (= {:message "Hello from the Source World extension"}
               (:value (service/invoke-extension system :commands :csb.example/hello {}))))
        (is (= {:world :source :definition-count 1}
               (:value (service/invoke-extension system :views :csb.example/summary {}))))
        (is (= :not-found (get-in (service/invoke-extension system :commands :missing/id {}) [:error :type])))))))

(deftest missing-capability-does-not-invoke-handler
  (let [registry (ext/registry) called (atom false)]
    (ext/register! registry {:id :test/ext :api-version 1
                             :contributions {:commands [{:id :test/run :title "Run"
                                                         :requires #{:runtime/eval}
                                                         :handler (fn [_ _] (reset! called true))}]}})
    (is (= :capability-unavailable
           (get-in (ext/invoke registry :commands :test/run {:capabilities #{:source/read}} {}) [:error :type])))
    (is (false? @called))))
