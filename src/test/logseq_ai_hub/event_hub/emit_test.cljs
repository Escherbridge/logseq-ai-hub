(ns logseq-ai-hub.event-hub.emit-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.event-hub.emit :as emit]
            [logseq-ai-hub.job-runner.executor :as executor]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def publish-calls (atom []))

(defn- setup-mocks!
  "Sets up *publish-event-fn* mock that records calls and returns event-id."
  []
  (reset! publish-calls [])
  (set! emit/*publish-event-fn*
    (fn [event-map]
      (swap! publish-calls conj event-map)
      (js/Promise.resolve {:event-id "evt-mock-123"}))))

(defn- teardown-mocks!
  "Resets dynamic var."
  []
  (set! emit/*publish-event-fn* nil))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest test-emit-publishes-correct-type
  (setup-mocks!)
  (testing "publishes event with correct type from config"
    (async done
      (-> (executor/execute-step
            {:step-action :emit-event
             :step-config {"type" "custom.deployment"
                           "data" {"env" "production"}}}
            {:job-id "test-job-1"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [result]
                   ;; Verify the published event
                   (is (= 1 (count @publish-calls)))
                   (let [published (first @publish-calls)]
                     (is (= "custom.deployment" (:type published)))
                     (is (= {"env" "production"} (:data published))))
                   ;; Verify return value
                   (is (= "evt-mock-123" (:event-id result)))
                   (is (true? (:published result)))
                   (teardown-mocks!)
                   (done)))
          (.catch (fn [err]
                    (teardown-mocks!)
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-emit-chain-depth-incremented
  (setup-mocks!)
  (testing "chain_depth is incremented from context metadata"
    (async done
      (-> (executor/execute-step
            {:step-action :emit-event
             :step-config {"type" "custom.event"
                           "data" {}}}
            {:job-id "test-job-2"
             :variables {}
             :inputs {}
             :step-results {}
             :metadata {:chain_depth 3}})
          (.then (fn [_]
                   (let [published (first @publish-calls)
                         metadata (:metadata published)]
                     (is (= 4 (get metadata "chain_depth"))
                         "chain_depth should be incremented from 3 to 4"))
                   (teardown-mocks!)
                   (done)))
          (.catch (fn [err]
                    (teardown-mocks!)
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-emit-chain-depth-starts-at-1
  (setup-mocks!)
  (testing "chain_depth starts at 1 when not in context"
    (async done
      (-> (executor/execute-step
            {:step-action :emit-event
             :step-config {"type" "custom.event"
                           "data" {}}}
            {:job-id "test-job-3"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [_]
                   (let [published (first @publish-calls)
                         metadata (:metadata published)]
                     (is (= 1 (get metadata "chain_depth"))
                         "chain_depth should start at 1 when not present"))
                   (teardown-mocks!)
                   (done)))
          (.catch (fn [err]
                    (teardown-mocks!)
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-emit-source-includes-job-id
  (setup-mocks!)
  (testing "source is set to skill:{job-id}"
    (async done
      (-> (executor/execute-step
            {:step-action :emit-event
             :step-config {"type" "custom.event"
                           "data" {}}}
            {:job-id "Jobs/my-deployment-job"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [_]
                   (let [published (first @publish-calls)]
                     (is (= "skill:Jobs/my-deployment-job" (:source published))))
                   (teardown-mocks!)
                   (done)))
          (.catch (fn [err]
                    (teardown-mocks!)
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-emit-interpolation-in-type-and-data
  (setup-mocks!)
  (testing "interpolation works in type and data values"
    (async done
      (-> (executor/execute-step
            {:step-action :emit-event
             :step-config {"type" "deploy.{{environment}}"
                           "data" {"version" "{{step-1-result}}"
                                   "deployed_by" "{{user}}"}}}
            {:job-id "test-job-4"
             :variables {}
             :inputs {"environment" "production" "user" "Alice"}
             :step-results {1 "v2.1.0"}})
          (.then (fn [_]
                   (let [published (first @publish-calls)]
                     (is (= "deploy.production" (:type published)))
                     (is (= "v2.1.0" (get-in published [:data "version"])))
                     (is (= "Alice" (get-in published [:data "deployed_by"]))))
                   (teardown-mocks!)
                   (done)))
          (.catch (fn [err]
                    (teardown-mocks!)
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-emit-returns-event-id-and-published
  (setup-mocks!)
  (testing "returns {:event-id ... :published true}"
    (async done
      (-> (executor/execute-step
            {:step-action :emit-event
             :step-config {"type" "test.event"
                           "data" {}}}
            {:job-id "test-job-5"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [result]
                   (is (= "evt-mock-123" (:event-id result)))
                   (is (true? (:published result)))
                   (teardown-mocks!)
                   (done)))
          (.catch (fn [err]
                    (teardown-mocks!)
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-emit-not-initialized
  (testing "rejects when *publish-event-fn* is nil"
    (set! emit/*publish-event-fn* nil)
    (async done
      (-> (executor/execute-step
            {:step-action :emit-event
             :step-config {"type" "test.event"
                           "data" {}}}
            {:job-id "test-job-6"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [_]
                   (is false "Should have been rejected")
                   (done)))
          (.catch (fn [err]
                    (is (= :emit-not-initialized (:type err)))
                    (done)))))))

(deftest test-emit-no-type-rejects
  (setup-mocks!)
  (testing "rejects when no event type provided"
    (async done
      (-> (executor/execute-step
            {:step-action :emit-event
             :step-config {"data" {"key" "value"}}}
            {:job-id "test-job-7"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [_]
                   (teardown-mocks!)
                   (is false "Should have been rejected")
                   (done)))
          (.catch (fn [err]
                    (is (= :emit-invalid-type (:type err)))
                    (teardown-mocks!)
                    (done)))))))

(deftest test-emit-preserves-config-metadata
  (setup-mocks!)
  (testing "metadata from config is preserved alongside chain_depth"
    (async done
      (-> (executor/execute-step
            {:step-action :emit-event
             :step-config {"type" "test.event"
                           "data" {}
                           "metadata" {"severity" "warning"
                                       "tags" ["deploy" "prod"]}}}
            {:job-id "test-job-8"
             :variables {}
             :inputs {}
             :step-results {}
             :metadata {:chain_depth 2}})
          (.then (fn [_]
                   (let [published (first @publish-calls)
                         metadata (:metadata published)]
                     (is (= "warning" (get metadata "severity")))
                     (is (= ["deploy" "prod"] (get metadata "tags")))
                     (is (= 3 (get metadata "chain_depth"))))
                   (teardown-mocks!)
                   (done)))
          (.catch (fn [err]
                    (teardown-mocks!)
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-emit-registered
  (testing ":emit-event executor is registered"
    (is (some? (get @executor/step-executors :emit-event)))))
