(ns logseq-ai-hub.event-hub.lifecycle-test
  "Tests for job runner lifecycle event emission.
   Verifies that *emit-event-fn* is called at each lifecycle point
   with the correct event type, source, and data."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.job-runner.runner :as runner]
            [logseq-ai-hub.util.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def emitted-events (atom []))

(def mock-graph-state (atom {}))

(defn- reset-all-mocks! []
  (reset! emitted-events [])
  (reset! mock-graph-state
          {:jobs {}
           :skills {}
           :status-updates []
           :property-updates []
           :log-entries []}))

;; ---------------------------------------------------------------------------
;; Mock Emit Function
;; ---------------------------------------------------------------------------

(defn- mock-emit-fn [event-map]
  (swap! emitted-events conj event-map)
  (js/Promise.resolve {:event-id "evt-lifecycle-mock"}))

;; ---------------------------------------------------------------------------
;; Mock Graph Functions
;; ---------------------------------------------------------------------------

(defn- mock-read-job-page [page-name]
  (js/Promise.resolve (get-in @mock-graph-state [:jobs page-name])))

(defn- mock-read-skill-page [page-name]
  (js/Promise.resolve (get-in @mock-graph-state [:skills page-name])))

(defn- mock-update-job-status! [page-name status]
  (swap! mock-graph-state update :status-updates conj {:page page-name :status status})
  (js/Promise.resolve nil))

(defn- mock-update-job-property! [page-name key val]
  (swap! mock-graph-state update :property-updates conj {:page page-name :key key :val val})
  (js/Promise.resolve nil))

(defn- mock-append-job-log! [page-name text]
  (swap! mock-graph-state update :log-entries conj {:page page-name :text text})
  (js/Promise.resolve nil))

(defn- mock-make-queue []
  {:items (atom [])})

(defn- mock-enqueue [queue entry]
  (swap! (:items queue) conj entry))

(defn- mock-remove-from-queue [queue id]
  (swap! (:items queue) (fn [items]
                          (vec (remove #(= (:job-id %) id) items)))))

;; ---------------------------------------------------------------------------
;; Mock Engine
;; ---------------------------------------------------------------------------

(def mock-engine-result (atom {:status :completed
                               :result {:output "test-output"}
                               :duration-ms 100}))

(defn- mock-execute-skill [skill-def inputs job-id max-retries on-progress]
  (js/Promise.resolve @mock-engine-result))

;; ---------------------------------------------------------------------------
;; Setup / Teardown
;; ---------------------------------------------------------------------------

(defn- setup!
  "Wires all mocks and resets state."
  []
  (reset-all-mocks!)
  (set! runner/graph-read-job-page mock-read-job-page)
  (set! runner/graph-read-skill-page mock-read-skill-page)
  (set! runner/graph-update-job-status! mock-update-job-status!)
  (set! runner/graph-update-job-property! mock-update-job-property!)
  (set! runner/graph-append-job-log! mock-append-job-log!)
  (set! runner/queue-make-queue mock-make-queue)
  (set! runner/queue-enqueue mock-enqueue)
  (set! runner/queue-remove-from-queue mock-remove-from-queue)
  (set! runner/engine-execute-skill-with-retries mock-execute-skill)
  (set! runner/*emit-event-fn* mock-emit-fn)
  (runner/reset-runner!))

(defn- teardown! []
  (set! runner/*emit-event-fn* nil))

;; ---------------------------------------------------------------------------
;; Helper
;; ---------------------------------------------------------------------------

(defn- find-event [event-type]
  (first (filter #(= event-type (:type %)) @emitted-events)))

(defn- count-events [event-type]
  (count (filter #(= event-type (:type %)) @emitted-events)))

;; ---------------------------------------------------------------------------
;; Tests: job.created
;; ---------------------------------------------------------------------------

(deftest test-lifecycle-job-created-on-enqueue
  (setup!)
  (testing "enqueue-job! emits job.created event"
    (async done
      (swap! mock-graph-state assoc-in [:jobs "Jobs/LC-1"]
             {:job-id "Jobs/LC-1"
              :job-skill "Skills/Test"
              :job-priority 2
              :job-depends-on #{}})

      (-> (runner/enqueue-job! "Jobs/LC-1")
          (.then (fn [_]
                   (let [evt (find-event "job.created")]
                     (is (some? evt) "job.created event should be emitted")
                     (is (= "system:job-runner" (:source evt)))
                     (is (= "Jobs/LC-1" (get-in evt [:data :job-id])))
                     (is (= 2 (get-in evt [:data :priority]))))
                   (teardown!)
                   (done)))
          (.catch (fn [err]
                    (teardown!)
                    (is false (str "Should not fail: " err))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Tests: job.started
;; ---------------------------------------------------------------------------

(deftest test-lifecycle-job-started-on-execute
  (setup!)
  (testing "execute-job! emits job.started event"
    (async done
      (swap! mock-graph-state assoc-in [:jobs "Jobs/LC-2"]
             {:job-id "Jobs/LC-2"
              :job-skill "Skills/TestSkill"
              :job-input {}
              :job-max-retries 0})
      (swap! mock-graph-state assoc-in [:skills "Skills/TestSkill"]
             {:skill-name "Skills/TestSkill"
              :steps [{:step-order 1 :step-action :test-action}]})
      (reset! mock-engine-result {:status :completed
                                  :result {:output "ok"}
                                  :duration-ms 50})

      (-> (runner/execute-job! "Jobs/LC-2")
          (.then (fn [_]
                   (let [evt (find-event "job.started")]
                     (is (some? evt) "job.started event should be emitted")
                     (is (= "system:job-runner" (:source evt)))
                     (is (= "Jobs/LC-2" (get-in evt [:data :job-id])))
                     (is (= "Skills/TestSkill" (get-in evt [:data :skill]))))
                   (teardown!)
                   (done)))
          (.catch (fn [err]
                    (teardown!)
                    (is false (str "Should not fail: " err))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Tests: job.completed
;; ---------------------------------------------------------------------------

(deftest test-lifecycle-job-completed-on-success
  (setup!)
  (testing "execute-job! emits job.completed on success"
    (async done
      (swap! mock-graph-state assoc-in [:jobs "Jobs/LC-3"]
             {:job-id "Jobs/LC-3"
              :job-skill "Skills/TestSkill"
              :job-input {}
              :job-max-retries 0})
      (swap! mock-graph-state assoc-in [:skills "Skills/TestSkill"]
             {:skill-name "Skills/TestSkill"
              :steps [{:step-order 1 :step-action :test-action}]})
      (reset! mock-engine-result {:status :completed
                                  :result {:output "success"}
                                  :duration-ms 200})

      (-> (runner/execute-job! "Jobs/LC-3")
          (.then (fn [_]
                   (let [evt (find-event "job.completed")]
                     (is (some? evt) "job.completed event should be emitted")
                     (is (= "system:job-runner" (:source evt)))
                     (is (= "Jobs/LC-3" (get-in evt [:data :job-id])))
                     (is (= 200 (get-in evt [:data :duration-ms]))))
                   (teardown!)
                   (done)))
          (.catch (fn [err]
                    (teardown!)
                    (is false (str "Should not fail: " err))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Tests: job.failed
;; ---------------------------------------------------------------------------

(deftest test-lifecycle-job-failed-on-failure
  (setup!)
  (testing "execute-job! emits job.failed when skill returns :failed status"
    (async done
      (swap! mock-graph-state assoc-in [:jobs "Jobs/LC-4"]
             {:job-id "Jobs/LC-4"
              :job-skill "Skills/TestSkill"
              :job-input {}
              :job-max-retries 0})
      (swap! mock-graph-state assoc-in [:skills "Skills/TestSkill"]
             {:skill-name "Skills/TestSkill"
              :steps [{:step-order 1 :step-action :test-action}]})
      (reset! mock-engine-result {:status :failed
                                  :error {:type :step-error :message "bad step"}
                                  :failed-step 1
                                  :duration-ms 30})

      (-> (runner/execute-job! "Jobs/LC-4")
          (.then (fn [_]
                   (let [evt (find-event "job.failed")]
                     (is (some? evt) "job.failed event should be emitted")
                     (is (= "system:job-runner" (:source evt)))
                     (is (= "Jobs/LC-4" (get-in evt [:data :job-id])))
                     (is (some? (get-in evt [:data :error])))
                     (is (= 1 (get-in evt [:data :failed-step]))))
                   (teardown!)
                   (done)))
          (.catch (fn [err]
                    (teardown!)
                    (is false (str "Should not fail: " err))
                    (done)))))))

(deftest test-lifecycle-job-failed-on-exception
  (setup!)
  (testing "execute-job! emits job.failed when engine throws"
    (async done
      (swap! mock-graph-state assoc-in [:jobs "Jobs/LC-5"]
             {:job-id "Jobs/LC-5"
              :job-skill "Skills/TestSkill"
              :job-input {}
              :job-max-retries 0})
      (swap! mock-graph-state assoc-in [:skills "Skills/TestSkill"]
             {:skill-name "Skills/TestSkill"
              :steps [{:step-order 1 :step-action :test-action}]})
      ;; Override engine to throw
      (set! runner/engine-execute-skill-with-retries
            (fn [_ _ _ _ _]
              (js/Promise.reject (js/Error. "engine explosion"))))

      (-> (runner/execute-job! "Jobs/LC-5")
          (.then (fn [_]
                   (let [evt (find-event "job.failed")]
                     (is (some? evt) "job.failed event should be emitted on exception")
                     (is (= "system:job-runner" (:source evt)))
                     (is (= "Jobs/LC-5" (get-in evt [:data :job-id])))
                     (is (some? (get-in evt [:data :error]))))
                   (teardown!)
                   (done)))
          (.catch (fn [err]
                    (teardown!)
                    (is false (str "Should not fail: " err))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Tests: job.cancelled
;; ---------------------------------------------------------------------------

(deftest test-lifecycle-job-cancelled
  (setup!)
  (testing "cancel-job! emits job.cancelled event"
    (async done
      (swap! mock-graph-state assoc-in [:jobs "Jobs/LC-6"]
             {:job-id "Jobs/LC-6"
              :job-skill "Skills/Test"
              :job-priority 3
              :job-depends-on #{}})

      (-> (runner/enqueue-job! "Jobs/LC-6")
          (.then (fn [_]
                   ;; Clear events from enqueue to isolate cancel event
                   (reset! emitted-events [])
                   (runner/cancel-job! "Jobs/LC-6")
                   (let [evt (find-event "job.cancelled")]
                     (is (some? evt) "job.cancelled event should be emitted")
                     (is (= "system:job-runner" (:source evt)))
                     (is (= "Jobs/LC-6" (get-in evt [:data :job-id]))))
                   (teardown!)
                   (done)))
          (.catch (fn [err]
                    (teardown!)
                    (is false (str "Should not fail: " err))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Tests: nil *emit-event-fn* is safe
;; ---------------------------------------------------------------------------

(deftest test-lifecycle-nil-emit-fn-safe-enqueue
  (setup!)
  (set! runner/*emit-event-fn* nil)
  (testing "enqueue-job! works when *emit-event-fn* is nil"
    (async done
      (swap! mock-graph-state assoc-in [:jobs "Jobs/LC-7"]
             {:job-id "Jobs/LC-7"
              :job-skill "Skills/Test"
              :job-priority 3
              :job-depends-on #{}})

      (-> (runner/enqueue-job! "Jobs/LC-7")
          (.then (fn [_]
                   (is (= 0 (count @emitted-events))
                       "No events should be emitted when fn is nil")
                   (is (= 1 (:queued (runner/runner-status)))
                       "Job should still be enqueued")
                   (teardown!)
                   (done)))
          (.catch (fn [err]
                    (teardown!)
                    (is false (str "Should not fail: " err))
                    (done)))))))

(deftest test-lifecycle-nil-emit-fn-safe-execute
  (setup!)
  (set! runner/*emit-event-fn* nil)
  (testing "execute-job! works when *emit-event-fn* is nil"
    (async done
      (swap! mock-graph-state assoc-in [:jobs "Jobs/LC-8"]
             {:job-id "Jobs/LC-8"
              :job-skill "Skills/TestSkill"
              :job-input {}
              :job-max-retries 0})
      (swap! mock-graph-state assoc-in [:skills "Skills/TestSkill"]
             {:skill-name "Skills/TestSkill"
              :steps [{:step-order 1 :step-action :test-action}]})
      (reset! mock-engine-result {:status :completed
                                  :result {:output "ok"}
                                  :duration-ms 10})

      (-> (runner/execute-job! "Jobs/LC-8")
          (.then (fn [_]
                   (is (= 0 (count @emitted-events))
                       "No events should be emitted when fn is nil")
                   (is (some #(= "completed" (:status %))
                            (:status-updates @mock-graph-state))
                       "Job should still complete normally")
                   (teardown!)
                   (done)))
          (.catch (fn [err]
                    (teardown!)
                    (is false (str "Should not fail: " err))
                    (done)))))))

(deftest test-lifecycle-nil-emit-fn-safe-cancel
  (setup!)
  (testing "cancel-job! works when *emit-event-fn* is nil"
    (async done
      (swap! mock-graph-state assoc-in [:jobs "Jobs/LC-9"]
             {:job-id "Jobs/LC-9"
              :job-skill "Skills/Test"
              :job-priority 3
              :job-depends-on #{}})

      (-> (runner/enqueue-job! "Jobs/LC-9")
          (.then (fn [_]
                   ;; Now set emit fn to nil before cancel
                   (set! runner/*emit-event-fn* nil)
                   (reset! emitted-events [])
                   (runner/cancel-job! "Jobs/LC-9")
                   (is (= 0 (count @emitted-events))
                       "No events should be emitted when fn is nil")
                   (is (some #(= "cancelled" (:status %))
                            (:status-updates @mock-graph-state))
                       "Job should still be cancelled normally")
                   (teardown!)
                   (done)))
          (.catch (fn [err]
                    (teardown!)
                    (is false (str "Should not fail: " err))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Tests: Full lifecycle sequence
;; ---------------------------------------------------------------------------

(deftest test-lifecycle-full-sequence-success
  (setup!)
  (testing "Full enqueue -> execute -> complete emits 3 events in order"
    (async done
      (swap! mock-graph-state assoc-in [:jobs "Jobs/LC-10"]
             {:job-id "Jobs/LC-10"
              :job-skill "Skills/TestSkill"
              :job-input {}
              :job-priority 1
              :job-depends-on #{}
              :job-max-retries 0})
      (swap! mock-graph-state assoc-in [:skills "Skills/TestSkill"]
             {:skill-name "Skills/TestSkill"
              :steps [{:step-order 1 :step-action :test-action}]})
      (reset! mock-engine-result {:status :completed
                                  :result {:output "full-run"}
                                  :duration-ms 75})

      (-> (runner/enqueue-job! "Jobs/LC-10")
          (.then (fn [_]
                   (runner/execute-job! "Jobs/LC-10")))
          (.then (fn [_]
                   (let [types (mapv :type @emitted-events)]
                     (is (= ["job.created" "job.started" "job.completed"] types)
                         "Events should appear in lifecycle order"))
                   (teardown!)
                   (done)))
          (.catch (fn [err]
                    (teardown!)
                    (is false (str "Should not fail: " err))
                    (done)))))))
