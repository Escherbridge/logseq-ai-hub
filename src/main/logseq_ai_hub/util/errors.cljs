(ns logseq-ai-hub.util.errors)

;; ---------------------------------------------------------------------------
;; Error Construction
;; ---------------------------------------------------------------------------

(defn make-error
  "Creates a structured error map with type, message, and optional data."
  ([type message]
   (make-error type message nil))
  ([type message data]
   {:error true
    :type type
    :message message
    :data data}))

(defn error?
  "Returns true if x is a structured error map."
  [x]
  (and (map? x) (:error x) (= true (:error x))))

(defn make-error-promise
  "Returns a rejected Promise wrapping a structured error."
  ([type message]
   (make-error-promise type message nil))
  ([type message data]
   (js/Promise.reject (make-error type message data))))

(defn wrap-promise-errors
  "Wraps a Promise so that rejections are caught and converted to
   structured error maps resolved (not rejected)."
  [promise error-type]
  (.catch promise
    (fn [err]
      (make-error error-type
                  (if (instance? js/Error err)
                    (.-message err)
                    (str err))
                  {:original err}))))
