(ns logseq-ai-hub.event-hub.parser
  "Parsers for event subscription and webhook source page types.
   Converts Logseq page content into normalized configuration entries."
  (:require [logseq-ai-hub.job-runner.parser :as block-parser]
            [clojure.string :as str]))

(defn parse-subscription-page
  "Parses an event subscription page's block content into a registry entry.
   Returns {:valid true :entry {...}} or {:valid false :errors [...]}.

   Expected properties:
     event-pattern:: webhook.grafana.*
     event-action:: skill
     event-skill:: Skills/alert-handler
     event-debounce:: 0
     event-route-to:: whatsapp:15551234567
     event-severity-filter:: warning,error,critical
     event-description:: Handle Grafana alerts"
  [page-name block-content]
  (let [props (block-parser/parse-block-properties block-content)
        pattern (:event-pattern props)
        action (when (:event-action props)
                 (keyword (:event-action props)))
        skill (:event-skill props)
        debounce (if (:event-debounce props)
                   (let [v (js/parseInt (:event-debounce props) 10)]
                     (if (js/isNaN v) 0 v))
                   0)
        route-to (:event-route-to props)
        severity-raw (:event-severity-filter props)
        severity-filter (when severity-raw
                          (if (vector? severity-raw)
                            (set (map keyword severity-raw))
                            (set (map (comp keyword str/trim)
                                      (str/split (str severity-raw) #",")))))
        description (or (:event-description props) "")
        errors (cond-> []
                 (str/blank? pattern)
                 (conj "Missing required property: event-pattern")
                 (nil? action)
                 (conj "Missing required property: event-action"))]
    (if (seq errors)
      {:valid false
       :errors errors
       :page page-name}
      {:valid true
       :entry {:id page-name
               :type :event-subscription
               :name page-name
               :description description
               :event-pattern pattern
               :event-action action
               :event-skill skill
               :event-debounce debounce
               :event-route-to route-to
               :event-severity-filter severity-filter
               :properties props
               :source :graph-page}})))

(defn parse-webhook-source-page
  "Parses a webhook source page's block content into a registry entry.
   Returns {:valid true :entry {...}} or {:valid false :errors [...]}.

   Expected properties:
     webhook-source:: grafana
     webhook-description:: Grafana alerting webhooks
     webhook-verify-token:: {{secret.GRAFANA_WEBHOOK_TOKEN}}
     webhook-extract-title:: $.alerts[0].labels.alertname
     webhook-extract-severity:: $.alerts[0].labels.severity
     webhook-extract-message:: $.alerts[0].annotations.summary
     webhook-page-prefix:: Events/Grafana
     webhook-route-to:: whatsapp:15551234567
     webhook-auto-job:: Skills/alert-handler"
  [page-name block-content]
  (let [props (block-parser/parse-block-properties block-content)
        source (:webhook-source props)
        description (or (:webhook-description props) "")
        verify-token (:webhook-verify-token props)
        extract-title (:webhook-extract-title props)
        extract-severity (:webhook-extract-severity props)
        extract-message (:webhook-extract-message props)
        page-prefix (:webhook-page-prefix props)
        route-to (:webhook-route-to props)
        auto-job (:webhook-auto-job props)
        errors (cond-> []
                 (str/blank? source)
                 (conj "Missing required property: webhook-source"))]
    (if (seq errors)
      {:valid false
       :errors errors
       :page page-name}
      {:valid true
       :entry {:id page-name
               :type :webhook-source
               :name page-name
               :description description
               :webhook-source source
               :webhook-verify-token verify-token
               :webhook-extract-title extract-title
               :webhook-extract-severity extract-severity
               :webhook-extract-message extract-message
               :webhook-page-prefix page-prefix
               :webhook-route-to route-to
               :webhook-auto-job auto-job
               :properties props
               :source :graph-page}})))
