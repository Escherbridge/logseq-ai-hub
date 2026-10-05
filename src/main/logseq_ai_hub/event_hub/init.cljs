(ns logseq-ai-hub.event-hub.init
  "Initialization and wiring for the Event Hub system.
   Wires dynamic vars, registers SSE listener for hub_event,
   and registers event:* slash commands."
  (:require [logseq-ai-hub.event-hub.dispatcher :as dispatcher]
            [logseq-ai-hub.event-hub.publish :as publish]
            [logseq-ai-hub.event-hub.emit :as emit]
            [logseq-ai-hub.event-hub.graph-watcher :as graph-watcher]
            [logseq-ai-hub.event-hub.commands :as commands]
            [logseq-ai-hub.messaging :as messaging]
            [logseq-ai-hub.job-runner.runner :as runner]
            [logseq-ai-hub.auth :as auth]))

(defonce initialized? (atom false))

(defn- fetch-recent-events
  "Fetches recent events from GET /api/events?limit=10.
   Returns Promise<[event-map ...]>; REJECTS (never silently resolves [])
   when the server is unconfigured or responds with a non-2xx status, so a
   real failure can never render as 'No recent events'."
  []
  (if (auth/auth-configured?)
    (-> (js/fetch (str (auth/get-server-url) "/api/events?limit=10")
                  (clj->js {:method "GET"
                            :headers {"Authorization" (str "Bearer " (auth/get-auth-token))}}))
        (.then (fn [res]
                 (if (.-ok res)
                   (.json res)
                   (throw (js/Error. (str "Server responded with HTTP " (.-status res)
                                          " while fetching recent events."))))))
        (.then (fn [json]
                 (let [result (js->clj json :keywordize-keys true)]
                   (or (:events result) [])))))
    (js/Promise.reject
      (js/Error. "Event server not configured -- set Settings -> 'Webhook Server URL' and 'Plugin API Token' first."))))

(defn- fetch-event-sources
  "Fetches unique event sources from GET /api/events?limit=200.
   Extracts distinct :source values. Returns Promise<[source-string ...]>;
   rejects on missing config or a non-2xx response (see fetch-recent-events)."
  []
  (if (auth/auth-configured?)
    (-> (js/fetch (str (auth/get-server-url) "/api/events?limit=200")
                  (clj->js {:method "GET"
                            :headers {"Authorization" (str "Bearer " (auth/get-auth-token))}}))
        (.then (fn [res]
                 (if (.-ok res)
                   (.json res)
                   (throw (js/Error. (str "Server responded with HTTP " (.-status res)
                                          " while fetching event sources."))))))
        (.then (fn [json]
                 (let [result (js->clj json :keywordize-keys true)
                       events (or (:events result) [])]
                   (vec (distinct (keep :source events)))))))
    (js/Promise.reject
      (js/Error. "Event server not configured -- set Settings -> 'Webhook Server URL' and 'Plugin API Token' first."))))

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
  "Registers the hub_event listener through messaging's listener registry
   (messaging/register-listener!), not a raw es.addEventListener bound to
   the current EventSource -- a direct attach dies silently on the next SSE
   reconnect (messaging/connect! builds a brand new EventSource every time),
   so event automations would stop firing after any sleep/blip/redeploy."
  []
  (messaging/register-listener! "hub_event"
    (fn [e]
      (dispatcher/handle-hub-event-sse (.-data e))))
  (js/console.log "[EventHub] SSE listener registered"))

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
