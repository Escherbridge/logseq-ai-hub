(ns logseq-ai-hub.llm.settings
  "Single reader for the LLM provider settings (key, endpoint, model).
   Values are trimmed: a pasted key with surrounding whitespace produced
   `Authorization: Bearer  sk-...` and a 401 from the provider."
  (:require [clojure.string :as str]))

(def default-endpoint "https://openrouter.ai/api/v1")
(def default-model "anthropic/claude-sonnet-4")

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

(defn chat-completions-url
  "Full chat-completions URL for the configured endpoint."
  []
  (str (endpoint) "/chat/completions"))

(defn configured?
  "True when an API key is present."
  []
  (some? (api-key)))
