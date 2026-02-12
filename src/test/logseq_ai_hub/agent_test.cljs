(ns logseq-ai-hub.agent-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.agent :as agent]))

(deftest test-register-model
  (testing "register-model adds handler to registry"
    (let [handler (fn [input _] (js/Promise.resolve input))]
      (agent/register-model "test-model" handler)
      (is (= handler (agent/get-model "test-model"))))))

(deftest test-get-model-nil
  (testing "get-model returns nil for unregistered model"
    (is (nil? (agent/get-model "nonexistent-model")))))

(deftest test-process-input-dispatch
  (testing "process-input dispatches to correct handler"
    (async done
      (let [handler (fn [input _] (js/Promise.resolve (str "handled:" input)))]
        (agent/register-model "dispatch-test" handler)
        (-> (agent/process-input "hello" "dispatch-test")
            (.then (fn [result]
                     (is (= "handled:hello" result))
                     (done))))))))

(deftest test-process-input-fallback
  (testing "process-input falls back to default-handler for unknown model"
    (async done
      (-> (agent/process-input "test" "unknown-model-xyz")
          (.then (fn [result]
                   (is (string? result))
                   (is (.includes result "test"))
                   (done)))))))

(deftest test-echo-handler
  (testing "echo-handler returns string containing input"
    (async done
      (-> (agent/echo-handler "hello world" "mock-model")
          (.then (fn [result]
                   (is (string? result))
                   (is (.includes result "hello world"))
                   (done)))))))

(deftest test-reverse-handler
  (testing "reverse-handler returns string containing reversed input"
    (async done
      (-> (agent/reverse-handler "hello" "reverse-model")
          (.then (fn [result]
                   (is (string? result))
                   (is (.includes result "olleh"))
                   (done)))))))
