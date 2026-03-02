(ns logseq-ai-hub.registry.schemas-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [logseq-ai-hub.registry.schemas :as schemas]))

(deftest test-validate-tool-properties-required-fields
  (testing "Missing required fields returns errors"
    (let [result (schemas/validate-properties
                  schemas/tool-properties-schema
                  {})]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :tool-name) (:errors result)))
      (is (some #(= (:field %) :tool-description) (:errors result)))
      (is (some #(= (:field %) :tool-handler) (:errors result)))
      (is (some #(= (:field %) :tool-input-schema) (:errors result)))))

  (testing "All required fields present validates"
    (let [result (schemas/validate-properties
                  schemas/tool-properties-schema
                  {:tool-name "send-slack-notification"
                   :tool-description "Send a notification to Slack"
                   :tool-handler :mcp-tool
                   :tool-input-schema {"type" "object"
                                       "properties" {"channel" {"type" "string"}}}})]
      (is (true? (:valid result)))
      (is (= "send-slack-notification" (get-in result [:properties :tool-name])))
      (is (= :mcp-tool (get-in result [:properties :tool-handler]))))))

(deftest test-validate-tool-handler-enum
  (testing "Valid handler types pass"
    (doseq [handler [:mcp-tool :skill :http :graph-query]]
      (let [result (schemas/validate-properties
                    schemas/tool-properties-schema
                    {:tool-name "test"
                     :tool-description "test"
                     :tool-handler handler
                     :tool-input-schema {"type" "object"}})]
        (is (true? (:valid result))
            (str "Handler " handler " should be valid")))))

  (testing "Invalid handler type fails"
    (let [result (schemas/validate-properties
                  schemas/tool-properties-schema
                  {:tool-name "test"
                   :tool-description "test"
                   :tool-handler :invalid-handler
                   :tool-input-schema {"type" "object"}})]
      (is (false? (:valid result)))
      (is (some #(and (= (:field %) :tool-handler)
                      (str/includes? (:error %) "Invalid enum value"))
                (:errors result))))))

(deftest test-validate-tool-handler-specific-props
  (testing "MCP tool handler with server and tool props"
    (let [result (schemas/validate-properties
                  schemas/tool-properties-schema
                  {:tool-name "slack-post"
                   :tool-description "Post to Slack"
                   :tool-handler :mcp-tool
                   :tool-input-schema {"type" "object"}
                   :tool-mcp-server "slack"
                   :tool-mcp-tool "post_message"})]
      (is (true? (:valid result)))
      (is (= "slack" (get-in result [:properties :tool-mcp-server])))
      (is (= "post_message" (get-in result [:properties :tool-mcp-tool])))))

  (testing "HTTP handler with url and method"
    (let [result (schemas/validate-properties
                  schemas/tool-properties-schema
                  {:tool-name "webhook"
                   :tool-description "Call webhook"
                   :tool-handler :http
                   :tool-input-schema {"type" "object"}
                   :tool-http-url "https://api.example.com/hook"
                   :tool-http-method "POST"})]
      (is (true? (:valid result)))
      (is (= "https://api.example.com/hook" (get-in result [:properties :tool-http-url])))))

  (testing "Graph query handler with query"
    (let [result (schemas/validate-properties
                  schemas/tool-properties-schema
                  {:tool-name "find-pages"
                   :tool-description "Find pages by tag"
                   :tool-handler :graph-query
                   :tool-input-schema {"type" "object"}
                   :tool-query "[:find ?p :where [?p :block/name]]"})]
      (is (true? (:valid result)))
      (is (= "[:find ?p :where [?p :block/name]]" (get-in result [:properties :tool-query]))))))

(deftest test-validate-prompt-properties
  (testing "Valid prompt properties pass"
    (let [result (schemas/validate-properties
                  schemas/prompt-properties-schema
                  {:prompt-name "code-review"
                   :prompt-description "Review code for quality"
                   :prompt-arguments ["code" "language" "focus"]})]
      (is (true? (:valid result)))
      (is (= "code-review" (get-in result [:properties :prompt-name])))
      (is (= "Review code for quality" (get-in result [:properties :prompt-description])))
      (is (= ["code" "language" "focus"] (get-in result [:properties :prompt-arguments])))))

  (testing "Missing required prompt fields fail"
    (let [result (schemas/validate-properties
                  schemas/prompt-properties-schema
                  {})]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :prompt-name) (:errors result)))
      (is (some #(= (:field %) :prompt-description) (:errors result)))))

  (testing "Prompt without arguments is valid (optional)"
    (let [result (schemas/validate-properties
                  schemas/prompt-properties-schema
                  {:prompt-name "simple"
                   :prompt-description "A simple prompt"})]
      (is (true? (:valid result))))))

(deftest test-validate-procedure-properties
  (testing "Valid procedure properties pass"
    (let [result (schemas/validate-properties
                  schemas/procedure-properties-schema
                  {:procedure-name "deploy-to-production"
                   :procedure-description "Full deployment checklist"
                   :procedure-requires-approval "true"
                   :procedure-approval-contact "whatsapp:15551234567"})]
      (is (true? (:valid result)))
      (is (= "deploy-to-production" (get-in result [:properties :procedure-name])))
      (is (= "true" (get-in result [:properties :procedure-requires-approval])))))

  (testing "Missing required procedure fields fail"
    (let [result (schemas/validate-properties
                  schemas/procedure-properties-schema
                  {})]
      (is (false? (:valid result)))
      (is (some #(= (:field %) :procedure-name) (:errors result)))
      (is (some #(= (:field %) :procedure-description) (:errors result)))))

  (testing "Procedure with defaults for optional fields"
    (let [result (schemas/validate-properties
                  schemas/procedure-properties-schema
                  {:procedure-name "test"
                   :procedure-description "test procedure"})]
      (is (true? (:valid result)))
      (is (= "false" (get-in result [:properties :procedure-requires-approval]))))))
