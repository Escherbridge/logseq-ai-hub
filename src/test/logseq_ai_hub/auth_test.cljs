(ns logseq-ai-hub.auth-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.auth :as auth]
            [logseq-ai-hub.messaging :as messaging]
            [logseq-ai-hub.event-hub.publish :as publish]))

;; ---------------------------------------------------------------------------
;; Test Setup
;; ---------------------------------------------------------------------------

(defn setup-settings!
  "Installs a mock js/logseq with the given settings map."
  [settings-map]
  (set! js/logseq
    #js {:settings (clj->js settings-map)}))

;; ---------------------------------------------------------------------------
;; get-auth-mode Tests
;; ---------------------------------------------------------------------------

(deftest test-get-auth-mode-token
  (testing "returns 'token' when authMode is 'token'"
    (setup-settings! {"authMode" "token" "pluginApiToken" "tok-123"})
    (is (= "token" (auth/get-auth-mode)))))

(deftest test-get-auth-mode-jwt
  (testing "returns 'jwt' when authMode is 'jwt'"
    (setup-settings! {"authMode" "jwt" "jwtToken" "jwt-abc"})
    (is (= "jwt" (auth/get-auth-mode)))))

(deftest test-get-auth-mode-nil-defaults-to-token
  (testing "defaults to 'token' when authMode is nil/missing"
    (setup-settings! {"pluginApiToken" "tok-123"})
    (is (= "token" (auth/get-auth-mode)))))

(deftest test-get-auth-mode-empty-defaults-to-token
  (testing "defaults to 'token' when authMode is empty string"
    (setup-settings! {"authMode" "" "pluginApiToken" "tok-123"})
    (is (= "token" (auth/get-auth-mode)))))

;; ---------------------------------------------------------------------------
;; get-auth-token Tests
;; ---------------------------------------------------------------------------

(deftest test-get-auth-token-token-mode
  (testing "authMode='token' returns pluginApiToken"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" "tok-abc"
                      "jwtToken" "jwt-xyz"})
    (is (= "tok-abc" (auth/get-auth-token)))))

(deftest test-get-auth-token-jwt-mode
  (testing "authMode='jwt' returns jwtToken"
    (setup-settings! {"authMode" "jwt"
                      "pluginApiToken" "tok-abc"
                      "jwtToken" "jwt-xyz"})
    (is (= "jwt-xyz" (auth/get-auth-token)))))

(deftest test-get-auth-token-nil-mode-defaults-to-plugin-token
  (testing "authMode=nil defaults to 'token' behavior, returns pluginApiToken"
    (setup-settings! {"pluginApiToken" "tok-fallback"
                      "jwtToken" "jwt-xyz"})
    (is (= "tok-fallback" (auth/get-auth-token)))))

;; ---------------------------------------------------------------------------
;; get-server-url Tests
;; ---------------------------------------------------------------------------

(deftest test-get-server-url
  (testing "returns webhookServerUrl from settings"
    (setup-settings! {"webhookServerUrl" "https://my-hub.example.com"})
    (is (= "https://my-hub.example.com" (auth/get-server-url)))))

(deftest test-get-server-url-missing
  (testing "returns nil when webhookServerUrl is not set"
    (setup-settings! {})
    (is (nil? (auth/get-server-url)))))

;; ---------------------------------------------------------------------------
;; auth-configured? Tests
;; ---------------------------------------------------------------------------

(deftest test-auth-configured-true
  (testing "returns true when token and server URL are both present"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" "tok-abc"
                      "webhookServerUrl" "http://localhost:3000"})
    (is (true? (auth/auth-configured?)))))

(deftest test-auth-configured-false-blank-token
  (testing "returns false when token is blank"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" ""
                      "webhookServerUrl" "http://localhost:3000"})
    (is (false? (auth/auth-configured?)))))

(deftest test-auth-configured-false-nil-token
  (testing "returns false when token is nil (not set)"
    (setup-settings! {"authMode" "token"
                      "webhookServerUrl" "http://localhost:3000"})
    (is (false? (auth/auth-configured?)))))

(deftest test-auth-configured-false-blank-server-url
  (testing "returns false when server URL is blank"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" "tok-abc"
                      "webhookServerUrl" ""})
    (is (false? (auth/auth-configured?)))))

(deftest test-auth-configured-false-nil-server-url
  (testing "returns false when server URL is nil (not set)"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" "tok-abc"})
    (is (false? (auth/auth-configured?)))))

(deftest test-auth-configured-jwt-mode
  (testing "returns true for jwt mode when jwtToken and server URL present"
    (setup-settings! {"authMode" "jwt"
                      "jwtToken" "jwt-abc"
                      "webhookServerUrl" "http://localhost:3000"})
    (is (true? (auth/auth-configured?)))))

(deftest test-auth-configured-jwt-mode-blank-jwt
  (testing "returns false for jwt mode when jwtToken is blank"
    (setup-settings! {"authMode" "jwt"
                      "jwtToken" ""
                      "webhookServerUrl" "http://localhost:3000"})
    (is (false? (auth/auth-configured?)))))

;; ---------------------------------------------------------------------------
;; log-auth-warnings! Tests
;; ---------------------------------------------------------------------------

(def console-warnings (atom []))

(deftest test-log-auth-warnings-jwt-no-token
  (testing "logs warning when jwt mode but no jwtToken"
    (setup-settings! {"authMode" "jwt"
                      "jwtToken" ""
                      "webhookServerUrl" "http://localhost:3000"})
    (reset! console-warnings [])
    (let [orig-warn js/console.warn]
      (set! js/console.warn (fn [& args] (swap! console-warnings conj (apply str args))))
      (auth/log-auth-warnings!)
      (set! js/console.warn orig-warn)
      (is (= 1 (count @console-warnings)))
      (is (.includes (first @console-warnings) "JWT mode selected but no JWT token configured")))))

(deftest test-log-auth-warnings-token-no-api-token
  (testing "logs warning when token mode but no pluginApiToken"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" ""
                      "webhookServerUrl" "http://localhost:3000"})
    (reset! console-warnings [])
    (let [orig-warn js/console.warn]
      (set! js/console.warn (fn [& args] (swap! console-warnings conj (apply str args))))
      (auth/log-auth-warnings!)
      (set! js/console.warn orig-warn)
      (is (= 1 (count @console-warnings)))
      (is (.includes (first @console-warnings) "Token mode selected but no API token configured")))))

(deftest test-log-auth-warnings-no-warning-when-configured
  (testing "no warning when auth is properly configured"
    (setup-settings! {"authMode" "token"
                      "pluginApiToken" "tok-123"
                      "webhookServerUrl" "http://localhost:3000"})
    (reset! console-warnings [])
    (let [orig-warn js/console.warn]
      (set! js/console.warn (fn [& args] (swap! console-warnings conj (apply str args))))
      (auth/log-auth-warnings!)
      (set! js/console.warn orig-warn)
      (is (= 0 (count @console-warnings))))))

(deftest test-log-auth-warnings-default-mode-no-token
  (testing "logs warning when default (nil) mode and no pluginApiToken"
    (setup-settings! {"webhookServerUrl" "http://localhost:3000"})
    (reset! console-warnings [])
    (let [orig-warn js/console.warn]
      (set! js/console.warn (fn [& args] (swap! console-warnings conj (apply str args))))
      (auth/log-auth-warnings!)
      (set! js/console.warn orig-warn)
      (is (= 1 (count @console-warnings)))
      (is (.includes (first @console-warnings) "Token mode selected but no API token configured")))))

;; ---------------------------------------------------------------------------
;; SSE Integration Test (FR-5 -- messaging/connect! uses centralized auth)
;; ---------------------------------------------------------------------------

(deftest test-connect-builds-sse-url-with-jwt-when-auth-mode-jwt
  (testing "messaging/connect! resolves authMode=jwt and builds the SSE URL carrying the jwtToken"
    (setup-settings! {"authMode" "jwt"
                      "jwtToken" "jwt-xyz"
                      "webhookServerUrl" "https://h.example"})
    (let [captured-url (atom nil)
          orig-event-source js/EventSource]
      (set! js/EventSource
        (fn [url]
          (reset! captured-url url)
          #js {:addEventListener (fn [_type _handler] nil)
               :close (fn [] nil)}))
      (try
        (messaging/connect!)
        (is (= "https://h.example/events?token=jwt-xyz" @captured-url)
            "SSE URL should carry the resolved JWT, not pluginApiToken")
        (finally
          (set! js/EventSource orig-event-source)
          (messaging/disconnect!))))))

;; ---------------------------------------------------------------------------
;; End-to-End Auth Test (FR-4/FR-5 -- JWT flows through to the Authorization header)
;; ---------------------------------------------------------------------------

(deftest test-e2e-jwt-token-flows-to-http-authorization-header
  (testing "JWT-mode settings -> get-auth-token returns the JWT -> an HTTP sender issues Bearer <jwt>"
    (setup-settings! {"authMode" "jwt"
                      "jwtToken" "jwt-e2e-999"
                      "webhookServerUrl" "https://h.example"})
    (is (= "jwt-e2e-999" (auth/get-auth-token))
        "get-auth-token should resolve the JWT in jwt mode")
    (async done
      (let [captured-headers (atom nil)
            orig-fetch js/fetch]
        (set! js/fetch
          (fn [_url opts]
            (reset! captured-headers (aget opts "headers"))
            (js/Promise.resolve
              #js {:json (fn [] (js/Promise.resolve #js {:success true :eventId "evt-e2e"}))})))
        (-> (publish/publish-to-server! {:type "test.event" :source "plugin" :data {}})
            (.then (fn [_result]
                     (set! js/fetch orig-fetch)
                     (is (= "Bearer jwt-e2e-999" (aget @captured-headers "Authorization"))
                         "HTTP request Authorization header should carry Bearer <jwt>, not the pluginApiToken")
                     (done)))
            (.catch (fn [err]
                      (set! js/fetch orig-fetch)
                      (is false (str "Unexpected error: " err))
                      (done))))))))
