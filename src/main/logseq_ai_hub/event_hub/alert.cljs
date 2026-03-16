(ns logseq-ai-hub.event-hub.alert
  "Alert routing engine: severity-based filtering, message formatting,
   cooldown/rate-limiting, and alert event creation."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Severity Levels
;; ---------------------------------------------------------------------------

(def severity-levels
  "Ordered severity levels. Higher number = more severe."
  {"info" 0
   "warning" 1
   "error" 2
   "critical" 3})

(defn severity-at-least?
  "Returns true if `event-severity` is at or above `min-severity`.
   Unknown severities are treated as 0 (info)."
  [event-severity min-severity]
  (let [event-level (get severity-levels (str event-severity) 0)
        min-level (get severity-levels (str min-severity) 0)]
    (>= event-level min-level)))

;; ---------------------------------------------------------------------------
;; Alert Message Formatting
;; ---------------------------------------------------------------------------

(defn format-alert-message
  "Formats a hub event into a human-readable alert message string.
   Format:
     [SEVERITY] event-type from source
     Data: {key: value, ...}
     Time: timestamp"
  [hub-event]
  (let [severity (str/upper-case
                   (or (get-in hub-event [:metadata :severity])
                       (get-in hub-event [:metadata "severity"])
                       "info"))
        event-type (or (:type hub-event) "unknown")
        source (or (:source hub-event) "unknown")
        data (:data hub-event)
        data-str (if (and data (seq data))
                   (str "{"
                        (str/join ", "
                          (map (fn [[k v]]
                                 (str (name k) ": " v))
                               data))
                        "}")
                   "{}")
        timestamp (or (get-in hub-event [:metadata :timestamp])
                      (get-in hub-event [:metadata "timestamp"])
                      (.toISOString (js/Date.)))]
    (str "[" severity "] " event-type " from " source "\n"
         "Data: " data-str "\n"
         "Time: " timestamp)))

;; ---------------------------------------------------------------------------
;; Cooldown / Rate Limiting
;; ---------------------------------------------------------------------------

(defonce cooldown-state
  (atom {}))  ;; alert-key -> last-alert-timestamp-ms

(defn- cooldown-key
  "Derives a cooldown key from the event type and source."
  [hub-event]
  (str (:type hub-event) ":" (:source hub-event)))

(defn- cooldown-allows?
  "Returns true if enough time has passed since the last alert for this key."
  [hub-event cooldown-ms]
  (if (or (nil? cooldown-ms) (<= cooldown-ms 0))
    true
    (let [key (cooldown-key hub-event)
          now (js/Date.now)
          last-alert (get @cooldown-state key 0)]
      (if (>= (- now last-alert) cooldown-ms)
        (do
          (swap! cooldown-state assoc key now)
          true)
        false))))

;; ---------------------------------------------------------------------------
;; Alert Decision
;; ---------------------------------------------------------------------------

(defn should-alert?
  "Determines if an event should trigger an alert based on alert-config.
   Config keys:
     :min-severity  - minimum severity level (string), e.g. \"warning\"
     :event-types   - set or seq of event type patterns to match (exact match)
     :cooldown-ms   - minimum ms between alerts for same event type+source
   All filters are optional; omitted filters are permissive."
  [hub-event alert-config]
  (let [min-sev (:min-severity alert-config)
        event-types (:event-types alert-config)
        cooldown-ms (:cooldown-ms alert-config)
        event-severity (or (get-in hub-event [:metadata :severity])
                           (get-in hub-event [:metadata "severity"])
                           "info")]
    (and
      ;; Check severity threshold
      (if min-sev
        (severity-at-least? event-severity min-sev)
        true)
      ;; Check event type filter
      (if (and event-types (seq event-types))
        (contains? (set event-types) (:type hub-event))
        true)
      ;; Check cooldown
      (cooldown-allows? hub-event cooldown-ms))))

;; ---------------------------------------------------------------------------
;; Alert Event Creation
;; ---------------------------------------------------------------------------

(defn create-alert-event
  "Transforms a hub event into an alert event with type \"alert.triggered\".
   Preserves original event data in nested :original-event key."
  [hub-event]
  {:type "alert.triggered"
   :source (str "alert:" (or (:source hub-event) "unknown"))
   :data {:original-event hub-event
          :alert-message (format-alert-message hub-event)}
   :metadata {:severity (or (get-in hub-event [:metadata :severity])
                             (get-in hub-event [:metadata "severity"])
                             "info")
              :triggered-at (.toISOString (js/Date.))}})
