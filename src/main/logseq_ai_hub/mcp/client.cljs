(ns logseq-ai-hub.mcp.client
  "MCP client connection manager with auto-reconnect and operation handling."
  (:require [logseq-ai-hub.mcp.protocol :as protocol]
            [logseq-ai-hub.mcp.transport :as transport]
            [logseq-ai-hub.util.errors :as errors]))

(defonce servers (atom {}))

(def ^:private max-reconnect-attempts 3)
(def ^:private reconnect-delays [1000 5000 15000]) ; Exponential backoff in ms

(defn with-timeout
  "Wraps a promise with a timeout. Returns the original promise result or
   rejects with timeout error."
  [promise timeout-ms]
  (js/Promise.race
   #js [promise
        (js/Promise. (fn [_ reject]
                       (js/setTimeout #(reject (js/Error. "MCP operation timed out"))
                                      timeout-ms)))]))

(defn- get-server
  "Gets server state, throws if not found or not connected."
  [server-id]
  (if-let [server (get @servers server-id)]
    (if (= :connected (:status server))
      server
      (throw (js/Error. (str "Server " server-id " not connected"))))
    (throw (js/Error. (str "Server " server-id " not found")))))

(defn- perform-connection
  "Performs the actual connection steps: initialize, initialized notification,
   and cache capabilities/tools."
  [server-id config transport-instance]
  (let [send! (:send! transport-instance)
        client-info {:name "Logseq AI Hub" :version "1.0.0"}
        capabilities {:tools {} :resources {} :prompts {}}]

    (-> (send! (protocol/initialize-request client-info capabilities))
        (.then (fn [init-response]
                 (when (:error init-response)
                   (throw (js/Error. (get-in init-response [:error :message]))))

                 ;; Send initialized notification
                 (send! (protocol/initialized-notification))

                 ;; Cache server capabilities
                 (let [server-caps (get-in init-response [:result :capabilities])
                       server-info (get-in init-response [:result :serverInfo])]
                   (swap! servers update server-id merge
                          {:status :connected
                           :transport transport-instance
                           :capabilities server-caps
                           :server-info server-info
                           :reconnect-count 0})

                   ;; Fetch initial tool list if supported
                   (if (get-in server-caps [:tools])
                     (-> (send! (protocol/tools-list-request))
                         (.then (fn [tools-response]
                                  (when-not (:error tools-response)
                                    (swap! servers assoc-in [server-id :tools]
                                           (get-in tools-response [:result :tools] [])))
                                  init-response)))
                     (js/Promise.resolve init-response))))))))

(defn- attempt-reconnect
  "Attempts to reconnect with exponential backoff."
  [server-id config attempt]
  (if (>= attempt max-reconnect-attempts)
    ;; Max retries exceeded
    (do
      (swap! servers assoc-in [server-id :status] :error)
      (js/Promise.reject (js/Error. "Max reconnection attempts exceeded")))

    ;; Try reconnecting after delay
    (js/Promise.
     (fn [resolve reject]
       (let [delay (get reconnect-delays attempt 15000)]
         (js/setTimeout
          (fn []
            (let [make-transport-fn (or (:make-transport-fn config)
                                        transport/make-transport)
                  transport-instance (make-transport-fn config)]

              ;; For SSE transport, call connect!
              (when (and (= :sse (:type transport-instance))
                         (:connect! transport-instance))
                ((:connect! transport-instance)))

              (-> (perform-connection server-id config transport-instance)
                  (.then resolve)
                  (.catch (fn [err]
                            (swap! servers update-in [server-id :reconnect-count] inc)
                            (-> (attempt-reconnect server-id config (inc attempt))
                                (.then resolve)
                                (.catch reject)))))))
          delay))))))

(defn connect-server!
  "Establishes connection to an MCP server.

   Config map:
   - :id - unique server identifier
   - :url - server endpoint URL
   - :name - human-readable server name
   - :transport-type (optional) - :streamable-http or :sse
   - :auth-token (optional) - bearer token
   - :make-transport-fn (optional) - custom transport factory for testing

   Returns a Promise that resolves when connected."
  [config]
  (let [server-id (:id config)]
    ;; Initialize server state
    (swap! servers assoc server-id
           {:id server-id
            :url (:url config)
            :name (:name config)
            :transport-type (or (:transport-type config) :streamable-http)
            :status :connecting
            :auth-token (:auth-token config)
            :transport nil
            :capabilities {}
            :tools []
            :resources []
            :prompts []
            :reconnect-count 0})

    (let [make-transport-fn (or (:make-transport-fn config)
                                transport/make-transport)
          transport-instance (make-transport-fn config)]

      ;; For SSE transport, call connect!
      (when (and (= :sse (:type transport-instance))
                 (:connect! transport-instance))
        ((:connect! transport-instance)))

      (-> (perform-connection server-id config transport-instance)
          (.catch (fn [err]
                    ;; Connection failed - attempt reconnect
                    (swap! servers update-in [server-id :reconnect-count] inc)
                    (attempt-reconnect server-id config 1)))))))

(defn disconnect-server!
  "Disconnects from an MCP server and cleans up resources."
  [server-id]
  (when-let [server (get @servers server-id)]
    (when-let [transport (:transport server)]
      ((:close! transport)))
    (swap! servers assoc-in [server-id :status] :disconnected)
    (swap! servers assoc-in [server-id :transport] nil)))

(defn list-servers
  "Returns a list of all registered servers."
  []
  (vals @servers))

(defn server-status
  "Returns the connection status of a specific server."
  [server-id]
  (get-in @servers [server-id :status] :disconnected))

(defn list-tools
  "Lists available tools from a server. Returns cached tools if available,
   otherwise fetches from server.

   Returns a Promise resolving to a vector of tool definitions."
  [server-id]
  (try
    (let [server (get-server server-id)]
      (if (seq (:tools server))
        (js/Promise.resolve (:tools server))
        (let [send! (get-in server [:transport :send!])]
          (-> (send! (protocol/tools-list-request))
              (.then (fn [response]
                       (if (:error response)
                         (throw (js/Error. (get-in response [:error :message])))
                         (let [tools (get-in response [:result :tools] [])]
                           (swap! servers assoc-in [server-id :tools] tools)
                           tools))))))))
    (catch js/Error e
      (js/Promise.reject e))))

(defn call-tool
  "Calls a tool on the MCP server with the given arguments.

   Returns a Promise resolving to the tool result (with 30s timeout)."
  [server-id tool-name arguments]
  (try
    (let [server (get-server server-id)
          send! (get-in server [:transport :send!])
          request (protocol/tools-call-request tool-name arguments)]
      (-> (with-timeout (send! request) 30000)
          (.then (fn [response]
                   (if (:error response)
                     (throw (js/Error. (get-in response [:error :message])))
                     (:result response))))))
    (catch js/Error e
      (js/Promise.reject e))))

(defn refresh-tools!
  "Re-fetches the tool list from the server, ignoring cache.

   Returns a Promise resolving to the updated tool list."
  [server-id]
  (try
    (let [server (get-server server-id)
          send! (get-in server [:transport :send!])]
      (-> (send! (protocol/tools-list-request))
          (.then (fn [response]
                   (if (:error response)
                     (throw (js/Error. (get-in response [:error :message])))
                     (let [tools (get-in response [:result :tools] [])]
                       (swap! servers assoc-in [server-id :tools] tools)
                       tools))))))
    (catch js/Error e
      (js/Promise.reject e))))

(defn list-resources
  "Lists available resources from the server.

   Returns a Promise resolving to a vector of resource definitions."
  [server-id]
  (try
    (let [server (get-server server-id)
          send! (get-in server [:transport :send!])]
      (-> (send! (protocol/resources-list-request))
          (.then (fn [response]
                   (if (:error response)
                     (throw (js/Error. (get-in response [:error :message])))
                     (get-in response [:result :resources] []))))))
    (catch js/Error e
      (js/Promise.reject e))))

(defn read-resource
  "Reads a resource from the server by URI.

   Returns a Promise resolving to the resource contents."
  [server-id uri]
  (try
    (let [server (get-server server-id)
          send! (get-in server [:transport :send!])]
      (-> (send! (protocol/resources-read-request uri))
          (.then (fn [response]
                   (if (:error response)
                     (throw (js/Error. (get-in response [:error :message])))
                     (get-in response [:result :contents] []))))))
    (catch js/Error e
      (js/Promise.reject e))))

(defn subscribe-resource!
  "Subscribes to resource updates (if supported by server).

   Takes a callback function that will be called with update events.
   Returns a Promise resolving to subscription details or nil if not supported."
  [server-id uri callback]
  ;; Note: Resource subscription is optional in MCP spec
  ;; This is a placeholder implementation
  (js/Promise.resolve nil))

(defn list-prompts
  "Lists available prompts from the server.

   Returns a Promise resolving to a vector of prompt definitions."
  [server-id]
  (try
    (let [server (get-server server-id)
          send! (get-in server [:transport :send!])]
      (-> (send! (protocol/prompts-list-request))
          (.then (fn [response]
                   (if (:error response)
                     (throw (js/Error. (get-in response [:error :message])))
                     (get-in response [:result :prompts] []))))))
    (catch js/Error e
      (js/Promise.reject e))))

(defn get-prompt
  "Gets a specific prompt from the server with the given arguments.

   Returns a Promise resolving to the prompt data."
  [server-id prompt-name arguments]
  (try
    (let [server (get-server server-id)
          send! (get-in server [:transport :send!])]
      (-> (send! (protocol/prompts-get-request prompt-name arguments))
          (.then (fn [response]
                   (if (:error response)
                     (throw (js/Error. (get-in response [:error :message])))
                     (:result response))))))
    (catch js/Error e
      (js/Promise.reject e))))
