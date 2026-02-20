(ns logseq-ai-hub.job-runner.schemas-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.job-runner.schemas :as schemas]))

(deftest test-validate-job-properties-required-fields
  (testing "Missing required fields returns errors"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {})]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :job-type) (:errors result)))
      (is (some #(= (:field %) :job-status) (:errors result)))
      (is (some #(= (:field %) :job-created-at) (:errors result)))))

  (testing "All required fields present validates"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"})]
      (is (true? (:valid result)))
      (is (= :autonomous (get-in result [:properties :job-type])))
      (is (= :queued (get-in result [:properties :job-status])))
      (is (= "2026-02-19T10:00:00Z" (get-in result [:properties :job-created-at]))))))

(deftest test-validate-job-properties-defaults
  (testing "Default values are applied for missing optional fields"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"})]
      (is (true? (:valid result)))
      (is (= 3 (get-in result [:properties :job-priority])))
      (is (= 0 (get-in result [:properties :job-max-retries])))
      (is (= 0 (get-in result [:properties :job-retry-count])))))

  (testing "Provided values override defaults"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-priority 5
                   :job-max-retries 3})]
      (is (true? (:valid result)))
      (is (= 5 (get-in result [:properties :job-priority])))
      (is (= 3 (get-in result [:properties :job-max-retries]))))))

(deftest test-validate-enum-types
  (testing "Valid enum values pass"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :manual
                   :job-status :running
                   :job-created-at "2026-02-19T10:00:00Z"})]
      (is (true? (:valid result)))
      (is (= :manual (get-in result [:properties :job-type])))
      (is (= :running (get-in result [:properties :job-status])))))

  (testing "Invalid enum values fail"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :invalid-type
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"})]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :job-type)
                      (clojure.string/includes? (:error %) "Invalid enum value"))
                (:errors result))))))

(deftest test-validate-integer-types
  (testing "Valid integers pass"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-priority 4})]
      (is (true? (:valid result)))
      (is (= 4 (get-in result [:properties :job-priority])))))

  (testing "Non-integers fail"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-priority "not a number"})]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :job-priority)
                      (clojure.string/includes? (:error %) "Expected integer"))
                (:errors result)))))

  (testing "Integer range validation - minimum"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-priority 0})]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :job-priority)
                      (clojure.string/includes? (:error %) "less than minimum"))
                (:errors result)))))

  (testing "Integer range validation - maximum"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-priority 6})]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :job-priority)
                      (clojure.string/includes? (:error %) "greater than maximum"))
                (:errors result))))))

(deftest test-validate-string-types
  (testing "Valid strings pass"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-schedule "0 0 * * *"})]
      (is (true? (:valid result)))
      (is (= "0 0 * * *" (get-in result [:properties :job-schedule])))))

  (testing "Non-strings fail"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-schedule 123})]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :job-schedule)
                      (clojure.string/includes? (:error %) "Expected string"))
                (:errors result))))))

(deftest test-validate-csv-types
  (testing "Valid CSV (vector) passes"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-depends-on ["job1" "job2"]})]
      (is (true? (:valid result)))
      (is (= ["job1" "job2"] (get-in result [:properties :job-depends-on])))))

  (testing "Non-vector CSV fails"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-depends-on "job1,job2"})]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :job-depends-on)
                      (clojure.string/includes? (:error %) "Expected vector"))
                (:errors result))))))

(deftest test-validate-json-types
  (testing "Valid JSON (map) passes"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-input {:query "test" :limit 10}})]
      (is (true? (:valid result)))
      (is (= {:query "test" :limit 10} (get-in result [:properties :job-input])))))

  (testing "Non-map JSON fails"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :autonomous
                   :job-status :queued
                   :job-created-at "2026-02-19T10:00:00Z"
                   :job-input "{\"query\": \"test\"}"})]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :job-input)
                      (clojure.string/includes? (:error %) "Expected map"))
                (:errors result))))))

(deftest test-validate-skill-properties
  (testing "Valid skill properties pass"
    (let [result (schemas/validate-properties
                  schemas/skill-properties-schema
                  {:skill-type :llm-chain
                   :skill-version 1
                   :skill-description "Test skill"
                   :skill-inputs ["input1" "input2"]
                   :skill-outputs ["output1"]})]
      (is (true? (:valid result)))
      (is (= :llm-chain (get-in result [:properties :skill-type])))
      (is (= 1 (get-in result [:properties :skill-version])))
      (is (= "Test skill" (get-in result [:properties :skill-description])))
      (is (= ["input1" "input2"] (get-in result [:properties :skill-inputs])))
      (is (= ["output1"] (get-in result [:properties :skill-outputs])))))

  (testing "Missing required skill fields fail"
    (let [result (schemas/validate-properties
                  schemas/skill-properties-schema
                  {:skill-type :llm-chain})]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :skill-version) (:errors result)))
      (is (some #(= (:field %) :skill-description) (:errors result)))
      (is (some #(= (:field %) :skill-inputs) (:errors result)))
      (is (some #(= (:field %) :skill-outputs) (:errors result))))))

(deftest test-validate-step-properties
  (testing "Valid step properties pass"
    (let [result (schemas/validate-properties
                  schemas/step-properties-schema
                  {:step-order 1
                   :step-action :llm-call
                   :step-prompt-template "Test {{input}}"
                   :step-model "gpt-4"})]
      (is (true? (:valid result)))
      (is (= 1 (get-in result [:properties :step-order])))
      (is (= :llm-call (get-in result [:properties :step-action])))
      (is (= "Test {{input}}" (get-in result [:properties :step-prompt-template])))
      (is (= "gpt-4" (get-in result [:properties :step-model])))))

  (testing "Invalid step action fails"
    (let [result (schemas/validate-properties
                  schemas/step-properties-schema
                  {:step-order 1
                   :step-action :invalid-action})]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :step-action)
                      (clojure.string/includes? (:error %) "Invalid enum value"))
                (:errors result)))))

  (testing "Step action types are exported"
    (is (= #{:graph-query :llm-call :block-insert :block-update :page-create
             :mcp-tool :mcp-resource :transform :conditional :sub-skill :legacy-task}
           schemas/step-action-types))))

(deftest test-validate-multiple-errors
  (testing "Multiple validation errors are collected"
    (let [result (schemas/validate-properties
                  schemas/job-properties-schema
                  {:job-type :invalid
                   :job-status :also-invalid
                   :job-priority 10})]
      (is (false? (:valid result)))
      (is (>= (count (:errors result)) 3))
      (is (some #(= (:field %) :job-type) (:errors result)))
      (is (some #(= (:field %) :job-status) (:errors result)))
      (is (some #(= (:field %) :job-priority) (:errors result)))
      (is (some #(= (:field %) :job-created-at) (:errors result))))))
