(ns logseq-ai-hub.doctor
  "Configuration self-test: /ai-hub:doctor.

   Most failures in this plugin present as silence or as an empty result,
   so the fastest route to a diagnosis is one command that EXERCISES the
   configuration rather than merely printing it: it calls the LLM provider
   and the companion server for real and reports what came back."
  (:require [clojure.string :as str]
            [logseq-ai-hub.settings :as plugin-settings]
            [logseq-ai-hub.llm.settings :as llm-settings]
            [logseq-ai-hub.llm.response :as llm-response]
            [logseq-ai-hub.llm.transport :as transport]
            [logseq-ai-hub.auth :as auth]))

(def ^:private ok "✅")
(def ^:private warn "⚠️")
(def ^:private bad "❌")

(defn- mask
  "Shows enough of a secret to recognise it, never enough to leak it."
  [s]
  (if (or (nil? s) (< (count s) 8))
    "(set)"
    (str (subs s 0 6) "…" (subs s (- (count s) 4)))))

(defn- line [icon label detail]
  (str icon " **" label "** — " detail))

(defn- valid-json?
  "Returns [valid? detail] for a setting expected to hold JSON."
  [raw]
  (if (str/blank? raw)
    [true "not set"]
    (try
      (js/JSON.parse raw)
      [true "valid JSON"]
      (catch js/Error e
        [false (str "INVALID JSON — " (.-message e))]))))

;; ---------------------------------------------------------------------------
;; Probes — each resolves a report line, never rejects
;; ---------------------------------------------------------------------------

(defn- credential-label
  "What authenticated the probe, masked."
  [mode]
  (cond
    (= "hub-server" mode) (if (llm-settings/relay-token) "relay token" "plugin token")
    (llm-settings/api-key) (str "key " (mask (llm-settings/api-key)))
    :else "extra headers"))

(defn- hub-own-error
  "Which of the AI Hub server's own replies `text` is, or nil when the status was relayed
   from the upstream gateway. Matches the hub's bodies exactly, never the status alone."
  [status text]
  (let [body (try (js/JSON.parse text) (catch :default _ nil))
        hub-error (when (false? (some-> body (aget "success"))) (aget body "error"))]
    (cond
      (and (= 503 status) (= "relay_not_configured" (some-> body (aget "error") (aget "type"))))
      :not-configured
      (and (= 503 status) (= "relay_misconfigured" (some-> body (aget "error") (aget "type"))))
      :misconfigured
      (and (= 401 status) (= "Unauthorized" hub-error)) :token-rejected
      (and (= 404 status) (= "Not found" hub-error)) :no-relay)))

(defn- probe-llm
  "Runs transport/probe! over the configured transport and reports what it proved.
   Free for direct/logseq-desktop; under hub-server it costs one completion token."
  []
  (let [mode  (llm-settings/transport)
        model (transport/request-model)]
    (if-let [config-error (transport/config-error)]
      (js/Promise.resolve (line bad "LLM" config-error))
      (let [started (js/Date.now)
            target  (transport/describe)]
        (-> (transport/probe!)
            (.then (fn [{accepted? :ok :keys [status text checked]}]
                     (let [ms (- (js/Date.now) started)
                           hub? (= "hub-server" mode)
                           hub-error (when hub? (hub-own-error status text))]
                       (cond
                         accepted?
                         (line ok "LLM"
                               (str (credential-label mode) " accepted by " target
                                    (when (= :chat checked) ", and the gateway answered a 1-token test completion")
                                    " (" ms "ms). Model: " (or model "server's AGENT_MODEL")))

                         (= :token-rejected hub-error)
                         (line bad "LLM"
                               (str "the AI Hub server rejected this plugin's token (" status "). "
                                    (if (llm-settings/relay-token)
                                      "Check 'LLM Relay Token' is one of the server's LLM_RELAY_TOKENS."
                                      "Check 'Plugin API Token' matches the server's PLUGIN_API_TOKEN.")))

                         (= :not-configured hub-error)
                         (line bad "LLM"
                               (str target " has no LLM credentials. Set LLM_API_KEY "
                                    "(or LLM_EXTRA_HEADERS) on the server."))

                         (= :misconfigured hub-error)
                         (line bad "LLM"
                               (str target " has invalid LLM settings, so it disabled LLM — "
                                    (llm-response/error-message status text)
                                    ". Fix them on the server (see its startup warnings)."))

                         (= :no-relay hub-error)
                         (line bad "LLM"
                               (str target " is not available — the server predates the "
                                    "LLM relay. Redeploy it, or switch 'LLM Transport'."))

                         (and (= :chat checked) (= 400 status))
                         (line bad "LLM"
                               (str "the gateway refused the test completion — "
                                    (llm-response/error-message status text)
                                    ". Check 'LLM Model Name' (and the server's LLM_ALLOWED_MODELS)."))

                         (and hub? (contains? #{401 403} status))
                         (line bad "LLM"
                               (str "the gateway rejected the AI Hub server's credentials — "
                                    (llm-response/error-message status text)
                                    ". Fix LLM_API_KEY / LLM_EXTRA_HEADERS on the server."))

                         (contains? #{401 403} status)
                         (line bad "LLM"
                               (str "provider rejected the credentials — "
                                    (llm-response/error-message status text)
                                    ". Check Settings → 'LLM API Key' or 'LLM Extra Headers'."))

                         :else
                         (line warn "LLM"
                               (str target " responded "
                                    (llm-response/error-message status text)
                                    ". The credentials may still be fine."))))))
            (.catch (fn [e]
                      (line bad "LLM" (str (.-message e) " Check Settings → 'LLM Endpoint' / 'LLM Transport'.")))))))))

(defn- probe-server
  "Calls GET <server>/health and reports whether this plugin is linked to it."
  []
  (let [url (auth/get-server-url)]
    (cond
      (str/blank? url)
      (js/Promise.resolve
        (line warn "Server"
              (str "no URL set — messaging, Event Hub and the MCP bridge are "
                   "unavailable. Set Settings → 'Webhook Server URL'.")))

      (str/blank? (auth/get-auth-token))
      (js/Promise.resolve
        (line bad "Server"
              (str "URL is set but the " (auth/get-auth-mode)
                   " token is empty. Set Settings → "
                   (if (= "jwt" (auth/get-auth-mode)) "'JWT Token'." "'Plugin API Token'."))))

      :else
      (-> (js/fetch (str url "/health"))
          (.then (fn [res]
                   (if (.-ok res)
                     (-> (.json res)
                         (.then (fn [data]
                                  (let [connected? (boolean
                                                     (some-> data (aget "agentApi")
                                                             (aget "pluginConnected")))
                                        tools (or (some-> data (aget "mcp") (aget "tools")) 0)]
                                    (if connected?
                                      (line ok "Server"
                                            (str url " is healthy and THIS plugin is linked ("
                                                 tools " MCP tools)."))
                                      (line warn "Server"
                                            (str url " is healthy but no plugin is linked. "
                                                 "Check 'Plugin API Token' matches the server's "
                                                 "PLUGIN_API_TOKEN, then reload the plugin.")))))))
                     (js/Promise.resolve
                       (line bad "Server"
                             (str url "/health returned HTTP " (.-status res) "."))))))
          (.catch (fn [e]
                    (line bad "Server"
                          (str "could not reach " url " — " (.-message e)
                               ". Is it running?"))))))))

;; ---------------------------------------------------------------------------
;; Synchronous checks
;; ---------------------------------------------------------------------------

(defn- check-feature [label setting-key enabled-note disabled-note]
  (if (plugin-settings/flag setting-key false)
    (line ok label enabled-note)
    (line warn label disabled-note)))

(defn- check-json [label setting-key note]
  (let [raw (plugin-settings/text setting-key "")
        [valid? detail] (valid-json? raw)]
    (if valid?
      (line ok label (str detail (when note (str " — " note))))
      (line bad label (str detail " — fix Settings → '" label "'.")))))

(defn- sync-checks []
  [(if-let [origin (transport/cleartext-origin)]
     (line warn "LLM Transport"
           (str "credentials go to " origin " over plain http. Use https unless that host "
                "is on a network you trust."))
     (line ok "LLM Transport" (str (llm-settings/transport) " — no credentials sent over plain http.")))
   (check-feature "AI Memory" "memoryEnabled"
                  "enabled."
                  "disabled — /ai-memory:* commands are not registered. Enable it, then reload the plugin.")
   (check-feature "Job Runner" "jobRunnerEnabled"
                  (str "enabled. Poll every "
                       (plugin-settings/number "jobRunnerPollInterval" 5000) "ms, max "
                       (plugin-settings/number "jobRunnerMaxConcurrent" 3) " concurrent.")
                  "disabled — /job:* will queue jobs that never execute. Enable it, then reload the plugin.")
   (check-feature "Event Hub" "eventHubEnabled" "enabled." "disabled.")
   (check-json "Secrets Vault" "secretsVault" nil)
   (check-json "MCP Server Configs" "mcpServers" nil)
   (let [raw (plugin-settings/text "httpAllowlist" "")
         [valid? detail] (valid-json? raw)
         entries (when valid?
                   (try (js->clj (js/JSON.parse (if (str/blank? raw) "[]" raw)))
                        (catch js/Error _ [])))]
     (cond
       (not valid?)
       (line bad "HTTP Allowlist" (str detail " — outbound HTTP is UNRESTRICTED until fixed."))
       (empty? entries)
       (line warn "HTTP Allowlist"
             "empty, which allows ALL hosts for outbound HTTP job steps.")
       :else
       (line ok "HTTP Allowlist" (str (count entries) " pattern(s) allowed."))))])

;; ---------------------------------------------------------------------------
;; Command
;; ---------------------------------------------------------------------------

(defn run-diagnostics!
  "Resolves a markdown report of the plugin's configuration health."
  []
  (-> (js/Promise.all #js [(probe-llm) (probe-server)])
      (.then (fn [probe-lines]
               (str/join "\n" (concat ["## AI Hub diagnostics"]
                                      (js->clj probe-lines)
                                      (sync-checks)))))))

(defn handle-doctor-command [e]
  (let [block-uuid (.-uuid e)]
    (-> (run-diagnostics!)
        (.then (fn [report]
                 (js/logseq.Editor.updateBlock block-uuid report)))
        (.catch (fn [err]
                  (js/console.error "[Doctor] diagnostics failed:" err)
                  (js/logseq.Editor.updateBlock
                    block-uuid
                    (str "❌ Diagnostics failed: " (.-message err))))))))

(defn register-commands! []
  (js/logseq.Editor.registerSlashCommand "ai-hub:doctor" handle-doctor-command))
