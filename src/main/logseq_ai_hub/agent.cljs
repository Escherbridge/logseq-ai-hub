(ns logseq-ai-hub.agent
  (:require [clojure.string :as str]))

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
     (let [settings js/logseq.settings
           api-key (aget settings "llmApiKey")
           endpoint (aget settings "llmEndpoint")
           model-name (or (aget settings "llmModel") "anthropic/claude-sonnet-4")
           url (str (if (str/ends-with? endpoint "/")
                      (subs endpoint 0 (dec (count endpoint)))
                      endpoint)
                    "/chat/completions")
           messages (cond-> []
                      (not (str/blank? system-prompt))
                      (conj {:role "system" :content system-prompt})
                      true
                      (conj {:role "user" :content input}))]

       (if (str/blank? api-key)
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
                                     (throw (js/Error. (str "API " (.-status response) ": " body)))))))))
             (.then (fn [data]
                      (let [msg (-> data .-choices (aget 0) .-message .-content)]
                        msg)))
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
