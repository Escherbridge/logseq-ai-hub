(ns logseq-ai-hub.event-hub.alert-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.event-hub.alert :as alert]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Setup
;; ---------------------------------------------------------------------------

(defn- reset-cooldown! []
  (reset! alert/cooldown-state {}))

;; ---------------------------------------------------------------------------
;; Severity Tests
;; ---------------------------------------------------------------------------

(deftest test-severity-at-least-same-level
  (testing "same severity meets threshold"
    (is (true? (alert/severity-at-least? "warning" "warning")))
    (is (true? (alert/severity-at-least? "info" "info")))
    (is (true? (alert/severity-at-least? "critical" "critical")))))

(deftest test-severity-at-least-higher-level
  (testing "higher severity meets lower threshold"
    (is (true? (alert/severity-at-least? "error" "warning")))
    (is (true? (alert/severity-at-least? "critical" "info")))
    (is (true? (alert/severity-at-least? "warning" "info")))))

(deftest test-severity-at-least-lower-level
  (testing "lower severity does not meet higher threshold"
    (is (false? (alert/severity-at-least? "info" "warning")))
    (is (false? (alert/severity-at-least? "warning" "error")))
    (is (false? (alert/severity-at-least? "info" "critical")))))

(deftest test-severity-at-least-unknown
  (testing "unknown severity treated as 0 (info level)"
    (is (true? (alert/severity-at-least? "unknown" "info")))
    (is (false? (alert/severity-at-least? "unknown" "warning")))))

;; ---------------------------------------------------------------------------
;; Format Alert Message Tests
;; ---------------------------------------------------------------------------

(deftest test-format-alert-message-basic
  (testing "formats a basic alert message"
    (let [event {:type "webhook.received"
                 :source "webhook:grafana"
                 :data {:alert "cpu_high" :host "server-1"}
                 :metadata {:severity "warning"
                            :timestamp "2026-03-06T12:00:00.000Z"}}
          msg (alert/format-alert-message event)]
      (is (str/includes? msg "[WARNING]"))
      (is (str/includes? msg "webhook.received"))
      (is (str/includes? msg "from webhook:grafana"))
      (is (str/includes? msg "alert: cpu_high"))
      (is (str/includes? msg "host: server-1"))
      (is (str/includes? msg "Time: 2026-03-06T12:00:00.000Z")))))

(deftest test-format-alert-message-defaults
  (testing "uses defaults when fields are missing"
    (let [event {:type "test.event"
                 :source "test"
                 :data {}
                 :metadata {}}
          msg (alert/format-alert-message event)]
      (is (str/includes? msg "[INFO]") "default severity is INFO")
      (is (str/includes? msg "test.event"))
      (is (str/includes? msg "Data: {}") "empty data renders as {}"))))

(deftest test-format-alert-message-no-metadata
  (testing "handles nil metadata"
    (let [event {:type "test.event"
                 :source "test"
                 :data {:key "value"}}
          msg (alert/format-alert-message event)]
      (is (str/includes? msg "[INFO]"))
      (is (str/includes? msg "key: value")))))

;; ---------------------------------------------------------------------------
;; should-alert? Tests
;; ---------------------------------------------------------------------------

(deftest test-should-alert-no-config
  (reset-cooldown!)
  (testing "alerts with empty config (no filters)"
    (is (true? (alert/should-alert?
                 {:type "test.event"
                  :source "test"
                  :data {}
                  :metadata {:severity "info"}}
                 {})))))

(deftest test-should-alert-severity-filter-passes
  (reset-cooldown!)
  (testing "alerts when severity meets minimum"
    (is (true? (alert/should-alert?
                 {:type "test.event"
                  :source "test"
                  :data {}
                  :metadata {:severity "error"}}
                 {:min-severity "warning"})))))

(deftest test-should-alert-severity-filter-fails
  (reset-cooldown!)
  (testing "does not alert when severity is below minimum"
    (is (false? (alert/should-alert?
                  {:type "test.event"
                   :source "test"
                   :data {}
                   :metadata {:severity "info"}}
                  {:min-severity "warning"})))))

(deftest test-should-alert-event-type-filter-passes
  (reset-cooldown!)
  (testing "alerts when event type matches filter set"
    (is (true? (alert/should-alert?
                 {:type "webhook.received"
                  :source "test"
                  :data {}
                  :metadata {:severity "info"}}
                 {:event-types #{"webhook.received" "job.failed"}})))))

(deftest test-should-alert-event-type-filter-fails
  (reset-cooldown!)
  (testing "does not alert when event type not in filter set"
    (is (false? (alert/should-alert?
                  {:type "job.completed"
                   :source "test"
                   :data {}
                   :metadata {:severity "info"}}
                  {:event-types #{"webhook.received" "job.failed"}})))))

(deftest test-should-alert-cooldown-blocks-rapid
  (reset-cooldown!)
  (testing "cooldown blocks rapid repeated alerts"
    (let [event {:type "alert.test"
                 :source "test"
                 :data {}
                 :metadata {:severity "info"}}
          config {:cooldown-ms 60000}]
      ;; First alert should pass
      (is (true? (alert/should-alert? event config)))
      ;; Second immediate alert should be blocked
      (is (false? (alert/should-alert? event config))))))

(deftest test-should-alert-cooldown-zero-allows-all
  (reset-cooldown!)
  (testing "cooldown of 0 allows all alerts"
    (let [event {:type "rapid.test"
                 :source "test"
                 :data {}
                 :metadata {:severity "info"}}
          config {:cooldown-ms 0}]
      (is (true? (alert/should-alert? event config)))
      (is (true? (alert/should-alert? event config))))))

(deftest test-should-alert-combined-filters
  (reset-cooldown!)
  (testing "all filters must pass together"
    ;; This should pass: correct type, high severity, no cooldown issue
    (is (true? (alert/should-alert?
                 {:type "webhook.received"
                  :source "test"
                  :data {}
                  :metadata {:severity "error"}}
                 {:min-severity "warning"
                  :event-types #{"webhook.received"}})))
    ;; This should fail: correct type but severity too low
    (reset-cooldown!)
    (is (false? (alert/should-alert?
                  {:type "webhook.received"
                   :source "test"
                   :data {}
                   :metadata {:severity "info"}}
                  {:min-severity "warning"
                   :event-types #{"webhook.received"}})))))

;; ---------------------------------------------------------------------------
;; create-alert-event Tests
;; ---------------------------------------------------------------------------

(deftest test-create-alert-event-type
  (testing "creates event with type alert.triggered"
    (let [event {:type "webhook.received"
                 :source "webhook:grafana"
                 :data {:alert "cpu_high"}
                 :metadata {:severity "warning"}}
          alert-event (alert/create-alert-event event)]
      (is (= "alert.triggered" (:type alert-event))))))

(deftest test-create-alert-event-source
  (testing "source is prefixed with alert:"
    (let [event {:type "webhook.received"
                 :source "webhook:grafana"
                 :data {}
                 :metadata {:severity "warning"}}
          alert-event (alert/create-alert-event event)]
      (is (= "alert:webhook:grafana" (:source alert-event))))))

(deftest test-create-alert-event-preserves-original
  (testing "original event is preserved in data"
    (let [event {:type "webhook.received"
                 :source "webhook:grafana"
                 :data {:alert "cpu_high"}
                 :metadata {:severity "warning"}}
          alert-event (alert/create-alert-event event)]
      (is (= event (get-in alert-event [:data :original-event]))))))

(deftest test-create-alert-event-has-alert-message
  (testing "alert-message is included in data"
    (let [event {:type "webhook.received"
                 :source "webhook:grafana"
                 :data {:alert "cpu_high"}
                 :metadata {:severity "warning"}}
          alert-event (alert/create-alert-event event)]
      (is (some? (get-in alert-event [:data :alert-message])))
      (is (str/includes? (get-in alert-event [:data :alert-message]) "[WARNING]")))))

(deftest test-create-alert-event-metadata
  (testing "metadata has severity and triggered-at"
    (let [event {:type "test.event"
                 :source "test"
                 :data {}
                 :metadata {:severity "error"}}
          alert-event (alert/create-alert-event event)]
      (is (= "error" (get-in alert-event [:metadata :severity])))
      (is (some? (get-in alert-event [:metadata :triggered-at]))))))
