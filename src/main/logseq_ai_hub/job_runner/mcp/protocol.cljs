(ns logseq-ai-hub.job-runner.mcp.protocol
  "JSON-RPC 2.0 message construction for Model Context Protocol (MCP).")

(defonce request-counter (atom 0))

(defn request-id!
  "Generates the next unique request ID."
  []
  (swap! request-counter inc))

(defn make-request
  "Creates a JSON-RPC 2.0 request map with method, params, and a unique ID."
  [method params]
  {:jsonrpc "2.0"
   :method method
   :params params
   :id (request-id!)})

(defn make-notification
  "Creates a JSON-RPC 2.0 notification (no ID) with method and params."
  [method params]
  {:jsonrpc "2.0"
   :method method
   :params params})

(defn encode-message
  "Converts a message map to JSON string."
  [msg]
  (js/JSON.stringify (clj->js msg)))

(defn parse-response
  "Parses a JSON-RPC response string.
   Returns:
   - {:id N :result data} for success
   - {:id N :error {:code N :message ... :data ...}} for error
   - {:parse-error true :raw json-str} for invalid JSON"
  [json-str]
  (try
    (let [obj (js/JSON.parse json-str)
          clj-obj (js->clj obj :keywordize-keys true)]
      (if (:error clj-obj)
        {:id (:id clj-obj)
         :error (:error clj-obj)}
        {:id (:id clj-obj)
         :result (:result clj-obj)}))
    (catch js/Error _
      {:parse-error true
       :raw json-str})))

;; MCP-specific message constructors

(defn initialize-request
  "Creates an MCP initialize request."
  [client-info capabilities]
  (make-request "initialize"
                {:protocolVersion "2025-03-26"
                 :capabilities capabilities
                 :clientInfo client-info}))

(defn initialized-notification
  "Creates an MCP initialized notification."
  []
  (make-notification "notifications/initialized" {}))

(defn tools-list-request
  "Creates an MCP tools/list request."
  []
  (make-request "tools/list" {}))

(defn tools-call-request
  "Creates an MCP tools/call request."
  [tool-name arguments]
  (make-request "tools/call"
                {:name tool-name
                 :arguments arguments}))

(defn resources-list-request
  "Creates an MCP resources/list request."
  []
  (make-request "resources/list" {}))

(defn resources-read-request
  "Creates an MCP resources/read request."
  [uri]
  (make-request "resources/read" {:uri uri}))

(defn prompts-list-request
  "Creates an MCP prompts/list request."
  []
  (make-request "prompts/list" {}))

(defn prompts-get-request
  "Creates an MCP prompts/get request."
  [name arguments]
  (make-request "prompts/get"
                {:name name
                 :arguments arguments}))
