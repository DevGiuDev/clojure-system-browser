(ns csb.model)

(defn ok [value] {:status :ok :value value})

(defn error
  ([type message] (error type message {}))
  ([type message details]
   {:status :error :error {:type type :message message :details details}}))

(defn fail! [type message details]
  (throw (ex-info message {:type type :details details})))

(defn attempt [f]
  (try (ok (f))
       (catch clojure.lang.ExceptionInfo e
         (error (or (:type (ex-data e)) :internal)
                (ex-message e) (:details (ex-data e))))
       (catch java.io.IOException e
         (error :io (ex-message e) {:class (.getName (class e))}))
       (catch SecurityException e
         (error :io (ex-message e) {:class (.getName (class e))}))
       (catch Exception e
         (error :internal (or (ex-message e) "Unexpected error")
                {:class (.getName (class e))}))))

(defprotocol SourceStore
  (scan-project [store])
  (definition-source [store definition-id])
  (save-source! [store definition-id new-source options]))
