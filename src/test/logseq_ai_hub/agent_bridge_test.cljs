(ns logseq-ai-hub.agent-bridge-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.agent-bridge :as bridge]
            [logseq-ai-hub.secrets :as secrets]
            [logseq-ai-hub.settings-writer :as settings-writer]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def callback-calls (atom []))

(defn setup-mocks! []
  (reset! callback-calls [])
  ;; Mock logseq with all APIs that agent-bridge handlers may reach.
  ;; - DB.datascriptQuery: used by graph/scan-skill-pages and graph/scan-job-pages
  ;; - Editor.getPageBlocksTree: used by graph/read-skill-page, graph/read-job-page
  ;; - Editor.createPage: used by handle-create-job and handle-create-skill
  (set! js/logseq
    #js {:settings #js {"webhookServerUrl" "http://localhost:3000"
                        "pluginApiToken" "test-token"
                        "skillPagePrefix" "Skills/"
                        "jobPagePrefix" "Jobs/"
                        "secretsVault" "{}"}
         :DB #js {:datascriptQuery (fn [_] (js/Promise.resolve #js []))}
         :Editor #js {:getPageBlocksTree (fn [_] (js/Promise.resolve #js []))
                      :createPage (fn [_ _ _] (js/Promise.resolve #js {}))}
         :updateSettings (fn [_] nil)
         :onSettingsChanged (fn [_] nil)
         :UI #js {:showMsg (fn [& _] nil)}})
  ;; Mock fetch to capture all outbound callback POSTs.
  ;; Parses the JSON body and records {url, body} for assertions.
  (set! js/fetch
    (fn [url opts]
      (let [body (js->clj (js/JSON.parse (.-body opts)) :keywordize-keys true)]
        (swap! callback-calls conj {:url url :body body}))
      (js/Promise.resolve #js {:ok true}))))

;; ---------------------------------------------------------------------------
;; Correlation ID Propagation Tests (FR-4 / FR-5)
;; ---------------------------------------------------------------------------

(deftest test-dispatch-includes-trace-id-in-callback
  (setup-mocks!)
  (testing "dispatch passes traceId through to the callback payload"
    (async done
      (bridge/dispatch-agent-request
        {"requestId" "req-123"
         "operation" "list_skills"
         "params" {}
         "traceId" "trace-abc-123"})
      ;; Allow the async chain (fetch mock + Promise.all) to resolve
      (js/setTimeout
        (fn []
          (is (pos? (count @callback-calls))
              "Expected at least one callback call to fetch")
          (when (pos? (count @callback-calls))
            (let [{:keys [body]} (first @callback-calls)]
              (is (= "trace-abc-123" (:traceId body))
                  "traceId should be propagated into callback body")
              (is (= "req-123" (:requestId body))
                  "requestId should be propagated into callback body")))
          (done))
        100))))

(deftest test-dispatch-works-without-trace-id
  (setup-mocks!)
  (testing "dispatch works normally when traceId is absent"
    (async done
      (bridge/dispatch-agent-request
        {"requestId" "req-456"
         "operation" "list_skills"
         "params" {}})
      (js/setTimeout
        (fn []
          (is (pos? (count @callback-calls))
              "Expected at least one callback call to fetch")
          (when (pos? (count @callback-calls))
            (let [{:keys [body]} (first @callback-calls)]
              (is (= "req-456" (:requestId body))
                  "requestId should be in callback body")
              ;; When traceId is nil, cond-> does not assoc it — key absent
              (is (not (contains? body :traceId))
                  "traceId should be absent from callback body when not provided")))
          (done))
        100))))

(deftest test-dispatch-unknown-operation-includes-trace-id
  (setup-mocks!)
  (testing "unknown operation callback still includes traceId and reports failure"
    (async done
      (bridge/dispatch-agent-request
        {"requestId" "req-789"
         "operation" "nonexistent_op"
         "traceId" "trace-xyz"})
      (js/setTimeout
        (fn []
          (is (pos? (count @callback-calls))
              "Expected callback even for unknown operation")
          (when (pos? (count @callback-calls))
            (let [{:keys [body]} (first @callback-calls)]
              (is (= "trace-xyz" (:traceId body))
                  "traceId should be present for unknown operation callbacks")
              (is (false? (:success body))
                  "success should be false for unknown operations")))
          (done))
        100))))

(deftest test-dispatch-known-operation-sends-success
  (setup-mocks!)
  (testing "known operation (list_skills) sends success=true in callback"
    (async done
      (bridge/dispatch-agent-request
        {"requestId" "req-111"
         "operation" "list_skills"
         "params" {}
         "traceId" "trace-success"})
      (js/setTimeout
        (fn []
          (is (pos? (count @callback-calls)))
          (when (pos? (count @callback-calls))
            (let [{:keys [body]} (first @callback-calls)]
              (is (true? (:success body))
                  "success should be true for a valid operation")
              (is (= "trace-success" (:traceId body)))))
          (done))
        150))))

(deftest test-callback-url-uses-server-url
  (setup-mocks!)
  (testing "callback is POSTed to webhookServerUrl/api/agent/callback"
    (async done
      (bridge/dispatch-agent-request
        {"requestId" "req-url-check"
         "operation" "list_skills"
         "params" {}})
      (js/setTimeout
        (fn []
          (is (pos? (count @callback-calls)))
          (when (pos? (count @callback-calls))
            (let [{:keys [url]} (first @callback-calls)]
              (is (= "http://localhost:3000/api/agent/callback" url)
                  "Callback should POST to the configured server URL")))
          (done))
        100))))

;; ---------------------------------------------------------------------------
;; Secrets Operation Tests (FR-6)
;; ---------------------------------------------------------------------------

(deftest test-list-secret-keys-operation
  (setup-mocks!)
  (testing "list_secret_keys returns key names from vault"
    (secrets/reset-vault!)
    (settings-writer/reset-queue!)
    (reset! secrets/vault-state {"API_KEY" "sk-123" "TOKEN" "tok-abc"})
    (async done
      (bridge/dispatch-agent-request
        {"requestId" "req-sec-1"
         "operation" "list_secret_keys"
         "params" {}
         "traceId" "trace-sec-1"})
      (js/setTimeout
        (fn []
          (is (pos? (count @callback-calls)))
          (when (pos? (count @callback-calls))
            (let [{:keys [body]} (first @callback-calls)]
              (is (true? (:success body)))
              (is (some? (:data body)))))
          (done))
        100))))

(deftest test-set-secret-operation
  (setup-mocks!)
  (testing "set_secret sends success callback"
    (secrets/reset-vault!)
    (settings-writer/reset-queue!)
    (async done
      (bridge/dispatch-agent-request
        {"requestId" "req-sec-2"
         "operation" "set_secret"
         "params" {"key" "NEW_KEY" "value" "new-value"}
         "traceId" "trace-sec-2"})
      (js/setTimeout
        (fn []
          (is (pos? (count @callback-calls)))
          (when (pos? (count @callback-calls))
            (let [{:keys [body]} (first @callback-calls)]
              (is (true? (:success body)))))
          (done))
        150))))

(deftest test-remove-secret-operation
  (setup-mocks!)
  (testing "remove_secret sends success callback"
    (secrets/reset-vault!)
    (settings-writer/reset-queue!)
    (reset! secrets/vault-state {"OLD_KEY" "old-value"})
    (async done
      (bridge/dispatch-agent-request
        {"requestId" "req-sec-3"
         "operation" "remove_secret"
         "params" {"key" "OLD_KEY"}
         "traceId" "trace-sec-3"})
      (js/setTimeout
        (fn []
          (is (pos? (count @callback-calls)))
          (when (pos? (count @callback-calls))
            (let [{:keys [body]} (first @callback-calls)]
              (is (true? (:success body)))))
          (done))
        150))))
