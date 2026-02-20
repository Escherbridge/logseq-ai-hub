(ns logseq-ai-hub.job-runner.openclaw-test
  (:require [cljs.test :refer [deftest testing is async]]
            [logseq-ai-hub.job-runner.openclaw :as openclaw]))

;;; ============================================================================
;;; Test Data
;;; ============================================================================

(def valid-openclaw-json
  {:name "summarize"
   :version "1.0.0"
   :description "Summarizes text input"
   :type "llm-chain"
   :author "user"
   :tags ["summarize" "llm"]
   :inputs [{:name "text" :type "string" :required true :description "Text to summarize"}]
   :outputs [{:name "summary" :type "string" :description "Summarized text"}]
   :steps [{:order 1
            :action "llm-call"
            :config {:model "gpt-4" :prompt "Summarize: {{text}}"}}]
   :metadata {:created "2026-01-01T00:00:00Z"
              :source "openclaw"}})

(def expected-skill-def
  {:skill-id "Skills/summarize"
   :skill-type "llm-chain"
   :skill-description "Summarizes text input"
   :skill-inputs [{:name "text" :type "string" :required true :description "Text to summarize"}]
   :skill-outputs [{:name "summary" :type "string" :description "Summarized text"}]
   :skill-tags ["summarize" "llm"]
   :steps [{:step-order 1
            :step-action :llm-call
            :step-config {:model "gpt-4" :prompt "Summarize: {{text}}"}}]
   :openclaw-meta {:version "1.0.0"
                   :author "user"
                   :metadata {:created "2026-01-01T00:00:00Z"
                              :source "openclaw"}}})

(def minimal-openclaw-json
  {:name "simple-skill"
   :type "tool-chain"
   :steps [{:order 1
            :action "graph-query"
            :config {:query "..."}}]})

(def minimal-skill-def
  {:skill-id "Skills/simple-skill"
   :skill-type "tool-chain"
   :steps [{:step-order 1
            :step-action :graph-query
            :step-config {:query "..."}}]})

;;; ============================================================================
;;; Validation Tests
;;; ============================================================================

(deftest test-validate-openclaw-json
  (testing "valid JSON passes validation"
    (let [result (openclaw/validate-openclaw-json valid-openclaw-json)]
      (is (true? (:valid result)))
      (is (nil? (:errors result)))))

  (testing "minimal valid JSON passes"
    (let [result (openclaw/validate-openclaw-json minimal-openclaw-json)]
      (is (true? (:valid result)))))

  (testing "missing required field 'name' fails"
    (let [invalid (dissoc valid-openclaw-json :name)
          result (openclaw/validate-openclaw-json invalid)]
      (is (false? (:valid result)))
      (is (some #(re-find #"name" %) (:errors result)))))

  (testing "missing required field 'type' fails"
    (let [invalid (dissoc valid-openclaw-json :type)
          result (openclaw/validate-openclaw-json invalid)]
      (is (false? (:valid result)))
      (is (some #(re-find #"type" %) (:errors result)))))

  (testing "missing required field 'steps' fails"
    (let [invalid (dissoc valid-openclaw-json :steps)
          result (openclaw/validate-openclaw-json invalid)]
      (is (false? (:valid result)))
      (is (some #(re-find #"steps" %) (:errors result)))))

  (testing "invalid skill type fails"
    (let [invalid (assoc valid-openclaw-json :type "invalid-type")
          result (openclaw/validate-openclaw-json invalid)]
      (is (false? (:valid result)))
      (is (some #(re-find #"type" %) (:errors result)))))

  (testing "empty steps array fails"
    (let [invalid (assoc valid-openclaw-json :steps [])
          result (openclaw/validate-openclaw-json invalid)]
      (is (false? (:valid result)))
      (is (some #(re-find #"steps" %) (:errors result)))))

  (testing "step missing order fails"
    (let [invalid (assoc valid-openclaw-json :steps [{:action "llm-call" :config {}}])
          result (openclaw/validate-openclaw-json invalid)]
      (is (false? (:valid result)))
      (is (some #(re-find #"order" %) (:errors result)))))

  (testing "step missing action fails"
    (let [invalid (assoc valid-openclaw-json :steps [{:order 1 :config {}}])
          result (openclaw/validate-openclaw-json invalid)]
      (is (false? (:valid result)))
      (is (some #(re-find #"action" %) (:errors result)))))

  (testing "invalid step action fails"
    (let [invalid (assoc valid-openclaw-json :steps [{:order 1 :action "invalid-action" :config {}}])
          result (openclaw/validate-openclaw-json invalid)]
      (is (false? (:valid result)))
      (is (some #(re-find #"action" %) (:errors result))))))

;;; ============================================================================
;;; Import Tests
;;; ============================================================================

(deftest test-openclaw->logseq
  (testing "converts complete OpenClaw JSON to Logseq skill def"
    (let [result (openclaw/openclaw->logseq valid-openclaw-json)]
      (is (= "Skills/summarize" (:skill-id result)))
      (is (= "llm-chain" (:skill-type result)))
      (is (= "Summarizes text input" (:skill-description result)))
      (is (= [{:name "text" :type "string" :required true :description "Text to summarize"}]
             (:skill-inputs result)))
      (is (= [{:name "summary" :type "string" :description "Summarized text"}]
             (:skill-outputs result)))
      (is (= ["summarize" "llm"] (:skill-tags result)))
      (is (= [{:step-order 1
               :step-action :llm-call
               :step-config {:model "gpt-4" :prompt "Summarize: {{text}}"}}]
             (:steps result)))
      (is (= {:version "1.0.0"
              :author "user"
              :metadata {:created "2026-01-01T00:00:00Z"
                         :source "openclaw"}}
             (:openclaw-meta result)))))

  (testing "converts minimal OpenClaw JSON"
    (let [result (openclaw/openclaw->logseq minimal-openclaw-json)]
      (is (= "Skills/simple-skill" (:skill-id result)))
      (is (= "tool-chain" (:skill-type result)))
      (is (nil? (:skill-description result)))
      (is (nil? (:skill-inputs result)))
      (is (nil? (:skill-outputs result)))
      (is (nil? (:skill-tags result)))
      (is (= [{:step-order 1
               :step-action :graph-query
               :step-config {:query "..."}}]
             (:steps result)))))

  (testing "handles multiple steps with correct ordering"
    (let [multi-step (assoc valid-openclaw-json
                            :steps [{:order 1 :action "llm-call" :config {:model "gpt-4"}}
                                    {:order 2 :action "transform" :config {:template "..."}}])
          result (openclaw/openclaw->logseq multi-step)]
      (is (= 2 (count (:steps result))))
      (is (= :llm-call (:step-action (first (:steps result)))))
      (is (= :transform (:step-action (second (:steps result)))))))

  (testing "handles empty inputs/outputs arrays"
    (let [empty-io (assoc valid-openclaw-json :inputs [] :outputs [])
          result (openclaw/openclaw->logseq empty-io)]
      (is (= [] (:skill-inputs result)))
      (is (= [] (:skill-outputs result))))))

(deftest test-import-skill
  (testing "imports valid JSON string"
    (let [json-str (js/JSON.stringify (clj->js valid-openclaw-json))
          result (openclaw/import-skill json-str)]
      (is (contains? result :ok))
      (is (= "Skills/summarize" (get-in result [:ok :skill-id])))))

  (testing "returns error for invalid JSON syntax"
    (let [result (openclaw/import-skill "invalid json {{{")]
      (is (contains? result :error))
      (is (string? (:error result)))))

  (testing "returns error for invalid skill definition"
    (let [invalid (dissoc valid-openclaw-json :name)
          json-str (js/JSON.stringify (clj->js invalid))
          result (openclaw/import-skill json-str)]
      (is (contains? result :error))
      (is (string? (:error result))))))

;;; ============================================================================
;;; Export Tests
;;; ============================================================================

(deftest test-logseq->openclaw
  (testing "converts Logseq skill def to OpenClaw JSON"
    (let [result (openclaw/logseq->openclaw expected-skill-def)]
      (is (= "summarize" (:name result)))
      (is (= "llm-chain" (:type result)))
      (is (= "Summarizes text input" (:description result)))
      (is (= [{:name "text" :type "string" :required true :description "Text to summarize"}]
             (:inputs result)))
      (is (= [{:name "summary" :type "string" :description "Summarized text"}]
             (:outputs result)))
      (is (= ["summarize" "llm"] (:tags result)))
      (is (= [{:order 1
               :action "llm-call"
               :config {:model "gpt-4" :prompt "Summarize: {{text}}"}}]
             (:steps result)))
      (is (= "1.0.0" (:version result)))
      (is (= "user" (:author result)))
      (is (= {:created "2026-01-01T00:00:00Z"
              :source "openclaw"}
             (:metadata result)))))

  (testing "uses defaults when openclaw-meta is missing"
    (let [no-meta (dissoc expected-skill-def :openclaw-meta)
          result (openclaw/logseq->openclaw no-meta)]
      (is (= "1.0.0" (:version result)))
      (is (= "logseq-ai-hub" (:author result)))
      (is (nil? (:metadata result)))))

  (testing "converts minimal skill def"
    (let [result (openclaw/logseq->openclaw minimal-skill-def)]
      (is (= "simple-skill" (:name result)))
      (is (= "tool-chain" (:type result)))
      (is (nil? (:description result)))
      (is (nil? (:inputs result)))
      (is (nil? (:outputs result)))
      (is (nil? (:tags result)))
      (is (= [{:order 1
               :action "graph-query"
               :config {:query "..."}}]
             (:steps result))))))

(deftest test-export-skill
  (testing "exports skill def to JSON string"
    (let [json-str (openclaw/export-skill expected-skill-def)
          parsed (js->clj (js/JSON.parse json-str) :keywordize-keys true)]
      (is (string? json-str))
      (is (= "summarize" (:name parsed)))
      (is (= "llm-chain" (:type parsed))))))

(deftest test-roundtrip
  (testing "import then export produces equivalent JSON"
    (let [json-str (js/JSON.stringify (clj->js valid-openclaw-json))
          imported (openclaw/import-skill json-str)
          skill-def (:ok imported)
          exported-str (openclaw/export-skill skill-def)
          exported-json (js->clj (js/JSON.parse exported-str) :keywordize-keys true)]
      (is (= (:name valid-openclaw-json) (:name exported-json)))
      (is (= (:type valid-openclaw-json) (:type exported-json)))
      (is (= (:description valid-openclaw-json) (:description exported-json)))
      (is (= (:inputs valid-openclaw-json) (:inputs exported-json)))
      (is (= (:outputs valid-openclaw-json) (:outputs exported-json)))
      (is (= (:tags valid-openclaw-json) (:tags exported-json)))
      (is (= (:steps valid-openclaw-json) (:steps exported-json))))))

;;; ============================================================================
;;; Graph Integration Tests
;;; ============================================================================

(deftest test-import-skill-to-graph
  (testing "creates page in Logseq graph"
    (async done
      (let [page-created (atom nil)
            blocks-appended (atom [])
            mock-create-page (fn [page-name]
                               (reset! page-created page-name)
                               (js/Promise.resolve #js {:uuid "test-uuid"}))
            mock-append-block (fn [uuid content opts]
                                (swap! blocks-appended conj {:uuid uuid :content content :opts opts})
                                (js/Promise.resolve #js {:uuid "block-uuid"}))
            json-str (js/JSON.stringify (clj->js valid-openclaw-json))]
        ;; Mock Logseq API
        (set! js/logseq #js {:Editor #js {:createPage mock-create-page
                                          :appendBlockInPage mock-append-block}})
        (-> (openclaw/import-skill-to-graph! json-str)
            (.then (fn [result]
                     (is (= "Skills/summarize" @page-created))
                     (is (pos? (count @blocks-appended)))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Should not error: " err))
                      (done))))))))

(deftest test-export-skill-from-graph
  (testing "reads skill page and exports to JSON"
    (async done
      (let [mock-graph-fn (fn [page-name]
                            (js/Promise.resolve (clj->js expected-skill-def)))
            json-str-promise (binding [openclaw/graph-read-skill-page mock-graph-fn]
                               (openclaw/export-skill-from-graph! "Skills/summarize"))]
        (-> json-str-promise
            (.then (fn [json-str]
                     (is (string? json-str))
                     (let [parsed (js->clj (js/JSON.parse json-str) :keywordize-keys true)]
                       (is (= "summarize" (:name parsed)))
                       (is (= "llm-chain" (:type parsed))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Should not error: " err))
                      (done)))))))

  (testing "returns error when skill page not found"
    (async done
      (let [mock-graph-fn (fn [page-name]
                            (js/Promise.resolve nil))
            json-str-promise (binding [openclaw/graph-read-skill-page mock-graph-fn]
                               (openclaw/export-skill-from-graph! "Skills/nonexistent"))]
        (-> json-str-promise
            (.then (fn [result]
                     (is false "Should not succeed")
                     (done)))
            (.catch (fn [err]
                      (is (string? (.-message err)))
                      (done))))))))
