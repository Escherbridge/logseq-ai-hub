(ns logseq-ai-hub.event-hub.init
  "Initialization and wiring for the Event Hub system.
   Wires dynamic vars, registers SSE listener for hub_event,
   and registers event:* slash commands."
  (:require [logseq-ai-hub.event-hub.dispatcher :as dispatcher]
            [logseq-ai-hub.event-hub.publish :as publish]
            [logseq-ai-hub.event-hub.emit :as emit]
            [logseq-ai-hub.event-hub.graph-watcher :as graph-watcher]
            [logseq-ai-hub.event-hub.commands :as commands]
            [logseq-ai-hub.auth :as auth]
            [logseq-ai-hub.messaging :as messaging]
            [logseq-ai-hub.job-runner.runner :as runner]))

(defonce initialized? (atom false))

(defn- get-server-url []
  (aget js/logseq "settings" "webhookServerUrl"))

(defn- get-api-token []
  (auth/get-auth-token))

(defn- fetch-recent-events
  "Fetches recent events from GET /api/events?limit=10.
   Returns Promise<[event-map ...]>."
  []
  (let [server-url (get-server-url)
        token (get-api-token)]
    (if (and server-url token)
      (-> (js/fetch (str server-url "/api/events?limit=10")
                    (clj->js {:method "GET"
                              :headers {"Authorization" (str "Bearer " token)}}))
          (.then (fn [res] (.json res)))
          (.then (fn [json]
                   (let [result (js->clj json :keywordize-keys true)]
                     (or (:events result) [])))))
      (js/Promise.resolve []))))

(defn- fetch-event-sources
  "Fetches unique event sources from GET /api/events?limit=200.
   Extracts distinct :source values. Returns Promise<[source-string ...]>."
  []
  (let [server-url (get-server-url)
        token (get-api-token)]
    (if (and server-url token)
      (-> (js/fetch (str server-url "/api/events?limit=200")
                    (clj->js {:method "GET"
                              :headers {"Authorization" (str "Bearer " token)}}))
          (.then (fn [res] (.json res)))
          (.then (fn [json]
                   (let [result (js->clj json :keywordize-keys true)
                         events (or (:events result) [])]
                     (vec (distinct (keep :source events)))))))
      (js/Promise.resolve []))))

(defn- wire-dynamic-vars!
  "Wires dispatcher dynamic vars to actual implementations."
  []
  (set! dispatcher/*enqueue-job-fn* runner/enqueue-job!)
  (set! dispatcher/*send-message-fn* messaging/send-message!)
  (set! runner/*emit-event-fn* publish/publish-to-server!)
  (set! emit/*publish-event-fn* publish/publish-to-server!)
  (set! graph-watcher/*publish-fn* publish/publish-to-server!)
  (set! commands/*publish-fn* publish/publish-to-server!)
  (set! commands/*fetch-recent-fn* fetch-recent-events)
  (set! commands/*fetch-sources-fn* fetch-event-sources))

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
        (graph-watcher/start!)
        (commands/register-commands!)
        (reset! initialized? true)
        (js/console.log "[EventHub] Initialized")))))
