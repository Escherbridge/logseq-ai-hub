(ns logseq-ai-hub.agent
  (:require [clojure.string :as str]
            [logseq-ai-hub.llm.settings :as llm-settings]
            [logseq-ai-hub.llm.response :as llm-response]))

;; -----------------------------------------------------------------------------
;; Registry
;; -----------------------------------------------------------------------------

(defonce models (atom {}))

(defn register-model
  "Registers a model handler function under a specific ID."
  [model-id handler-fn]
  (swap! models assoc model-id handler-fn)
  (println (str "Registered model: " model-id)))

(defn get-model
  "Retrieves a model handler by ID."
  [model-id]
  (get @models model-id))

;; -----------------------------------------------------------------------------
;; Dispatch
;; -----------------------------------------------------------------------------

(defn default-handler
  "Fallback for an unregistered model id -- rejects instead of echoing the
   input back as a fabricated AI response, so a misconfigured model setting
   surfaces as an error the user can act on."
  [_input model-id]
  (js/Promise.reject
   (js/Error.
    (str "Unknown AI model \"" model-id "\". Check the setting that supplied this id "
         "(e.g. Settings -> 'LLM Model'). Registered models: "
         (str/join ", " (sort (keys @models))) "."))))

(defn process-input
  "Dispatches input to the registered model handler based on model-id.
   Falls back to default-handler if model-id is not found.
   Returns a Promise resolving to the processed string."
  [input model-id]
  (let [handler (or (get-model model-id) default-handler)]
    (handler input model-id)))

;; -----------------------------------------------------------------------------
;; Built-in Models
;; -----------------------------------------------------------------------------

(defn echo-handler [input model-id]
  (js/Promise.resolve
   (str "🤖 **" model-id "** says: " input)))

(defn reverse-handler [input model-id]
  (js/Promise.resolve
   (str "🤖 **" model-id "** says: " (str/reverse input))))

;; -----------------------------------------------------------------------------
;; LLM Model
;; -----------------------------------------------------------------------------

(defn- llm-failure
  "An Error for a failed LLM call; its displayText is what /LLM shows the user."
  [message display-text]
  (doto (js/Error. message) (aset "displayText" display-text)))

(defn failure-display-text
  "Block text /LLM inserts for a rejected call: an LLM failure's displayText, else \"Error: <message>\"."
  [err]
  (or (when (some? err) (aget err "displayText"))
      (str "Error: " (if (instance? js/Error err) (.-message err) (str err)))))

(defn make-llm-handler
  "Creates an LLM API handler with an optional system prompt.
   Returns a function with signature [input model-id] -> Promise<string>, which
   rejects (never resolves an error string) when the call fails. A model-id that is
   not a registered handler id is sent as the provider model; otherwise the
   'LLM Model' setting is."
  ([]
   (make-llm-handler nil))
  ([system-prompt]
   (fn [input model-id]
     (let [api-key (llm-settings/api-key)
           model-name (if (or (str/blank? model-id) (get-model model-id))
                        (llm-settings/model)
                        model-id)
           url (llm-settings/chat-completions-url)
           messages (cond-> []
                      (not (str/blank? system-prompt))
                      (conj {:role "system" :content system-prompt})
                      true
                      (conj {:role "user" :content input}))]

       (if (nil? api-key)
         (js/Promise.reject
          (llm-failure "LLM API Key is missing. Please check Plugin Settings."
                       "⚠️ **Error**: LLM API Key is missing. Please check Plugin Settings."))
         (-> (js/fetch url
                       (clj->js {:method "POST"
                                 :headers {"Content-Type" "application/json"
                                           "Authorization" (str "Bearer " api-key)}
                                 :body (js/JSON.stringify
                                        (clj->js {:model model-name
                                                  :messages messages}))}))
             (.then (fn [response]
                      (if (.-ok response)
                        (.json response)
                        (-> (.text response)
                            (.then (fn [body]
                                     (js/console.error "API response body:" body)
                                     ;; Surface only the provider's own message — the raw
                                     ;; JSON envelope used to land in the user's graph.
                                     (throw (js/Error.
                                              (llm-response/error-message (.-status response) body)))))))))
             (.then (fn [data]
                      (let [{:keys [text error]} (llm-response/extract-text data)]
                        (if error
                          (throw (js/Error. error))
                          text))))
             (.catch (fn [err]
                       (js/console.error "LLM Handler Error:" err)
                       (let [message (if (instance? js/Error err) (.-message err) (str err))]
                         (throw (llm-failure message
                                             (str "⚠️ **Error calling LLM API**: " message))))))))))))

(def llm-handler
  "Default LLM handler with no system prompt."
  (make-llm-handler))

(defn process-with-system-prompt
  "Creates an ad-hoc handler with the given system prompt, calls it with input.
   Returns a Promise<string>."
  [input system-prompt]
  (let [handler (make-llm-handler system-prompt)]
    (handler input nil)))

;; Register models
(register-model "mock-model" echo-handler)
(register-model "reverse-model" reverse-handler)
(register-model "llm-model" llm-handler)
