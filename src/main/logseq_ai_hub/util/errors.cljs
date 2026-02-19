(ns logseq-ai-hub.util.errors)

(defn make-error
  "Creates a structured error map."
  ([type message]
   {:error? true :type type :message message})
  ([type message data]
   {:error? true :type type :message message :data data}))

(defn error?
  "Returns true if x is a structured error map."
  [x]
  (and (map? x) (:error? x)))

(defn wrap-promise-errors
  "Wraps a Promise's rejection into a resolved structured error map.
   On success, returns the resolved value unchanged."
  [promise error-type]
  (.catch promise
          (fn [err]
            (make-error error-type
                        (if (instance? js/Error err)
                          (.-message err)
                          (str err))))))
