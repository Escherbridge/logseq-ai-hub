(ns logseq-ai-hub.auth
  "Centralized auth token resolution.
   Reads authMode and returns the appropriate token for outbound requests.
   Only depends on js/logseq.settings — no other plugin namespaces.")

(defn get-auth-mode
  "Returns the configured auth mode: \"token\" or \"jwt\".
   Defaults to \"token\" when the setting is absent or nil."
  []
  (let [mode (aget js/logseq "settings" "authMode")]
    (if (= mode "jwt") "jwt" "token")))

(defn get-auth-token
  "Returns the active auth token based on authMode.
   In \"jwt\" mode returns jwtToken; in \"token\" mode returns pluginApiToken."
  []
  (if (= (get-auth-mode) "jwt")
    (aget js/logseq "settings" "jwtToken")
    (aget js/logseq "settings" "pluginApiToken")))

(defn auth-configured?
  "Returns true when a non-blank auth token and server URL are both set."
  []
  (let [token (get-auth-token)
        server-url (aget js/logseq "settings" "webhookServerUrl")]
    (and (string? token)
         (not (empty? token))
         (string? server-url)
         (not (empty? server-url)))))
