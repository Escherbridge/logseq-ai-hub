(ns logseq-ai-hub.llm.tool-use
  "Multi-turn tool-calling LLM handler using OpenAI-compatible API.
   Converts MCP tool definitions to OpenAI format, handles the tool-call loop,
   and routes tool calls back to the correct MCP server."
  (:require [logseq-ai-hub.mcp.client :as mcp-client]
            [clojure.string :as str]))

(def ^:private max-tool-rounds 10)

;; ---------------------------------------------------------------------------
;; Tool Name Namespacing
;; ---------------------------------------------------------------------------

(defn namespace-tool-name
  "Creates a namespaced tool name: server-id__tool-name.
   The __ delimiter avoids collisions across MCP servers."
  [server-id tool-name]
  (str server-id "__" tool-name))

(defn parse-namespaced-tool-name
  "Splits 'server-id__tool-name' into [server-id tool-name].
   Returns [nil tool-name] if no delimiter found."
  [namespaced-name]
  (let [idx (str/index-of namespaced-name "__")]
    (if idx
      [(subs namespaced-name 0 idx)
       (subs namespaced-name (+ idx 2))]
      [nil namespaced-name])))

;; ---------------------------------------------------------------------------
;; OpenAI Tool Format Conversion
;; ---------------------------------------------------------------------------

(defn build-openai-tools
  "Converts MCP tool definitions into OpenAI-compatible tool format.
   server-tools is a vector of {:server-id \"x\" :tools [{:name :description :inputSchema}]}."
  [server-tools]
  (vec
    (for [{:keys [server-id tools]} server-tools
          tool tools]
      {:type "function"
       :function {:name (namespace-tool-name server-id (:name tool))
                  :description (or (:description tool) "")
                  :parameters (or (:inputSchema tool) {})}})))

;; ---------------------------------------------------------------------------
;; API Call
;; ---------------------------------------------------------------------------

(defn- make-api-call
  "Makes a single call to the chat/completions API.
   Returns Promise<parsed-response-map>."
  [messages openai-tools]
  (let [settings js/logseq.settings
        api-key (aget settings "llmApiKey")
        endpoint (aget settings "llmEndpoint")
        model-name (or (aget settings "llmModel") "anthropic/claude-sonnet-4")
        url (str (if (str/ends-with? endpoint "/")
                   (subs endpoint 0 (dec (count endpoint)))
                   endpoint)
                 "/chat/completions")
        body (cond-> {:model model-name
                      :messages messages}
               (seq openai-tools) (assoc :tools openai-tools))]
    (if (str/blank? api-key)
      (js/Promise.reject (js/Error. "LLM API Key is missing. Check Plugin Settings."))
      (-> (js/fetch url
            (clj->js {:method "POST"
                      :headers {"Content-Type" "application/json"
                                "Authorization" (str "Bearer " api-key)}
                      :body (js/JSON.stringify (clj->js body))}))
          (.then (fn [response]
                   (if (.-ok response)
                     (.json response)
                     (-> (.text response)
                         (.then (fn [body-text]
                                  (throw (js/Error. (str "API " (.-status response) ": " body-text)))))))))
          (.then (fn [data]
                   (js->clj data :keywordize-keys true)))))))

;; ---------------------------------------------------------------------------
;; Tool Execution
;; ---------------------------------------------------------------------------

(defn- execute-tool-call
  "Executes a single tool call via MCP.
   Returns Promise<{:tool-call-id :content}>."
  [tool-call]
  (let [call-id (:id tool-call)
        fn-info (:function tool-call)
        namespaced-name (:name fn-info)
        args-str (:arguments fn-info)
        [server-id tool-name] (parse-namespaced-tool-name namespaced-name)
        arguments (try
                    (js->clj (js/JSON.parse args-str) :keywordize-keys true)
                    (catch :default _ {}))]
    (if (nil? server-id)
      (js/Promise.resolve
        {:tool-call-id call-id
         :content (str "Error: Could not determine server for tool: " namespaced-name)})
      (-> (mcp-client/call-tool server-id tool-name (clj->js arguments))
          (.then (fn [result]
                   (let [content-parts (:content result)
                         text-content (if (sequential? content-parts)
                                        (str/join "\n"
                                          (map (fn [part]
                                                 (if (= "text" (:type part))
                                                   (:text part)
                                                   (js/JSON.stringify (clj->js part))))
                                               content-parts))
                                        (str result))]
                     {:tool-call-id call-id
                      :content text-content})))
          (.catch (fn [err]
                    {:tool-call-id call-id
                     :content (str "Error calling tool " tool-name ": "
                                   (if (instance? js/Error err) (.-message err) (str err)))}))))))

(defn- execute-all-tool-calls
  "Executes all tool calls in parallel. Returns Promise<vector-of-results>."
  [tool-calls]
  (-> (js/Promise.all
        (clj->js (mapv execute-tool-call tool-calls)))
      (.then (fn [results] (vec (js->clj results))))))

;; ---------------------------------------------------------------------------
;; Multi-Turn Loop
;; ---------------------------------------------------------------------------

(defn tool-use-loop
  "Runs the multi-turn tool-calling loop.
   - messages: message array (system + user + history)
   - openai-tools: tool definitions in OpenAI format
   - round: current iteration (starts at 0)
   Returns Promise<string> with the final text response."
  [messages openai-tools round]
  (if (>= round max-tool-rounds)
    (js/Promise.resolve "Error: Maximum tool-calling rounds exceeded.")
    (-> (make-api-call messages openai-tools)
        (.then (fn [data]
                 (let [choice (first (:choices data))
                       message (:message choice)
                       finish-reason (or (:finish_reason choice)
                                         (get choice :finish-reason))]
                   (if (= "tool_calls" finish-reason)
                     ;; LLM wants to call tools
                     (let [tool-calls (:tool_calls message)
                           ;; Append assistant message with tool calls to history
                           updated-messages (conj messages
                                             {:role "assistant"
                                              :content (:content message)
                                              :tool_calls tool-calls})]
                       (-> (execute-all-tool-calls tool-calls)
                           (.then (fn [results]
                                    ;; Append tool results to history
                                    (let [tool-messages (mapv (fn [{:keys [tool-call-id content]}]
                                                               {:role "tool"
                                                                :tool_call_id tool-call-id
                                                                :content content})
                                                             results)
                                          next-messages (into updated-messages tool-messages)]
                                      ;; Recurse
                                      (tool-use-loop next-messages openai-tools (inc round)))))))
                     ;; Final text response
                     (js/Promise.resolve (or (:content message) "")))))))))

;; ---------------------------------------------------------------------------
;; Public Entry Point
;; ---------------------------------------------------------------------------

(defn handle-tool-use-request
  "Entry point for tool-augmented LLM calls.

   Given a prompt, server-tools, and optional system prompt,
   runs the full multi-turn tool-use loop and returns Promise<string>.

   server-tools is vector of {:server-id :tools [...]}."
  ([prompt server-tools]
   (handle-tool-use-request prompt server-tools nil))
  ([prompt server-tools system-prompt]
   (let [openai-tools (build-openai-tools server-tools)
         messages (cond-> []
                    (not (str/blank? system-prompt))
                    (conj {:role "system" :content system-prompt})
                    true
                    (conj {:role "user" :content prompt}))]
     (tool-use-loop messages openai-tools 0))))
