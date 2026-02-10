(ns cljs-playground.agent
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
;; OpenAI Model
;; -----------------------------------------------------------------------------

(defn openai-handler [input _model-id]
  (let [settings js/logseq.settings
        api-key (aget settings "openAIKey")
        endpoint (aget settings "openAIEndpoint")
        model-name (or (aget settings "chatModel") "gpt-3.5-turbo")
        url (str (if (str/ends-with? endpoint "/")
                   (subs endpoint 0 (dec (count endpoint)))
                   endpoint)
                 "/chat/completions")]

    (if (str/blank? api-key)
      (js/Promise.resolve "⚠️ **Error**: OpenAI API Key is missing. Please check Plugin Settings.")
      (-> (js/fetch url
                    (clj->js {:method "POST"
                              :headers {"Content-Type" "application/json"
                                        "Authorization" (str "Bearer " api-key)}
                              :body (js/JSON.stringify
                                     (clj->js {:model model-name
                                               :messages [{:role "user" :content input}]}))}))
          (.then (fn [response]
                   (if (.-ok response)
                     (.json response)
                     (throw (js/Error. (str "API Error: " (.-statusText response)))))))
          (.then (fn [data]
                   (let [msg (-> data .-choices (aget 0) .-message .-content)]
                     msg)))
          (.catch (fn [err]
                    (js/console.error "OpenAI Handler Error:" err)
                    (str "⚠️ **Error calling OpenAI**: " (.-message err))))))))

;; Register models
(register-model "mock-model" echo-handler)
(register-model "reverse-model" reverse-handler)
(register-model "openai-model" openai-handler)

