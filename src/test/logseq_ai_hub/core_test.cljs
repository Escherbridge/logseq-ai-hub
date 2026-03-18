(ns logseq-ai-hub.core-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.core :as core]
            [logseq-ai-hub.settings-writer :as settings-writer]
            [clojure.string :as str]))

(def updated-settings (atom {}))

(defn setup-mocks!
  "Sets up mock logseq object with given settings."
  [settings-map]
  (reset! updated-settings {})
  (settings-writer/reset-queue!)
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

(deftest test-settings-schema-has-auth-keys
  (testing "settings schema contains authMode and jwtToken"
    (let [keys (set (map :key core/settings-schema))
          find-setting (fn [k] (first (filter #(= k (:key %)) core/settings-schema)))]
      (is (contains? keys "authMode"))
      (is (contains? keys "jwtToken"))
      (is (contains? keys "pluginApiToken"))
      (is (= "token" (:default (find-setting "authMode"))))
      (is (= "enum"  (:type  (find-setting "authMode"))))
      (is (= "" (:default (find-setting "jwtToken"))))
      (is (= ["token" "jwt"] (:enumChoices (find-setting "authMode")))))))

(deftest test-settings-schema-defaults
  (testing "new settings have correct defaults"
    (let [find-setting (fn [key] (first (filter #(= key (:key %)) core/settings-schema)))]
      (is (= "https://openrouter.ai/api/v1" (:default (find-setting "llmEndpoint"))))
      (is (= "anthropic/claude-sonnet-4" (:default (find-setting "llmModel"))))
      (is (= "" (:default (find-setting "llmApiKey")))))))

(deftest test-settings-schema-event-hub-keys
  (testing "settings schema contains event hub settings"
    (let [keys (set (map :key core/settings-schema))]
      (is (contains? keys "eventHubEnabled"))
      (is (contains? keys "httpAllowlist"))
      (is (contains? keys "eventRetentionDays"))
      (is (contains? keys "eventGraphPersistence")))))

;; ---------------------------------------------------------------------------
;; Migration Tests (FR-1 — backwards-compat migration from old key names)
;; ---------------------------------------------------------------------------

(deftest test-migrate-copies-old-to-new
  (testing "migration copies old key values to new keys when new keys are empty"
    (async done
      (setup-mocks! {"openAIKey" "sk-my-key"
                     "openAIEndpoint" "https://custom.api.com/v1"
                     "chatModel" "custom-model"
                     "llmApiKey" ""
                     "llmEndpoint" ""
                     "llmModel" ""})
      (core/migrate-settings!)
      ;; Wait for the settings writer queue to flush
      (-> (:promise @settings-writer/queue-state)
          (.then (fn [_]
                   (is (= "sk-my-key" (:llmApiKey @updated-settings)))
                   (is (= "https://custom.api.com/v1" (:llmEndpoint @updated-settings)))
                   (is (= "custom-model" (:llmModel @updated-settings)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

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
    (async done
      (setup-mocks! {"openAIKey" "sk-partial"
                     "openAIEndpoint" ""
                     "chatModel" ""
                     "llmApiKey" ""
                     "llmEndpoint" ""
                     "llmModel" ""})
      (core/migrate-settings!)
      ;; Wait for the settings writer queue to flush
      (-> (:promise @settings-writer/queue-state)
          (.then (fn [_]
                   ;; Only llmApiKey should have been updated
                   (is (= "sk-partial" (:llmApiKey @updated-settings)))
                   (is (not (contains? @updated-settings :llmEndpoint)))
                   (is (not (contains? @updated-settings :llmModel)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

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

;; ---------------------------------------------------------------------------
;; Auth Mode Migration Tests (FR-6)
;; ---------------------------------------------------------------------------

(deftest test-migrate-backfills-auth-mode-for-token-users
  (testing "existing token users get authMode set to \"token\" when it is absent"
    (async done
      (setup-mocks! {"pluginApiToken" "my-existing-token"})
      (core/migrate-settings!)
      (-> (:promise @settings-writer/queue-state)
          (.then (fn [_]
                   (is (= "token" (:authMode @updated-settings)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest test-migrate-does-not-overwrite-existing-auth-mode
  (testing "migration leaves authMode alone when it is already set"
    (setup-mocks! {"pluginApiToken" "my-token"
                   "authMode" "jwt"})
    (core/migrate-settings!)
    (is (not (contains? @updated-settings :authMode)))))

(deftest test-migrate-skips-auth-mode-backfill-when-no-token
  (testing "no authMode backfill when pluginApiToken is blank"
    (setup-mocks! {"pluginApiToken" ""})
    (core/migrate-settings!)
    (is (not (contains? @updated-settings :authMode))))

  (testing "no authMode backfill when pluginApiToken is absent"
    (setup-mocks! {})
    (core/migrate-settings!)
    (is (not (contains? @updated-settings :authMode)))))

;; ---------------------------------------------------------------------------
;; Auth Warning Tests (FR-7)
;; ---------------------------------------------------------------------------

(deftest test-check-auth-config-warns-jwt-mode-no-token
  (testing "warns when JWT mode is selected but jwtToken is blank"
    (let [warnings (atom [])]
      (setup-mocks! {"authMode" "jwt" "jwtToken" ""})
      (set! (.-warn js/console) (fn [& args] (swap! warnings conj (str/join " " args))))
      (#'core/check-auth-config!)
      (is (some #(str/includes? % "JWT mode") @warnings)))))

(deftest test-check-auth-config-warns-token-mode-no-token
  (testing "warns when token mode is selected but pluginApiToken is blank"
    (let [warnings (atom [])]
      (setup-mocks! {"authMode" "token" "pluginApiToken" ""})
      (set! (.-warn js/console) (fn [& args] (swap! warnings conj (str/join " " args))))
      (#'core/check-auth-config!)
      (is (some #(str/includes? % "no API token") @warnings)))))

(deftest test-check-auth-config-no-warning-when-configured
  (testing "no warning when token mode and token is set"
    (let [warnings (atom [])]
      (setup-mocks! {"authMode" "token" "pluginApiToken" "my-token"})
      (set! (.-warn js/console) (fn [& args] (swap! warnings conj (str/join " " args))))
      (#'core/check-auth-config!)
      (is (empty? @warnings))))

  (testing "no warning when jwt mode and jwt token is set"
    (let [warnings (atom [])]
      (setup-mocks! {"authMode" "jwt" "jwtToken" "eyJ.payload.sig"})
      (set! (.-warn js/console) (fn [& args] (swap! warnings conj (str/join " " args))))
      (#'core/check-auth-config!)
      (is (empty? @warnings)))))
