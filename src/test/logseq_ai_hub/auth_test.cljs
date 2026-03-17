(ns logseq-ai-hub.auth-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.auth :as auth]))

(defn- setup-settings! [settings-map]
  (set! js/logseq #js {:settings (clj->js settings-map)}))

;; ---------------------------------------------------------------------------
;; get-auth-mode
;; ---------------------------------------------------------------------------

(deftest test-get-auth-mode-defaults-to-token
  (testing "returns \"token\" when authMode is absent"
    (setup-settings! {})
    (is (= "token" (auth/get-auth-mode))))

  (testing "returns \"token\" when authMode is nil"
    (setup-settings! {"authMode" nil})
    (is (= "token" (auth/get-auth-mode))))

  (testing "returns \"token\" when authMode is explicitly \"token\""
    (setup-settings! {"authMode" "token"})
    (is (= "token" (auth/get-auth-mode)))))

(deftest test-get-auth-mode-jwt
  (testing "returns \"jwt\" when authMode is \"jwt\""
    (setup-settings! {"authMode" "jwt"})
    (is (= "jwt" (auth/get-auth-mode)))))

(deftest test-get-auth-mode-unknown-falls-back-to-token
  (testing "any unrecognized value falls back to \"token\""
    (setup-settings! {"authMode" "saml"})
    (is (= "token" (auth/get-auth-mode)))))

;; ---------------------------------------------------------------------------
;; get-auth-token
;; ---------------------------------------------------------------------------

(deftest test-get-auth-token-token-mode
  (testing "returns pluginApiToken in token mode"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" "secret-token"
                      "jwtToken" "should-not-be-used"})
    (is (= "secret-token" (auth/get-auth-token))))

  (testing "returns pluginApiToken when authMode is absent"
    (setup-settings! {"pluginApiToken" "fallback-token"
                      "jwtToken" "should-not-be-used"})
    (is (= "fallback-token" (auth/get-auth-token)))))

(deftest test-get-auth-token-jwt-mode
  (testing "returns jwtToken in jwt mode"
    (setup-settings! {"authMode" "jwt"
                      "pluginApiToken" "should-not-be-used"
                      "jwtToken" "eyJhbGciOiJIUzI1NiJ9.payload.sig"})
    (is (= "eyJhbGciOiJIUzI1NiJ9.payload.sig" (auth/get-auth-token)))))

(deftest test-get-auth-token-empty-values
  (testing "returns empty string when token is empty in token mode"
    (setup-settings! {"authMode" "token" "pluginApiToken" ""})
    (is (= "" (auth/get-auth-token))))

  (testing "returns empty string when jwtToken is empty in jwt mode"
    (setup-settings! {"authMode" "jwt" "jwtToken" ""})
    (is (= "" (auth/get-auth-token)))))

;; ---------------------------------------------------------------------------
;; auth-configured?
;; ---------------------------------------------------------------------------

(deftest test-auth-configured-true
  (testing "returns true when token and server URL are both non-blank in token mode"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" "my-token"
                      "webhookServerUrl" "https://hub.example.com"})
    (is (true? (auth/auth-configured?))))

  (testing "returns true when jwt token and server URL are both non-blank"
    (setup-settings! {"authMode" "jwt"
                      "jwtToken" "my-jwt"
                      "webhookServerUrl" "https://hub.example.com"})
    (is (true? (auth/auth-configured?)))))

(deftest test-auth-configured-false-missing-token
  (testing "returns false when token is blank"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" ""
                      "webhookServerUrl" "https://hub.example.com"})
    (is (false? (auth/auth-configured?))))

  (testing "returns false when token is absent"
    (setup-settings! {"webhookServerUrl" "https://hub.example.com"})
    (is (false? (auth/auth-configured?)))))

(deftest test-auth-configured-false-missing-url
  (testing "returns false when server URL is blank"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" "my-token"
                      "webhookServerUrl" ""})
    (is (false? (auth/auth-configured?))))

  (testing "returns false when server URL is absent"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" "my-token"})
    (is (false? (auth/auth-configured?)))))

(deftest test-auth-configured-false-both-missing
  (testing "returns false when both token and URL are absent"
    (setup-settings! {})
    (is (false? (auth/auth-configured?)))))
