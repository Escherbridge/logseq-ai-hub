(ns logseq-ai-hub.core-llm-command-test
  "Integration tests for the real /LLM slash-command seam: core/handle-llm-command.
   Only the process edges are faked: js/logseq (host API) and js/fetch (provider).
   arg-parser, enriched/call, agent/process-input, and the registered llm-handler
   all run for real, exactly as they do when a user fires the slash command.

   Several tests below assert DESIRED behaviour that production code does not yet
   implement (see each docstring tagged DESIRED) -- they are expected to fail until
   the corresponding fix lands; that failure is the point."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as str]
            [logseq-ai-hub.core :as core]
            [logseq-ai-hub.memory :as memory]
            [logseq-ai-hub.mcp.on-demand :as on-demand]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- install-logseq!
  "Installs js/logseq so getBlock resolves to block-content and insertBlock
   records every call. Returns an atom of recorded {:uuid :content} inserts."
  [block-content settings-map]
  (let [inserted (atom [])]
    (set! js/logseq
      #js {:Editor #js {:getBlock (fn [uuid]
                                     (js/Promise.resolve #js {:uuid uuid :content block-content}))
                        :insertBlock (fn [uuid content]
                                       (swap! inserted conj {:uuid uuid :content content})
                                       (js/Promise.resolve #js {:uuid "inserted-uuid"}))
                        :getPageBlocksTree (fn [_] (js/Promise.resolve nil))}
           :settings (clj->js settings-map)})
    inserted))

(defn- with-fetch!
  "Installs a js/fetch stub that records every call (parsed URL/headers/body)
   and delegates the response to handler-fn. Returns [calls-atom restore!]."
  [handler-fn]
  (let [calls (atom [])
        orig-fetch js/fetch]
    (set! js/fetch
      (fn [url opts]
        (swap! calls conj {:url url
                           :headers (js->clj (aget opts "headers"))
                           :body (js->clj (js/JSON.parse (aget opts "body")) :keywordize-keys true)})
        (handler-fn url opts)))
    [calls (fn [] (set! js/fetch orig-fetch))]))

(defn- last-inserted-text [inserted]
  (:content (last @inserted)))

(defn- json-response [body-map]
  #js {:ok true :json (fn [] (js/Promise.resolve (clj->js body-map)))})

;; ---------------------------------------------------------------------------
;; Happy path -- this is the actual entry point a user triggers
;; ---------------------------------------------------------------------------

(deftest test-llm-command-happy-path-sends-real-request-and-inserts-reply
  (testing "slash-command fires -> getBlock -> real fetch with trimmed Bearer key, model, and prompt -> reply inserted via insertBlock"
    (async done
      (let [inserted (install-logseq! "What is the weather today?"
                       {"llmApiKey" " sk-test-123 "
                        "llmEndpoint" "https://example.test/v1"
                        "llmModel" "test-model"})
            [calls restore!]
            (with-fetch!
              (fn [_url _opts]
                (js/Promise.resolve
                  (json-response {:choices [{:message {:content "The weather is sunny today."}}]}))))]
        (-> (core/handle-llm-command #js {:uuid "block-1"})
            (.then (fn [_]
                     (restore!)
                     (let [{:keys [url headers body]} (first @calls)]
                       (is (= "https://example.test/v1/chat/completions" url))
                       (is (= "Bearer sk-test-123" (get headers "Authorization"))
                           "a pasted key's surrounding whitespace must not leak into the header")
                       (is (= "test-model" (:model body)))
                       (is (= "What is the weather today?" (-> body :messages last :content))
                           "the user's prompt must reach the provider request body"))
                     (is (= "The weather is sunny today." (last-inserted-text inserted))
                         "the model's reply must be inserted back into the graph")
                     (done)))
            (.catch (fn [err]
                      (restore!)
                      (is false (str "handler rejected: " err))
                      (done))))))))

(deftest test-llm-command-missing-key-skips-provider-and-inserts-guidance
  (testing "no llmApiKey configured -> provider is never called; a 'missing key' block is inserted instead"
    (async done
      (let [inserted (install-logseq! "Hello there" {"llmEndpoint" "https://example.test/v1"})
            [calls restore!] (with-fetch! (fn [_ _] (js/Promise.resolve #js {:ok true})))]
        (-> (core/handle-llm-command #js {:uuid "block-2"})
            (.then (fn [_]
                     (restore!)
                     (is (zero? (count @calls)) "the provider must not be called without a key")
                     (is (re-find #"(?i)API Key is missing" (last-inserted-text inserted)))
                     (done)))
            (.catch (fn [err] (restore!) (is false (str "handler rejected: " err)) (done))))))))

;; ---------------------------------------------------------------------------
;; DESIRED behaviour -- reproduced against the live provider; tests define
;; the contract the production fix must satisfy. See notepad/issues for the
;; live-provider evidence (empty-input 400, reasoning-model truncation).
;; ---------------------------------------------------------------------------

(deftest test-llm-command-blank-block-content-skips-provider
  (testing "DESIRED: whitespace-only block content never reaches the provider; the user gets actionable guidance, not an API error"
    (async done
      (let [inserted (install-logseq! "   "
                       {"llmApiKey" "sk-test-123"
                        "llmEndpoint" "https://example.test/v1"
                        "llmModel" "test-model"})
            [calls restore!] (with-fetch! (fn [_ _] (js/Promise.resolve #js {:ok true})))]
        (-> (core/handle-llm-command #js {:uuid "block-3"})
            (.then (fn [_]
                     (restore!)
                     (is (zero? (count @calls))
                         "an empty prompt must not be sent to the provider")
                     (is (re-find #"(?i)prompt" (last-inserted-text inserted))
                         "guidance should point the user at typing a prompt, not a raw API error")
                     (done)))
            (.catch (fn [err] (restore!) (is false (str "handler rejected: " err)) (done))))))))

(deftest test-llm-command-shows-reasoning-when-content-blank
  (testing "DESIRED: choices[0].message.content blank but .reasoning non-blank -> the user sees the reasoning, not an empty-response error"
    (async done
      (let [inserted (install-logseq! "Solve this step by step"
                       {"llmApiKey" "sk-test-123"
                        "llmEndpoint" "https://example.test/v1"
                        "llmModel" "deepseek/deepseek-v4-pro"})
            [_calls restore!]
            (with-fetch!
              (fn [_ _]
                (js/Promise.resolve
                  (json-response
                    {:choices [{:finish_reason "stop"
                               :message {:content nil
                                        :reasoning "Step 1: break the problem down. Step 2: combine results."
                                        :role "assistant"}}]}))))]
        (-> (core/handle-llm-command #js {:uuid "block-4"})
            (.then (fn [_]
                     (restore!)
                     (is (str/includes? (last-inserted-text inserted)
                                         "Step 1: break the problem down.")
                         "reasoning content must reach the user when content is blank")
                     (done)))
            (.catch (fn [err] (restore!) (is false (str "handler rejected: " err)) (done))))))))

(deftest test-llm-command-indicates-truncated-reply-on-length-finish-reason
  (testing "DESIRED: finish_reason='length' tells the user the reply was cut off, alongside whatever content was produced"
    (async done
      (let [inserted (install-logseq! "Write a long essay"
                       {"llmApiKey" "sk-test-123"
                        "llmEndpoint" "https://example.test/v1"
                        "llmModel" "test-model"})
            [_calls restore!]
            (with-fetch!
              (fn [_ _]
                (js/Promise.resolve
                  (json-response
                    {:choices [{:finish_reason "length"
                               :message {:content "Here is the beginning of my essay but"
                                        :role "assistant"}}]}))))]
        (-> (core/handle-llm-command #js {:uuid "block-5"})
            (.then (fn [_]
                     (restore!)
                     (let [text (last-inserted-text inserted)]
                       (is (str/includes? text "Here is the beginning of my essay but")
                           "the partial content the model did produce must still be shown")
                       (is (re-find #"(?i)(cut off|truncated)" text)
                           "the user must be told the reply was truncated"))
                     (done)))
            (.catch (fn [err] (restore!) (is false (str "handler rejected: " err)) (done))))))))

(deftest test-llm-command-names-likely-cause-when-content-and-reasoning-both-blank
  (testing "DESIRED: content AND reasoning both blank is still an error, but it must name a likely cause instead of the generic 'Empty response from model.'"
    (async done
      (let [inserted (install-logseq! "Hello"
                       {"llmApiKey" "sk-test-123"
                        "llmEndpoint" "https://example.test/v1"
                        "llmModel" "test-model"})
            [_calls restore!]
            (with-fetch!
              (fn [_ _]
                (js/Promise.resolve
                  (json-response
                    {:choices [{:finish_reason "stop"
                               :message {:content nil :reasoning nil :role "assistant"}}]}))))]
        (-> (core/handle-llm-command #js {:uuid "block-6"})
            (.then (fn [_]
                     (restore!)
                     (let [text (last-inserted-text inserted)]
                       (is (not= text "Error: Empty response from model.")
                           "the generic placeholder alone is not an acceptable final message")
                       (is (re-find #"(?i)no usable content" text)
                           "the error must name the likely cause (no usable content), not just report emptiness"))
                     (done)))
            (.catch (fn [err] (restore!) (is false (str "handler rejected: " err)) (done))))))))

(deftest test-llm-command-surfaces-provider-error-message-not-raw-json
  (testing "DESIRED: a non-2xx provider response surfaces error.message to the user, never the raw JSON envelope"
    (async done
      (let [inserted (install-logseq! "Hello"
                       {"llmApiKey" "sk-test-123"
                        "llmEndpoint" "https://example.test/v1"
                        "llmModel" "test-model"})
            [_calls restore!]
            (with-fetch!
              (fn [_ _]
                (js/Promise.resolve
                  #js {:ok false
                       :status 400
                       :text (fn [] (js/Promise.resolve
                                      (js/JSON.stringify
                                        (clj->js {:error {:message "Input must have at least 1 token."
                                                          :code 400}}))))})))]
        (-> (core/handle-llm-command #js {:uuid "block-7"})
            (.then (fn [_]
                     (restore!)
                     (let [text (last-inserted-text inserted)]
                       (is (str/includes? text "Input must have at least 1 token.")
                           "the provider's actual error message must reach the user")
                       (is (not (str/includes? text "{\"error\""))
                           "the raw JSON envelope must not be dumped into the user's graph"))
                     (done)))
            (.catch (fn [err] (restore!) (is false (str "handler rejected: " err)) (done))))))))

(deftest test-llm-command-empty-choices-array-handled-cleanly
  (testing "DESIRED: an empty choices array must not surface a raw JS crash message; it gets a clean, intentional error instead"
    (async done
      (let [inserted (install-logseq! "Hello"
                       {"llmApiKey" "sk-test-123"
                        "llmEndpoint" "https://example.test/v1"
                        "llmModel" "test-model"})
            [_calls restore!]
            (with-fetch!
              (fn [_ _]
                (js/Promise.resolve (json-response {:choices []}))))]
        (-> (core/handle-llm-command #js {:uuid "block-8"})
            (.then (fn [_]
                     (restore!)
                     (let [text (last-inserted-text inserted)]
                       (is (not (re-find #"(?i)(cannot read|undefined|typeerror)" text))
                           "an accidental JS crash message must never leak into the user's graph")
                       (is (re-find #"(?i)(no (response|choices|reply)|error)" text)
                           "a clean, intentional error block should be inserted instead"))
                     (done)))
            (.catch (fn [err] (restore!) (is false (str "handler rejected: " err)) (done))))))))

;; ---------------------------------------------------------------------------
;; Enriched path: [[AI-Memory/x]] + [[MCP/y]] refs reach tool-use with a
;; composed request. Fakes only the MCP bridge edge (on-demand) and the
;; provider (fetch); arg-parser, memory-context (real memory/state +
;; getPageBlocksTree), and tool-use all run for real.
;; ---------------------------------------------------------------------------

(deftest test-llm-command-with-memory-and-mcp-refs-reaches-tool-use-with-composed-request
  (testing "memory + MCP refs compose a system prompt (real memory content) and a namespaced tool list (MCP bridge), both visible in the actual outbound request"
    (async done
      (reset! memory/state {:config {:page-prefix "AI-Memory/" :enabled true} :index {}})
      (let [orig-connect on-demand/connect-servers-from-refs!
            orig-collect on-demand/collect-tools
            inserted (atom [])]
        (set! js/logseq
          #js {:Editor #js {:getBlock (fn [uuid]
                                         (js/Promise.resolve
                                           #js {:uuid uuid
                                                :content "[[AI-Memory/notes]] [[MCP/search]] find stuff"}))
                            :insertBlock (fn [uuid content]
                                           (swap! inserted conj {:uuid uuid :content content})
                                           (js/Promise.resolve #js {:uuid "inserted-uuid"}))
                            :getPageBlocksTree (fn [page-name]
                                                  (if (= page-name "AI-Memory/notes")
                                                    (js/Promise.resolve
                                                      (clj->js [{:content "Remember to use tabs, not spaces"}]))
                                                    (js/Promise.resolve nil)))}
               :settings #js {"llmApiKey" "sk-test-123"
                              "llmEndpoint" "https://example.test/v1"
                              "llmModel" "test-model"}})
        ;; Fake the MCP bridge edge -- real MCP transport is out of scope here.
        (set! on-demand/connect-servers-from-refs!
              (fn [_page-names] (js/Promise.resolve ["search-server"])))
        (set! on-demand/collect-tools
              (fn [_server-ids]
                (js/Promise.resolve
                  [{:server-id "search-server"
                    :tools [{:name "web_search"
                            :description "Searches the web"
                            :inputSchema {:type "object"}}]}])))
        (let [[calls restore-fetch!]
              (with-fetch!
                (fn [_ _]
                  (js/Promise.resolve
                    (json-response
                      {:choices [{:finish_reason "stop"
                                 :message {:content "search result summary"}}]}))))
              restore-on-demand! (fn []
                                   (set! on-demand/connect-servers-from-refs! orig-connect)
                                   (set! on-demand/collect-tools orig-collect))]
          (-> (core/handle-llm-command #js {:uuid "block-9"})
              (.then (fn [_]
                       (restore-fetch!)
                       (restore-on-demand!)
                       (let [{:keys [body]} (first @calls)
                             system-message (first (filter #(= "system" (:role %)) (:messages body)))
                             tools (:tools body)]
                         (is (some? system-message)
                             "a system prompt carrying memory context must be composed")
                         (is (str/includes? (:content system-message) "Remember to use tabs, not spaces")
                             "real memory content resolved from AI-Memory/notes must reach the request")
                         (is (= ["search-server__web_search"]
                                (mapv #(get-in % [:function :name]) tools))
                             "MCP tools must be namespaced server__tool and included in the request"))
                       (is (= "search result summary" (:content (last @inserted)))
                           "the tool-use result must be inserted back into the graph")
                       (done)))
              (.catch (fn [err]
                        (restore-fetch!)
                        (restore-on-demand!)
                        (is false (str "Unexpected error: " err))
                        (done)))))))))
