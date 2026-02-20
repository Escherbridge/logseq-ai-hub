(ns logseq-ai-hub.job-runner.engine-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.job-runner.engine :as engine]
            [logseq-ai-hub.util.errors :as errors]))

;; Mock executor for testing
(def mock-executor-state (atom {}))

(defn reset-mock-executor! []
  (reset! mock-executor-state
          {:execute-step-fn nil
           :step-executors {}
           :call-count 0}))

(defn mock-execute-step [step ctx]
  (swap! mock-executor-state update :call-count inc)
  (let [handler (get-in @mock-executor-state [:step-executors (:step-action step)])]
    (if handler
      (handler step ctx)
      (js/Promise.resolve {:result "default-result" :step-order (:step-order step)}))))

(defn set-step-executor! [action handler]
  (swap! mock-executor-state assoc-in [:step-executors action] handler))

;; Tests for context management

(deftest test-make-context
  (testing "Creates context with job-id and inputs"
    (let [ctx (engine/make-context "job-123" {:input1 "value1" :input2 42})]
      (is (= "job-123" (:job-id ctx)))
      (is (= {:input1 "value1" :input2 42} (:inputs ctx)))
      (is (= {} (:step-results ctx)))
      (is (= "job-123" (get-in ctx [:variables :job-id])))
      (is (string? (get-in ctx [:variables :today])))
      (is (string? (get-in ctx [:variables :now]))))))

(deftest test-update-context
  (testing "Updates context with step result"
    (let [ctx (engine/make-context "job-1" {})
          updated (engine/update-context ctx 1 {:result "step-1-result"})]
      (is (= {:result "step-1-result"} (get-in updated [:step-results 1])))
      (is (= {} (:step-results ctx)) "Original context unchanged"))))

(deftest test-resolve-input
  (testing "Resolves step result references"
    (let [ctx (-> (engine/make-context "job-1" {:input-key "input-value"})
                  (engine/update-context 1 "result-from-step-1")
                  (engine/update-context 2 {:data "result-from-step-2"}))]
      (is (= "result-from-step-1" (engine/resolve-input ctx "step-1-result")))
      (is (= {:data "result-from-step-2"} (engine/resolve-input ctx "step-2-result")))
      (is (= "input-value" (engine/resolve-input ctx "input-key")))
      (is (nil? (engine/resolve-input ctx "nonexistent"))))))

;; Tests for step execution

(deftest test-run-steps-sequential
  (async done
    (engine/set-executor-execute-step! mock-execute-step)
    (reset-mock-executor!)
    (set-step-executor! :action-1
      (fn [step ctx]
        (js/Promise.resolve {:result "result-1"})))
    (set-step-executor! :action-2
      (fn [step ctx]
        (is (= {:result "result-1"} (get-in ctx [:step-results 1])))
        (js/Promise.resolve {:result "result-2"})))

    (let [steps [{:step-order 1 :step-action :action-1}
                 {:step-order 2 :step-action :action-2}]
          ctx (engine/make-context "job-1" {})
          step-completions (atom [])]

      (-> (engine/run-steps steps ctx
            (fn [step result]
              (swap! step-completions conj {:step (:step-order step) :result result})))
          (.then (fn [final-ctx]
                   (is (= {:result "result-1"} (get-in final-ctx [:step-results 1])))
                   (is (= {:result "result-2"} (get-in final-ctx [:step-results 2])))
                   (is (= 2 (count @step-completions)))
                   (is (= 2 (:call-count @mock-executor-state)))
                   (engine/set-executor-execute-step! nil)
                   (done)))
          (.catch (fn [err]
                    (engine/set-executor-execute-step! nil)
                    (is false (str "Should not fail: " err))
                    (done)))))))

(deftest test-run-steps-conditional-jump
  (async done
    (engine/set-executor-execute-step! mock-execute-step)
    (reset-mock-executor!)
    (set-step-executor! :conditional
      (fn [step ctx]
        (js/Promise.resolve {:directive :jump :target-step 4})))
    (set-step-executor! :action-skip
      (fn [step ctx]
        (is false "Step 2 and 3 should be skipped")
        (js/Promise.resolve {:result "skipped"})))
    (set-step-executor! :action-final
      (fn [step ctx]
        (js/Promise.resolve {:result "final-result"})))

    (let [steps [{:step-order 1 :step-action :conditional}
                 {:step-order 2 :step-action :action-skip}
                 {:step-order 3 :step-action :action-skip}
                 {:step-order 4 :step-action :action-final}]
          ctx (engine/make-context "job-1" {})]

      (-> (engine/run-steps steps ctx nil)
          (.then (fn [final-ctx]
                   (is (= {:directive :jump :target-step 4}
                          (get-in final-ctx [:step-results 1])))
                   (is (nil? (get-in final-ctx [:step-results 2])) "Step 2 skipped")
                   (is (nil? (get-in final-ctx [:step-results 3])) "Step 3 skipped")
                   (is (= {:result "final-result"} (get-in final-ctx [:step-results 4])))
                   (is (= 2 (:call-count @mock-executor-state)) "Only 2 steps executed")
                   (engine/set-executor-execute-step! nil)
                   (done)))
          (.catch (fn [err]
                    (engine/set-executor-execute-step! nil)
                    (is false (str "Should not fail: " err))
                    (done)))))))

(deftest test-run-steps-jump-to-nonexistent
  (async done
    (engine/set-executor-execute-step! mock-execute-step)
    (reset-mock-executor!)
    (set-step-executor! :conditional
      (fn [step ctx]
        (js/Promise.resolve {:directive :jump :target-step 99})))

    (let [steps [{:step-order 1 :step-action :conditional}]
          ctx (engine/make-context "job-1" {})]

      (-> (engine/run-steps steps ctx nil)
          (.then (fn [_]
                   (engine/set-executor-execute-step! nil)
                   (is false "Should reject on invalid jump target")
                   (done)))
          (.catch (fn [err]
                    (engine/set-executor-execute-step! nil)
                    (is (errors/error? err))
                    (is (= :execution-error (:type err)))
                    (is (re-find #"Jump target step 99 not found" (:message err)))
                    (done)))))))

(deftest test-run-steps-error-handling
  (async done
    (engine/set-executor-execute-step! mock-execute-step)
    (reset-mock-executor!)
    (set-step-executor! :action-1
      (fn [step ctx]
        (js/Promise.resolve {:result "ok"})))
    (set-step-executor! :action-fail
      (fn [step ctx]
        (js/Promise.reject (js/Error. "Step execution failed"))))

    (let [steps [{:step-order 1 :step-action :action-1}
                 {:step-order 2 :step-action :action-fail}
                 {:step-order 3 :step-action :action-1}]
          ctx (engine/make-context "job-1" {})]

      (-> (engine/run-steps steps ctx nil)
          (.then (fn [_]
                   (engine/set-executor-execute-step! nil)
                   (is false "Should reject on step failure")
                   (done)))
          (.catch (fn [err]
                    (engine/set-executor-execute-step! nil)
                    (is (:error err))
                    (is (= :step-execution-error (:type err)))
                    (is (= 2 (:failed-step err)))
                    (is (= "Step execution failed" (:message err)))
                    (is (= {:result "ok"} (get-in err [:context :step-results 1])))
                    (done)))))))

;; Tests for skill execution

(deftest test-execute-skill-success
  (async done
    (engine/set-executor-execute-step! mock-execute-step)
    (reset-mock-executor!)
    (set-step-executor! :action-1
      (fn [step ctx]
        (js/Promise.resolve {:result "step-1-done"})))
    (set-step-executor! :action-2
      (fn [step ctx]
        (js/Promise.resolve {:result "step-2-done"})))

    (let [skill {:steps [{:step-order 1 :step-action :action-1}
                         {:step-order 2 :step-action :action-2}]}
          inputs {:input-a "value-a"}
          progress-calls (atom [])]

      (-> (engine/execute-skill skill inputs "job-123"
            (fn [step result]
              (swap! progress-calls conj {:step (:step-order step) :result result})))
          (.then (fn [result]
                   (is (= :completed (:status result)))
                   (is (= {:result "step-2-done"} (:result result)))
                   (is (number? (:duration-ms result)))
                   (is (>= (:duration-ms result) 0))
                   (is (= 2 (count @progress-calls)))
                   (engine/set-executor-execute-step! nil)
                   (done)))
          (.catch (fn [err]
                    (engine/set-executor-execute-step! nil)
                    (is false (str "Should not fail: " err))
                    (done)))))))

(deftest test-execute-skill-failure
  (async done
    (engine/set-executor-execute-step! mock-execute-step)
    (reset-mock-executor!)
    (set-step-executor! :action-ok
      (fn [step ctx]
        (js/Promise.resolve {:result "ok"})))
    (set-step-executor! :action-fail
      (fn [step ctx]
        (js/Promise.reject (js/Error. "Execution error"))))

    (let [skill {:steps [{:step-order 1 :step-action :action-ok}
                         {:step-order 2 :step-action :action-fail}]}
          inputs {}]

      (-> (engine/execute-skill skill inputs "job-123" nil)
          (.then (fn [result]
                   (is (= :failed (:status result)))
                   (is (:error result))
                   (is (= 2 (:failed-step result)))
                   (is (number? (:duration-ms result)))
                   (engine/set-executor-execute-step! nil)
                   (done)))
          (.catch (fn [err]
                    (engine/set-executor-execute-step! nil)
                    (is false (str "Should resolve with error status, not reject: " err))
                    (done)))))))

;; Tests for retry logic

(deftest test-execute-skill-with-retries-success-first-try
  (async done
    (engine/set-executor-execute-step! mock-execute-step)
    (reset-mock-executor!)
    (set-step-executor! :action-1
      (fn [step ctx]
        (js/Promise.resolve {:result "success"})))

    (let [skill {:steps [{:step-order 1 :step-action :action-1}]}
          inputs {}
          progress-calls (atom [])]

      (-> (engine/execute-skill-with-retries skill inputs "job-123" 3
            (fn [step result retry-count]
              (swap! progress-calls conj {:step (:step-order step)
                                          :retry retry-count})))
          (.then (fn [result]
                   (is (= :completed (:status result)))
                   (is (= {:result "success"} (:result result)))
                   (is (= 1 (count @progress-calls)))
                   (is (= 0 (:retry (first @progress-calls))))
                   (engine/set-executor-execute-step! nil)
                   (done)))
          (.catch (fn [err]
                    (engine/set-executor-execute-step! nil)
                    (is false (str "Should not fail: " err))
                    (done)))))))

(deftest test-execute-skill-with-retries-success-after-retry
  (async done
    (engine/set-executor-execute-step! mock-execute-step)
    (reset-mock-executor!)
    (let [attempt-count (atom 0)]
      (set-step-executor! :flaky-action
          (fn [step ctx]
            (swap! attempt-count inc)
            (if (< @attempt-count 3)
              (js/Promise.reject (js/Error. "Temporary failure"))
              (js/Promise.resolve {:result "success-after-retries"}))))

        (let [skill {:steps [{:step-order 1 :step-action :flaky-action}]}
              inputs {}
              execution-count (atom 0)]

          (-> (engine/execute-skill-with-retries skill inputs "job-123" 3
                (fn [step result retry-count]
                  (swap! execution-count inc)))
              (.then (fn [result]
                       (is (= :completed (:status result)))
                       (is (= {:result "success-after-retries"} (:result result)))
                       (is (= 3 @attempt-count) "Should have tried 3 times")
                       (engine/set-executor-execute-step! nil)
                       (done)))
              (.catch (fn [err]
                        (engine/set-executor-execute-step! nil)
                        (is false (str "Should not fail: " err))
                        (done))))))))

(deftest test-execute-skill-with-retries-exhausted
  (async done
    (engine/set-executor-execute-step! mock-execute-step)
    (reset-mock-executor!)
    (set-step-executor! :always-fail
        (fn [step ctx]
          (js/Promise.reject (js/Error. "Persistent failure"))))

      (let [skill {:steps [{:step-order 1 :step-action :always-fail}]}
            inputs {}
            attempt-count (atom 0)]

        (-> (engine/execute-skill-with-retries skill inputs "job-123" 2
              (fn [step result retry-count]
                (swap! attempt-count inc)))
            (.then (fn [result]
                     (is (= :failed (:status result)))
                     (is (:error result))
                     (is (= 3 @attempt-count) "Initial attempt + 2 retries = 3 total")
                     (engine/set-executor-execute-step! nil)
                     (done)))
            (.catch (fn [err]
                      (engine/set-executor-execute-step! nil)
                      (is false (str "Should resolve with error status, not reject: " err))
                      (done)))))))

(deftest test-execute-skill-with-retries-zero-retries
  (async done
    (engine/set-executor-execute-step! mock-execute-step)
    (reset-mock-executor!)
    (set-step-executor! :fail
        (fn [step ctx]
          (js/Promise.reject (js/Error. "Failure"))))

      (let [skill {:steps [{:step-order 1 :step-action :fail}]}
            inputs {}
            attempt-count (atom 0)]

        (-> (engine/execute-skill-with-retries skill inputs "job-123" 0
              (fn [step result retry-count]
                (swap! attempt-count inc)))
            (.then (fn [result]
                     (is (= :failed (:status result)))
                     (is (= 1 @attempt-count) "Should only try once with 0 retries")
                     (engine/set-executor-execute-step! nil)
                     (done)))
            (.catch (fn [err]
                      (engine/set-executor-execute-step! nil)
                      (is false (str "Should resolve with error status, not reject: " err))
                      (done)))))))
