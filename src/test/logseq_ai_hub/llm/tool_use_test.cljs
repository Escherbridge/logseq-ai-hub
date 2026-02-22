(ns logseq-ai-hub.llm.tool-use-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.llm.tool-use :as tool-use]
            [logseq-ai-hub.mcp.client :as mcp-client]))

;; ---------------------------------------------------------------------------
;; Pure function tests
;; ---------------------------------------------------------------------------

(deftest namespace-tool-name-test
  (testing "creates namespaced tool name"
    (is (= "brave__web_search" (tool-use/namespace-tool-name "brave" "web_search")))
    (is (= "github__list_repos" (tool-use/namespace-tool-name "github" "list_repos")))))

(deftest parse-namespaced-tool-name-test
  (testing "splits namespaced tool name"
    (is (= ["brave" "web_search"] (tool-use/parse-namespaced-tool-name "brave__web_search")))
    (is (= ["github" "list_repos"] (tool-use/parse-namespaced-tool-name "github__list_repos"))))

  (testing "handles missing delimiter"
    (is (= [nil "plain_tool"] (tool-use/parse-namespaced-tool-name "plain_tool")))))

(deftest namespace-roundtrip-test
  (testing "namespace/parse round-trips correctly"
    (let [server "my-server"
          tool "my-tool"
          namespaced (tool-use/namespace-tool-name server tool)
          [parsed-server parsed-tool] (tool-use/parse-namespaced-tool-name namespaced)]
      (is (= server parsed-server))
      (is (= tool parsed-tool)))))

(deftest build-openai-tools-test
  (testing "converts MCP tools to OpenAI format"
    (let [server-tools [{:server-id "brave"
                         :tools [{:name "web_search"
                                  :description "Search the web"
                                  :inputSchema {:type "object"
                                                :properties {:query {:type "string"}}}}]}]
          result (tool-use/build-openai-tools server-tools)]
      (is (= 1 (count result)))
      (is (= "function" (:type (first result))))
      (is (= "brave__web_search" (get-in (first result) [:function :name])))
      (is (= "Search the web" (get-in (first result) [:function :description])))
      (is (= {:type "object" :properties {:query {:type "string"}}}
             (get-in (first result) [:function :parameters]))))))

(deftest build-openai-tools-multiple-servers
  (testing "handles multiple servers with multiple tools"
    (let [server-tools [{:server-id "a" :tools [{:name "t1" :description ""}]}
                        {:server-id "b" :tools [{:name "t2" :description ""} {:name "t3" :description ""}]}]
          result (tool-use/build-openai-tools server-tools)]
      (is (= 3 (count result)))
      (is (= "a__t1" (get-in (first result) [:function :name])))
      (is (= "b__t2" (get-in (second result) [:function :name])))
      (is (= "b__t3" (get-in (nth result 2) [:function :name]))))))

(deftest build-openai-tools-empty
  (testing "returns empty for no tools"
    (is (= [] (tool-use/build-openai-tools [])))
    (is (= [] (tool-use/build-openai-tools [{:server-id "x" :tools []}])))))

;; ---------------------------------------------------------------------------
;; Integration tests with mocked fetch
;; ---------------------------------------------------------------------------

(defn- setup-logseq-settings! []
  (set! js/logseq
        #js {:settings #js {:llmApiKey "test-key"
                            :llmEndpoint "https://api.test.com/v1"
                            :llmModel "test-model"}}))

(defn- mock-fetch-responses!
  "Installs a fetch mock that returns responses sequentially.
   responses is a vector of response data maps."
  [responses]
  (let [call-count (atom 0)]
    (set! js/fetch
          (fn [_url _opts]
            (let [idx @call-count
                  resp (get responses idx (last responses))]
              (swap! call-count inc)
              (js/Promise.resolve
                #js {:ok true
                     :json (fn []
                             (js/Promise.resolve (clj->js resp)))}))))
    call-count))

(deftest handle-tool-use-no-tools-test
  (testing "LLM returns text immediately when finish_reason is stop"
    (async done
      (setup-logseq-settings!)
      (mock-fetch-responses!
        [{:choices [{:message {:role "assistant" :content "Hello!"}
                     :finish_reason "stop"}]}])
      (-> (tool-use/handle-tool-use-request "Hi" [])
          (.then (fn [result]
                   (is (= "Hello!" result))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Error: " (.-message err)))
                    (done)))))))

(deftest handle-tool-use-single-turn-test
  (testing "LLM calls a tool, gets result, then returns text"
    (async done
      (setup-logseq-settings!)

      ;; Mock MCP client
      (reset! mcp-client/servers
              {"brave" {:id "brave" :status :connected
                        :transport {:send! (fn [message]
                                             (js/Promise.resolve
                                               {:id (:id message)
                                                :result {:content [{:type "text"
                                                                    :text "Search results here"}]}}))}}})

      ;; First call: LLM wants to use a tool
      ;; Second call: LLM returns final text
      (mock-fetch-responses!
        [{:choices [{:message {:role "assistant"
                               :content nil
                               :tool_calls [{:id "call_1"
                                             :type "function"
                                             :function {:name "brave__web_search"
                                                        :arguments "{\"query\":\"news\"}"}}]}
                     :finish_reason "tool_calls"}]}
         {:choices [{:message {:role "assistant" :content "Based on the search, here are the results."}
                     :finish_reason "stop"}]}])

      (let [server-tools [{:server-id "brave"
                           :tools [{:name "web_search"
                                    :description "Search"
                                    :inputSchema {}}]}]]
        (-> (tool-use/handle-tool-use-request "Find news" server-tools)
            (.then (fn [result]
                     (is (= "Based on the search, here are the results." result))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Error: " (.-message err)))
                      (done))))))))

(deftest handle-tool-use-with-system-prompt
  (testing "includes system prompt in messages"
    (async done
      (setup-logseq-settings!)
      (let [captured-body (atom nil)]
        (set! js/fetch
              (fn [_url opts]
                (let [body (js->clj (js/JSON.parse (.-body opts)) :keywordize-keys true)]
                  (reset! captured-body body))
                (js/Promise.resolve
                  #js {:ok true
                       :json (fn []
                               (js/Promise.resolve
                                 (clj->js {:choices [{:message {:role "assistant"
                                                                :content "response"}
                                                      :finish_reason "stop"}]})))})))
        (-> (tool-use/handle-tool-use-request "query" [] "You are helpful")
            (.then (fn [_]
                     (let [messages (:messages @captured-body)]
                       (is (= 2 (count messages)))
                       (is (= "system" (:role (first messages))))
                       (is (= "You are helpful" (:content (first messages))))
                       (is (= "user" (:role (second messages)))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Error: " (.-message err)))
                      (done))))))))
