(ns logseq-ai-hub.event-hub.pattern-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.event-hub.pattern :as pattern]))

(deftest test-exact-match
  (testing "Exact string matches"
    (is (true? (pattern/pattern-matches? "job.completed" "job.completed")))
    (is (true? (pattern/pattern-matches? "webhook.received" "webhook.received")))))

(deftest test-exact-mismatch
  (testing "Exact string mismatches"
    (is (false? (pattern/pattern-matches? "job.completed" "job.failed")))
    (is (false? (pattern/pattern-matches? "webhook.received" "webhook.sent")))))

(deftest test-single-wildcard
  (testing "Single * matches any one segment"
    (is (true? (pattern/pattern-matches? "job.*" "job.completed")))
    (is (true? (pattern/pattern-matches? "job.*" "job.failed")))
    (is (true? (pattern/pattern-matches? "job.*" "job.cancelled")))
    (is (true? (pattern/pattern-matches? "*.completed" "job.completed")))
    (is (true? (pattern/pattern-matches? "*.completed" "task.completed")))))

(deftest test-wildcard-segment-count
  (testing "Wildcard does NOT match across segments"
    (is (false? (pattern/pattern-matches? "job.*" "job.step.completed")))
    (is (false? (pattern/pattern-matches? "job.*" "job")))
    (is (false? (pattern/pattern-matches? "*" "job.completed")))))

(deftest test-multiple-wildcards
  (testing "Multiple wildcards each match one segment"
    (is (true? (pattern/pattern-matches? "webhook.*.*" "webhook.grafana.alert")))
    (is (true? (pattern/pattern-matches? "*.*.*" "a.b.c")))
    (is (false? (pattern/pattern-matches? "*.*" "a.b.c")))))

(deftest test-segment-count-mismatch
  (testing "Different segment counts never match"
    (is (false? (pattern/pattern-matches? "job" "job.completed")))
    (is (false? (pattern/pattern-matches? "job.completed" "job")))
    (is (false? (pattern/pattern-matches? "a.b.c" "a.b")))
    (is (false? (pattern/pattern-matches? "a.b" "a.b.c")))))

(deftest test-all-wildcards
  (testing "All-wildcard pattern matches any event of same depth"
    (is (true? (pattern/pattern-matches? "*.*" "job.completed")))
    (is (true? (pattern/pattern-matches? "*.*" "webhook.received")))
    (is (false? (pattern/pattern-matches? "*.*" "single")))))

(deftest test-mixed-wildcards
  (testing "Mix of wildcards and literal segments"
    (is (true? (pattern/pattern-matches? "webhook.*.received" "webhook.grafana.received")))
    (is (false? (pattern/pattern-matches? "webhook.*.received" "webhook.grafana.sent")))
    (is (true? (pattern/pattern-matches? "*.grafana.*" "webhook.grafana.alert")))))

(deftest test-single-segment
  (testing "Single segment patterns"
    (is (true? (pattern/pattern-matches? "heartbeat" "heartbeat")))
    (is (false? (pattern/pattern-matches? "heartbeat" "ping")))
    (is (true? (pattern/pattern-matches? "*" "anything")))))
