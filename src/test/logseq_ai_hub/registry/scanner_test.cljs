(ns logseq-ai-hub.registry.scanner-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [logseq-ai-hub.registry.scanner :as scanner]
            [logseq-ai-hub.registry.store :as store]))

(defn setup-mocks! []
  (store/init-store!)
  ;; Mock logseq API
  (set! js/logseq
    #js {:settings #js {"skillPagePrefix" "Skills/"}
         :DB #js {:datascriptQuery
                  (fn [query]
                    ;; Return different results based on query content
                    (cond
                      ;; Tool tag query
                      (re-find #"logseq-ai-hub-tool" query)
                      (js/Promise.resolve
                        #js [#js [#js {"block/name" "tools/send-slack"
                                       "block/original-name" "Tools/send-slack"}]])

                      ;; Prompt tag query
                      (re-find #"logseq-ai-hub-prompt" query)
                      (js/Promise.resolve
                        #js [#js [#js {"block/name" "prompts/code-review"
                                       "block/original-name" "Prompts/code-review"}]])

                      ;; Procedure tag query
                      (re-find #"logseq-ai-hub-procedure" query)
                      (js/Promise.resolve
                        #js [#js [#js {"block/name" "procedures/deploy"
                                       "block/original-name" "Procedures/deploy"}]])

                      ;; Default: empty
                      :else (js/Promise.resolve #js [])))}
         :Editor #js {:getPageBlocksTree
                      (fn [page-name]
                        (cond
                          ;; Tool page
                          (= page-name "Tools/send-slack")
                          (js/Promise.resolve
                            #js [#js {:content "tool-name:: send-slack\ntool-description:: Send to Slack\ntool-handler:: http\ntool-input-schema:: {\"type\": \"object\", \"properties\": {\"message\": {\"type\": \"string\"}}}\ntool-http-url:: https://slack.com/api\ntool-http-method:: POST\ntags:: logseq-ai-hub-tool"}])

                          ;; Prompt page
                          (= page-name "Prompts/code-review")
                          (js/Promise.resolve
                            #js [#js {:content "prompt-name:: code-review\nprompt-description:: Review code\nprompt-arguments:: code, language\ntags:: logseq-ai-hub-prompt\n\n## System\nYou are an expert reviewer.\n\n## User\n```{{language}}\n{{code}}\n```"}])

                          ;; Procedure page
                          (= page-name "Procedures/deploy")
                          (js/Promise.resolve
                            #js [#js {:content "procedure-name:: deploy\nprocedure-description:: Deploy to production\nprocedure-requires-approval:: true\ntags:: logseq-ai-hub-procedure\n\n1. Run tests\n2. Build\n3. Deploy"}])

                          :else
                          (js/Promise.resolve #js [])))}}))

(use-fixtures :each {:before setup-mocks!})

(deftest test-scan-tagged-pages
  (testing "Scans for pages with a specific tag"
    (async done
      (-> (scanner/scan-tagged-pages! "logseq-ai-hub-tool")
          (.then (fn [results]
                   (is (= 1 (count results)))
                   (is (= "tools/send-slack" (:page-name (first results))))
                   (is (= "Tools/send-slack" (:original-name (first results))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Error: " err))
                    (done)))))))

(deftest test-scan-tagged-pages-empty
  (testing "Returns empty for unknown tag"
    (async done
      (-> (scanner/scan-tagged-pages! "nonexistent-tag")
          (.then (fn [results]
                   (is (= [] results))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Error: " err))
                    (done)))))))

(deftest test-refresh-registry-full
  (testing "Full refresh discovers tools, prompts, and procedures"
    (async done
      ;; Mock skill and agent scanners
      (set! scanner/*scan-skill-pages-fn*
        (fn [_prefix]
          (js/Promise.resolve
            [{:skill-id "Skills/summarize"
              :properties {:skill-type :llm-chain
                           :skill-version 1
                           :skill-description "Summarize text"
                           :skill-inputs ["query"]
                           :skill-outputs ["summary"]}
              :steps []
              :valid true}])))
      (set! scanner/*scan-agent-pages-fn*
        (fn []
          (js/Promise.resolve
            [{:page-name "agents/reviewer"
              :original-name "Agents/reviewer"}])))

      (-> (scanner/refresh-registry!)
          (.then (fn [counts]
                   ;; Tools: 1 from tag + 1 from skill wrapping
                   (is (= 2 (:tools counts)))
                   (is (= 1 (:skills counts)))
                   (is (= 1 (:prompts counts)))
                   (is (= 1 (:procedures counts)))
                   (is (= 1 (:agents counts)))

                   ;; Verify store has entries
                   (is (some? (store/get-entry :tool "Tools/send-slack")))
                   (is (some? (store/get-entry :prompt "Prompts/code-review")))
                   (is (some? (store/get-entry :procedure "Procedures/deploy")))
                   (is (some? (store/get-entry :skill "Skills/summarize")))
                   (is (some? (store/get-entry :agent "agents/reviewer")))

                   ;; Version was bumped
                   (is (= 1 (:version (store/get-snapshot))))

                   ;; Clean up dynamic vars
                   (set! scanner/*scan-skill-pages-fn* nil)
                   (set! scanner/*scan-agent-pages-fn* nil)
                   (done)))
          (.catch (fn [err]
                    (set! scanner/*scan-skill-pages-fn* nil)
                    (set! scanner/*scan-agent-pages-fn* nil)
                    (is false (str "Error: " err))
                    (done)))))))
