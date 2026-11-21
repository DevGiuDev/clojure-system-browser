(ns csb.session
  "Shared client actions and draft lifecycle; independent of Desktop and terminal widgets."
  (:require [clojure.string :as str]
            [csb.service :as service]))

(defn create [] (atom {:status "Open a project" :draft "" :dirty? false}))

(defn namespaces [state]
  (when-let [system (:system state)] (:value (service/list-namespaces system))))

(defn categories
  "Category facets composed with the \"(all)\" sentinel: {:total :files :entries}.
   The sentinel entry carries :category nil and the namespace total."
  [state]
  (when-let [system (:system state)]
    (let [{:keys [total files facets]} (:value (service/list-categories system (:namespace state)))]
      (when facets
        {:total total :files files
         :entries (conj (vec facets) {:category nil :count total})}))))

(defn definitions [state]
  (when-let [system (:system state)]
    (let [category (:category state)]
      (->> (:value (service/list-definitions system (:namespace state)))
           (filter #(or (nil? category) (= category (:category %)))) vec))))

(defn- message [result]
  (str (name (get-in result [:error :type] :error)) ": " (get-in result [:error :message])))

(defn- allow-navigation! [state]
  (if (:dirty? @state)
    (do (swap! state assoc :status "Unsaved changes: Save or Discard before navigating") false)
    true))

(defn- select-first [state]
  (let [state (assoc state :category nil)
        d (first (definitions state))]
    (assoc state :definition d :draft (or (:source d) "") :dirty? false)))

(defn- project-status [system action]
  (let [{:keys [root roots namespaces diagnostics]} (service/project-info system)]
    (str action " · " root " · " (count namespaces)
         " namespaces · " (count diagnostics) " source errors"
         (when (empty? namespaces)
           (str " · No namespaces found in " (str/join ", " roots)
                ". Supported files: .clj, .cljc, .cljd. Check source roots and diagnostics.")))))

(defn open! [state root options]
  (when (allow-navigation! state)
    (let [result (service/open-project root options)]
      (if (= :ok (:status result))
        (let [system (:value result)
              available (:value (service/list-namespaces system))
              ns-sym (first available)]
          (swap! state #(select-first (assoc % :system system :namespace ns-sym
                                            :filter "" :focus 0 :scroll 0
                                            :help? false :diagnostics? false :source-order? false
                                            :status (project-status system "Source World")))))
        (swap! state assoc :status (message result))))))

(defn select-namespace! [state ns-sym]
  (when (and (not= ns-sym (:namespace @state)) (allow-navigation! state))
    (swap! state #(select-first (assoc % :namespace ns-sym)))))

(defn select-category! [state category]
  (when (and (not= category (:category @state)) (allow-navigation! state))
    (swap! state (fn [s]
                   (let [s (assoc s :category category)
                         d (first (definitions s))]
                     (assoc s :definition d :draft (or (:source d) "") :dirty? false))))))

(defn select-definition! [state id]
  (when (and (not= id (get-in @state [:definition :id])) (allow-navigation! state))
    (let [result (service/get-definition-source (:system @state) id)]
      (if (= :ok (:status result))
        (swap! state assoc :definition (:value result) :draft (:source (:value result)) :dirty? false)
        (swap! state assoc :status (message result))))))

(defn edit! [state text]
  (when (get-in @state [:definition :writable?])
    (swap! state assoc :draft text :dirty? (not= text (get-in @state [:definition :source])))))

(defn discard! [state]
  (swap! state assoc :draft (or (get-in @state [:definition :source]) "") :dirty? false :status "Draft discarded"))

(defn save! [state]
  (let [{:keys [system definition draft]} @state]
    (when (and system definition)
      (let [result (service/save-definition! system (:id definition) draft
                                              {:expected-revision (:revision definition)})]
        (if (= :ok (:status result))
          (let [saved (:value result)]
            (if (:reindex-error saved)
              (swap! state assoc :definition (assoc definition :source draft :revision (:revision saved))
                     :dirty? false :status "Saved; index refresh failed. Reload the project.")
              (swap! state assoc :definition (:definition saved) :draft (get-in saved [:definition :source])
                     :dirty? false :status "Saved")))
          (swap! state assoc :status (message result)))))))

(defn reload! [state]
  (when (and (:system @state) (allow-navigation! state))
    (let [result (service/refresh! (:system @state))]
      (if (= :ok (:status result))
        (swap! state (fn [s]
                       (let [available (namespaces s)
                             ns-sym (if (some #{(:namespace s)} available) (:namespace s) (first available))]
                         (select-first (assoc s :namespace ns-sym
                                              :status (project-status (:system s) "Reloaded"))))))
        (swap! state assoc :status (message result))))))

(defn extension! [state kind id]
  (when-let [system (:system @state)]
    (let [r (service/invoke-extension system kind id {})]
      (swap! state assoc :status (if (= :ok (:status r)) (pr-str (:value r)) (message r))))))
