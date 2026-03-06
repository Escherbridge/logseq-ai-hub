(ns logseq-ai-hub.event-hub.init
  "Initialization and wiring for the Event Hub system.
   Wires dynamic vars, registers SSE listener for hub_event."
  (:require [logseq-ai-hub.event-hub.dispatcher :as dispatcher]
            [logseq-ai-hub.messaging :as messaging]
            [logseq-ai-hub.job-runner.runner :as runner]
            [logseq-ai-hub.job-runner.graph :as job-graph]))

(defonce initialized? (atom false))

(defn- wire-dynamic-vars!
  "Wires dispatcher dynamic vars to actual implementations."
  []
  (set! dispatcher/*enqueue-job-fn* runner/enqueue-job!)
  (set! dispatcher/*read-skill-fn* job-graph/read-skill-page)
  (set! dispatcher/*send-message-fn* messaging/send-message!))

(defn- register-sse-listener!
  "Registers the hub_event SSE listener on the current EventSource.
   Does NOT modify messaging.cljs doseq -- attaches directly."
  []
  (when-let [es (messaging/get-event-source)]
    (.addEventListener es "hub_event"
      (fn [e]
        (dispatcher/handle-hub-event-sse (.-data e))))
    (js/console.log "[EventHub] SSE listener registered")))

(defn init!
  "Initializes the Event Hub system.
   Safe to call multiple times (only initializes once).

   1. Checks if Event Hub is enabled in settings
   2. Wires dynamic vars for dispatcher
   3. Registers hub_event SSE listener"
  []
  (when-not @initialized?
    (let [enabled? (let [v (aget js/logseq "settings" "eventHubEnabled")]
                     (if (nil? v) true v))]  ;; default true
      (when enabled?
        (wire-dynamic-vars!)
        (register-sse-listener!)
        (reset! initialized? true)
        (js/console.log "[EventHub] Initialized")))))
