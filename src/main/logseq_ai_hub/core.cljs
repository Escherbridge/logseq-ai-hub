(ns logseq-ai-hub.core
  (:require [logseq-ai-hub.agent :as agent]))

(def settings-schema
  [{:key "openAIKey"
    :type "string"
    :title "OpenAI API Key"
    :description "Enter your OpenAI API Key here."
    :default ""}
   {:key "openAIEndpoint"
    :type "string"
    :title "OpenAI API Endpoint"
    :description "The API endpoint URL (default: https://api.openai.com/v1)"
    :default "https://api.openai.com/v1"}
   {:key "chatModel"
    :type "string"
    :title "Chat Model Name"
    :description "The model ID to use (e.g. gpt-3.5-turbo, gpt-4, mistralai/mistral-7b-instruct)"
    :default "gpt-3.5-turbo"}
   {:key "selectedModel"
    :type "enum"
    :title "Select Model"
    :description "Choose which model to use for the /LLM command."
    :enum-choices ["mock-model" "reverse-model" "openai-model"]
    :default "mock-model"}])

(defn handle-llm-command [e]
  (let [block-uuid (.-uuid e)
        model-id (aget js/logseq.settings "selectedModel")] ;; Get selected model from settings
    (-> (js/logseq.Editor.getBlock block-uuid)
        (.then (fn [block]
                 (let [content (.-content block)]
                   ;; process-input now returns a Promise
                   (agent/process-input content (or model-id "mock-model")))))
        (.then (fn [response]
                 (when response
                   (js/logseq.Editor.insertBlock block-uuid response))))
        (.catch js/console.error))))

(defn main []
  (js/console.log "Loaded Logseq AI Hub Plugin")
  (js/logseq.useSettingsSchema (clj->js settings-schema))
  (js/logseq.Editor.registerSlashCommand "LLM" handle-llm-command))

(defn init []
  (-> (js/logseq.ready main)
      (.catch js/console.error)))
