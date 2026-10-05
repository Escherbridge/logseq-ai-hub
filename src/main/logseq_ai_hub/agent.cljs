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

(defn default-handler [input _model-id]
  (js/Promise.resolve
   (str "🤖 **AI Agent (Unknown Model)**: " input " ... [Processed by Default]")))

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

(defn make-llm-handler
  "Creates an LLM API handler with an optional system prompt.
   Returns a function with signature [input model-id] -> Promise<string>."
  ([]
   (make-llm-handler nil))
  ([system-prompt]
   (fn [input _model-id]
     (let [api-key (llm-settings/api-key)
           model-name (llm-settings/model)
           url (llm-settings/chat-completions-url)
           messages (cond-> []
                      (not (str/blank? system-prompt))
                      (conj {:role "system" :content system-prompt})
                      true
                      (conj {:role "user" :content input}))]

       (if (nil? api-key)
         (js/Promise.resolve "⚠️ **Error**: LLM API Key is missing. Please check Plugin Settings.")
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
                       (str "⚠️ **Error calling LLM API**: " (.-message err))))))))))

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
