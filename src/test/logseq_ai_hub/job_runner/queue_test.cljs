(ns logseq-ai-hub.job-runner.queue-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.job-runner.queue :as queue]))

(deftest make-queue-test
  (testing "creates empty queue"
    (let [q (queue/make-queue)]
      (is (vector? q))
      (is (empty? q))
      (is (= 0 (queue/queue-size q))))))

(deftest enqueue-test
  (testing "enqueue single job"
    (let [q (queue/make-queue)
          job {:job-id "Jobs/job1" :priority 3 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          q2 (queue/enqueue q job)]
      (is (= 1 (queue/queue-size q2)))
      (is (= job (first q2)))))

  (testing "enqueue maintains priority order - lower priority number first"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 3 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          job2 {:job-id "Jobs/job2" :priority 1 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          job3 {:job-id "Jobs/job3" :priority 5 :created-at "2026-02-19T10:02:00Z" :depends-on #{}}
          q2 (-> q
                 (queue/enqueue job1)
                 (queue/enqueue job2)
                 (queue/enqueue job3))]
      (is (= 3 (queue/queue-size q2)))
      (is (= "Jobs/job2" (:job-id (nth q2 0))))
      (is (= "Jobs/job1" (:job-id (nth q2 1))))
      (is (= "Jobs/job3" (:job-id (nth q2 2))))))

  (testing "enqueue maintains created-at order within same priority"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 3 :created-at "2026-02-19T10:02:00Z" :depends-on #{}}
          job2 {:job-id "Jobs/job2" :priority 3 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          job3 {:job-id "Jobs/job3" :priority 3 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          q2 (-> q
                 (queue/enqueue job1)
                 (queue/enqueue job2)
                 (queue/enqueue job3))]
      (is (= 3 (queue/queue-size q2)))
      (is (= "Jobs/job2" (:job-id (nth q2 0))))
      (is (= "Jobs/job3" (:job-id (nth q2 1))))
      (is (= "Jobs/job1" (:job-id (nth q2 2))))))

  (testing "enqueue complex ordering - priority then created-at"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 2 :created-at "2026-02-19T10:05:00Z" :depends-on #{}}
          job2 {:job-id "Jobs/job2" :priority 1 :created-at "2026-02-19T10:03:00Z" :depends-on #{}}
          job3 {:job-id "Jobs/job3" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          job4 {:job-id "Jobs/job4" :priority 1 :created-at "2026-02-19T10:02:00Z" :depends-on #{}}
          job5 {:job-id "Jobs/job5" :priority 3 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          q2 (-> q
                 (queue/enqueue job1)
                 (queue/enqueue job2)
                 (queue/enqueue job3)
                 (queue/enqueue job4)
                 (queue/enqueue job5))]
      (is (= 5 (queue/queue-size q2)))
      ;; Priority 1, earlier created-at first
      (is (= "Jobs/job4" (:job-id (nth q2 0))))
      (is (= "Jobs/job2" (:job-id (nth q2 1))))
      ;; Priority 2, earlier created-at first
      (is (= "Jobs/job3" (:job-id (nth q2 2))))
      (is (= "Jobs/job1" (:job-id (nth q2 3))))
      ;; Priority 3
      (is (= "Jobs/job5" (:job-id (nth q2 4)))))))

(deftest dequeue-test
  (testing "dequeue from empty queue returns nil"
    (let [q (queue/make-queue)
          [job remaining] (queue/dequeue q {} 10 #{})]
      (is (nil? job))
      (is (= q remaining))))

  (testing "dequeue returns highest priority eligible job"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 3 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          job2 {:job-id "Jobs/job2" :priority 1 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2))
          [job remaining] (queue/dequeue q2 {} 10 #{})]
      (is (= job2 job))
      (is (= 1 (queue/queue-size remaining)))
      (is (= job1 (first remaining)))))

  (testing "dequeue with unmet dependencies skips blocked job"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{"Jobs/dep"}}
          job2 {:job-id "Jobs/job2" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2))
          status-map {"Jobs/dep" :running}
          [job remaining] (queue/dequeue q2 status-map 10 #{})]
      ;; Should skip job1 (blocked) and return job2
      (is (= job2 job))
      (is (= 1 (queue/queue-size remaining)))
      (is (= job1 (first remaining)))))

  (testing "dequeue with met dependencies allows job through"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{"Jobs/dep"}}
          job2 {:job-id "Jobs/job2" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2))
          status-map {"Jobs/dep" :completed}
          [job remaining] (queue/dequeue q2 status-map 10 #{})]
      ;; Should return job1 (higher priority, deps met)
      (is (= job1 job))
      (is (= 1 (queue/queue-size remaining)))
      (is (= job2 (first remaining)))))

  (testing "dequeue with multiple unmet dependencies"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{"Jobs/dep1" "Jobs/dep2"}}
          job2 {:job-id "Jobs/job2" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2))
          status-map {"Jobs/dep1" :completed "Jobs/dep2" :running}
          [job remaining] (queue/dequeue q2 status-map 10 #{})]
      ;; Should skip job1 (dep2 not completed) and return job2
      (is (= job2 job))))

  (testing "dequeue with all dependencies completed"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{"Jobs/dep1" "Jobs/dep2"}}
          q2 (queue/enqueue q job1)
          status-map {"Jobs/dep1" :completed "Jobs/dep2" :completed}
          [job remaining] (queue/dequeue q2 status-map 10 #{})]
      ;; Should return job1 (all deps completed)
      (is (= job1 job))
      (is (= 0 (queue/queue-size remaining)))))

  (testing "dequeue respects max-concurrent limit"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          q2 (queue/enqueue q job1)
          running-set #{"Jobs/running1" "Jobs/running2"}
          [job remaining] (queue/dequeue q2 {} 2 running-set)]
      ;; Should return nil (max-concurrent reached)
      (is (nil? job))
      (is (= q2 remaining))))

  (testing "dequeue allows job when under max-concurrent"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          q2 (queue/enqueue q job1)
          running-set #{"Jobs/running1"}
          [job remaining] (queue/dequeue q2 {} 3 running-set)]
      ;; Should return job1 (under max-concurrent)
      (is (= job1 job))
      (is (= 0 (queue/queue-size remaining)))))

  (testing "dequeue with empty depends-on set is eligible"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          q2 (queue/enqueue q job1)
          [job remaining] (queue/dequeue q2 {} 10 #{})]
      (is (= job1 job))))

  (testing "dequeue returns nil when all jobs blocked"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{"Jobs/dep1"}}
          job2 {:job-id "Jobs/job2" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{"Jobs/dep2"}}
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2))
          status-map {"Jobs/dep1" :running "Jobs/dep2" :running}
          [job remaining] (queue/dequeue q2 status-map 10 #{})]
      (is (nil? job))
      (is (= q2 remaining)))))

(deftest remove-from-queue-test
  (testing "remove from empty queue"
    (let [q (queue/make-queue)
          q2 (queue/remove-from-queue q "Jobs/nonexistent")]
      (is (= q q2))))

  (testing "remove existing job"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          job2 {:job-id "Jobs/job2" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          job3 {:job-id "Jobs/job3" :priority 3 :created-at "2026-02-19T10:02:00Z" :depends-on #{}}
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2) (queue/enqueue job3))
          q3 (queue/remove-from-queue q2 "Jobs/job2")]
      (is (= 2 (queue/queue-size q3)))
      (is (= "Jobs/job1" (:job-id (nth q3 0))))
      (is (= "Jobs/job3" (:job-id (nth q3 1))))))

  (testing "remove non-existent job"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          q2 (queue/enqueue q job1)
          q3 (queue/remove-from-queue q2 "Jobs/nonexistent")]
      (is (= q2 q3))))

  (testing "remove first job"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          job2 {:job-id "Jobs/job2" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2))
          q3 (queue/remove-from-queue q2 "Jobs/job1")]
      (is (= 1 (queue/queue-size q3)))
      (is (= "Jobs/job2" (:job-id (first q3))))))

  (testing "remove last job"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          job2 {:job-id "Jobs/job2" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2))
          q3 (queue/remove-from-queue q2 "Jobs/job2")]
      (is (= 1 (queue/queue-size q3)))
      (is (= "Jobs/job1" (:job-id (first q3)))))))

(deftest queue-size-test
  (testing "empty queue size"
    (is (= 0 (queue/queue-size (queue/make-queue)))))

  (testing "queue size after enqueue"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          job2 {:job-id "Jobs/job2" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2))]
      (is (= 2 (queue/queue-size q2))))))

(deftest find-in-queue-test
  (testing "find in empty queue"
    (is (nil? (queue/find-in-queue (queue/make-queue) "Jobs/job1"))))

  (testing "find existing job"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          job2 {:job-id "Jobs/job2" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{}}
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2))
          found (queue/find-in-queue q2 "Jobs/job2")]
      (is (= job2 found))))

  (testing "find non-existent job"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          q2 (queue/enqueue q job1)]
      (is (nil? (queue/find-in-queue q2 "Jobs/nonexistent"))))))

(deftest multiple-enqueue-dequeue-cycles-test
  (testing "complex workflow with multiple operations"
    (let [q (queue/make-queue)
          job1 {:job-id "Jobs/job1" :priority 1 :created-at "2026-02-19T10:00:00Z" :depends-on #{}}
          job2 {:job-id "Jobs/job2" :priority 2 :created-at "2026-02-19T10:01:00Z" :depends-on #{"Jobs/job1"}}
          job3 {:job-id "Jobs/job3" :priority 1 :created-at "2026-02-19T10:02:00Z" :depends-on #{}}

          ;; Enqueue all jobs
          q2 (-> q (queue/enqueue job1) (queue/enqueue job2) (queue/enqueue job3))
          _ (is (= 3 (queue/queue-size q2)))

          ;; Dequeue first job (job1, priority 1, earlier timestamp)
          [first-job q3] (queue/dequeue q2 {} 10 #{})
          _ (is (= "Jobs/job1" (:job-id first-job)))
          _ (is (= 2 (queue/queue-size q3)))

          ;; Mark job1 as running, try to dequeue (should get job3, not job2 which depends on job1)
          status1 {"Jobs/job1" :running}
          [second-job q4] (queue/dequeue q3 status1 10 #{"Jobs/job1"})
          _ (is (= "Jobs/job3" (:job-id second-job)))
          _ (is (= 1 (queue/queue-size q4)))

          ;; Mark job1 as completed, now job2 should be eligible
          status2 {"Jobs/job1" :completed "Jobs/job3" :running}
          [third-job q5] (queue/dequeue q4 status2 10 #{"Jobs/job3"})
          _ (is (= "Jobs/job2" (:job-id third-job)))
          _ (is (= 0 (queue/queue-size q5)))]
      (is true))))
