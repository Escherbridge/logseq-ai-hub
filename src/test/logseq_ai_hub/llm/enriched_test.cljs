(ns logseq-ai-hub.llm.enriched-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.llm.enriched :as enriched]
            [logseq-ai-hub.agent :as agent]
            [logseq-ai-hub.memory :as memory]
            [clojure.string :as str]))

(defn- setup-base-mocks!
  "Sets up base mocks for logseq and agent."
  []
  (set! js/logseq
    #js {:Editor #js {:getPageBlocksTree
                      (fn [_] (js/Promise.resolve nil))}
         :settings #js {:selectedModel "llm-model"
                        :llmApiKey "test-key"
                        :llmEndpoint "https://api.test.com/v1"
                        :llmModel "test-model"
                        :memoryEnabled false
                        :pageRefDepth 0
                        :pageRefMaxTokens 8000}
         :DB #js {:datascriptQuery (fn [_] #js [])}}))

(deftest enriched-call-plain-text
  (testing "plain text with no refs takes simple path"
    (async done
      (setup-base-mocks!)
      (let [captured (atom nil)]
        ;; Mock process-input to capture what's called
        (set! agent/process-input
              (fn [prompt model-id]
                (reset! captured {:prompt prompt :model-id model-id})
                (js/Promise.resolve "mock response")))
        (-> (enriched/call "What is 2+2?")
            (.then (fn [result]
                     (is (= "mock response" result))
                     (is (= "What is 2+2?" (:prompt @captured)))
                     (is (= "llm-model" (:model-id @captured)))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Error: " err))
                      (done))))))))

(deftest enriched-call-with-page-refs
  (testing "page refs trigger enriched path with graph context"
    (async done
      (set! js/logseq
        #js {:Editor #js {:getPageBlocksTree
                          (fn [page-name]
                            (case page-name
                              "My Notes" (js/Promise.resolve
                                           (clj->js [{:content "Important notes here"}]))
                              (js/Promise.resolve nil)))}
             :settings #js {:selectedModel "llm-model"
                            :llmApiKey "test-key"
                            :llmEndpoint "https://api.test.com/v1"
                            :llmModel "test-model"
                            :memoryEnabled false
                            :pageRefDepth 0
                            :pageRefMaxTokens 8000}
             :DB #js {:datascriptQuery (fn [_] #js [])}})
      ;; Reset memory state to disabled
      (reset! memory/state {:config {:page-prefix "AI-Memory/" :enabled false}
                            :index {}})
      (let [captured (atom nil)]
        (set! agent/process-with-system-prompt
              (fn [prompt system-prompt]
                (reset! captured {:prompt prompt :system-prompt system-prompt})
                (js/Promise.resolve "enriched response")))
        (-> (enriched/call "[[My Notes]] summarize this")
            (.then (fn [result]
                     (is (= "enriched response" result))
                     (is (= "summarize this" (:prompt @captured)))
                     (is (str/includes? (:system-prompt @captured) "Context from referenced pages"))
                     (is (str/includes? (:system-prompt @captured) "Important notes"))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Error: " err))
                      (done))))))))

(deftest enriched-call-with-extra-system-prompt
  (testing "extra-system-prompt is prepended to resolved context"
    (async done
      (set! js/logseq
        #js {:Editor #js {:getPageBlocksTree
                          (fn [page-name]
                            (case page-name
                              "Ref Page" (js/Promise.resolve
                                           (clj->js [{:content "Page content"}]))
                              (js/Promise.resolve nil)))}
             :settings #js {:selectedModel "llm-model"
                            :llmApiKey "test-key"
                            :llmEndpoint "https://api.test.com/v1"
                            :llmModel "test-model"
                            :memoryEnabled false
                            :pageRefDepth 0
                            :pageRefMaxTokens 8000}
             :DB #js {:datascriptQuery (fn [_] #js [])}})
      (reset! memory/state {:config {:page-prefix "AI-Memory/" :enabled false}
                            :index {}})
      (let [captured (atom nil)]
        (set! agent/process-with-system-prompt
              (fn [prompt system-prompt]
                (reset! captured {:prompt prompt :system-prompt system-prompt})
                (js/Promise.resolve "agent response")))
        (-> (enriched/call "[[Ref Page]] do stuff"
              :extra-system-prompt "You are a helpful assistant")
            (.then (fn [result]
                     (is (= "agent response" result))
                     ;; Extra system prompt should be first
                     (is (str/starts-with? (:system-prompt @captured) "You are a helpful assistant"))
                     ;; Page context should also be present
                     (is (str/includes? (:system-prompt @captured) "Context from referenced pages"))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Error: " err))
                      (done))))))))

(deftest enriched-call-extra-prompt-only
  (testing "extra-system-prompt alone (no refs) triggers enriched path"
    (async done
      (setup-base-mocks!)
      (reset! memory/state {:config {:page-prefix "AI-Memory/" :enabled false}
                            :index {}})
      (let [captured (atom nil)]
        (set! agent/process-with-system-prompt
              (fn [prompt system-prompt]
                (reset! captured {:prompt prompt :system-prompt system-prompt})
                (js/Promise.resolve "with-system response")))
        (-> (enriched/call "hello" :extra-system-prompt "You are a pirate")
            (.then (fn [result]
                     (is (= "with-system response" result))
                     (is (= "hello" (:prompt @captured)))
                     (is (= "You are a pirate" (:system-prompt @captured)))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Error: " err))
                      (done))))))))
