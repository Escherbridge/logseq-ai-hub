(ns logseq-ai-hub.event-hub.webhook-source-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.event-hub.webhook-source :as webhook-source]))

;; ---------------------------------------------------------------------------
;; extract-by-path tests
;; ---------------------------------------------------------------------------

(deftest test-extract-by-path-simple
  (testing "extracts top-level key"
    (is (= "firing"
           (webhook-source/extract-by-path {"status" "firing"} "status")))))

(deftest test-extract-by-path-nested
  (testing "extracts nested value via dot-path"
    (is (= "critical"
           (webhook-source/extract-by-path
             {"alert" {"status" "firing" "severity" "critical"}}
             "alert.severity")))))

(deftest test-extract-by-path-deeply-nested
  (testing "extracts deeply nested value"
    (is (= 95
           (webhook-source/extract-by-path
             {"metrics" {"cpu" {"usage" 95}}}
             "metrics.cpu.usage")))))

(deftest test-extract-by-path-missing-key
  (testing "returns nil for non-existent path"
    (is (nil? (webhook-source/extract-by-path
                {"alert" {"status" "firing"}}
                "alert.missing")))))

(deftest test-extract-by-path-nil-data
  (testing "returns nil when data is nil"
    (is (nil? (webhook-source/extract-by-path nil "some.path")))))

(deftest test-extract-by-path-nil-path
  (testing "returns nil when path is nil"
    (is (nil? (webhook-source/extract-by-path {"key" "val"} nil)))))

(deftest test-extract-by-path-blank-path
  (testing "returns nil when path is blank"
    (is (nil? (webhook-source/extract-by-path {"key" "val"} "")))))

(deftest test-extract-by-path-returns-maps
  (testing "returns entire sub-map when path points to a map"
    (is (= {"status" "firing" "name" "cpu_high"}
           (webhook-source/extract-by-path
             {"alert" {"status" "firing" "name" "cpu_high"}}
             "alert")))))

;; ---------------------------------------------------------------------------
;; apply-webhook-source tests
;; ---------------------------------------------------------------------------

(deftest test-apply-no-config
  (testing "returns original event when config is nil"
    (let [event {:type "webhook.received"
                 :source "webhook:grafana"
                 :data {"alert" {"status" "firing"} "value" 95}}]
      (is (= event (webhook-source/apply-webhook-source event nil))))))

(deftest test-apply-empty-config
  (testing "returns original event when config is empty"
    (let [event {:type "webhook.received"
                 :source "webhook:grafana"
                 :data {"alert" {"status" "firing"} "value" 95}}]
      (is (= event (webhook-source/apply-webhook-source event {}))))))

(deftest test-apply-extract-paths
  (testing "extract-paths replaces data with extracted subset"
    (let [event {:type "webhook.received"
                 :source "webhook:grafana"
                 :data {"alert" {"status" "firing" "name" "cpu_high"}
                        "value" 95
                        "host" "server-1"}}
          config {:extract-paths ["alert.status" "value"]}
          result (webhook-source/apply-webhook-source event config)]
      (is (= {"alert.status" "firing" "value" 95}
             (:data result)))
      ;; Other fields should be preserved
      (is (= "webhook.received" (:type result)))
      (is (= "webhook:grafana" (:source result))))))

(deftest test-apply-extract-paths-missing-values
  (testing "extract-paths skips paths that resolve to nil"
    (let [event {:type "webhook.received"
                 :source "webhook:test"
                 :data {"alert" {"status" "firing"}}}
          config {:extract-paths ["alert.status" "missing.path"]}
          result (webhook-source/apply-webhook-source event config)]
      (is (= {"alert.status" "firing"}
             (:data result))))))

(deftest test-apply-page-prefix
  (testing "page-prefix sets metadata :page-prefix"
    (let [event {:type "webhook.received"
                 :source "webhook:grafana"
                 :data {"alert" "cpu_high"}}
          config {:page-prefix "Alerts"}
          result (webhook-source/apply-webhook-source event config)]
      (is (= "Alerts" (get-in result [:metadata :page-prefix])))
      ;; Data should be unchanged
      (is (= {"alert" "cpu_high"} (:data result))))))

(deftest test-apply-both-options
  (testing "extract-paths and page-prefix can be combined"
    (let [event {:type "webhook.received"
                 :source "webhook:grafana"
                 :data {"alert" {"status" "firing" "severity" "critical"}
                        "host" "server-1"
                        "region" "us-east"}
                 :metadata {:existing "value"}}
          config {:extract-paths ["alert.status" "alert.severity"]
                  :page-prefix "Monitoring"}
          result (webhook-source/apply-webhook-source event config)]
      ;; Data should be extracted subset
      (is (= {"alert.status" "firing" "alert.severity" "critical"}
             (:data result)))
      ;; Page prefix should be set
      (is (= "Monitoring" (get-in result [:metadata :page-prefix])))
      ;; Existing metadata should be preserved
      (is (= "value" (get-in result [:metadata :existing]))))))

(deftest test-apply-preserves-event-identity
  (testing "type and source are never modified"
    (let [event {:type "custom.alert"
                 :source "webhook:pagerduty"
                 :data {"incident" {"id" "PD123"}}}
          config {:extract-paths ["incident.id"]
                  :page-prefix "Incidents"}
          result (webhook-source/apply-webhook-source event config)]
      (is (= "custom.alert" (:type result)))
      (is (= "webhook:pagerduty" (:source result))))))
