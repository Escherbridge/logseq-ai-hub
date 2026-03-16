(ns logseq-ai-hub.job-runner.executor-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [logseq-ai-hub.job-runner.executor :as executor]))

(defn setup-mocks! []
  ;; Mock Logseq API
  (set! js/logseq
    #js {:Editor #js {:appendBlockInPage (fn [page content]
                                            (js/Promise.resolve
                                              #js {:uuid "new-block-uuid"}))
                       :updateBlock (fn [uuid content]
                                     (js/Promise.resolve #js {}))
                       :createPage (fn [name props opts]
                                    (js/Promise.resolve
                                      #js {:name name}))}
         :DB #js {:datascriptQuery (fn [query]
                                     #js [#js [#js {"block/content" "Test content"}]])}
         :settings #js {:selectedModel "mock-model"}})

  ;; Mock agent process-input
  (set! executor/*agent-process-input-fn*
        (fn [input model-id]
          (js/Promise.resolve (str "AI response to: " input))))

  ;; Mock execute-skill-fn
  (set! executor/*execute-skill-fn*
        (fn [skill-id context]
          (js/Promise.resolve {:result "skill-result"})))

  ;; Mock MCP functions
  (set! executor/*call-mcp-tool-fn*
        (fn [server tool args]
          (js/Promise.resolve {:tool-result "mcp-tool-result"})))

  (set! executor/*read-mcp-resource-fn*
        (fn [server resource]
          (js/Promise.resolve {:resource-content "mcp-resource-content"}))))

(use-fixtures :each
  {:before setup-mocks!})

(deftest test-register-and-execute
  (async done
    (let [test-handler (fn [step context]
                        (js/Promise.resolve "test-result"))]
      (executor/register-executor! :test-action test-handler)

      (-> (executor/execute-step
            {:step-action :test-action
             :step-order 1}
            {:job-id "test-job"})
          (.then (fn [result]
                   (is (= "test-result" result) "Should execute registered handler")
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-execute-unknown-action
  (async done
    (-> (executor/execute-step
          {:step-action :unknown-action
           :step-order 1}
          {:job-id "test-job"})
        (.then (fn [result]
                 (is false "Should reject for unknown action")
                 (done)))
        (.catch (fn [err]
                  (is (some? err) "Should reject with error")
                  (done))))))

(deftest test-graph-query-executor
  (async done
    (-> (executor/execute-step
          {:step-action :graph-query
           :step-order 1
           :step-config {"query" "[:find ?b :where [?b :block/content]]"}}
          {:job-id "test-job"
           :variables {}
           :step-results {}})
        (.then (fn [result]
                 (is (some? result) "Should return query results")
                 (is (array? result) "Should return array from datascriptQuery")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-graph-query-with-interpolation
  (async done
    (-> (executor/execute-step
          {:step-action :graph-query
           :step-order 1
           :step-config {"query" "[:find ?b :where [?b :block/content \"{{keyword}}\"]]"}}
          {:job-id "test-job"
           :variables {:keyword "test"}
           :step-results {}})
        (.then (fn [result]
                 (is (some? result) "Should return interpolated query results")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-llm-call-executor
  (async done
    (-> (executor/execute-step
          {:step-action :llm-call
           :step-order 1
           :step-prompt-template "Summarize: {{input}}"
           :step-model "gpt-4"}
          {:job-id "test-job"
           :inputs {"input" "test data"}
           :variables {}
           :step-results {}})
        (.then (fn [result]
                 (is (string? result) "Should return string response")
                 (is (.includes result "AI response") "Should contain AI response")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-block-insert-executor
  (async done
    (-> (executor/execute-step
          {:step-action :block-insert
           :step-order 1
           :step-config {"page" "Test Page"
                        "content" "New block content: {{value}}"}}
          {:job-id "test-job"
           :variables {:value "123"}
           :step-results {}})
        (.then (fn [result]
                 (is (some? result) "Should return block creation result")
                 (is (= "new-block-uuid" (.-uuid result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-block-update-executor
  (async done
    (-> (executor/execute-step
          {:step-action :block-update
           :step-order 1
           :step-config {"uuid" "block-123"
                        "content" "Updated: {{result}}"}}
          {:job-id "test-job"
           :variables {}
           :step-results {0 "previous result"}})
        (.then (fn [result]
                 (is (some? result) "Should return update result")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-page-create-executor
  (async done
    (-> (executor/execute-step
          {:step-action :page-create
           :step-order 1
           :step-config {"name" "New Page {{suffix}}"
                        "content" "Page content"}}
          {:job-id "test-job"
           :variables {:suffix "001"}
           :step-results {}})
        (.then (fn [result]
                 (is (some? result) "Should return page creation result")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-transform-get-in
  (async done
    (-> (executor/execute-step
          {:step-action :transform
           :step-order 2
           :step-config {"op" "get-in"
                        "path" ["user" "name"]
                        "input" "step-1-result"}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 {"user" {"name" "Alice" "age" 30}}}})
        (.then (fn [result]
                 (is (= "Alice" result) "Should extract nested value")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-transform-join
  (async done
    (-> (executor/execute-step
          {:step-action :transform
           :step-order 2
           :step-config {"op" "join"
                        "separator" ", "
                        "input" "step-1-result"}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 ["apple" "banana" "cherry"]}})
        (.then (fn [result]
                 (is (= "apple, banana, cherry" result) "Should join strings")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-transform-split
  (async done
    (-> (executor/execute-step
          {:step-action :transform
           :step-order 2
           :step-config {"op" "split"
                        "separator" ","
                        "input" "step-1-result"}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 "a,b,c"}})
        (.then (fn [result]
                 (is (= ["a" "b" "c"] (js->clj result)) "Should split string")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-transform-count
  (async done
    (-> (executor/execute-step
          {:step-action :transform
           :step-order 2
           :step-config {"op" "count"
                        "input" "step-1-result"}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 [1 2 3 4 5]}})
        (.then (fn [result]
                 (is (= 5 result) "Should count items")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-transform-filter
  (async done
    (-> (executor/execute-step
          {:step-action :transform
           :step-order 2
           :step-config {"op" "filter"
                        "predicate" "not-empty"
                        "input" "step-1-result"}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 ["a" "" "b" nil "c"]}})
        (.then (fn [result]
                 (is (= ["a" "b" "c"] (js->clj result)) "Should filter non-empty values")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-conditional-not-empty-true
  (async done
    (-> (executor/execute-step
          {:step-action :conditional
           :step-order 2
           :step-config {"condition" "not-empty"
                        "input" "step-1-result"
                        "then-step" 3
                        "else-step" 5}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 ["data"]}})
        (.then (fn [result]
                 (is (= :jump (:directive result)) "Should return jump directive")
                 (is (= 3 (:target-step result)) "Should jump to then-step")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-conditional-not-empty-false
  (async done
    (-> (executor/execute-step
          {:step-action :conditional
           :step-order 2
           :step-config {"condition" "not-empty"
                        "input" "step-1-result"
                        "then-step" 3
                        "else-step" 5}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 []}})
        (.then (fn [result]
                 (is (= :jump (:directive result)) "Should return jump directive")
                 (is (= 5 (:target-step result)) "Should jump to else-step")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-conditional-equals
  (async done
    (-> (executor/execute-step
          {:step-action :conditional
           :step-order 2
           :step-config {"condition" "equals"
                        "input" "step-1-result"
                        "value" "expected"
                        "then-step" 3
                        "else-step" 5}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 "expected"}})
        (.then (fn [result]
                 (is (= :jump (:directive result)))
                 (is (= 3 (:target-step result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-conditional-contains
  (async done
    (-> (executor/execute-step
          {:step-action :conditional
           :step-order 2
           :step-config {"condition" "contains"
                        "input" "step-1-result"
                        "value" "needle"
                        "then-step" 3
                        "else-step" 5}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 "haystack with needle inside"}})
        (.then (fn [result]
                 (is (= :jump (:directive result)))
                 (is (= 3 (:target-step result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-conditional-greater-than
  (async done
    (-> (executor/execute-step
          {:step-action :conditional
           :step-order 2
           :step-config {"condition" "greater-than"
                        "input" "step-1-result"
                        "value" 10
                        "then-step" 3
                        "else-step" 5}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 15}})
        (.then (fn [result]
                 (is (= :jump (:directive result)))
                 (is (= 3 (:target-step result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-conditional-no-else-step
  (async done
    (-> (executor/execute-step
          {:step-action :conditional
           :step-order 2
           :step-config {"condition" "empty"
                        "input" "step-1-result"
                        "then-step" 3}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 []}})
        (.then (fn [result]
                 (is (= :jump (:directive result)))
                 (is (= 3 (:target-step result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-conditional-continue
  (async done
    (-> (executor/execute-step
          {:step-action :conditional
           :step-order 2
           :step-config {"condition" "empty"
                        "input" "step-1-result"
                        "then-step" 3}}
          {:job-id "test-job"
           :variables {}
           :step-results {1 ["not-empty"]}})
        (.then (fn [result]
                 (is (= :continue (:directive result)) "Should continue when condition fails and no else")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-sub-skill-executor
  (async done
    (-> (executor/execute-step
          {:step-action :sub-skill
           :step-order 1
           :step-config {"skill-id" "Skills/Summarize"
                        "inputs" {"text" "{{input}}"}}}
          {:job-id "test-job"
           :inputs {"input" "test data"}
           :variables {}
           :step-results {}})
        (.then (fn [result]
                 (is (some? result) "Should return skill execution result")
                 (is (= "skill-result" (:result result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-sub-skill-not-initialized
  (async done
    (set! executor/*execute-skill-fn* nil)
    (-> (executor/execute-step
          {:step-action :sub-skill
           :step-order 1
           :step-config {"skill-id" "Skills/Test"}}
          {:job-id "test-job"
           :variables {}
           :step-results {}})
        (.then (fn [result]
                 (is false "Should reject when skill executor not initialized")
                 (done)))
        (.catch (fn [err]
                  (is (some? err) "Should reject with error")
                  (done))))))

(deftest test-legacy-task-executor
  (async done
    ;; Mock tasks namespace
    (set! executor/*run-legacy-task-fn*
          (fn [task-id]
            (js/Promise.resolve {:task-result "legacy-result"})))

    (-> (executor/execute-step
          {:step-action :legacy-task
           :step-order 1
           :step-config {"task-id" "daily-summary"}}
          {:job-id "test-job"
           :variables {}
           :step-results {}})
        (.then (fn [result]
                 (is (some? result) "Should return task result")
                 (is (= "legacy-result" (:task-result result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-mcp-tool-executor
  (async done
    (-> (executor/execute-step
          {:step-action :mcp-tool
           :step-order 1
           :step-mcp-server "filesystem"
           :step-mcp-tool "read_file"
           :step-config {"path" "/test/file.txt"}}
          {:job-id "test-job"
           :variables {}
           :step-results {}})
        (.then (fn [result]
                 (is (some? result) "Should return MCP tool result")
                 (is (= "mcp-tool-result" (:tool-result result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-mcp-tool-not-initialized
  (async done
    (set! executor/*call-mcp-tool-fn* nil)
    (-> (executor/execute-step
          {:step-action :mcp-tool
           :step-order 1
           :step-mcp-server "filesystem"
           :step-mcp-tool "read_file"
           :step-config {}}
          {:job-id "test-job"
           :variables {}
           :step-results {}})
        (.then (fn [result]
                 (is false "Should reject when MCP not initialized")
                 (done)))
        (.catch (fn [err]
                  (is (some? err) "Should reject with error")
                  (is (.includes (str err) "MCP client not initialized"))
                  (done))))))

(deftest test-mcp-resource-executor
  (async done
    (-> (executor/execute-step
          {:step-action :mcp-resource
           :step-order 1
           :step-mcp-server "filesystem"
           :step-config {"resource" "file:///test/resource.txt"}}
          {:job-id "test-job"
           :variables {}
           :step-results {}})
        (.then (fn [result]
                 (is (some? result) "Should return MCP resource result")
                 (is (= "mcp-resource-content" (:resource-content result)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Promise rejected: " err))
                  (done))))))

(deftest test-mcp-resource-not-initialized
  (async done
    (set! executor/*read-mcp-resource-fn* nil)
    (-> (executor/execute-step
          {:step-action :mcp-resource
           :step-order 1
           :step-mcp-server "filesystem"
           :step-config {"resource" "file:///test"}}
          {:job-id "test-job"
           :variables {}
           :step-results {}})
        (.then (fn [result]
                 (is false "Should reject when MCP not initialized")
                 (done)))
        (.catch (fn [err]
                  (is (some? err) "Should reject with error")
                  (is (.includes (str err) "MCP client not initialized"))
                  (done))))))

(deftest ask-human-executor-test
  (testing "ask-human not initialized"
    (async done
      (set! executor/*ask-human-fn* nil)
      (-> (executor/execute-step {:step-action :ask-human
                                  :step-config {"contact" "whatsapp:123"
                                                "question" "Deploy?"}}
                                 {})
          (.catch (fn [err]
                    (is (= :ask-human-not-initialized (.-type err)))
                    (done))))))

  (testing "ask-human success"
    (async done
      (set! executor/*ask-human-fn*
            (fn [params]
              (is (= "whatsapp:123" (:contact params)))
              (is (= "Deploy to production?" (:question params)))
              (js/Promise.resolve #js {:status "approved" :response "yes"})))
      (-> (executor/execute-step {:step-action :ask-human
                                  :step-config {"contact" "whatsapp:123"
                                                "question" "Deploy to production?"}}
                                 {})
          (.then (fn [result]
                   (is (= "approved" (:status result)))
                   (is (= "yes" (:response result)))
                   (set! executor/*ask-human-fn* nil)
                   (done))))))

  (testing "ask-human timeout with fail (default)"
    (async done
      (set! executor/*ask-human-fn*
            (fn [_params]
              (js/Promise.resolve #js {:status "timeout" :response nil})))
      (-> (executor/execute-step {:step-action :ask-human
                                  :step-config {"contact" "whatsapp:123"
                                                "question" "Approve?"}}
                                 {})
          (.catch (fn [err]
                    (is (= :approval-timeout (.-type err)))
                    (set! executor/*ask-human-fn* nil)
                    (done))))))

  (testing "ask-human timeout with continue"
    (async done
      (set! executor/*ask-human-fn*
            (fn [_params]
              (js/Promise.resolve #js {:status "timeout" :response nil})))
      (-> (executor/execute-step {:step-action :ask-human
                                  :step-config {"contact" "whatsapp:123"
                                                "question" "Approve?"
                                                "on-timeout" "continue"}}
                                 {})
          (.then (fn [result]
                   (is (= "timeout" (:status result)))
                   (is (true? (:continued result)))
                   (set! executor/*ask-human-fn* nil)
                   (done))))))

  (testing "ask-human with interpolation"
    (async done
      (set! executor/*ask-human-fn*
            (fn [params]
              (is (= "" (:contact params)))
              (is (= "Deploy ?" (:question params)))
              (js/Promise.resolve #js {:status "approved" :response "go"})))
      (-> (executor/execute-step {:step-action :ask-human
                                  :step-config {"contact" "{{deployer}}"
                                                "question" "Deploy {{version}}?"}}
                                 {:variables {"deployer" "John"
                                              "version" "v1.2.3"}})
          (.then (fn [result]
                   (is (= "approved" (:status result)))
                   (set! executor/*ask-human-fn* nil)
                   (done)))))))

(deftest ask-human-registered-test
  (testing "ask-human is registered"
    (is (some? (get @executor/step-executors :ask-human)))))
