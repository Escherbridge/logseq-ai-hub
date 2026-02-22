(ns logseq-ai-hub.job-runner.interpolation-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.job-runner.interpolation :as interp]))

(deftest test-interpolate-inputs
  (testing "Interpolate from inputs context"
    (let [context {:inputs {"query" "today's journal" "detail-level" "brief"}
                   :step-results {}
                   :variables {}}
          template "Search for {{query}} with {{detail-level}} detail"
          result (interp/interpolate template context)]
      (is (= "Search for today's journal with brief detail" result))))

  (testing "Multiple references to same input"
    (let [context {:inputs {"name" "Claude"}
                   :step-results {}
                   :variables {}}
          template "Hello {{name}}! How are you, {{name}}?"
          result (interp/interpolate template context)]
      (is (= "Hello Claude! How are you, Claude?" result)))))

(deftest test-interpolate-step-results
  (testing "Interpolate from step results"
    (let [context {:inputs {}
                   :step-results {1 "First step output" 2 "Second step output"}
                   :variables {}}
          template "Step 1: {{step-1-result}}, Step 2: {{step-2-result}}"
          result (interp/interpolate template context)]
      (is (= "Step 1: First step output, Step 2: Second step output" result))))

  (testing "Step result reference format variations"
    (let [context {:inputs {}
                   :step-results {1 "output1" 2 "output2" 10 "output10"}
                   :variables {}}
          template "{{step-1-result}} {{step-2-result}} {{step-10-result}}"
          result (interp/interpolate template context)]
      (is (= "output1 output2 output10" result)))))

(deftest test-interpolate-variables
  (testing "Interpolate from variables"
    (let [context {:inputs {}
                   :step-results {}
                   :variables {:today "2026-02-19" :now "2026-02-19T10:00:00Z"}}
          template "Today is {{today}} and now is {{now}}"
          result (interp/interpolate template context)]
      (is (= "Today is 2026-02-19 and now is 2026-02-19T10:00:00Z" result))))

  (testing "Job ID variable"
    (let [context {:inputs {}
                   :step-results {}
                   :variables {:job-id "Jobs/test-job-123"}}
          template "Processing {{job-id}}"
          result (interp/interpolate template context)]
      (is (= "Processing Jobs/test-job-123" result)))))

(deftest test-interpolate-mixed-sources
  (testing "Interpolate from all context sources"
    (let [context {:inputs {"query" "search term"}
                   :step-results {1 "result1"}
                   :variables {:today "2026-02-19" :job-id "Jobs/test"}}
          template "Job {{job-id}} on {{today}}: query={{query}}, previous={{step-1-result}}"
          result (interp/interpolate template context)]
      (is (= "Job Jobs/test on 2026-02-19: query=search term, previous=result1" result)))))

(deftest test-interpolate-missing-variables
  (testing "Missing input replaced with empty string"
    (let [context {:inputs {"existing" "value"}
                   :step-results {}
                   :variables {}}
          template "{{existing}} {{missing}}"
          result (interp/interpolate template context)]
      (is (= "value " result))))

  (testing "Missing step result replaced with empty string"
    (let [context {:inputs {}
                   :step-results {1 "exists"}
                   :variables {}}
          template "{{step-1-result}} {{step-99-result}}"
          result (interp/interpolate template context)]
      (is (= "exists " result))))

  (testing "Missing variable replaced with empty string"
    (let [context {:inputs {}
                   :step-results {}
                   :variables {:existing "value"}}
          template "{{existing}} {{missing}}"
          result (interp/interpolate template context)]
      (is (= "value " result)))))

(deftest test-interpolate-edge-cases
  (testing "Empty template returns empty string"
    (let [context {:inputs {} :step-results {} :variables {}}
          result (interp/interpolate "" context)]
      (is (= "" result))))

  (testing "Template with no placeholders returns unchanged"
    (let [context {:inputs {} :step-results {} :variables {}}
          template "No placeholders here"
          result (interp/interpolate template context)]
      (is (= "No placeholders here" result))))

  (testing "Multiple consecutive placeholders"
    (let [context {:inputs {"a" "A" "b" "B" "c" "C"}
                   :step-results {}
                   :variables {}}
          template "{{a}}{{b}}{{c}}"
          result (interp/interpolate template context)]
      (is (= "ABC" result))))

  (testing "Placeholders with spaces in names are not matched"
    (let [context {:inputs {"test" "value"}
                   :step-results {}
                   :variables {}}
          template "{{test}} {{ spaced }}"
          result (interp/interpolate template context)]
      (is (= "value {{ spaced }}" result))))

  (testing "Malformed placeholders are left unchanged"
    (let [context {:inputs {"test" "value"}
                   :step-results {}
                   :variables {}}
          template "{{test}} {incomplete {{also-incomplete}"
          result (interp/interpolate template context)]
      (is (= "value {incomplete {{also-incomplete}" result))))

  (testing "Nested braces are not processed recursively"
    (let [context {:inputs {"outer" "{{inner}}"}
                   :step-results {}
                   :variables {}}
          template "{{outer}}"
          result (interp/interpolate template context)]
      (is (= "{{inner}}" result)))))

(deftest test-interpolate-special-characters
  (testing "Values with special characters"
    (let [context {:inputs {"text" "Value with \"quotes\" and 'apostrophes'"}
                   :step-results {}
                   :variables {}}
          template "Text: {{text}}"
          result (interp/interpolate template context)]
      (is (= "Text: Value with \"quotes\" and 'apostrophes'" result))))

  (testing "Values with newlines"
    (let [context {:inputs {"multiline" "Line 1\nLine 2\nLine 3"}
                   :step-results {}
                   :variables {}}
          template "Content:\n{{multiline}}"
          result (interp/interpolate template context)]
      (is (= "Content:\nLine 1\nLine 2\nLine 3" result))))

  (testing "Values with special regex characters"
    (let [context {:inputs {"regex" "$100 + 50% = $150 (approx.)"}
                   :step-results {}
                   :variables {}}
          template "Price: {{regex}}"
          result (interp/interpolate template context)]
      (is (= "Price: $100 + 50% = $150 (approx.)" result)))))

(deftest test-interpolate-lookup-precedence
  (testing "Input lookup uses inputs map"
    (let [context {:inputs {"value" "from-inputs"}
                   :step-results {}
                   :variables {:value "from-variables"}}
          template "{{value}}"
          result (interp/interpolate template context)]
      (is (= "from-inputs" result))))

  (testing "Step result lookup pattern"
    (let [context {:inputs {"step-1-result" "from-inputs"}
                   :step-results {1 "from-step-results"}
                   :variables {}}
          template "{{step-1-result}}"
          result (interp/interpolate template context)]
      (is (= "from-step-results" result))))

  (testing "Variable lookup uses variables map"
    (let [context {:inputs {}
                   :step-results {}
                   :variables {:today "2026-02-19"}}
          template "{{today}}"
          result (interp/interpolate template context)]
      (is (= "2026-02-19" result)))))

(deftest test-interpolate-real-world-prompts
  (testing "LLM prompt template"
    (let [context {:inputs {"context" "user's journal entries"
                            "question" "What were my main activities?"}
                   :step-results {1 "Entries from Feb 1-15"}
                   :variables {:today "2026-02-19"}}
          template "Date: {{today}}\nContext: {{context}}\nData: {{step-1-result}}\nQuestion: {{question}}\nPlease provide a detailed answer."
          result (interp/interpolate template context)]
      (is (= "Date: 2026-02-19\nContext: user's journal entries\nData: Entries from Feb 1-15\nQuestion: What were my main activities?\nPlease provide a detailed answer."
             result))))

  (testing "Graph query template"
    (let [context {:inputs {"page" "Project X"}
                   :step-results {}
                   :variables {:today "2026-02-19"}}
          template "[:find ?b :where [?p :block/name \"{{page}}\"] [?b :block/page ?p]]"
          result (interp/interpolate template context)]
      (is (= "[:find ?b :where [?p :block/name \"Project X\"] [?b :block/page ?p]]" result)))))

(deftest test-interpolate-nil-context
  (testing "Nil context handled gracefully"
    (let [template "{{test}}"
          result (interp/interpolate template nil)]
      (is (= "" result))))

  (testing "Partial nil context"
    (let [context {:inputs nil
                   :step-results {1 "test"}
                   :variables nil}
          template "{{input}} {{step-1-result}} {{var}}"
          result (interp/interpolate template context)]
      (is (= " test " result)))))

;; =========================================================================
;; Secret interpolation tests (FR-3)
;; =========================================================================

(deftest test-interpolate-secret-reference
  (testing "Secret reference resolves when *resolve-secret* is bound"
    (binding [interp/*resolve-secret* (fn [key]
                                        (get {"API_KEY" "sk-123"
                                              "TOKEN" "tok-abc"} key))]
      (let [context {:inputs {} :step-results {} :variables {}}
            template "Key: {{secret.API_KEY}}"
            result (interp/interpolate template context)]
        (is (= "Key: sk-123" result)))))

  (testing "Multiple secret references"
    (binding [interp/*resolve-secret* (fn [key]
                                        (get {"KEY1" "val1" "KEY2" "val2"} key))]
      (let [context {:inputs {} :step-results {} :variables {}}
            template "{{secret.KEY1}} and {{secret.KEY2}}"
            result (interp/interpolate template context)]
        (is (= "val1 and val2" result)))))

  (testing "Missing secret resolves to empty string"
    (binding [interp/*resolve-secret* (fn [_] nil)]
      (let [context {:inputs {} :step-results {} :variables {}}
            template "Key: {{secret.MISSING}}"
            result (interp/interpolate template context)]
        (is (= "Key: " result)))))

  (testing "Secret mixed with other variable types"
    (binding [interp/*resolve-secret* (fn [key]
                                        (when (= key "TOKEN") "secret-val"))]
      (let [context {:inputs {"name" "test"}
                     :step-results {1 "result1"}
                     :variables {:today "2026-02-22"}}
            template "{{name}} {{step-1-result}} {{today}} {{secret.TOKEN}}"
            result (interp/interpolate template context)]
        (is (= "test result1 2026-02-22 secret-val" result)))))

  (testing "Default *resolve-secret* returns nil (no secrets configured)"
    (let [context {:inputs {} :step-results {} :variables {}}
          template "{{secret.UNCONFIGURED}}"
          result (interp/interpolate template context)]
      (is (= "" result)))))

(deftest test-interpolate-with-metadata
  (testing "No secrets — contains-secrets is false"
    (let [context {:inputs {"key" "value"} :step-results {} :variables {}}
          result (interp/interpolate-with-metadata "Hello {{key}}" context)]
      (is (= "Hello value" (:result result)))
      (is (false? (:contains-secrets result)))
      (is (empty? (:secret-keys result)))))

  (testing "With secrets — contains-secrets is true, keys tracked"
    (binding [interp/*resolve-secret* (fn [key]
                                        (get {"API_KEY" "sk-123"} key))]
      (let [context {:inputs {} :step-results {} :variables {}}
            result (interp/interpolate-with-metadata "Key: {{secret.API_KEY}}" context)]
        (is (= "Key: sk-123" (:result result)))
        (is (true? (:contains-secrets result)))
        (is (= #{"API_KEY"} (:secret-keys result))))))

  (testing "Multiple secrets tracked"
    (binding [interp/*resolve-secret* (fn [key]
                                        (get {"K1" "v1" "K2" "v2"} key))]
      (let [context {:inputs {} :step-results {} :variables {}}
            result (interp/interpolate-with-metadata "{{secret.K1}} {{secret.K2}}" context)]
        (is (= "v1 v2" (:result result)))
        (is (true? (:contains-secrets result)))
        (is (= #{"K1" "K2"} (:secret-keys result))))))

  (testing "Missing secret not tracked as used"
    (binding [interp/*resolve-secret* (fn [_] nil)]
      (let [context {:inputs {} :step-results {} :variables {}}
            result (interp/interpolate-with-metadata "{{secret.MISSING}}" context)]
        (is (= "" (:result result)))
        (is (false? (:contains-secrets result)))
        (is (empty? (:secret-keys result))))))

  (testing "Empty template"
    (let [result (interp/interpolate-with-metadata "" {})]
      (is (= "" (:result result)))
      (is (false? (:contains-secrets result))))))

(deftest test-secret-lookup-precedence
  (testing "Inputs take precedence over secrets (per spec: secrets checked AFTER inputs)"
    (binding [interp/*resolve-secret* (fn [key]
                                        (when (= key "KEY") "from-secrets"))]
      (let [context {:inputs {"secret.KEY" "from-inputs"}
                     :step-results {}
                     :variables {}}
            template "{{secret.KEY}}"
            result (interp/interpolate template context)]
        ;; Per spec FR-3: secret resolution AFTER inputs/step results
        (is (= "from-inputs" result)))))

  (testing "Secret resolves when no conflicting input exists"
    (binding [interp/*resolve-secret* (fn [key]
                                        (when (= key "MY_TOKEN") "secret-value"))]
      (let [context {:inputs {"other" "value"}
                     :step-results {}
                     :variables {}}
            template "{{secret.MY_TOKEN}}"
            result (interp/interpolate template context)]
        (is (= "secret-value" result))))))
