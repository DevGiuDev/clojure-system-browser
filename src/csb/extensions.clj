(ns csb.extensions
  (:require [csb.model :as m]))

(def api-version 1)

(defn registry [] (atom {}))

(defn contributions [registry kind]
  (->> @registry vals (mapcat #(get-in % [:contributions kind]))
       (sort-by (comp str :id)) vec))

(defn register! [registry extension]
  (m/attempt
   (fn [] (let [{:keys [id contributions]} extension
          ids (concat [id] (map :id (mapcat val contributions)))]
      (when-not (and (qualified-keyword? id) (= api-version (:api-version extension))
                     (every? #{:commands :views} (keys contributions))
                     (every? qualified-keyword? ids)
                     (= (count ids) (count (set ids)))
                     (every? (fn [c] (and (string? (:title c)) (ifn? (:handler c))))
                             (mapcat val contributions)))
        (m/fail! :extension-invalid "Invalid extension or unsupported API version" {:id id}))
      (swap! registry
             (fn [current]
               (let [occupied (set (concat (keys current)
                                          (map :id (mapcat #(mapcat val (:contributions %)) (vals current)))))]
                 (when (some occupied ids)
                   (m/fail! :extension-collision "Extension contribution ID already registered" {:id id}))
                 (assoc current id extension))))
      id))))

(defn unregister! [registry id] (swap! registry dissoc id) nil)

(defn invoke [registry kind id context args]
  (m/attempt
   #(let [item (some (fn [c] (when (= id (:id c)) c)) (contributions registry kind))]
      (when-not item (m/fail! :not-found "Unknown extension contribution" {:id id}))
      (when-not (every? (:capabilities context) (:requires item))
        (m/fail! :capability-unavailable "Contribution requires unavailable capabilities" {:id id}))
      ((:handler item) context args))))

(def example
  {:id :csb.ext/example :api-version 1
   :contributions
   {:commands [{:id :csb.example/hello :title "Example: hello" :requires #{}
                :handler (fn [_ _] {:message "Hello from the Source World extension"})}]
    :views [{:id :csb.example/summary :title "Project summary" :requires #{:source/read}
             :handler (fn [context _]
                        {:world :source
                         :definition-count (count ((:definitions context)))})}]}})
