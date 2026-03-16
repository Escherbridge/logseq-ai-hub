(ns logseq-ai-hub.event-hub.parser-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.event-hub.parser :as parser]))

;; ---------------------------------------------------------------------------
;; Subscription Page Parser Tests
;; ---------------------------------------------------------------------------

(deftest test-parse-subscription-page-valid
  (testing "Parses a valid subscription page with all properties"
    (let [content (str "event-pattern:: webhook.grafana.*\n"
                       "event-action:: skill\n"
                       "event-skill:: Skills/alert-handler\n"
                       "event-debounce:: 5000\n"
                       "event-route-to:: whatsapp:15551234567\n"
                       "event-severity-filter:: warning,error,critical\n"
                       "event-description:: Handle Grafana alerts\n"
                       "tags:: logseq-ai-hub-event-subscription")
          result (parser/parse-subscription-page "Subscriptions/grafana-alerts" content)]
      (is (true? (:valid result)))
      (let [entry (:entry result)]
        (is (= "Subscriptions/grafana-alerts" (:id entry)))
        (is (= :event-subscription (:type entry)))
        (is (= "webhook.grafana.*" (:event-pattern entry)))
        (is (= :skill (:event-action entry)))
        (is (= "Skills/alert-handler" (:event-skill entry)))
        (is (= 5000 (:event-debounce entry)))
        (is (= "whatsapp:15551234567" (:event-route-to entry)))
        (is (= #{:warning :error :critical} (:event-severity-filter entry)))
        (is (= "Handle Grafana alerts" (:description entry)))
        (is (= :graph-page (:source entry)))))))

(deftest test-parse-subscription-page-minimal
  (testing "Parses a subscription with only required properties"
    (let [content (str "event-pattern:: job.*\n"
                       "event-action:: log\n"
                       "tags:: logseq-ai-hub-event-subscription")
          result (parser/parse-subscription-page "Subscriptions/job-log" content)]
      (is (true? (:valid result)))
      (let [entry (:entry result)]
        (is (= "job.*" (:event-pattern entry)))
        (is (= :log (:event-action entry)))
        (is (nil? (:event-skill entry)))
        (is (= 0 (:event-debounce entry)))
        (is (nil? (:event-route-to entry)))
        (is (nil? (:event-severity-filter entry)))
        (is (= "" (:description entry)))))))

(deftest test-parse-subscription-page-route-action
  (testing "Parses a subscription with route action"
    (let [content (str "event-pattern:: webhook.*.*\n"
                       "event-action:: route\n"
                       "event-route-to:: telegram:12345\n"
                       "event-description:: Route all webhooks\n"
                       "tags:: logseq-ai-hub-event-subscription")
          result (parser/parse-subscription-page "Subscriptions/route-all" content)]
      (is (true? (:valid result)))
      (is (= :route (get-in result [:entry :event-action])))
      (is (= "telegram:12345" (get-in result [:entry :event-route-to]))))))

(deftest test-parse-subscription-page-missing-pattern
  (testing "Returns invalid when event-pattern is missing"
    (let [content (str "event-action:: skill\n"
                       "event-skill:: Skills/handler\n"
                       "tags:: logseq-ai-hub-event-subscription")
          result (parser/parse-subscription-page "Subscriptions/bad" content)]
      (is (false? (:valid result)))
      (is (some #(re-find #"event-pattern" %) (:errors result))))))

(deftest test-parse-subscription-page-missing-action
  (testing "Returns invalid when event-action is missing"
    (let [content (str "event-pattern:: job.*\n"
                       "tags:: logseq-ai-hub-event-subscription")
          result (parser/parse-subscription-page "Subscriptions/bad" content)]
      (is (false? (:valid result)))
      (is (some #(re-find #"event-action" %) (:errors result))))))

(deftest test-parse-subscription-page-missing-both
  (testing "Returns invalid with two errors when both required properties missing"
    (let [content "tags:: logseq-ai-hub-event-subscription"
          result (parser/parse-subscription-page "Subscriptions/empty" content)]
      (is (false? (:valid result)))
      (is (= 2 (count (:errors result)))))))

(deftest test-parse-subscription-page-debounce-default
  (testing "Debounce defaults to 0 when not specified"
    (let [content (str "event-pattern:: test.*\n"
                       "event-action:: log")
          result (parser/parse-subscription-page "Sub/test" content)]
      (is (true? (:valid result)))
      (is (= 0 (get-in result [:entry :event-debounce]))))))

(deftest test-parse-subscription-page-debounce-invalid
  (testing "Invalid debounce string defaults to 0"
    (let [content (str "event-pattern:: test.*\n"
                       "event-action:: log\n"
                       "event-debounce:: not-a-number")
          result (parser/parse-subscription-page "Sub/test" content)]
      (is (true? (:valid result)))
      (is (= 0 (get-in result [:entry :event-debounce]))))))

;; ---------------------------------------------------------------------------
;; Webhook Source Page Parser Tests
;; ---------------------------------------------------------------------------

(deftest test-parse-webhook-source-page-valid
  (testing "Parses a valid webhook source page with all properties"
    (let [content (str "webhook-source:: grafana\n"
                       "webhook-description:: Grafana alerting webhooks\n"
                       "webhook-verify-token:: {{secret.GRAFANA_TOKEN}}\n"
                       "webhook-extract-title:: $.alerts[0].labels.alertname\n"
                       "webhook-extract-severity:: $.alerts[0].labels.severity\n"
                       "webhook-extract-message:: $.alerts[0].annotations.summary\n"
                       "webhook-page-prefix:: Events/Grafana\n"
                       "webhook-route-to:: whatsapp:15551234567\n"
                       "webhook-auto-job:: Skills/alert-handler\n"
                       "tags:: logseq-ai-hub-webhook-source")
          result (parser/parse-webhook-source-page "Webhooks/grafana" content)]
      (is (true? (:valid result)))
      (let [entry (:entry result)]
        (is (= "Webhooks/grafana" (:id entry)))
        (is (= :webhook-source (:type entry)))
        (is (= "grafana" (:webhook-source entry)))
        (is (= "Grafana alerting webhooks" (:description entry)))
        (is (= "{{secret.GRAFANA_TOKEN}}" (:webhook-verify-token entry)))
        (is (= "$.alerts[0].labels.alertname" (:webhook-extract-title entry)))
        (is (= "$.alerts[0].labels.severity" (:webhook-extract-severity entry)))
        (is (= "$.alerts[0].annotations.summary" (:webhook-extract-message entry)))
        (is (= "Events/Grafana" (:webhook-page-prefix entry)))
        (is (= "whatsapp:15551234567" (:webhook-route-to entry)))
        (is (= "Skills/alert-handler" (:webhook-auto-job entry)))
        (is (= :graph-page (:source entry)))))))

(deftest test-parse-webhook-source-page-minimal
  (testing "Parses a webhook source page with only required properties"
    (let [content (str "webhook-source:: github\n"
                       "tags:: logseq-ai-hub-webhook-source")
          result (parser/parse-webhook-source-page "Webhooks/github" content)]
      (is (true? (:valid result)))
      (let [entry (:entry result)]
        (is (= "github" (:webhook-source entry)))
        (is (= "" (:description entry)))
        (is (nil? (:webhook-verify-token entry)))
        (is (nil? (:webhook-extract-title entry)))))))

(deftest test-parse-webhook-source-page-missing-source
  (testing "Returns invalid when webhook-source is missing"
    (let [content (str "webhook-description:: Some hooks\n"
                       "tags:: logseq-ai-hub-webhook-source")
          result (parser/parse-webhook-source-page "Webhooks/bad" content)]
      (is (false? (:valid result)))
      (is (some #(re-find #"webhook-source" %) (:errors result))))))
