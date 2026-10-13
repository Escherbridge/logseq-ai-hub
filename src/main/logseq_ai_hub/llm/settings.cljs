(ns logseq-ai-hub.llm.settings
  "Single reader for the LLM provider settings (key, endpoint, model).
   Values are trimmed: a pasted key with surrounding whitespace produced
   `Authorization: Bearer  sk-...` and a 401 from the provider."
  (:require [clojure.string :as str]
            [logseq-ai-hub.secrets :as secrets]))

(def default-endpoint "https://openrouter.ai/api/v1")
(def default-model "anthropic/claude-sonnet-4")
(def default-transport "direct")

(defn- setting
  "Reads a plugin setting as a trimmed string, or nil when absent/blank."
  [k]
  (let [v (aget (aget js/logseq "settings") k)]
    (when (string? v)
      (let [t (str/trim v)]
        (when-not (str/blank? t) t)))))

(defn api-key
  "Trimmed llmApiKey, or nil when unset/blank."
  []
  (setting "llmApiKey"))

(defn endpoint
  "Trimmed llmEndpoint without a trailing slash, defaulting to OpenRouter."
  []
  (let [e (or (setting "llmEndpoint") default-endpoint)]
    (if (str/ends-with? e "/")
      (subs e 0 (dec (count e)))
      e)))

(defn model
  "Trimmed llmModel, defaulting when unset or blank.
   `or` alone is unsafe here: in CLJS an empty string is truthy."
  []
  (or (setting "llmModel") default-model))

(defn configured-model
  "Trimmed llmModel, or nil when blank (no default applied)."
  []
  (setting "llmModel"))

(defn relay-token
  "Trimmed llmRelayToken (relay-only hub-server credential), or nil when blank."
  []
  (setting "llmRelayToken"))

(defn chat-completions-url
  "Full chat-completions URL for the configured endpoint."
  []
  (str (endpoint) "/chat/completions"))

(defn transport
  "llmTransport: \"direct\", \"logseq-desktop\" or \"hub-server\". An unknown value is
   returned as-is so the caller can reject it rather than silently fall back."
  []
  (or (setting "llmTransport") default-transport))

(def ^:private secret-ref #"\{\{secret\.([A-Za-z0-9_]+)\}\}")

(defn- resolve-secrets
  "Replaces {{secret.KEY}} refs from the vault; {:value s} or {:error msg} naming the missing key."
  [s]
  (if-let [missing (some (fn [[_ k]] (when (nil? (secrets/get-secret k)) k))
                         (re-seq secret-ref s))]
    {:error (str "'LLM Extra Headers' references {{secret." missing
                 "}}, which is not in the Secrets Vault.")}
    {:value (str/replace s secret-ref (fn [[_ k]] (secrets/get-secret k)))}))

(defn extra-headers
  "Parsed llmExtraHeaders as {:headers {name value}} (empty when unset), or {:error msg}."
  []
  (let [raw (setting "llmExtraHeaders")
        parsed (when raw (try (js/JSON.parse raw) (catch :default _ ::invalid)))]
    (cond
      (nil? raw) {:headers {}}

      (or (= ::invalid parsed) (not (object? parsed)))
      {:error "'LLM Extra Headers' must be a JSON object, e.g. {\"api-key\": \"{{secret.AZURE_KEY}}\"}."}

      :else
      (reduce (fn [acc [k v]]
                (if-not (string? v)
                  (reduced {:error (str "'LLM Extra Headers' value for \"" k "\" must be a string.")})
                  (let [{:keys [value error]} (resolve-secrets v)]
                    (cond
                      error (reduced {:error error})
                      ;; A blank value would count as a credential and send an unauthenticated request.
                      (str/blank? value)
                      (reduced {:error (str "'LLM Extra Headers' value for \"" k "\" is empty.")})
                      :else (assoc-in acc [:headers k] (str/trim value))))))
              {:headers {}}
              (js->clj parsed)))))

(defn configured?
  "True when an API key is present."
  []
  (some? (api-key)))
