(ns logseq-ai-hub.agent-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.agent :as agent]))

(deftest test-register-model
  (testing "register-model adds handler to registry"
    (let [handler (fn [input _] (js/Promise.resolve input))]
      (agent/register-model "test-model" handler)
      (is (= handler (agent/get-model "test-model"))))))

(deftest test-get-model-nil
  (testing "get-model returns nil for unregistered model"
    (is (nil? (agent/get-model "nonexistent-model")))))

(deftest test-process-input-dispatch
  (testing "process-input dispatches to correct handler"
    (async done
      (let [handler (fn [input _] (js/Promise.resolve (str "handled:" input)))]
        (agent/register-model "dispatch-test" handler)
        (-> (agent/process-input "hello" "dispatch-test")
            (.then (fn [result]
                     (is (= "handled:hello" result))
                     (done))))))))

(deftest test-process-input-fallback
  (testing "process-input falls back to default-handler for unknown model"
    (async done
      (-> (agent/process-input "test" "unknown-model-xyz")
          (.then (fn [result]
                   (is (string? result))
                   (is (.includes result "test"))
                   (done)))))))

(deftest test-echo-handler
  (testing "echo-handler returns string containing input"
    (async done
      (-> (agent/echo-handler "hello world" "mock-model")
          (.then (fn [result]
                   (is (string? result))
                   (is (.includes result "hello world"))
                   (done)))))))

(deftest test-reverse-handler
  (testing "reverse-handler returns string containing reversed input"
    (async done
      (-> (agent/reverse-handler "hello" "reverse-model")
          (.then (fn [result]
                   (is (string? result))
                   (is (.includes result "olleh"))
                   (done)))))))

;; -----------------------------------------------------------------------------
;; Helpers for OpenAI fetch mocking
;; -----------------------------------------------------------------------------

(defn- setup-logseq-settings!
  "Sets up js/logseq with test settings."
  []
  (set! js/logseq
        #js {:settings #js {:llmApiKey "test-key"
                            :llmEndpoint "https://api.test.com/v1"
                            :llmModel "test-model"}}))

(defn- mock-fetch!
  "Installs a js/fetch mock that captures the request body and returns a
   successful response with the given content string. Returns an atom that
   will contain the parsed request body after the fetch is called."
  [response-content]
  (let [captured-body (atom nil)]
    (set! js/fetch
          (fn [_url opts]
            (let [body-str (.-body opts)
                  body-parsed (js->clj (js/JSON.parse body-str) :keywordize-keys true)]
              (reset! captured-body body-parsed))
            (js/Promise.resolve
             #js {:ok true
                  :json (fn []
                          (js/Promise.resolve
                           #js {:choices #js [#js {:message #js {:content response-content}}]}))})))
    captured-body))

;; -----------------------------------------------------------------------------
;; make-llm-handler tests
;; -----------------------------------------------------------------------------

(deftest test-make-llm-handler-no-system-prompt
  (testing "make-llm-handler with no system prompt sends only user message"
    (async done
      (setup-logseq-settings!)
      (let [captured-body (mock-fetch! "response")
            handler (agent/make-llm-handler)]
        (-> (handler "test input" "test-model-id")
            (.then (fn [result]
                     (is (= "response" result))
                     (is (= [{:role "user" :content "test input"}]
                            (:messages @captured-body)))
                     (done))))))))

(deftest test-make-llm-handler-with-system-prompt
  (testing "make-llm-handler with system prompt sends system + user messages"
    (async done
      (setup-logseq-settings!)
      (let [captured-body (mock-fetch! "response")
            handler (agent/make-llm-handler "You are a helpful assistant")]
        (-> (handler "test input" "test-model-id")
            (.then (fn [result]
                     (is (= "response" result))
                     (is (= [{:role "system" :content "You are a helpful assistant"}
                             {:role "user" :content "test input"}]
                            (:messages @captured-body)))
                     (done))))))))

(deftest test-process-with-system-prompt
  (testing "process-with-system-prompt sends system + user messages via fetch"
    (async done
      (setup-logseq-settings!)
      (let [captured-body (mock-fetch! "response")]
        (-> (agent/process-with-system-prompt "hello" "Be concise")
            (.then (fn [result]
                     (is (= "response" result))
                     (is (= [{:role "system" :content "Be concise"}
                             {:role "user" :content "hello"}]
                            (:messages @captured-body)))
                     (done))))))))
