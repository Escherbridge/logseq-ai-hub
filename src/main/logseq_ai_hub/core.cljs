(ns logseq-ai-hub.core
  (:require [logseq-ai-hub.agent :as agent]
            [logseq-ai-hub.messaging :as messaging]
            [logseq-ai-hub.memory :as memory]
            [logseq-ai-hub.tasks :as tasks]
            [logseq-ai-hub.sub-agents :as sub-agents]
            [logseq-ai-hub.secrets :as secrets]
            [logseq-ai-hub.job-runner.init :as job-runner-init]
            [logseq-ai-hub.agent-bridge :as agent-bridge]
            [logseq-ai-hub.settings-writer :as settings-writer]
            [logseq-ai-hub.llm.enriched :as enriched]
            [logseq-ai-hub.registry.init :as registry-init]
            [logseq-ai-hub.code-repo.init :as code-repo-init]
            [logseq-ai-hub.event-hub.init :as event-hub-init]
            [logseq-ai-hub.auth :as auth]
            [clojure.string :as str]))

(def settings-schema
  [   {:key "webhookServerUrl"
    :type "string"
    :title "Webhook Server URL"
    :description "URL of your AI Hub webhook server (e.g. https://your-app.railway.app)"
    :default ""}
   {:key "authMode"
    :type "enum"
    :title "Auth Mode"
    :description "Authentication mode: \"token\" for single-tenant open-source server, \"jwt\" for multi-tenant server."
    :default "token"
    :enumChoices ["token" "jwt"]}
   {:key "pluginApiToken"
    :type "string"
    :title "Plugin API Token"
    :description "Shared secret token for authenticating with the webhook server (used when Auth Mode is \"token\")."
    :default ""}
   {:key "jwtToken"
    :type "string"
    :title "JWT Token"
    :description "Long-lived JWT from the admin dashboard (used when Auth Mode is \"jwt\")."
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
   {:key "llmApiKey"
    :type "string"
    :title "LLM API Key"
    :description "Your LLM provider API key (OpenRouter, etc.)"
    :default ""}
   {:key "llmEndpoint"
    :type "string"
    :title "LLM API Endpoint"
    :description "The API endpoint URL (default: OpenRouter)"
    :default "https://openrouter.ai/api/v1"}
   {:key "llmModel"
    :type "string"
    :title "LLM Model Name"
    :description "The model ID to use (e.g. anthropic/claude-sonnet-4)"
    :default "anthropic/claude-sonnet-4"}
   {:key "pageRefDepth"
    :type "number"
    :title "Page Reference Link Depth"
    :description "How many levels of [[links]] to follow when fetching page context (0 = referenced pages only)."
    :default 0}
   {:key "pageRefMaxTokens"
    :type "number"
    :title "Page Reference Max Tokens"
    :description "Approximate token budget for injected page context."
    :default 8000}
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
    :default "[]"}
   {:key "secretsVault"
    :type "string"
    :title "Secrets Vault"
    :description "JSON object of secret key-value pairs. Reference in configs with {{secret.KEY_NAME}}. Example: {\"OPENROUTER_KEY\": \"sk-...\", \"SLACK_TOKEN\": \"xoxb-...\"}. Values are visible in this field — do not share screenshots."
    :default "{}"}
   {:key "piDevEnabled"
    :type "boolean"
    :title "Enable Pi.dev Agent Platform"
    :description "Enable pi.dev agent integration for code-aware autonomous workflows (requires pi CLI installed)."
    :default false}
   {:key "piDevInstallPath"
    :type "string"
    :title "Pi.dev Install Path"
    :description "Path to the pi CLI binary (e.g. /usr/local/bin/pi or C:\\Program Files\\pi\\pi.exe)."
    :default ""}
   {:key "piDevDefaultModel"
    :type "string"
    :title "Pi.dev Default Model"
    :description "Default LLM model for pi.dev sessions."
    :default "anthropic/claude-sonnet-4"}
   {:key "piDevRpcPort"
    :type "number"
    :title "Pi.dev RPC Port"
    :description "Port for pi.dev RPC communication (0 = auto-assign)."
    :default 0}
   {:key "piDevMaxConcurrentSessions"
    :type "number"
    :title "Pi.dev Max Concurrent Sessions"
    :description "Maximum number of concurrent pi.dev agent sessions."
    :default 3}
   {:key "eventHubEnabled"
    :type "boolean"
    :title "Enable Event Hub"
    :description "Enable the Event Hub system for event-driven automation."
    :default true}
   {:key "httpAllowlist"
    :type "string"
    :title "HTTP Allowlist"
    :description "JSON array of domain patterns allowed for outbound HTTP requests (e.g. [\"api.example.com\", \"*.slack.com\"])."
    :default "[]"}
   {:key "eventRetentionDays"
    :type "number"
    :title "Event Retention Days"
    :description "Number of days to retain events before auto-pruning."
    :default 30}
   {:key "eventGraphPersistence"
    :type "boolean"
    :title "Persist Events to Graph"
    :description "Persist incoming events to Logseq graph pages under Events/."
    :default true}])

(defn migrate-settings!
  "Migrates old settings keys to new names and backfills new settings for existing users.
   Copies values forward only if old key has value and new key is empty/default."
  []
  (let [settings js/logseq.settings]
    (doseq [[old-key new-key default-val]
            [["openAIKey" "llmApiKey" ""]
             ["openAIEndpoint" "llmEndpoint" "https://openrouter.ai/api/v1"]
             ["chatModel" "llmModel" "anthropic/claude-sonnet-4"]]]
      (let [old-val (aget settings old-key)
            new-val (aget settings new-key)]
        (when (and (not (str/blank? old-val))
                   (or (nil? new-val) (str/blank? new-val)))
          (settings-writer/queue-settings-write!
            (fn []
              (js/logseq.updateSettings (clj->js {(keyword new-key) old-val}))
              (js/console.log (str "Settings migration: " old-key " -> " new-key)))))))
    ;; Backfill authMode for existing token users: if they have a pluginApiToken
    ;; but authMode has never been set, default them to "token" mode explicitly.
    (let [existing-token (aget settings "pluginApiToken")
          existing-mode  (aget settings "authMode")]
      (when (and (not (str/blank? existing-token))
                 (nil? existing-mode))
        (settings-writer/queue-settings-write!
          (fn []
            (js/logseq.updateSettings (clj->js {:authMode "token"}))
            (js/console.log "Settings migration: backfilled authMode -> token")))))))

(defn handle-llm-command [e]
  (let [block-uuid (.-uuid e)]
    (js/console.log "LLM command fired. block:" block-uuid)
    (-> (js/logseq.Editor.getBlock block-uuid)
        (.then (fn [block]
                 (if block
                   (do (js/console.log "LLM processing content:" (.-content block))
                       (enriched/call (.-content block)))
                   (do (js/console.error "LLM: block not found for uuid" block-uuid)
                       (js/Promise.resolve "Error: Could not read block content.")))))
        (.then (fn [response]
                 (js/console.log "LLM response received, length:" (count response))
                 (if (and response (not= response ""))
                   (js/logseq.Editor.insertBlock block-uuid response)
                   (js/logseq.Editor.insertBlock block-uuid "Error: Empty response from model."))))
        (.catch (fn [err]
                  (js/console.error "LLM command error:" err)
                  (js/logseq.Editor.insertBlock block-uuid
                    (str "Error: " (if (instance? js/Error err) (.-message err) (str err)))))))))

(defn- check-auth-config!
  "Logs a warning when the selected auth mode has no token configured."
  []
  (let [mode (auth/get-auth-mode)]
    (cond
      (and (= mode "jwt")
           (str/blank? (aget js/logseq "settings" "jwtToken")))
      (js/console.warn "[Auth] JWT mode selected but no JWT token configured")

      (and (= mode "token")
           (str/blank? (aget js/logseq "settings" "pluginApiToken")))
      (js/console.warn "[Auth] Token mode selected but no API token configured"))))

(defn main []
  (js/console.log "Loaded Logseq AI Hub Plugin")
  (js/logseq.useSettingsSchema (clj->js settings-schema))
  (migrate-settings!)
  (check-auth-config!)
  (js/logseq.Editor.registerSlashCommand "LLM" handle-llm-command)
  (secrets/init!)
  (secrets/register-commands!)
  (messaging/init!)
  (memory/init!)
  (tasks/init!)
  (sub-agents/init!)
  (job-runner-init/init!)
  (registry-init/init!)
  (code-repo-init/init!)
  (event-hub-init/init!)
  (agent-bridge/init!))

(defn init []
  (-> (js/logseq.ready main)
      (.catch js/console.error)))
