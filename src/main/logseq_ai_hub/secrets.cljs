(ns logseq-ai-hub.secrets
  "Secure key-value vault stored in Logseq plugin settings.
   Secrets are resolvable at runtime via {{secret.KEY_NAME}} template interpolation.
   Secret values are NEVER logged, printed, or included in error messages."
  (:require [clojure.string :as str]
            [logseq-ai-hub.settings-writer :as settings-writer]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private key-pattern #"^[A-Z0-9_]+$")
(def ^:private max-keys 100)

;; =============================================================================
;; State
;; =============================================================================

(defonce vault-state (atom {}))

;; =============================================================================
;; Validation
;; =============================================================================

(defn valid-key?
  "Returns true if key matches [A-Z0-9_]+ pattern."
  [key]
  (and (string? key)
       (not (str/blank? key))
       (some? (re-matches key-pattern key))))

;; =============================================================================
;; Private Helpers
;; =============================================================================

(defn- persist-vault!
  "Serializes the vault atom to JSON and writes to Logseq settings.
   Returns a Promise that resolves when the write completes."
  []
  (settings-writer/queue-settings-write!
    (fn []
      (let [json-str (js/JSON.stringify (clj->js @vault-state))]
        (js/logseq.updateSettings (clj->js {:secretsVault json-str}))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn get-secret
  "Returns the value for a key, or nil if not found.
   NEVER log the return value."
  [key]
  (get @vault-state key))

(defn has-secret?
  "Returns true if the key exists in the vault."
  [key]
  (contains? @vault-state key))

(defn list-keys
  "Returns a vector of key names (NOT values) for discovery."
  []
  (vec (keys @vault-state)))

(defn set-secret!
  "Updates a secret in the vault and persists to Logseq settings.
   Validates key format and vault size limit.
   Returns a Promise that resolves when persisted."
  [key value]
  (cond
    (not (valid-key? key))
    (js/Promise.reject
      (js/Error. (str "Invalid secret key: " key ". Must match [A-Z0-9_]+")))

    (and (not (contains? @vault-state key))
         (>= (count @vault-state) max-keys))
    (js/Promise.reject
      (js/Error. (str "Secret vault full (max " max-keys " keys)")))

    :else
    (do
      (swap! vault-state assoc key value)
      (js/console.log "Secrets: Set key" key)
      (persist-vault!))))

(defn remove-secret!
  "Removes a secret from the vault and persists to Logseq settings.
   Returns a Promise that resolves when persisted."
  [key]
  (if (contains? @vault-state key)
    (do
      (swap! vault-state dissoc key)
      (js/console.log "Secrets: Removed key" key)
      (persist-vault!))
    (js/Promise.reject
      (js/Error. (str "Secret key not found: " key)))))

(defn reload!
  "Re-reads the vault from Logseq settings."
  []
  (let [vault-json (or (aget js/logseq.settings "secretsVault") "{}")
        parsed (try
                 (js->clj (js/JSON.parse vault-json))
                 (catch js/Error _e
                   (js/console.warn "Secrets: Invalid vault JSON on reload")
                   @vault-state))]
    (reset! vault-state parsed)
    (js/console.log "Secrets: Reloaded with" (count parsed) "keys")))

(defn init!
  "Initializes the secrets vault from Logseq settings.
   Parses the secretsVault JSON, caches in memory, and registers
   a settings change listener for live reloading."
  []
  (let [vault-json (or (aget js/logseq.settings "secretsVault") "{}")
        parsed (try
                 (js->clj (js/JSON.parse vault-json))
                 (catch js/Error _e
                   (js/console.warn "Secrets: Invalid vault JSON, using empty vault")
                   {}))]
    (reset! vault-state parsed)
    (js/console.log "Secrets: Initialized with" (count parsed) "keys")
    ;; Register settings change listener for live reloading
    (js/logseq.onSettingsChanged
      (fn [new-settings old-settings]
        (when (not= (aget new-settings "secretsVault")
                    (aget old-settings "secretsVault"))
          (reload!))))))

;; =============================================================================
;; Redaction Utilities (FR-8)
;; =============================================================================

(defn redact-value
  "Replaces a specific secret value with [REDACTED:KEY_NAME] in text.
   Returns the text unchanged if the key doesn't exist."
  [text key]
  (if-let [value (get @vault-state key)]
    (if (and (string? text) (string? value) (not (str/blank? value)))
      (str/replace text value (str "[REDACTED:" key "]"))
      text)
    text))

(defn redact-all
  "Redacts all known secret values found in text.
   Scans through all vault entries and replaces matches."
  [text]
  (if (string? text)
    (reduce (fn [t [k v]]
              (if (and (string? v) (not (str/blank? v)))
                (str/replace t v (str "[REDACTED:" k "]"))
                t))
            text
            @vault-state)
    text))

;; =============================================================================
;; Slash Commands (FR-5)
;; =============================================================================

(defn register-commands!
  "Registers /secrets:* slash commands in Logseq."
  []
  ;; /secrets:list — Lists all secret key names (NOT values)
  (js/logseq.Editor.registerSlashCommand "secrets:list"
    (fn [e]
      (let [block-uuid (.-uuid e)
            ks (list-keys)]
        (if (empty? ks)
          (js/logseq.UI.showMsg "No secrets stored" "info")
          (-> (reduce (fn [p k]
                        (.then p (fn [_]
                                   (js/logseq.Editor.insertBlock
                                     block-uuid k
                                     (clj->js {:sibling false})))))
                      (js/Promise.resolve nil)
                      ks)
              (.then (fn [_]
                       (js/logseq.UI.showMsg
                         (str (count ks) " secret keys listed") "info"))))))))

  ;; /secrets:set — Sets a secret. Block text: KEY_NAME value-here
  (js/logseq.Editor.registerSlashCommand "secrets:set"
    (fn [e]
      (let [block-uuid (.-uuid e)]
        (-> (js/logseq.Editor.getBlock block-uuid)
            (.then (fn [block]
                     (when block
                       (let [content (str/trim (.-content block))
                             cleaned (str/replace content #"^/secrets:set\s*" "")
                             parts (str/split cleaned #"\s+" 2)
                             key (first parts)
                             value (second parts)]
                         (if (and key value (not (str/blank? value)))
                           (-> (set-secret! key value)
                               (.then (fn [_]
                                        (js/logseq.Editor.updateBlock
                                          block-uuid (str "Secret " key " saved"))
                                        (js/logseq.UI.showMsg
                                          (str "Secret " key " saved") "success")))
                               (.catch (fn [err]
                                         (js/logseq.UI.showMsg
                                           (str "Error: " (.-message err)) "error"))))
                           (js/logseq.UI.showMsg
                             "Usage: KEY_NAME value" "warning"))))))))))

  ;; /secrets:remove — Removes a secret. Block text = key name
  (js/logseq.Editor.registerSlashCommand "secrets:remove"
    (fn [e]
      (let [block-uuid (.-uuid e)]
        (-> (js/logseq.Editor.getBlock block-uuid)
            (.then (fn [block]
                     (when block
                       (let [content (str/trim (.-content block))
                             key (-> content
                                     (str/replace #"^/secrets:remove\s*" "")
                                     (str/trim))]
                         (if (not (str/blank? key))
                           (-> (remove-secret! key)
                               (.then (fn [_]
                                        (js/logseq.Editor.updateBlock
                                          block-uuid (str "Secret " key " removed"))
                                        (js/logseq.UI.showMsg
                                          (str "Secret " key " removed") "success")))
                               (.catch (fn [err]
                                         (js/logseq.UI.showMsg
                                           (str "Error: " (.-message err)) "error"))))
                           (js/logseq.UI.showMsg
                             "Usage: KEY_NAME" "warning"))))))))))

  ;; /secrets:test — Tests if a secret exists without revealing value
  (js/logseq.Editor.registerSlashCommand "secrets:test"
    (fn [e]
      (let [block-uuid (.-uuid e)]
        (-> (js/logseq.Editor.getBlock block-uuid)
            (.then (fn [block]
                     (when block
                       (let [content (str/trim (.-content block))
                             key (-> content
                                     (str/replace #"^/secrets:test\s*" "")
                                     (str/trim)
                                     (str/replace #"^\{\{secret\." "")
                                     (str/replace #"\}\}$" ""))]
                         (if (not (str/blank? key))
                           (let [exists (has-secret? key)
                                 msg (if exists
                                       (str "\u2713 " key " exists")
                                       (str "\u2717 " key " not found"))]
                             (js/logseq.Editor.updateBlock block-uuid msg)
                             (js/logseq.UI.showMsg msg (if exists "success" "warning")))
                           (js/logseq.UI.showMsg
                             "Usage: KEY_NAME or {{secret.KEY_NAME}}" "warning"))))))))))

  (js/console.log "Secrets: Slash commands registered"))

;; =============================================================================
;; Testing Helpers
;; =============================================================================

(defn reset-vault!
  "Resets the vault state. For testing only."
  []
  (reset! vault-state {}))
