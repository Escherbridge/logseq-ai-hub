(ns logseq-ai-hub.job-runner.parser-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.job-runner.parser :as parser]))

(deftest test-parse-block-properties-basic
  (testing "Parse simple key-value pairs"
    (let [content "job-type:: autonomous\njob-status:: queued\njob-priority:: 4"
          result (parser/parse-block-properties content)]
      (is (= "autonomous" (:job-type result)))
      (is (= "queued" (:job-status result)))
      (is (= "4" (:job-priority result)))))

  (testing "Parse with whitespace variations"
    (let [content "job-type::autonomous\njob-status::  queued  \njob-priority::   4"
          result (parser/parse-block-properties content)]
      (is (= "autonomous" (:job-type result)))
      (is (= "queued" (:job-status result)))
      (is (= "4" (:job-priority result)))))

  (testing "Empty content returns empty map"
    (let [result (parser/parse-block-properties "")]
      (is (= {} result))))

  (testing "Content without properties returns empty map"
    (let [result (parser/parse-block-properties "Just some text without properties")]
      (is (= {} result)))))

(deftest test-parse-block-properties-multiline
  (testing "Multiline values are joined"
    (let [content "job-description:: This is a long description\nthat spans multiple lines\nand continues here\njob-type:: autonomous"
          result (parser/parse-block-properties content)]
      (is (= "This is a long description that spans multiple lines and continues here"
             (:job-description result)))
      (is (= "autonomous" (:job-type result)))))

  (testing "Multiline with indentation"
    (let [content "job-description:: First line\n  Second line with indent\n  Third line\njob-type:: manual"
          result (parser/parse-block-properties content)]
      (is (= "First line Second line with indent Third line"
             (:job-description result)))
      (is (= "manual" (:job-type result))))))

(deftest test-parse-block-properties-csv
  (testing "Comma-separated values are parsed to vectors"
    (let [content "skill-inputs:: query, detail-level, context\nskill-outputs:: summary, recommendations"
          result (parser/parse-block-properties content)]
      (is (= ["query" "detail-level" "context"] (:skill-inputs result)))
      (is (= ["summary" "recommendations"] (:skill-outputs result)))))

  (testing "CSV with extra whitespace"
    (let [content "job-depends-on::  job1 ,  job2  , job3  "
          result (parser/parse-block-properties content)]
      (is (= ["job1" "job2" "job3"] (:job-depends-on result)))))

  (testing "Single value CSV becomes single-element vector"
    (let [content "skill-inputs:: single-input"
          result (parser/parse-block-properties content)]
      (is (= ["single-input"] (:skill-inputs result))))))

(deftest test-parse-block-properties-json
  (testing "JSON objects are parsed"
    (let [content "step-config:: {\"model\": \"gpt-4\", \"temperature\": 0.7}"
          result (parser/parse-block-properties content)]
      (is (map? (:step-config result)))
      (is (= "gpt-4" (get (:step-config result) "model")))
      (is (= 0.7 (get (:step-config result) "temperature")))))

  (testing "JSON arrays are parsed"
    (let [content "job-input:: [\"item1\", \"item2\", \"item3\"]"
          result (parser/parse-block-properties content)]
      (is (vector? (:job-input result)))
      (is (= ["item1" "item2" "item3"] (:job-input result)))))

  (testing "Nested JSON structures"
    (let [content "step-config:: {\"llm\": {\"model\": \"gpt-4\", \"temp\": 0.5}, \"max_tokens\": 100}"
          result (parser/parse-block-properties content)]
      (is (map? (:step-config result)))
      (is (map? (get (:step-config result) "llm")))
      (is (= "gpt-4" (get-in (:step-config result) ["llm" "model"])))
      (is (= 100 (get (:step-config result) "max_tokens")))))

  (testing "Invalid JSON remains as string"
    (let [content "step-config:: {invalid json}"
          result (parser/parse-block-properties content)]
      (is (string? (:step-config result)))
      (is (= "{invalid json}" (:step-config result))))))

(deftest test-parse-block-properties-mixed
  (testing "Mixed property types"
    (let [content "job-type:: autonomous\njob-depends-on:: job1, job2\njob-input:: {\"query\": \"test\"}\njob-priority:: 3"
          result (parser/parse-block-properties content)]
      (is (= "autonomous" (:job-type result)))
      (is (= ["job1" "job2"] (:job-depends-on result)))
      (is (map? (:job-input result)))
      (is (= "test" (get (:job-input result) "query")))
      (is (= "3" (:job-priority result))))))

(deftest test-parse-job-definition-valid
  (testing "Parse complete job definition"
    (let [first-block "job-type:: autonomous\njob-status:: queued\njob-priority:: 4\njob-created-at:: 2026-02-19T10:00:00Z"
          child-blocks ["step-order:: 1\nstep-action:: graph-query\nstep-config:: {\"query\": \"today's journal\"}"
                        "step-order:: 2\nstep-action:: llm-call\nstep-prompt-template:: Summarize: {{step-1-result}}"]
          result (parser/parse-job-definition first-block child-blocks "Jobs/test-job")]
      (is (true? (:valid result)))
      (is (= "Jobs/test-job" (:job-id result)))
      (is (= :autonomous (get-in result [:properties :job-type])))
      (is (= :queued (get-in result [:properties :job-status])))
      (is (= 4 (get-in result [:properties :job-priority])))
      (is (= 2 (count (:steps result))))
      (is (= 1 (get-in result [:steps 0 :step-order])))
      (is (= :graph-query (get-in result [:steps 0 :step-action])))
      (is (= 2 (get-in result [:steps 1 :step-order])))
      (is (= :llm-call (get-in result [:steps 1 :step-action])))))

  (testing "Parse job with defaults applied"
    (let [first-block "job-type:: manual\njob-status:: draft\njob-created-at:: 2026-02-19T10:00:00Z"
          result (parser/parse-job-definition first-block [] "Jobs/simple")]
      (is (true? (:valid result)))
      (is (= 3 (get-in result [:properties :job-priority])))
      (is (= 0 (get-in result [:properties :job-max-retries])))
      (is (= 0 (get-in result [:properties :job-retry-count])))
      (is (empty? (:steps result)))))

  (testing "Parse job with CSV and JSON properties"
    (let [first-block "job-type:: autonomous\njob-status:: queued\njob-created-at:: 2026-02-19T10:00:00Z\njob-depends-on:: job1, job2\njob-input:: {\"query\": \"test\", \"limit\": 10}"
          result (parser/parse-job-definition first-block [] "Jobs/complex")]
      (is (true? (:valid result)))
      (is (= ["job1" "job2"] (get-in result [:properties :job-depends-on])))
      (is (= {"query" "test" "limit" 10} (get-in result [:properties :job-input]))))))

(deftest test-parse-job-definition-invalid
  (testing "Missing required fields"
    (let [first-block "job-type:: autonomous"
          result (parser/parse-job-definition first-block [] "Jobs/invalid")]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :job-status) (:errors result)))
      (is (some #(= (:field %) :job-created-at) (:errors result)))))

  (testing "Invalid enum value"
    (let [first-block "job-type:: invalid-type\njob-status:: queued\njob-created-at:: 2026-02-19T10:00:00Z"
          result (parser/parse-job-definition first-block [] "Jobs/bad-enum")]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :job-type)
                      (clojure.string/includes? (:error %) "Invalid enum value"))
                (:errors result)))))

  (testing "Invalid priority range"
    (let [first-block "job-type:: autonomous\njob-status:: queued\njob-created-at:: 2026-02-19T10:00:00Z\njob-priority:: 10"
          result (parser/parse-job-definition first-block [] "Jobs/bad-priority")]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :job-priority)
                      (clojure.string/includes? (:error %) "greater than maximum"))
                (:errors result))))))

(deftest test-parse-job-definition-steps
  (testing "Steps are validated individually"
    (let [first-block "job-type:: autonomous\njob-status:: queued\njob-created-at:: 2026-02-19T10:00:00Z"
          child-blocks ["step-order:: 1\nstep-action:: llm-call\nstep-model:: gpt-4"
                        "step-order:: 2\nstep-action:: invalid-action"]
          result (parser/parse-job-definition first-block child-blocks "Jobs/bad-step")]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :step-action)
                      (clojure.string/includes? (:error %) "Invalid enum value"))
                (:errors result)))))

  (testing "Steps are sorted by step-order"
    (let [first-block "job-type:: autonomous\njob-status:: queued\njob-created-at:: 2026-02-19T10:00:00Z"
          child-blocks ["step-order:: 3\nstep-action:: llm-call"
                        "step-order:: 1\nstep-action:: graph-query"
                        "step-order:: 2\nstep-action:: transform"]
          result (parser/parse-job-definition first-block child-blocks "Jobs/ordered")]
      (is (true? (:valid result)))
      (is (= 1 (get-in result [:steps 0 :step-order])))
      (is (= 2 (get-in result [:steps 1 :step-order])))
      (is (= 3 (get-in result [:steps 2 :step-order]))))))

(deftest test-parse-skill-definition-valid
  (testing "Parse complete skill definition"
    (let [first-block "skill-type:: llm-chain\nskill-version:: 1\nskill-description:: Test skill\nskill-inputs:: input1, input2\nskill-outputs:: output1"
          child-blocks ["step-order:: 1\nstep-action:: llm-call\nstep-model:: gpt-4\nstep-prompt-template:: Test {{input1}}"]
          result (parser/parse-skill-definition first-block child-blocks "Skills/test-skill")]
      (is (true? (:valid result)))
      (is (= "Skills/test-skill" (:skill-id result)))
      (is (= :llm-chain (get-in result [:properties :skill-type])))
      (is (= 1 (get-in result [:properties :skill-version])))
      (is (= "Test skill" (get-in result [:properties :skill-description])))
      (is (= ["input1" "input2"] (get-in result [:properties :skill-inputs])))
      (is (= ["output1"] (get-in result [:properties :skill-outputs])))
      (is (= 1 (count (:steps result))))
      (is (= :llm-call (get-in result [:steps 0 :step-action])))))

  (testing "Parse skill with tags"
    (let [first-block "skill-type:: tool-chain\nskill-version:: 2\nskill-description:: Tagged skill\nskill-inputs:: input1\nskill-outputs:: output1\nskill-tags:: automation, reporting, daily"
          result (parser/parse-skill-definition first-block [] "Skills/tagged")]
      (is (true? (:valid result)))
      (is (= ["automation" "reporting" "daily"] (get-in result [:properties :skill-tags]))))))

(deftest test-parse-skill-definition-invalid
  (testing "Missing required skill fields"
    (let [first-block "skill-type:: llm-chain\nskill-version:: 1"
          result (parser/parse-skill-definition first-block [] "Skills/incomplete")]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :skill-description) (:errors result)))
      (is (some #(= (:field %) :skill-inputs) (:errors result)))
      (is (some #(= (:field %) :skill-outputs) (:errors result)))))

  (testing "Invalid skill type"
    (let [first-block "skill-type:: invalid\nskill-version:: 1\nskill-description:: Test\nskill-inputs:: in\nskill-outputs:: out"
          result (parser/parse-skill-definition first-block [] "Skills/bad-type")]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :skill-type)
                      (clojure.string/includes? (:error %) "Invalid enum value"))
                (:errors result))))))

(deftest test-parse-edge-cases
  (testing "Empty child blocks array"
    (let [first-block "job-type:: autonomous\njob-status:: queued\njob-created-at:: 2026-02-19T10:00:00Z"
          result (parser/parse-job-definition first-block [] "Jobs/no-steps")]
      (is (true? (:valid result)))
      (is (empty? (:steps result)))))

  (testing "Nil child blocks"
    (let [first-block "job-type:: autonomous\njob-status:: queued\njob-created-at:: 2026-02-19T10:00:00Z"
          result (parser/parse-job-definition first-block nil "Jobs/nil-steps")]
      (is (true? (:valid result)))
      (is (empty? (:steps result)))))

  (testing "Property with no value"
    (let [content "job-type::\njob-status:: queued\njob-created-at:: 2026-02-19T10:00:00Z"
          result (parser/parse-block-properties content)]
      (is (= "" (:job-type result)))))

  (testing "Properties with special characters in values"
    (let [content "job-description:: Task with [[page-ref]] and #tag"
          result (parser/parse-block-properties content)]
      (is (= "Task with [[page-ref]] and #tag" (:job-description result))))))
