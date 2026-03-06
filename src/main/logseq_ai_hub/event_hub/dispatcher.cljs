(ns logseq-ai-hub.event-hub.dispatcher
  "Event dispatcher: matches incoming hub events against subscriptions,
   applies severity filtering and debounce, then fires matching actions."
  (:require [logseq-ai-hub.registry.store :as store]
            [logseq-ai-hub.event-hub.pattern :as pattern]
            [logseq-ai-hub.event-hub.graph :as event-graph]
            [logseq-ai-hub.messaging :as messaging]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Dynamic vars for dependency injection
;; ---------------------------------------------------------------------------

(def ^:dynamic *enqueue-job-fn*
  "Function to enqueue a job. (fn [job-id] -> Promise)"
  nil)

(def ^:dynamic *read-skill-fn*
  "Function to read a skill page. (fn [skill-id] -> Promise<skill-def>)"
  nil)

(def ^:dynamic *send-message-fn*
  "Function to send a message. (fn [platform recipient content] -> Promise)"
  nil)

;; ---------------------------------------------------------------------------
;; Debounce state
;; ---------------------------------------------------------------------------

(defonce debounce-state
  (atom {}))  ;; subscription-id -> last-fire-timestamp-ms

(defn- debounce-allows?
  "Returns true if the subscription's debounce window has elapsed."
  [subscription-id debounce-ms]
  (if (or (nil? debounce-ms) (<= debounce-ms 0))
    true
    (let [now (js/Date.now)
          last-fire (get @debounce-state subscription-id 0)]
      (if (>= (- now last-fire) debounce-ms)
        (do
          (swap! debounce-state assoc subscription-id now)
          true)
        false))))

;; ---------------------------------------------------------------------------
;; Subscription matching
;; ---------------------------------------------------------------------------

(defn- severity-matches?
  "Returns true if the event matches the subscription's severity filter.
   If no filter is set, all events match."
  [hub-event subscription]
  (let [filter-set (:event-severity-filter subscription)]
    (if (and filter-set (seq filter-set))
      (let [event-severity (keyword (get-in hub-event [:metadata :severity] "info"))]
        (contains? filter-set event-severity))
      true)))

(defn- find-matching-subscriptions
  "Returns subscriptions whose pattern matches the event type AND
   whose severity filter matches the event."
  [hub-event]
  (let [subscriptions (store/list-entries :event-subscription)
        event-type (:type hub-event)]
    (filter (fn [sub]
              (and (pattern/pattern-matches? (:event-pattern sub) event-type)
                   (severity-matches? hub-event sub)))
            subscriptions)))

;; ---------------------------------------------------------------------------
;; Action execution
;; ---------------------------------------------------------------------------

(defn- execute-skill-action!
  "Triggers a job for the given skill with event data as inputs."
  [subscription hub-event]
  (when-let [skill-id (:event-skill subscription)]
    (when *enqueue-job-fn*
      (let [job-page (str "Jobs/event-" (or (:id hub-event) (str (random-uuid))))]
        ;; Create a job page for the event-triggered skill
        (-> (js/logseq.Editor.createPage
              job-page
              #js {}
              #js {:createFirstBlock false :redirect false})
            (.catch (fn [_] nil))
            (.then (fn [_]
                     (let [content (str "job-skill:: " skill-id "\n"
                                        "job-status:: queued\n"
                                        "job-priority:: 2\n"
                                        "job-input:: " (js/JSON.stringify (clj->js (:data hub-event))) "\n"
                                        "job-trigger:: event\n"
                                        "job-trigger-event:: " (:type hub-event) "\n"
                                        "tags:: logseq-ai-hub-job")]
                       (js/logseq.Editor.appendBlockInPage job-page content))))
            (.then (fn [_]
                     (*enqueue-job-fn* job-page)))
            (.catch (fn [err]
                      (js/console.error "[EventHub] Failed to trigger skill action:" err))))))))

(defn- execute-route-action!
  "Routes the event as a message to the configured destination."
  [subscription hub-event]
  (when-let [route-to (:event-route-to subscription)]
    (let [send-fn (or *send-message-fn* messaging/send-message!)
          [platform recipient] (str/split route-to #":" 2)
          content (str "Event: " (:type hub-event) "\n"
                       "Source: " (:source hub-event) "\n"
                       "Severity: " (get-in hub-event [:metadata :severity] "info") "\n"
                       "Data: " (js/JSON.stringify (clj->js (:data hub-event)) nil 2))]
      (when (and platform recipient)
        (-> (send-fn platform recipient content)
            (.catch (fn [err]
                      (js/console.warn "[EventHub] Failed to route event:" err))))))))

(defn- execute-log-action!
  "Log action: event is persisted to graph (handled separately). This is a no-op."
  [_subscription _hub-event]
  nil)

(defn- fire-subscription!
  "Fires the appropriate action for a matching subscription."
  [subscription hub-event]
  (when (debounce-allows? (:id subscription) (:event-debounce subscription))
    (case (:event-action subscription)
      :skill  (execute-skill-action! subscription hub-event)
      :route  (execute-route-action! subscription hub-event)
      :log    (execute-log-action! subscription hub-event)
      (js/console.warn "[EventHub] Unknown action:" (:event-action subscription)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn dispatch-event!
  "Dispatches a hub event to all matching subscriptions.
   Loads subscriptions from store, applies pattern + severity matching,
   checks debounce, and fires actions."
  [hub-event]
  (let [matching (find-matching-subscriptions hub-event)]
    (doseq [sub matching]
      (fire-subscription! sub hub-event))))

(defn handle-hub-event-sse
  "Handles a raw SSE hub_event data string.
   Parses JSON, extracts from :payload key, dispatches + persists."
  [raw-data]
  (try
    (let [parsed (js->clj (js/JSON.parse raw-data) :keywordize-keys true)
          hub-event (:payload parsed)]
      (when hub-event
        ;; Persist to graph if enabled
        (when (aget js/logseq "settings" "eventGraphPersistence")
          (event-graph/persist-to-graph! hub-event))
        ;; Dispatch to matching subscriptions
        (dispatch-event! hub-event)))
    (catch :default err
      (js/console.error "[EventHub] Failed to handle SSE event:" err))))
