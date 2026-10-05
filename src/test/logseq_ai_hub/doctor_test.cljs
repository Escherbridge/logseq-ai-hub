(ns logseq-ai-hub.doctor-test
  "Drives run-diagnostics! with the host and provider edges stubbed, asserting
   the report a user would actually read."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as str]
            [logseq-ai-hub.doctor :as doctor]))

(defn- setup! [settings-map]
  (set! js/logseq #js {:settings (clj->js settings-map)
                       :App #js {:showMsg (fn [_ _] nil)}}))

(defn- stub-fetch!
  "Routes by URL substring. Each handler returns a map {:ok :status :body}."
  [routes]
  (set! (.-fetch js/globalThis)
        (fn [url & _]
          (let [u (str url)
                hit (some (fn [[frag resp]] (when (str/includes? u frag) resp)) routes)]
            (if hit
              (js/Promise.resolve
                #js {:ok (:ok hit)
                     :status (:status hit)
                     :json (fn [] (js/Promise.resolve (clj->js (:body hit {}))))})
              (js/Promise.reject (js/Error. (str "unrouted: " u))))))))

(defn- with-fetch [routes f]
  (let [orig (.-fetch js/globalThis)]
    (stub-fetch! routes)
    (f (fn [] (set! (.-fetch js/globalThis) orig)))))

;; ---------------------------------------------------------------------------

(deftest test-doctor-reports-healthy-llm-and-linked-server
  (testing "a fully working configuration reports the plugin as linked"
    (setup! {"llmApiKey" "sk-or-v1-abcdefghijklmnop"
             "llmEndpoint" "https://provider.test/v1"
             "llmModel" "test/model"
             "webhookServerUrl" "https://hub.test"
             "pluginApiToken" "tok-123"
             "authMode" "token"
             "memoryEnabled" true
             "jobRunnerEnabled" true
             "httpAllowlist" "[\"api.example.com\"]"})
    (async done
      (with-fetch
        {"/models" {:ok true :status 200}
         "/health" {:ok true :status 200
                    :body {"agentApi" {"pluginConnected" true} "mcp" {"tools" 95}}}}
        (fn [restore]
          (-> (doctor/run-diagnostics!)
              (.then (fn [report]
                       (restore)
                       (is (str/includes? report "✅ **LLM**")
                           "a reachable provider that accepts the key reports OK")
                       (is (str/includes? report "THIS plugin is linked")
                           "pluginConnected true must be reported as linked")
                       (is (str/includes? report "95 MCP tools"))
                       (is (not (str/includes? report "sk-or-v1-abcdefghijklmnop"))
                           "the API key must never appear in full")
                       (done)))
              (.catch (fn [e] (restore) (is false (str "rejected: " e)) (done)))))))))

(deftest test-doctor-names-the-setting-for-a-rejected-key
  (testing "a 401 from the provider points at the API key setting"
    (setup! {"llmApiKey" "sk-bad-key-value"
             "llmEndpoint" "https://provider.test/v1"
             "webhookServerUrl" ""})
    (async done
      (with-fetch
        {"/models" {:ok false :status 401}}
        (fn [restore]
          (-> (doctor/run-diagnostics!)
              (.then (fn [report]
                       (restore)
                       (is (str/includes? report "❌ **LLM**"))
                       (is (str/includes? report "LLM API Key")
                           "must name the setting to change")
                       (done)))
              (.catch (fn [e] (restore) (is false (str "rejected: " e)) (done)))))))))

(deftest test-doctor-distinguishes-healthy-server-from-linked-plugin
  (testing "a reachable server with no plugin attached is a warning, not an OK"
    (setup! {"llmApiKey" "sk-test-key-1234"
             "llmEndpoint" "https://provider.test/v1"
             "webhookServerUrl" "https://hub.test"
             "pluginApiToken" "tok-123"
             "authMode" "token"})
    (async done
      (with-fetch
        {"/models" {:ok true :status 200}
         "/health" {:ok true :status 200
                    :body {"agentApi" {"pluginConnected" false} "mcp" {"tools" 95}}}}
        (fn [restore]
          (-> (doctor/run-diagnostics!)
              (.then (fn [report]
                       (restore)
                       (is (str/includes? report "⚠️ **Server**"))
                       (is (str/includes? report "no plugin is linked"))
                       (is (str/includes? report "PLUGIN_API_TOKEN")
                           "must say which value has to match")
                       (done)))
              (.catch (fn [e] (restore) (is false (str "rejected: " e)) (done)))))))))

(deftest test-doctor-flags-unrestricted-and-invalid-allowlist
  (testing "an empty allowlist is reported as allowing all hosts"
    (setup! {"llmApiKey" "sk-test-key-1234"
             "llmEndpoint" "https://provider.test/v1"
             "webhookServerUrl" ""
             "httpAllowlist" "[]"})
    (async done
      (with-fetch {"/models" {:ok true :status 200}}
        (fn [restore]
          (-> (doctor/run-diagnostics!)
              (.then (fn [report]
                       (restore)
                       (is (str/includes? report "allows ALL hosts"))
                       (done)))
              (.catch (fn [e] (restore) (is false (str "rejected: " e)) (done))))))))
  (testing "malformed JSON settings are reported as invalid, not ignored"
    (setup! {"llmApiKey" "sk-test-key-1234"
             "llmEndpoint" "https://provider.test/v1"
             "webhookServerUrl" ""
             "httpAllowlist" "[\"unclosed"
             "secretsVault" "{not json}"})
    (async done
      (with-fetch {"/models" {:ok true :status 200}}
        (fn [restore]
          (-> (doctor/run-diagnostics!)
              (.then (fn [report]
                       (restore)
                       (is (str/includes? report "❌ **HTTP Allowlist**"))
                       (is (str/includes? report "UNRESTRICTED"))
                       (is (str/includes? report "❌ **Secrets Vault**"))
                       (done)))
              (.catch (fn [e] (restore) (is false (str "rejected: " e)) (done)))))))))

(deftest test-doctor-reports-unreachable-server-without-throwing
  (testing "a network failure becomes a report line, not a rejected promise"
    (setup! {"llmApiKey" "sk-test-key-1234"
             "llmEndpoint" "https://provider.test/v1"
             "webhookServerUrl" "https://down.test"
             "pluginApiToken" "tok-123"
             "authMode" "token"})
    (async done
      (with-fetch {"/models" {:ok true :status 200}}   ; /health is unrouted -> rejects
        (fn [restore]
          (-> (doctor/run-diagnostics!)
              (.then (fn [report]
                       (restore)
                       (is (str/includes? report "❌ **Server**"))
                       (is (str/includes? report "could not reach https://down.test"))
                       (done)))
              (.catch (fn [e] (restore) (is false (str "diagnostics must not reject: " e)) (done)))))))))
