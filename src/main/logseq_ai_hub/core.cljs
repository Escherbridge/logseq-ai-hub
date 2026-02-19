(ns logseq-ai-hub.core
  (:require [logseq-ai-hub.agent :as agent]
            [logseq-ai-hub.messaging :as messaging]
            [logseq-ai-hub.memory :as memory]
            [logseq-ai-hub.tasks :as tasks]))

(def settings-schema
  [{:key "webhookServerUrl"
    :type "string"
    :title "Webhook Server URL"
    :description "URL of your AI Hub webhook server (e.g. https://your-app.railway.app)"
    :default ""}
   {:key "pluginApiToken"
    :type "string"
    :title "Plugin API Token"
    :description "Shared secret token for authenticating with the webhook server."
    :default ""}
   {:key "memoryEnabled"
    :type "boolean"
    :title "Enable AI Memory"
    :description "Enable the AI memory system for storing and retrieving memories."
    :default false}
   {:key "memoryPagePrefix"
    :type "string"
    :title "Memory Page Prefix"
    :description "Prefix for memory pages in Logseq (e.g. AI-Memory/)."
    :default "AI-Memory/"}
   {:key "openAIKey"
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
  (js/logseq.Editor.registerSlashCommand "LLM" handle-llm-command)
  (messaging/init!)
  (memory/init!)
  (tasks/init!))

(defn init []
  (-> (js/logseq.ready main)
      (.catch js/console.error)))
