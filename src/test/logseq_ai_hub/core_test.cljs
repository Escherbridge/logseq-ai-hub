(ns logseq-ai-hub.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.core :as core]))

(def updated-settings (atom {}))

(defn setup-mocks!
  "Sets up mock logseq object with given settings."
  [settings-map]
  (reset! updated-settings {})
  (set! js/logseq
    #js {:settings (clj->js settings-map)
         :useSettingsSchema (fn [_] nil)
         :updateSettings (fn [updates]
                           (let [updates-map (js->clj updates :keywordize-keys true)]
                             (swap! updated-settings merge updates-map)
                             ;; Also update the mock settings object in place
                             (doseq [[k v] updates-map]
                               (aset js/logseq "settings" (name k) v))))
         :Editor #js {:registerSlashCommand (fn [_ _] nil)}}))

;; ---------------------------------------------------------------------------
;; Settings Schema Tests (FR-1 — provider-agnostic LLM settings)
;; ---------------------------------------------------------------------------

(deftest test-settings-schema-has-new-keys
  (testing "settings schema contains llmApiKey, llmEndpoint, llmModel"
    (let [keys (set (map :key core/settings-schema))]
      (is (contains? keys "llmApiKey"))
      (is (contains? keys "llmEndpoint"))
      (is (contains? keys "llmModel"))
      ;; Old OpenAI-specific keys should NOT be in schema
      (is (not (contains? keys "openAIKey")))
      (is (not (contains? keys "openAIEndpoint")))
      (is (not (contains? keys "chatModel"))))))

(deftest test-settings-schema-defaults
  (testing "new settings have correct defaults"
    (let [find-setting (fn [key] (first (filter #(= key (:key %)) core/settings-schema)))]
      (is (= "https://openrouter.ai/api/v1" (:default (find-setting "llmEndpoint"))))
      (is (= "anthropic/claude-sonnet-4" (:default (find-setting "llmModel"))))
      (is (= "" (:default (find-setting "llmApiKey")))))))

(deftest test-settings-schema-selected-model
  (testing "selectedModel enum includes llm-model as an option and default"
    (let [find-setting (fn [key] (first (filter #(= key (:key %)) core/settings-schema)))
          selected (find-setting "selectedModel")]
      (is (some #(= "llm-model" %) (:enumChoices selected)))
      (is (= "llm-model" (:default selected))))))

;; ---------------------------------------------------------------------------
;; Migration Tests (FR-1 — backwards-compat migration from old key names)
;; ---------------------------------------------------------------------------

(deftest test-migrate-copies-old-to-new
  (testing "migration copies old key values to new keys when new keys are empty"
    (setup-mocks! {"openAIKey" "sk-my-key"
                   "openAIEndpoint" "https://custom.api.com/v1"
                   "chatModel" "custom-model"
                   "llmApiKey" ""
                   "llmEndpoint" ""
                   "llmModel" ""})
    (core/migrate-settings!)
    (is (= "sk-my-key" (:llmApiKey @updated-settings)))
    (is (= "https://custom.api.com/v1" (:llmEndpoint @updated-settings)))
    (is (= "custom-model" (:llmModel @updated-settings)))))

(deftest test-migrate-preserves-new-values
  (testing "migration does NOT overwrite existing new key values"
    (setup-mocks! {"openAIKey" "old-key"
                   "llmApiKey" "already-set-key"
                   "openAIEndpoint" "old-endpoint"
                   "llmEndpoint" "already-set-endpoint"
                   "chatModel" "old-model"
                   "llmModel" "already-set-model"})
    (core/migrate-settings!)
    ;; updateSettings should not have been called — new keys already have values
    (is (empty? @updated-settings))))

(deftest test-migrate-skips-empty-old-values
  (testing "migration does NOT migrate when old values are empty strings"
    (setup-mocks! {"openAIKey" ""
                   "llmApiKey" ""
                   "openAIEndpoint" ""
                   "llmEndpoint" ""
                   "chatModel" ""
                   "llmModel" ""})
    (core/migrate-settings!)
    (is (empty? @updated-settings))))

(deftest test-migrate-handles-nil-old-values
  (testing "migration handles nil/absent old values gracefully"
    ;; Only new keys present, old keys absent (nil from aget)
    (setup-mocks! {"llmApiKey" ""
                   "llmEndpoint" ""
                   "llmModel" ""})
    (core/migrate-settings!)
    (is (empty? @updated-settings))))

(deftest test-migrate-partial-old-values
  (testing "migration migrates only the old keys that have values"
    (setup-mocks! {"openAIKey" "sk-partial"
                   "openAIEndpoint" ""
                   "chatModel" ""
                   "llmApiKey" ""
                   "llmEndpoint" ""
                   "llmModel" ""})
    (core/migrate-settings!)
    ;; Only llmApiKey should have been updated
    (is (= "sk-partial" (:llmApiKey @updated-settings)))
    (is (not (contains? @updated-settings :llmEndpoint)))
    (is (not (contains? @updated-settings :llmModel)))))

(deftest test-migrate-preserves-default-values-if-intentional
  (testing "migration does NOT overwrite new keys that hold the default value"
    ;; User intentionally has OpenRouter default; old OpenAI endpoint still lingers
    (setup-mocks! {"openAIEndpoint" "https://api.openai.com/v1"
                   "llmEndpoint" "https://openrouter.ai/api/v1"
                   "openAIKey" ""
                   "llmApiKey" ""
                   "chatModel" ""
                   "llmModel" ""})
    (core/migrate-settings!)
    ;; llmEndpoint should NOT be overwritten — it already has a value
    (is (not (contains? @updated-settings :llmEndpoint)))))
