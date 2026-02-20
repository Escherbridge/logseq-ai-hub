(ns logseq-ai-hub.job-runner.scheduler-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [logseq-ai-hub.job-runner.scheduler :as scheduler]
            [logseq-ai-hub.job-runner.cron :as cron]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Test Helpers
;; ---------------------------------------------------------------------------

(defn reset-scheduler-state! []
  (reset! scheduler/scheduler-state
          {:status :stopped
           :registered {}
           :timer-id nil}))

(defn make-test-date
  "Creates a JS Date with specific values for testing."
  [year month day hour minute]
  (js/Date. year month day hour minute 0 0))

;; ---------------------------------------------------------------------------
;; Register/Unregister Tests
;; ---------------------------------------------------------------------------

(deftest test-register-schedule
  (testing "register-schedule! with valid cron expression"
    (reset-scheduler-state!)
    (let [result (scheduler/register-schedule! "Jobs/TestJob" "30 9 * * *")]
      (is (true? result) "Should return true for valid cron")
      (let [registered (get-in @scheduler/scheduler-state [:registered "Jobs/TestJob"])]
        (is (some? registered) "Should have registered entry")
        (is (= "30 9 * * *" (:cron-expr registered)))
        (is (some? (:parsed-cron registered)))
        (is (nil? (:last-fired registered))))))

  (testing "register-schedule! with invalid cron expression"
    (reset-scheduler-state!)
    (let [result (scheduler/register-schedule! "Jobs/BadJob" "invalid cron")]
      (is (false? result) "Should return false for invalid cron")
      (is (nil? (get-in @scheduler/scheduler-state [:registered "Jobs/BadJob"])))))

  (testing "register-schedule! overwrites existing registration"
    (reset-scheduler-state!)
    (scheduler/register-schedule! "Jobs/Job1" "0 10 * * *")
    (scheduler/register-schedule! "Jobs/Job1" "0 11 * * *")
    (let [registered (get-in @scheduler/scheduler-state [:registered "Jobs/Job1"])]
      (is (= "0 11 * * *" (:cron-expr registered)) "Should have new cron expression"))))

(deftest test-unregister-schedule
  (testing "unregister-schedule! removes registration"
    (reset-scheduler-state!)
    (scheduler/register-schedule! "Jobs/Job1" "0 10 * * *")
    (scheduler/unregister-schedule! "Jobs/Job1")
    (is (nil? (get-in @scheduler/scheduler-state [:registered "Jobs/Job1"])))))

(deftest test-list-schedules
  (testing "list-schedules returns all registered schedules"
    (reset-scheduler-state!)
    (scheduler/register-schedule! "Jobs/Job1" "0 10 * * *")
    (scheduler/register-schedule! "Jobs/Job2" "30 14 * * *")
    (let [schedules (scheduler/list-schedules)]
      (is (= 2 (count schedules)))
      (is (contains? schedules "Jobs/Job1"))
      (is (contains? schedules "Jobs/Job2")))))

;; ---------------------------------------------------------------------------
;; Check Schedules Tests
;; ---------------------------------------------------------------------------

(deftest test-check-schedules-fires-matching
  (testing "check-schedules! fires job when cron matches and hasn't fired this minute"
    (async done
      (reset-scheduler-state!)
      (let [fired-jobs (atom [])
            enqueued-jobs (atom [])
            mock-graph-read (fn [page-name]
                             (js/Promise.resolve
                              {:job-id page-name
                               :job-skill "Skills/TestSkill"
                               :job-type :scheduled}))
            mock-runner-enqueue (fn [job-id]
                                 (swap! enqueued-jobs conj job-id)
                                 (js/Promise.resolve nil))]

        ;; Inject dependencies
        (set! scheduler/graph-read-job-page mock-graph-read)
        (set! scheduler/runner-enqueue-job! mock-runner-enqueue)

        ;; Mock logseq.Editor.createPage
        (set! (.-createPage (.-Editor js/logseq))
              (fn [page-name _props _opts]
                (swap! fired-jobs conj page-name)
                (js/Promise.resolve #js {:name page-name})))

        ;; Register a job that should fire at 9:30
        (scheduler/register-schedule! "Jobs/DailyReport" "30 9 * * *")

        ;; Create a date that matches: 9:30 AM
        (let [now (make-test-date 2026 1 19 9 30)]
          (-> (scheduler/check-schedules! now)
              (.then (fn [_]
                       ;; Should have created a new job instance page
                       (is (= 1 (count @fired-jobs)))
                       (is (str/starts-with? (first @fired-jobs) "Jobs/DailyReport-"))

                       ;; Should have enqueued the new instance
                       (is (= 1 (count @enqueued-jobs)))

                       ;; Should have updated last-fired
                       (let [registered (get-in @scheduler/scheduler-state
                                                [:registered "Jobs/DailyReport"])]
                         (is (= "2026-2-19-9-30" (:last-fired registered))))

                       (done)))
              (.catch (fn [err]
                       (js/console.error "Test error:" err)
                       (is false "Promise should not reject")
                       (done)))))))))

(deftest test-check-schedules-skips-already-fired
  (testing "check-schedules! skips if already fired this minute"
    (async done
      (reset-scheduler-state!)
      (let [fired-jobs (atom [])
            mock-graph-read (fn [page-name]
                             (js/Promise.resolve
                              {:job-id page-name
                               :job-skill "Skills/TestSkill"}))
            mock-runner-enqueue (fn [job-id]
                                 (js/Promise.resolve nil))]

        (set! scheduler/graph-read-job-page mock-graph-read)
        (set! scheduler/runner-enqueue-job! mock-runner-enqueue)
        (set! (.-createPage (.-Editor js/logseq))
              (fn [page-name _props _opts]
                (swap! fired-jobs conj page-name)
                (js/Promise.resolve #js {:name page-name})))

        ;; Register and manually set last-fired to current minute
        (scheduler/register-schedule! "Jobs/DailyReport" "30 9 * * *")
        (swap! scheduler/scheduler-state assoc-in
               [:registered "Jobs/DailyReport" :last-fired]
               "2026-2-19-9-30")

        (let [now (make-test-date 2026 1 19 9 30)]
          (-> (scheduler/check-schedules! now)
              (.then (fn [_]
                       ;; Should not fire again
                       (is (= 0 (count @fired-jobs)))
                       (done)))
              (.catch (fn [err]
                       (js/console.error "Test error:" err)
                       (is false)
                       (done)))))))))

(deftest test-check-schedules-no-match
  (testing "check-schedules! does nothing when time doesn't match"
    (async done
      (reset-scheduler-state!)
      (let [fired-jobs (atom [])]

        (set! (.-createPage (.-Editor js/logseq))
              (fn [page-name _props _opts]
                (swap! fired-jobs conj page-name)
                (js/Promise.resolve #js {:name page-name})))

        ;; Register job for 9:30
        (scheduler/register-schedule! "Jobs/DailyReport" "30 9 * * *")

        ;; Check at 9:25 (doesn't match)
        (let [now (make-test-date 2026 1 19 9 25)]
          (-> (scheduler/check-schedules! now)
              (.then (fn [_]
                       (is (= 0 (count @fired-jobs)))
                       (done)))
              (.catch (fn [err]
                       (is false)
                       (done)))))))))

;; ---------------------------------------------------------------------------
;; Start/Stop Scheduler Tests
;; ---------------------------------------------------------------------------

(deftest test-start-stop-scheduler
  (testing "start-scheduler! sets status to running and starts timer"
    (reset-scheduler-state!)
    (scheduler/start-scheduler!)
    (is (= :running (:status @scheduler/scheduler-state)))
    (is (some? (:timer-id @scheduler/scheduler-state))))

  (testing "stop-scheduler! clears timer and sets status to stopped"
    (reset-scheduler-state!)
    (scheduler/start-scheduler!)
    (scheduler/stop-scheduler!)
    (is (= :stopped (:status @scheduler/scheduler-state)))
    (is (nil? (:timer-id @scheduler/scheduler-state)))))

;; ---------------------------------------------------------------------------
;; Scan and Register Tests
;; ---------------------------------------------------------------------------

(deftest test-scan-and-register-schedules
  (testing "scan-and-register-schedules! registers all scheduled jobs"
    (async done
      (reset-scheduler-state!)
      (let [mock-graph-scan (fn [_prefix]
                             (js/Promise.resolve
                              [{:job-id "Jobs/DailyReport"
                                :job-type :scheduled
                                :job-schedule "0 9 * * *"}
                               {:job-id "Jobs/WeeklyBackup"
                                :job-type :scheduled
                                :job-schedule "0 0 * * 0"}
                               {:job-id "Jobs/ManualTask"
                                :job-type :manual
                                :job-schedule nil}
                               {:job-id "Jobs/BadSchedule"
                                :job-type :scheduled
                                :job-schedule "invalid"}]))]

        (set! scheduler/graph-scan-job-pages mock-graph-scan)

        (-> (scheduler/scan-and-register-schedules! "Jobs/")
            (.then (fn [count]
                     ;; Should register 2 valid scheduled jobs
                     (is (= 2 count))
                     (is (contains? (:registered @scheduler/scheduler-state)
                                   "Jobs/DailyReport"))
                     (is (contains? (:registered @scheduler/scheduler-state)
                                   "Jobs/WeeklyBackup"))
                     ;; Should not register manual or invalid jobs
                     (is (not (contains? (:registered @scheduler/scheduler-state)
                                        "Jobs/ManualTask")))
                     (is (not (contains? (:registered @scheduler/scheduler-state)
                                        "Jobs/BadSchedule")))
                     (done)))
            (.catch (fn [err]
                     (js/console.error "Test error:" err)
                     (is false)
                     (done))))))))

;; ---------------------------------------------------------------------------
;; Init Scheduler Tests
;; ---------------------------------------------------------------------------

(deftest test-init-scheduler
  (testing "init-scheduler! sets dynamic vars from deps"
    (let [mock-runner-enqueue (fn [_] nil)
          mock-graph-read (fn [_] nil)
          mock-graph-scan (fn [_] nil)
          deps {:runner {:enqueue-job! mock-runner-enqueue}
                :graph {:read-job-page mock-graph-read
                       :scan-job-pages mock-graph-scan}}]

      (scheduler/init-scheduler! deps)

      (is (= mock-runner-enqueue scheduler/runner-enqueue-job!))
      (is (= mock-graph-read scheduler/graph-read-job-page))
      (is (= mock-graph-scan scheduler/graph-scan-job-pages)))))
