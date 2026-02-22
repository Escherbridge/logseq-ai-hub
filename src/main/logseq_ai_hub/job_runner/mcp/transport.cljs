(ns logseq-ai-hub.job-runner.mcp.transport
  "MCP transport implementations: Streamable HTTP and SSE."
  (:require [logseq-ai-hub.job-runner.mcp.protocol :as protocol]
            [logseq-ai-hub.util.errors :as errors]))

(defn- parse-sse-stream
  "Parses SSE stream from response body reader and extracts final JSON-RPC response."
  [reader]
  (js/Promise.
   (fn [resolve reject]
     (let [decoder (js/TextDecoder.)
           buffer (atom "")]
       ((fn read-chunk []
          (-> (.read reader)
              (.then (fn [result]
                       (if (.-done result)
                         ;; Stream complete - parse accumulated data
                         (let [lines (clojure.string/split @buffer #"\n")
                               data-lines (filter #(clojure.string/starts-with? % "data: ") lines)]
                           (if (seq data-lines)
                             (let [last-data (subs (last data-lines) 6) ; Remove "data: " prefix
                                   parsed (protocol/parse-response last-data)]
                               (resolve parsed))
                             (reject (js/Error. "No data found in SSE stream"))))
                         ;; More chunks to read
                         (do
                           (swap! buffer str (.decode decoder (.-value result)))
                           (read-chunk)))))
              (.catch reject))))))))

(defn make-http-transport
  "Creates a streamable HTTP transport for MCP.

   Returns a transport map with:
   - :type :streamable-http
   - :url the endpoint URL
   - :session-id (atom) for MCP session tracking
   - :send! function that POSTs message and returns Promise<response>
   - :close! function (no-op for HTTP)"
  [url auth-token]
  (let [session-id (atom nil)]
    {:type :streamable-http
     :url url
     :session-id session-id
     :send! (fn [message]
              (let [headers (cond-> {"Content-Type" "application/json"
                                     "Accept" "application/json, text/event-stream"}
                              auth-token (assoc "Authorization" (str "Bearer " auth-token))
                              @session-id (assoc "Mcp-Session-Id" @session-id))
                    body (protocol/encode-message message)
                    opts {:method "POST"
                          :headers (clj->js headers)
                          :body body}]
                (-> (js/fetch url (clj->js opts))
                    (.then (fn [response]
                             ;; Store session ID if present
                             (when-let [sid (.get (.-headers response) "Mcp-Session-Id")]
                               (reset! session-id sid))

                             (let [content-type (.get (.-headers response) "Content-Type")]
                               (cond
                                 ;; JSON response
                                 (and content-type
                                      (clojure.string/includes? content-type "application/json"))
                                 (-> (.text response)
                                     (.then protocol/parse-response))

                                 ;; SSE stream response
                                 (and content-type
                                      (clojure.string/includes? content-type "text/event-stream"))
                                 (let [reader (.getReader (.-body response))]
                                   (parse-sse-stream reader))

                                 ;; Unknown content type
                                 :else
                                 (js/Promise.reject
                                  (js/Error. (str "Unexpected Content-Type: " content-type)))))))
                    (.catch (fn [err]
                              (js/Promise.reject
                               (errors/make-error
                                :mcp-transport-error
                                (str "HTTP transport error: " (.-message err))
                                {:url url :error err})))))))
     :close! (fn [] nil)}))

(defn make-sse-transport
  "Creates an SSE (Server-Sent Events) transport for MCP.

   Returns a transport map with:
   - :type :sse
   - :url the SSE endpoint URL
   - :event-source (atom) holding the EventSource instance
   - :post-url (atom) discovered from 'endpoint' event
   - :pending-requests (atom) mapping request ID -> {:resolve fn :reject fn}
   - :connect! function to establish SSE connection
   - :send! function that POSTs message and returns Promise
   - :close! function to close EventSource"
  [url auth-token]
  (let [event-source (atom nil)
        post-url (atom nil)
        pending-requests (atom {})]
    {:type :sse
     :url url
     :event-source event-source
     :post-url post-url
     :pending-requests pending-requests
     :connect! (fn []
                 (let [es (js/EventSource. url)]
                   ;; Listen for endpoint discovery
                   (.addEventListener es "endpoint"
                                      (fn [event]
                                        (reset! post-url (.-data event))))

                   ;; Listen for message responses
                   (.addEventListener es "message"
                                      (fn [event]
                                        (let [parsed (protocol/parse-response (.-data event))
                                              request-id (:id parsed)]
                                          (when-let [handlers (get @pending-requests request-id)]
                                            (if (:error parsed)
                                              ((:reject handlers)
                                               (errors/make-error
                                                :mcp-rpc-error
                                                (get-in parsed [:error :message])
                                                {:error (:error parsed)}))
                                              ((:resolve handlers) parsed))
                                            (swap! pending-requests dissoc request-id)))))

                   (reset! event-source es)))
     :send! (fn [message]
              (js/Promise.
               (fn [resolve reject]
                 (if-not @post-url
                   (reject (js/Error. "SSE transport not connected - call connect! first"))

                 (let [request-id (:id message)
                       headers (cond-> {"Content-Type" "application/json"}
                                 auth-token (assoc "Authorization" (str "Bearer " auth-token)))
                       body (protocol/encode-message message)
                       opts {:method "POST"
                             :headers (clj->js headers)
                             :body body}]

                   ;; Store promise handlers for this request ID
                   (swap! pending-requests assoc request-id {:resolve resolve :reject reject})

                   ;; Send the request
                   (-> (js/fetch @post-url (clj->js opts))
                       (.catch (fn [err]
                                 (swap! pending-requests dissoc request-id)
                                 (reject (errors/make-error
                                          :mcp-transport-error
                                          (str "SSE POST error: " (.-message err))
                                          {:url @post-url :error err}))))))))))
     :close! (fn []
               (when @event-source
                 (.close @event-source)
                 (reset! event-source nil)))}))

(defn make-transport
  "Auto-detects and creates the appropriate transport based on config.

   Config map:
   - :transport (optional) - :streamable-http or :sse
   - :url - endpoint URL
   - :auth-token (optional) - bearer token

   Defaults to :streamable-http if not specified."
  [config]
  (case (:transport config)
    :streamable-http (make-http-transport (:url config) (:auth-token config))
    :sse (make-sse-transport (:url config) (:auth-token config))
    ;; Default to streamable HTTP
    (make-http-transport (:url config) (:auth-token config))))
