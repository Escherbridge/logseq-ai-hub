(ns logseq-ai-hub.job-runner.runner-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.job-runner.queue :as queue]
            [logseq-ai-hub.job-runner.runner :as runner]
            [logseq-ai-hub.util.errors :as errors]))

;; Mock state for graph operations
(def mock-graph-state (atom {}))

(defn reset-mock-graph! []
  (reset! mock-graph-state
          {:jobs {}
           :skills {}
           :read-job-calls []
           :read-skill-calls []
           :status-updates []
           :property-updates []
           :log-entries []}))

(defn mock-read-job-page [page-name]
  (swap! mock-graph-state update :read-job-calls conj page-name)
  (js/Promise.resolve (get-in @mock-graph-state [:jobs page-name])))

(defn mock-read-skill-page [page-name]
  (swap! mock-graph-state update :read-skill-calls conj page-name)
  (js/Promise.resolve (get-in @mock-graph-state [:skills page-name])))

(defn mock-scan-job-pages [prefix]
  (let [jobs (vals (get @mock-graph-state :jobs))]
    (js/Promise.resolve (vec (filter some? jobs)))))

(defn mock-update-job-status! [page-name status]
  (swap! mock-graph-state update :status-updates conj {:page page-name :status status})
  (js/Promise.resolve nil))

(defn mock-update-job-property! [page-name key val]
  (swap! mock-graph-state update :property-updates conj {:page page-name :key key :val val})
  (js/Promise.resolve nil))

(defn mock-append-job-log! [page-name text]
  (swap! mock-graph-state update :log-entries conj {:page page-name :text text})
  (js/Promise.resolve nil))

(defn mock-queue-write! [write-fn]
  (write-fn))

;; Mock state for engine operations
(def mock-engine-state (atom {}))

(defn reset-mock-engine! []
  (reset! mock-engine-state
          {:execute-calls []
           :skill-results {}}))

(defn mock-execute-skill-with-retries [skill-def inputs job-id max-retries on-progress]
  (swap! mock-engine-state update :execute-calls conj
         {:skill skill-def :inputs inputs :job-id job-id :max-retries max-retries})
  (let [result (get-in @mock-engine-state [:skill-results (:skill-name skill-def)]
                       {:status :completed :result {:output "default-output"} :duration-ms 100})]
    (js/Promise.resolve result)))

(defn set-skill-result! [skill-name result]
  (swap! mock-engine-state assoc-in [:skill-results skill-name] result))

;; The queue is the real, pure queue module (no I/O, nothing to fake). An earlier
;; mutating mock encoded a different API and hid that the runner dropped every enqueue.
(defn- use-real-queue! []
  (set! runner/queue-make-queue queue/make-queue)
  (set! runner/queue-enqueue queue/enqueue)
  (set! runner/queue-dequeue queue/dequeue)
  (set! runner/queue-remove-from-queue queue/remove-from-queue)
  (set! runner/queue-queue-size queue/queue-size)
  (set! runner/queue-find-in-queue queue/find-in-queue))

;; Helper to create mock job
(defn make-mock-job
  ([job-id skill-name]
   (make-mock-job job-id skill-name {} 3 #{}))
  ([job-id skill-name inputs]
   (make-mock-job job-id skill-name inputs 3 #{}))
  ([job-id skill-name inputs priority depends-on]
   {:job-id job-id
    :job-skill skill-name
    :job-input inputs
    :job-priority priority
    :job-depends-on depends-on
    :job-status :draft
    :job-max-retries 0}))

;; Helper to create mock skill
(defn make-mock-skill [skill-name]
  {:skill-name skill-name
   :steps [{:step-order 1 :step-action :action-1}]})

;; ---------------------------------------------------------------------------
;; Setup / Teardown helpers (use set! instead of with-redefs for async safety)
;; ---------------------------------------------------------------------------

(defn- setup-enqueue-mocks!
  "Wire mocks needed for enqueue-job! tests."
  []
  (reset-mock-graph!)
  (use-real-queue!)
  (runner/reset-runner!)
  (set! runner/graph-read-job-page mock-read-job-page)
  (set! runner/graph-update-job-status! mock-update-job-status!))

(defn- setup-execute-mocks!
  "Wire mocks needed for execute-job! tests."
  []
  (reset-mock-graph!)
  (reset-mock-engine!)
  (runner/reset-runner!)
  (set! runner/graph-read-job-page mock-read-job-page)
  (set! runner/graph-read-skill-page mock-read-skill-page)
  (set! runner/graph-update-job-status! mock-update-job-status!)
  (set! runner/graph-update-job-property! mock-update-job-property!)
  (set! runner/graph-append-job-log! mock-append-job-log!)
  (set! runner/engine-execute-skill-with-retries mock-execute-skill-with-retries))

(defn- setup-full-mocks!
  "Wire all mocks (enqueue + execute + dequeue)."
  []
  (reset-mock-graph!)
  (reset-mock-engine!)
  (use-real-queue!)
  (runner/reset-runner!)
  (set! runner/graph-read-job-page mock-read-job-page)
  (set! runner/graph-read-skill-page mock-read-skill-page)
  (set! runner/graph-scan-job-pages mock-scan-job-pages)
  (set! runner/graph-update-job-status! mock-update-job-status!)
  (set! runner/graph-update-job-property! mock-update-job-property!)
  (set! runner/graph-append-job-log! mock-append-job-log!)
  (set! runner/engine-execute-skill-with-retries mock-execute-skill-with-retries))

;; Tests

(deftest test-runner-status-initial
  (testing "Initial runner status"
    (runner/reset-runner!)
    (let [status (runner/runner-status)]
      (is (= :stopped (:status status)))
      (is (= 0 (:queued status)))
      (is (= 0 (:running status)))
      (is (= 0 (:completed status)))
      (is (= 0 (:failed status))))))

(deftest test-update-config
  (testing "Update runner configuration"
    (runner/reset-runner!)
    (runner/update-config! {:max-concurrent 5 :poll-interval-ms 10000})
    (let [state @runner/runner-state]
      (is (= 5 (get-in state [:config :max-concurrent])))
      (is (= 10000 (get-in state [:config :poll-interval-ms]))))))

(deftest test-enqueue-job
  (async done
    (setup-enqueue-mocks!)
    (swap! mock-graph-state assoc-in [:jobs "Jobs/Test Job"]
           (make-mock-job "Jobs/Test Job" "Skills/Test Skill" {} 3 #{}))

    (-> (runner/enqueue-job! "Jobs/Test Job")
        (.then (fn [result]
                 (is (= {:job-id "Jobs/Test Job" :status "queued"} result)
                     "enqueue-job! must resolve a real {:job-id :status} map, not the bare status-write result")
                 (let [status (runner/runner-status)]
                   (is (= 1 (:queued status)))
                   (is (some #(= "queued" (:status %))
                            (:status-updates @mock-graph-state))))
                 ;; create_job enqueues, and start-runner! re-enqueues every job-status:: queued page
                 (runner/enqueue-job! "Jobs/Test Job")))
        (.then (fn [_]
                 (is (= 1 (:queued (runner/runner-status)))
                     "enqueueing an already-queued job must not queue it twice")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Should not fail: " err))
                  (done))))))

(deftest test-cancel-job
  (async done
    (setup-enqueue-mocks!)
    (swap! mock-graph-state assoc-in [:jobs "Jobs/Test Job"]
           (make-mock-job "Jobs/Test Job" "Skills/Test Skill"))

    (-> (runner/enqueue-job! "Jobs/Test Job")
        (.then (fn [_]
                 (runner/cancel-job! "Jobs/Test Job")))
        (.then (fn [_]
                 (let [status (runner/runner-status)]
                   (is (= 0 (:queued status)))
                   (is (some #(= "cancelled" (:status %))
                            (:status-updates @mock-graph-state)))
                   (done))))
        (.catch (fn [err]
                  (is false (str "Should not fail: " err))
                  (done))))))

(deftest test-pause-resume-job
  (async done
    (setup-enqueue-mocks!)
    (swap! mock-graph-state assoc-in [:jobs "Jobs/Test Job"]
           (make-mock-job "Jobs/Test Job" "Skills/Test Skill"))

    (-> (runner/enqueue-job! "Jobs/Test Job")
        (.then (fn [_]
                 (runner/pause-job! "Jobs/Test Job")))
        (.then (fn [_]
                 (is (= 0 (:queued (runner/runner-status))))
                 (is (some #(= "paused" (:status %))
                          (:status-updates @mock-graph-state)))
                 (runner/resume-job! "Jobs/Test Job")))
        (.then (fn [_]
                 (is (= 1 (:queued (runner/runner-status))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Should not fail: " err))
                  (done))))))

(deftest test-execute-job-success
  (async done
    (setup-execute-mocks!)
    (swap! mock-graph-state assoc-in [:jobs "Jobs/Test Job"]
           (make-mock-job "Jobs/Test Job" "Skills/Test Skill" {:input1 "value1"}))
    (swap! mock-graph-state assoc-in [:skills "Skills/Test Skill"]
           (make-mock-skill "Skills/Test Skill"))

    (set-skill-result! "Skills/Test Skill"
                      {:status :completed
                       :result {:output "success-result"}
                       :duration-ms 150})

    (-> (runner/execute-job! "Jobs/Test Job")
        (.then (fn [_]
                 (is (some #(= "running" (:status %))
                          (:status-updates @mock-graph-state)))
                 (is (some #(= "completed" (:status %))
                          (:status-updates @mock-graph-state)))
                 (is (some #(and (= "job-result" (:key %))
                                (= "{:output \"success-result\"}" (:val %)))
                          (:property-updates @mock-graph-state)))
                 (is (pos? (count (:log-entries @mock-graph-state))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Should not fail: " err))
                  (done))))))

(deftest test-execute-job-failure
  (async done
    (setup-execute-mocks!)
    (swap! mock-graph-state assoc-in [:jobs "Jobs/Test Job"]
           (make-mock-job "Jobs/Test Job" "Skills/Test Skill"))
    (swap! mock-graph-state assoc-in [:skills "Skills/Test Skill"]
           (make-mock-skill "Skills/Test Skill"))

    (set-skill-result! "Skills/Test Skill"
                      {:status :failed
                       :error {:type :step-execution-error :message "Step failed"}
                       :failed-step 1
                       :duration-ms 50})

    (-> (runner/execute-job! "Jobs/Test Job")
        (.then (fn [_]
                 (is (some #(= "failed" (:status %))
                          (:status-updates @mock-graph-state)))
                 (is (some #(and (= "job-error" (:key %)))
                          (:property-updates @mock-graph-state)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Should not fail: " err))
                  (done))))))

(deftest test-build-status-map
  (testing "Build status map from runner state"
    (runner/reset-runner!)
    (swap! runner/runner-state assoc
           :running #{"job-1" "job-2"}
           :completed {"job-3" {:result "ok"}}
           :failed {"job-4" {:error "err"}})

    (let [status-map (runner/build-status-map)]
      (is (= :running (get status-map "job-1")))
      (is (= :running (get status-map "job-2")))
      (is (= :completed (get status-map "job-3")))
      (is (= :failed (get status-map "job-4"))))))

(deftest test-poll-tick-dequeues-eligible-job
  (async done
    (setup-full-mocks!)
    (swap! mock-graph-state assoc-in [:jobs "Jobs/Test Job"]
           (make-mock-job "Jobs/Test Job" "Skills/Test Skill"))
    (swap! mock-graph-state assoc-in [:skills "Skills/Test Skill"]
           (make-mock-skill "Skills/Test Skill"))

    (-> (runner/enqueue-job! "Jobs/Test Job")
        (.then (fn [_]
                 ;; Runner must be :running for poll-tick! to dequeue
                 (swap! runner/runner-state assoc :status :running)
                 ;; Simulate poll tick
                 (runner/poll-tick!)))
        (.then (fn [_]
                 ;; Wait for async execution to settle
                 (js/setTimeout
                  (fn []
                    (is (= 0 (:queued (runner/runner-status))))
                    (is (some #(= "completed" (:status %)) (:status-updates @mock-graph-state))
                        "the dequeued job was actually executed")
                    ;; Clean up the polling timer scheduled by poll-tick!
                    (runner/stop-runner!)
                    (done))
                  100)))
        (.catch (fn [err]
                  (is false (str "Should not fail: " err))
                  (done))))))

(deftest test-dependency-resolution
  (async done
    (setup-enqueue-mocks!)

    (swap! mock-graph-state assoc-in [:jobs "Jobs/Job1"]
           (make-mock-job "Jobs/Job1" "Skills/Skill1" {} 3 #{}))
    (swap! mock-graph-state assoc-in [:jobs "Jobs/Job2"]
           (make-mock-job "Jobs/Job2" "Skills/Skill2" {} 3 #{"Jobs/Job1"}))

    (-> (runner/enqueue-job! "Jobs/Job1")
        (.then (fn [_] (runner/enqueue-job! "Jobs/Job2")))
        (.then (fn [_]
                 (let [[first-out q] (queue/dequeue (:queue @runner/runner-state)
                                                    (runner/build-status-map) 3 #{})
                       [blocked _] (queue/dequeue q (runner/build-status-map) 3 #{})]
                   (is (= "Jobs/Job1" (:job-id first-out)))
                   (is (nil? blocked) "Job2 waits while Job1 is not completed")
                   ;; Mark Job1 as completed
                   (swap! runner/runner-state assoc-in [:completed "Jobs/Job1"] {:result "ok"})
                   (let [[eligible _] (queue/dequeue q (runner/build-status-map) 3 #{})]
                     (is (= "Jobs/Job2" (:job-id eligible)))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Should not fail: " err))
                  (done))))))

(deftest test-start-stop-runner
  (async done
    (setup-full-mocks!)
    (-> (runner/start-runner!)
        (.then (fn [_]
                 (is (= :running (:status (runner/runner-status))))
                 (runner/stop-runner!)
                 (is (= :stopped (:status (runner/runner-status))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Should not fail: " err))
                  (done))))))
