(ns logseq-ai-hub.secrets-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.secrets :as secrets]
            [logseq-ai-hub.settings-writer :as settings-writer]))

;; =============================================================================
;; Test Setup — Mock Logseq API for node test environment
;; =============================================================================

(def mock-settings (atom {"secretsVault" "{}"}))

(when-not (exists? js/logseq)
  (set! js/logseq
    #js {:settings (clj->js @mock-settings)
         :updateSettings (fn [updates]
                           (let [u (js->clj updates)]
                             (swap! mock-settings merge u)))
         :onSettingsChanged (fn [_cb] nil)
         :UI #js {:showMsg (fn [& _] nil)}
         :Editor #js {:registerSlashCommand (fn [& _] nil)
                      :getBlock (fn [_] (js/Promise.resolve nil))
                      :insertBlock (fn [& _] (js/Promise.resolve nil))
                      :updateBlock (fn [& _] (js/Promise.resolve nil))}}))

(defn- setup! []
  (secrets/reset-vault!)
  (settings-writer/reset-queue!))

;; =============================================================================
;; Key Validation Tests
;; =============================================================================

(deftest test-valid-key?
  (testing "Valid key names"
    (is (true? (secrets/valid-key? "API_KEY")))
    (is (true? (secrets/valid-key? "OPENROUTER_KEY")))
    (is (true? (secrets/valid-key? "A")))
    (is (true? (secrets/valid-key? "KEY_123")))
    (is (true? (secrets/valid-key? "X")))
    (is (true? (secrets/valid-key? "A1B2C3"))))

  (testing "Invalid key names"
    (is (false? (secrets/valid-key? "api-key")))
    (is (false? (secrets/valid-key? "key.name")))
    (is (false? (secrets/valid-key? "")))
    (is (false? (secrets/valid-key? "key with spaces")))
    (is (false? (secrets/valid-key? nil)))
    (is (false? (secrets/valid-key? "lowercase")))
    (is (false? (secrets/valid-key? "MiXeD")))
    (is (false? (secrets/valid-key? "KEY-NAME")))))

;; =============================================================================
;; Get/Has/List Tests (synchronous via direct atom manipulation)
;; =============================================================================

(deftest test-get-secret
  (testing "Get existing secret"
    (setup!)
    (reset! secrets/vault-state {"API_KEY" "sk-123"})
    (is (= "sk-123" (secrets/get-secret "API_KEY"))))

  (testing "Get non-existing secret returns nil"
    (setup!)
    (is (nil? (secrets/get-secret "MISSING"))))

  (testing "Get from empty vault returns nil"
    (setup!)
    (is (nil? (secrets/get-secret "ANYTHING")))))

(deftest test-has-secret?
  (testing "Returns true for existing key"
    (setup!)
    (reset! secrets/vault-state {"TOKEN" "value"})
    (is (true? (secrets/has-secret? "TOKEN"))))

  (testing "Returns false for non-existing key"
    (setup!)
    (is (false? (secrets/has-secret? "MISSING"))))

  (testing "Returns false for empty vault"
    (setup!)
    (is (false? (secrets/has-secret? "ANYTHING")))))

(deftest test-list-keys
  (testing "Returns all key names"
    (setup!)
    (reset! secrets/vault-state {"API_KEY" "v1" "TOKEN" "v2" "SECRET" "v3"})
    (let [ks (set (secrets/list-keys))]
      (is (= #{"API_KEY" "TOKEN" "SECRET"} ks))))

  (testing "Empty vault returns empty vector"
    (setup!)
    (is (= [] (secrets/list-keys))))

  (testing "Never includes values"
    (setup!)
    (reset! secrets/vault-state {"KEY" "super-secret-value"})
    (let [ks (secrets/list-keys)]
      (is (= ["KEY"] ks))
      (is (not (some #(= % "super-secret-value") ks))))))

;; =============================================================================
;; Set/Remove Tests (async — use vault-state for verification)
;; =============================================================================

(deftest test-set-secret-valid
  (testing "Set a new secret updates vault state"
    (setup!)
    (secrets/set-secret! "NEW_KEY" "new-value")
    (is (= "new-value" (secrets/get-secret "NEW_KEY")))))

(deftest test-set-secret-invalid-key
  (testing "Invalid key name is rejected"
    (setup!)
    (let [result (secrets/set-secret! "invalid-key" "value")]
      ;; Should return a rejected promise
      (.catch result
        (fn [err]
          (is (some? err)))))))

(deftest test-set-secret-overwrite
  (testing "Overwriting existing key updates value"
    (setup!)
    (reset! secrets/vault-state {"EXISTING" "old"})
    (secrets/set-secret! "EXISTING" "new")
    (is (= "new" (secrets/get-secret "EXISTING")))))

(deftest test-remove-secret-existing
  (testing "Remove existing secret"
    (setup!)
    (reset! secrets/vault-state {"TARGET" "value"})
    (secrets/remove-secret! "TARGET")
    (is (nil? (secrets/get-secret "TARGET")))))

(deftest test-remove-secret-nonexistent
  (testing "Remove non-existing secret is rejected"
    (setup!)
    (let [result (secrets/remove-secret! "GHOST")]
      (.catch result
        (fn [err]
          (is (some? err)))))))

;; =============================================================================
;; Redaction Tests
;; =============================================================================

(deftest test-redact-value
  (testing "Redacts known secret value"
    (setup!)
    (reset! secrets/vault-state {"API_KEY" "sk-secret-123"})
    (is (= "key: [REDACTED:API_KEY]"
           (secrets/redact-value "key: sk-secret-123" "API_KEY"))))

  (testing "Handles missing key gracefully"
    (setup!)
    (is (= "unchanged text"
           (secrets/redact-value "unchanged text" "MISSING"))))

  (testing "Handles nil text"
    (setup!)
    (is (nil? (secrets/redact-value nil "API_KEY")))))

(deftest test-redact-all
  (testing "Redacts all secrets in text"
    (setup!)
    (reset! secrets/vault-state {"KEY1" "secret1" "KEY2" "secret2"})
    (let [result (secrets/redact-all "Values: secret1 and secret2")]
      (is (not (re-find #"secret1" result)))
      (is (not (re-find #"secret2" result)))
      (is (re-find #"\[REDACTED:KEY1\]" result))
      (is (re-find #"\[REDACTED:KEY2\]" result))))

  (testing "Empty vault returns unchanged text"
    (setup!)
    (is (= "no secrets here"
           (secrets/redact-all "no secrets here"))))

  (testing "Handles nil text"
    (setup!)
    (is (nil? (secrets/redact-all nil)))))

;; =============================================================================
;; Reset Tests
;; =============================================================================

(deftest test-reset-vault
  (testing "Reset clears all secrets"
    (reset! secrets/vault-state {"KEY" "value"})
    (secrets/reset-vault!)
    (is (= {} @secrets/vault-state))
    (is (= [] (secrets/list-keys)))))

;; =============================================================================
;; Vault Size Limit Tests
;; =============================================================================

(deftest test-vault-size-limit
  (testing "Cannot exceed max-keys limit"
    (setup!)
    ;; Fill vault to capacity
    (reset! secrets/vault-state
      (into {} (map (fn [i] [(str "KEY_" i) (str "val_" i)]) (range 100))))
    (let [result (secrets/set-secret! "OVERFLOW" "value")]
      (.catch result
        (fn [err]
          (is (re-find #"vault full" (.-message err))))))))
