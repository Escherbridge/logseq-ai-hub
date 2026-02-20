(ns logseq-ai-hub.core
  (:require [logseq-ai-hub.agent :as agent]
            [logseq-ai-hub.messaging :as messaging]
            [logseq-ai-hub.memory :as memory]
            [logseq-ai-hub.tasks :as tasks]
            [logseq-ai-hub.sub-agents :as sub-agents]
            [logseq-ai-hub.job-runner.init :as job-runner-init]))

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
    :enumChoices ["openai-model" "mock-model" "reverse-model"]
    :default "openai-model"}
   {:key "jobRunnerEnabled"
    :type "boolean"
    :title "Enable Job Runner"
    :description "Enable the autonomous job runner system."
    :default false}
   {:key "jobRunnerMaxConcurrent"
    :type "number"
    :title "Max Concurrent Jobs"
    :description "Maximum number of jobs that can run simultaneously."
    :default 3}
   {:key "jobRunnerPollInterval"
    :type "number"
    :title "Poll Interval (ms)"
    :description "How often the runner checks for new jobs (in milliseconds)."
    :default 5000}
   {:key "jobRunnerDefaultTimeout"
    :type "number"
    :title "Default Job Timeout (ms)"
    :description "Default timeout for job execution (in milliseconds)."
    :default 300000}
   {:key "jobPagePrefix"
    :type "string"
    :title "Job Page Prefix"
    :description "Prefix for job pages in Logseq."
    :default "Jobs/"}
   {:key "skillPagePrefix"
    :type "string"
    :title "Skill Page Prefix"
    :description "Prefix for skill pages in Logseq."
    :default "Skills/"}
   {:key "mcpServers"
    :type "string"
    :title "MCP Server Configs"
    :description "JSON array of MCP server configurations. Each: {\"id\": \"...\", \"url\": \"...\", \"auth-token\": \"...\"}"
    :default "[]"}])

(defn handle-llm-command [e]
  (let [block-uuid (.-uuid e)
        model-id (aget js/logseq.settings "selectedModel")]
    (js/console.log "LLM command fired. block:" block-uuid "model:" model-id)
    (-> (js/logseq.Editor.getBlock block-uuid)
        (.then (fn [block]
                 (if block
                   (let [content (.-content block)]
                     (js/console.log "LLM processing content:" content)
                     (agent/process-input content (or model-id "openai-model")))
                   (do (js/console.error "LLM: block not found for uuid" block-uuid)
                       (js/Promise.resolve "Error: Could not read block content.")))))
        (.then (fn [response]
                 (js/console.log "LLM response:" response)
                 (if (and response (not= response ""))
                   (js/logseq.Editor.insertBlock block-uuid response)
                   (js/logseq.Editor.insertBlock block-uuid "Error: Empty response from model."))))
        (.catch (fn [err]
                  (js/console.error "LLM command error:" err)
                  (js/logseq.Editor.insertBlock block-uuid
                    (str "Error: " (.-message err))))))))

(defn main []
  (js/console.log "Loaded Logseq AI Hub Plugin")
  (js/logseq.useSettingsSchema (clj->js settings-schema))
  (js/logseq.Editor.registerSlashCommand "LLM" handle-llm-command)
  (messaging/init!)
  (memory/init!)
  (tasks/init!)
  (sub-agents/init!)
  (job-runner-init/init!))

(defn init []
  (-> (js/logseq.ready main)
      (.catch js/console.error)))
