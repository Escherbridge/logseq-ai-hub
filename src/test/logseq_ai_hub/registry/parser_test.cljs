(ns logseq-ai-hub.registry.parser-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [logseq-ai-hub.registry.parser :as parser]))

(deftest test-parse-tool-page-valid
  (testing "Parses a valid tool page"
    (let [content "tool-name:: send-slack-notification\ntool-description:: Send a notification to a Slack channel\ntool-handler:: mcp-tool\ntool-input-schema:: {\"type\": \"object\", \"properties\": {\"channel\": {\"type\": \"string\"}, \"message\": {\"type\": \"string\"}}, \"required\": [\"channel\", \"message\"]}\ntool-mcp-server:: slack\ntool-mcp-tool:: post_message\ntags:: logseq-ai-hub-tool"
          result (parser/parse-tool-page "Tools/send-slack-notification" content)]
      (is (true? (:valid result)))
      (is (= "Tools/send-slack-notification" (get-in result [:entry :id])))
      (is (= :tool (get-in result [:entry :type])))
      (is (= "send-slack-notification" (get-in result [:entry :name])))
      (is (= "Send a notification to a Slack channel" (get-in result [:entry :description])))
      (is (= :mcp-tool (get-in result [:entry :properties :tool-handler])))
      (is (= "slack" (get-in result [:entry :properties :tool-mcp-server])))
      (is (= "post_message" (get-in result [:entry :properties :tool-mcp-tool])))
      (is (= :graph-page (get-in result [:entry :source]))))))

(deftest test-parse-tool-page-http-handler
  (testing "Parses a tool page with HTTP handler"
    (let [content "tool-name:: webhook-call\ntool-description:: Call an HTTP webhook\ntool-handler:: http\ntool-input-schema:: {\"type\": \"object\", \"properties\": {\"payload\": {\"type\": \"string\"}}}\ntool-http-url:: https://api.example.com/hook\ntool-http-method:: POST"
          result (parser/parse-tool-page "Tools/webhook-call" content)]
      (is (true? (:valid result)))
      (is (= :http (get-in result [:entry :properties :tool-handler])))
      (is (= "https://api.example.com/hook" (get-in result [:entry :properties :tool-http-url])))
      (is (= "POST" (get-in result [:entry :properties :tool-http-method]))))))

(deftest test-parse-tool-page-invalid
  (testing "Missing required fields returns errors"
    (let [result (parser/parse-tool-page "Tools/bad" "tool-name:: test")]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :tool-description) (:errors result)))
      (is (some #(= (:field %) :tool-handler) (:errors result)))
      (is (some #(= (:field %) :tool-input-schema) (:errors result)))))

  (testing "Invalid handler type returns error"
    (let [content "tool-name:: test\ntool-description:: test\ntool-handler:: invalid\ntool-input-schema:: {\"type\": \"object\"}"
          result (parser/parse-tool-page "Tools/test" content)]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :tool-handler) (:errors result))))))

(deftest test-parse-prompt-page-valid
  (testing "Parses a prompt page with system and user sections"
    (let [content "prompt-name:: code-review\nprompt-description:: Review code for quality\nprompt-arguments:: code, language, focus\ntags:: logseq-ai-hub-prompt\n\n## System\nYou are an expert code reviewer. Review the following {{language}} code with a focus on {{focus}}.\n\n## User\n```{{language}}\n{{code}}\n```\nPlease provide your review."
          result (parser/parse-prompt-page "Prompts/code-review" content)]
      (is (true? (:valid result)))
      (is (= "Prompts/code-review" (get-in result [:entry :id])))
      (is (= :prompt (get-in result [:entry :type])))
      (is (= "code-review" (get-in result [:entry :name])))
      (is (= "Review code for quality" (get-in result [:entry :description])))
      (is (= ["code" "language" "focus"] (get-in result [:entry :arguments])))
      (is (some? (get-in result [:entry :system-section])))
      (is (str/includes? (get-in result [:entry :system-section]) "expert code reviewer"))
      (is (some? (get-in result [:entry :user-section])))
      (is (str/includes? (get-in result [:entry :user-section]) "{{code}}")))))

(deftest test-parse-prompt-page-no-args
  (testing "Prompt without arguments is valid"
    (let [content "prompt-name:: simple\nprompt-description:: A simple prompt\n\n## User\nJust do something."
          result (parser/parse-prompt-page "Prompts/simple" content)]
      (is (true? (:valid result)))
      (is (nil? (get-in result [:entry :arguments]))))))

(deftest test-parse-prompt-page-invalid
  (testing "Missing required fields"
    (let [result (parser/parse-prompt-page "Prompts/bad" "prompt-name:: test")]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :prompt-description) (:errors result))))))

(deftest test-parse-procedure-page-valid
  (testing "Parses a procedure page with steps"
    (let [content "procedure-name:: deploy-to-production\nprocedure-description:: Full deployment checklist\nprocedure-requires-approval:: true\nprocedure-approval-contact:: whatsapp:15551234567\ntags:: logseq-ai-hub-procedure\n\n1. Run all tests\n2. Build production artifacts\n3. **[APPROVAL REQUIRED]** Deploy to Railway\n4. Verify health endpoint"
          result (parser/parse-procedure-page "Procedures/deploy" content)]
      (is (true? (:valid result)))
      (is (= "Procedures/deploy" (get-in result [:entry :id])))
      (is (= :procedure (get-in result [:entry :type])))
      (is (= "deploy-to-production" (get-in result [:entry :name])))
      (is (= "Full deployment checklist" (get-in result [:entry :description])))
      (is (true? (get-in result [:entry :requires-approval])))
      (is (= "whatsapp:15551234567" (get-in result [:entry :approval-contact])))
      (is (str/includes? (get-in result [:entry :body]) "Run all tests"))
      (is (str/includes? (get-in result [:entry :body]) "APPROVAL REQUIRED")))))

(deftest test-parse-procedure-page-no-approval
  (testing "Procedure without approval defaults to false"
    (let [content "procedure-name:: simple-deploy\nprocedure-description:: Quick deploy"
          result (parser/parse-procedure-page "Procedures/simple" content)]
      (is (true? (:valid result)))
      (is (false? (get-in result [:entry :requires-approval]))))))

(deftest test-parse-procedure-page-invalid
  (testing "Missing required fields"
    (let [result (parser/parse-procedure-page "Procedures/bad" "procedure-name:: test")]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :procedure-description) (:errors result))))))

(deftest test-parse-skill-as-tool
  (testing "Converts a skill definition to a tool entry"
    (let [skill-def {:skill-id "Skills/summarize-notes"
                     :properties {:skill-type :llm-chain
                                  :skill-version 1
                                  :skill-description "Summarize text"
                                  :skill-inputs ["query" "detail-level"]
                                  :skill-outputs ["summary"]}
                     :steps []
                     :valid true}
          result (parser/parse-skill-as-tool skill-def)]
      (is (= "Skills/summarize-notes" (:id result)))
      (is (= :tool (:type result)))
      (is (= "skill_summarize-notes" (:name result)))
      (is (= "Summarize text" (:description result)))
      (is (= :skill (:handler result)))
      (is (= "Skills/summarize-notes" (:skill-id result)))
      (is (= :auto-detected (:source result)))
      ;; Check input schema
      (let [schema (:input-schema result)]
        (is (= "object" (get schema "type")))
        (is (= {"type" "string"} (get-in schema ["properties" "query"])))
        (is (= {"type" "string"} (get-in schema ["properties" "detail-level"])))
        (is (= ["query" "detail-level"] (get schema "required")))))))

(deftest test-parse-skill-as-tool-no-inputs
  (testing "Skill with no inputs produces empty schema"
    (let [skill-def {:skill-id "Skills/no-input"
                     :properties {:skill-type :llm-chain
                                  :skill-version 1
                                  :skill-description "No input skill"
                                  :skill-inputs []
                                  :skill-outputs ["result"]}
                     :steps []
                     :valid true}
          result (parser/parse-skill-as-tool skill-def)]
      (is (= {} (get-in result [:input-schema "properties"])))
      (is (= [] (get-in result [:input-schema "required"]))))))
