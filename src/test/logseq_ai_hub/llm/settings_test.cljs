(ns logseq-ai-hub.llm.settings-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.llm.settings :as llm-settings]
            [logseq-ai-hub.agent :as agent]))

(defn setup-settings!
  "Installs a mock js/logseq carrying the given plugin settings."
  [settings-map]
  (set! js/logseq #js {:settings (clj->js settings-map)}))

;; ---------------------------------------------------------------------------
;; Trimming — the production failure was a pasted key with a leading space,
;; which produced "Bearer  sk-..." and a 401 "Missing Authentication header".
;; ---------------------------------------------------------------------------

(deftest test-api-key-trims-surrounding-whitespace
  (testing "a pasted key with a leading space is trimmed"
    (setup-settings! {"llmApiKey" " sk-or-v1-abc"})
    (is (= "sk-or-v1-abc" (llm-settings/api-key))))
  (testing "trailing whitespace and newlines are trimmed"
    (setup-settings! {"llmApiKey" "sk-or-v1-abc \n"})
    (is (= "sk-or-v1-abc" (llm-settings/api-key)))))

(deftest test-api-key-nil-when-absent-or-whitespace-only
  (testing "missing key is nil"
    (setup-settings! {})
    (is (nil? (llm-settings/api-key))))
  (testing "whitespace-only key is nil, not a blank Bearer token"
    (setup-settings! {"llmApiKey" "   "})
    (is (nil? (llm-settings/api-key)))))

(deftest test-endpoint-trimmed-and-slash-stripped
  (testing "surrounding whitespace and a trailing slash are removed"
    (setup-settings! {"llmEndpoint" "  https://openrouter.ai/api/v1/  "})
    (is (= "https://openrouter.ai/api/v1" (llm-settings/endpoint))))
  (testing "defaults when unset"
    (setup-settings! {})
    (is (= "https://openrouter.ai/api/v1" (llm-settings/endpoint)))))

(deftest test-model-defaults-when-blank
  (testing "an empty llmModel falls back to the default (empty string is truthy in CLJS)"
    (setup-settings! {"llmModel" ""})
    (is (= "anthropic/claude-sonnet-4" (llm-settings/model))))
  (testing "a configured model is used, trimmed"
    (setup-settings! {"llmModel" " deepseek/deepseek-v4-pro "})
    (is (= "deepseek/deepseek-v4-pro" (llm-settings/model)))))

(deftest test-chat-completions-url-has-no-double-slash
  (testing "endpoint with trailing slash still yields a single slash before the path"
    (setup-settings! {"llmEndpoint" "https://example.test/v1/"})
    (is (= "https://example.test/v1/chat/completions"
           (llm-settings/chat-completions-url)))))

;; ---------------------------------------------------------------------------
;; End-to-end through the /LLM seam: the header actually put on the wire.
;; ---------------------------------------------------------------------------

(deftest test-llm-handler-sends-clean-bearer-header-for-padded-key
  (testing "a key stored with a leading space still produces a single-space Bearer header"
    (setup-settings! {"llmApiKey" " sk-or-v1-padded"
                      "llmEndpoint" "https://example.test/v1"
                      "llmModel" "test/model"})
    (let [captured  (atom nil)
          orig-fetch (.-fetch js/globalThis)]
      (set! (.-fetch js/globalThis)
            (fn [url opts]
              (reset! captured {:url url
                                :auth (-> opts (aget "headers") (aget "Authorization"))})
              (js/Promise.resolve
                #js {:ok true
                     :json (fn [] (js/Promise.resolve
                                    #js {:choices #js [#js {:message #js {:content "ok"}}]}))})))
      (async done
        (-> ((agent/make-llm-handler) "hello" nil)
            (.then (fn [_]
                     (set! (.-fetch js/globalThis) orig-fetch)
                     (is (= "Bearer sk-or-v1-padded" (:auth @captured))
                         "Authorization must not contain the pasted leading space")
                     (is (= "https://example.test/v1/chat/completions" (:url @captured)))
                     (done)))
            (.catch (fn [e]
                      (set! (.-fetch js/globalThis) orig-fetch)
                      (is false (str "handler rejected: " e))
                      (done))))))))

(deftest test-llm-handler-reports-missing-key-without-calling-provider
  (testing "a whitespace-only key is treated as missing, so no request is made"
    (setup-settings! {"llmApiKey" "  "
                      "llmEndpoint" "https://example.test/v1"})
    (let [called?    (atom false)
          orig-fetch (.-fetch js/globalThis)]
      (set! (.-fetch js/globalThis)
            (fn [_ _] (reset! called? true) (js/Promise.resolve #js {:ok true})))
      (async done
        (-> ((agent/make-llm-handler) "hello" nil)
            (.then (fn [msg]
                     (set! (.-fetch js/globalThis) orig-fetch)
                     (is (false? @called?) "must not call the provider with a blank key")
                     (is (re-find #"API Key is missing" msg))
                     (done))))))))
