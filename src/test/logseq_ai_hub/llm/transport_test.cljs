(ns logseq-ai-hub.llm.transport-test
  "Drives the real /LLM seam (core/handle-llm-command) under each llmTransport.
   Only the process edges are faked: js/logseq (editor + logseq.Request host API)
   and js/fetch (provider / AI Hub server)."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [clojure.string :as str]
            [logseq-ai-hub.core :as core]
            [logseq-ai-hub.doctor :as doctor]
            [logseq-ai-hub.secrets :as secrets]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private saved-globals (atom nil))

;; These tests replace js/logseq and js/fetch; put the originals back so later namespaces never inherit them.
(use-fixtures :each
  {:before (fn [] (reset! saved-globals {:logseq (aget js/globalThis "logseq")
                                          :fetch (aget js/globalThis "fetch")}))
   :after (fn []
            (aset js/globalThis "logseq" (:logseq @saved-globals))
            (aset js/globalThis "fetch" (:fetch @saved-globals))
            (secrets/reset-vault!))})

(def ^:private reply-payload
  {:choices [{:message {:content "Gateway reply."} :finish_reason "stop"}]})

(def ^:private reply-json (js/JSON.stringify (clj->js reply-payload)))

(defn- install-logseq!
  "Installs js/logseq with the given settings and optional logseq.Request client.
   Returns an atom of inserted block texts."
  [settings request-client]
  (let [inserted (atom [])]
    (set! js/logseq
      #js {:settings (clj->js settings)
           :Editor #js {:getBlock (fn [uuid] (js/Promise.resolve #js {:uuid uuid :content "Summarise this"}))
                        :insertBlock (fn [_ content]
                                       (swap! inserted conj content)
                                       (js/Promise.resolve #js {:uuid "inserted"}))
                        :getPageBlocksTree (fn [_] (js/Promise.resolve nil))}})
    (when request-client (aset js/logseq "Request" request-client))
    inserted))

(defn- fake-host
  "logseq.Request modelled on Logseq's :httpRequest handler: returnType defaults to json and
   is honoured; a :modern host honours includeResponse, a :legacy (0.10) host returns only the
   body. Records each options object. Returns [client calls]."
  [host status body-text]
  (let [calls (atom [])]
    [#js {:_request (fn [opts]
                      (swap! calls conj {:data-is-object? (object? (aget opts "data"))
                                         :opts (js->clj opts)})
                      (let [payload (if (= "text" (or (aget opts "returnType") "json"))
                                      body-text
                                      (js/JSON.parse body-text))]
                        (js/Promise.resolve
                          (if (and (= :modern host) (aget opts "includeResponse"))
                            #js {:status status :ok (<= 200 status 299) :body payload}
                            payload))))}
     calls]))

(defn- with-fetch!
  "Replaces js/fetch with handler-fn, recording {:url :headers :body}. Returns [calls restore!]."
  [handler-fn]
  (let [calls (atom [])
        orig js/fetch]
    (set! js/fetch
      (fn [url opts]
        ;; opts is undefined for a bare fetch(url), e.g. the doctor's /health probe.
        (swap! calls conj {:url url
                           :headers (some-> opts (aget "headers") js->clj)
                           :body (some-> opts (aget "body") js/JSON.parse (js->clj :keywordize-keys true))})
        (handler-fn url opts)))
    [calls (fn [] (set! js/fetch orig))]))

(defn- response [status payload]
  #js {:ok (<= 200 status 299) :status status
       :json (fn [] (js/Promise.resolve (clj->js payload)))
       :text (fn [] (js/Promise.resolve (js/JSON.stringify (clj->js payload))))})

(defn- run-llm! [] (core/handle-llm-command #js {:uuid "block-1"}))

(defn- fail! [restore! done]
  (fn [err] (restore!) (secrets/reset-vault!) (is false (str "rejected: " err)) (done)))

(defn- run-cases!
  "Runs (run-case! case) for each case in sequence; resolves when all are done."
  [run-case! cases]
  (reduce (fn [chain c] (.then chain (fn [_] (run-case! c))))
          (js/Promise.resolve nil)
          cases))

;; ---------------------------------------------------------------------------
;; logseq-desktop: the host makes the call, so CORS never applies
;; ---------------------------------------------------------------------------

(deftest test-logseq-desktop-sends-through-host-and-never-touches-fetch
  (testing "the request goes to logseq.Request with an object body; js/fetch is not used"
    (async done
      (let [[client calls] (fake-host :modern 200 reply-json)
            inserted (install-logseq! {"llmTransport" "logseq-desktop"
                                       "llmApiKey" "sk-org-1"
                                       "llmEndpoint" "https://gateway.corp.test/v1/"
                                       "llmModel" "corp/model"}
                                      client)
            [fetch-calls restore!] (with-fetch! (fn [_ _] (js/Promise.reject (js/Error. "fetch used"))))]
        (-> (run-llm!)
            (.then (fn [_]
                     (restore!)
                     (let [{:keys [opts data-is-object?]} (first @calls)]
                       (is (= "https://gateway.corp.test/v1/chat/completions" (get opts "url")))
                       (is (= "POST" (get opts "method")))
                       (is (= "Bearer sk-org-1" (get-in opts ["headers" "Authorization"])))
                       (is (= "application/json" (get-in opts ["headers" "Content-Type"])))
                       (is data-is-object?
                           "Logseq 0.10 JSON.stringifies `data` itself; a string would be double-encoded")
                       (is (= "corp/model" (get-in opts ["data" "model"])))
                       (is (= "Summarise this" (-> (get-in opts ["data" "messages"]) last (get "content")))))
                     (is (empty? @fetch-calls) "the CORS-bound browser fetch must not be used")
                     (is (= "Gateway reply." (last @inserted)))
                     (done)))
            (.catch (fail! restore! done)))))))

(deftest test-logseq-desktop-reports-outcomes-on-both-host-versions
  (testing "a modern host reports status; Logseq 0.10 resolves only the body, so success needs `choices`"
    (let [cases [{:label "0.10: success body -> reply inserted"
                  :host :legacy :status 200 :body reply-json
                  :expect #(= "Gateway reply." %)}
                 {:label "0.10: OpenAI error envelope -> provider message, status flagged unknown"
                  :host :legacy :status 401 :body "{\"error\":{\"message\":\"Invalid subscription key\"}}"
                  :expect #(and (str/includes? % "Invalid subscription key")
                                (str/includes? % "HTTP status not reported"))}
                 {:label "0.10: gateway error without an `error` key (Kong/APIM) is not a success"
                  :host :legacy :status 401 :body "{\"message\":\"Unauthorized\"}"
                  :expect #(and (str/includes? % "Unauthorized")
                                (not (str/includes? % "no choices")))}
                 {:label "0.10: HTML proxy page -> snippet, not a JSON parse crash"
                  :host :legacy :status 502 :body "<html>502 Bad Gateway</html>"
                  :expect #(str/includes? % "502 Bad Gateway")}
                 {:label "modern: non-2xx carries its real status"
                  :host :modern :status 403 :body "{\"detail\":\"Model not allowed\"}"
                  :expect #(and (str/includes? % "Model not allowed") (str/includes? % "HTTP 403"))}]]
      (async done
        (let [[_ restore!] (with-fetch! (fn [_ _] (js/Promise.reject (js/Error. "fetch used"))))]
          (-> (run-cases!
                (fn [{:keys [label host status body expect]}]
                  (let [[client] (fake-host host status body)
                        inserted (install-logseq! {"llmTransport" "logseq-desktop"
                                                   "llmApiKey" "sk-org-1"
                                                   "llmEndpoint" "https://gateway.corp.test/v1"}
                                                  client)]
                    (-> (run-llm!)
                        (.then (fn [_] (is (expect (last @inserted))
                                           (str label ": got " (last @inserted))))))))
                cases)
              (.then (fn [_] (restore!) (done)))
              (.catch (fail! restore! done))))))))

(deftest test-logseq-desktop-host-error-never-carries-the-key-into-the-graph
  (testing "a host fetch error quoting a header value is redacted before it becomes block text"
    (async done
      (let [client #js {:_request (fn [_]
                                    (js/Promise.reject
                                      (js/TypeError. "Headers.append: \"Bearer sk-org-secret-1\" is an invalid header value.")))}
            inserted (install-logseq! {"llmTransport" "logseq-desktop"
                                       "llmApiKey" "sk-org-secret-1"
                                       "llmEndpoint" "https://gateway.corp.test/v1"}
                                      client)]
        (-> (run-llm!)
            (.then (fn [_]
                     (is (not (str/includes? (last @inserted) "sk-org-secret-1")))
                     (is (str/includes? (last @inserted) "[redacted]"))
                     (done)))
            (.catch (fn [err] (is false (str "rejected: " err)) (done))))))))

;; ---------------------------------------------------------------------------
;; hub-server: the AI Hub server relays; org credentials stay server-side
;; ---------------------------------------------------------------------------

(deftest test-hub-server-relays-with-plugin-token-and-never-sends-provider-key
  (testing "the request goes to <server>/api/llm with the plugin token; the local key and extra headers stay local"
    (async done
      (let [inserted (install-logseq! {"llmTransport" "hub-server"
                                       "webhookServerUrl" "https://hub.corp.test/"
                                       "authMode" "token"
                                       "pluginApiToken" "plugin-tok"
                                       "llmApiKey" "sk-personal-should-not-leak"
                                       "llmExtraHeaders" "{\"api-key\":\"local-only\"}"
                                       "llmModel" "corp/model"}
                                      nil)
            [calls restore!] (with-fetch! (fn [_ _] (js/Promise.resolve (response 200 reply-payload))))]
        (-> (run-llm!)
            (.then (fn [_]
                     (restore!)
                     (let [{:keys [url headers body]} (first @calls)]
                       (is (= "https://hub.corp.test/api/llm/chat/completions" url))
                       (is (= "Bearer plugin-tok" (get headers "Authorization")))
                       (is (= ["Authorization" "Content-Type"] (sort (keys headers)))
                           "only the plugin token and content type; gateway headers belong to the server")
                       (is (not-any? #(str/includes? (pr-str %) "sk-personal-should-not-leak") [headers body]))
                       (is (not-any? #(str/includes? (pr-str %) "local-only") [headers body]))
                       (is (= "corp/model" (:model body))))
                     (is (= "Gateway reply." (last @inserted)))
                     (done)))
            (.catch (fail! restore! done)))))))

(deftest test-hub-server-prefers-the-relay-only-token
  (testing "an org user with only a relay token (no Plugin API Token) reaches the relay with it"
    (async done
      (let [inserted (install-logseq! {"llmTransport" "hub-server"
                                       "webhookServerUrl" "https://hub.corp.test"
                                       "pluginApiToken" ""
                                       "llmRelayToken" " relay-tok-for-alice-0001 "}
                                      nil)
            [calls restore!] (with-fetch! (fn [_ _] (js/Promise.resolve (response 200 reply-payload))))]
        (-> (run-llm!)
            (.then (fn [_]
                     (restore!)
                     (is (= "Bearer relay-tok-for-alice-0001" (get-in (first @calls) [:headers "Authorization"])))
                     (is (= "Gateway reply." (last @inserted)))
                     (done)))
            (.catch (fail! restore! done)))))))

(deftest test-hub-server-blank-model-lets-the-server-choose
  (testing "with 'LLM Model Name' cleared, no model is sent, so the server's AGENT_MODEL applies"
    (async done
      (let [inserted (install-logseq! {"llmTransport" "hub-server"
                                       "webhookServerUrl" "https://hub.corp.test"
                                       "pluginApiToken" "plugin-tok"
                                       "llmModel" ""}
                                      nil)
            [calls restore!] (with-fetch! (fn [_ _] (js/Promise.resolve (response 200 reply-payload))))]
        (-> (run-llm!)
            (.then (fn [_]
                     (restore!)
                     (is (not (contains? (:body (first @calls)) :model))
                         "the plugin's built-in default must not override an org's model aliases")
                     (is (= "Gateway reply." (last @inserted)))
                     (done)))
            (.catch (fail! restore! done)))))))

;; ---------------------------------------------------------------------------
;; direct: gateway auth headers, secrets, and the CORS hint
;; ---------------------------------------------------------------------------

(deftest test-direct-gateway-headers-resolve-secrets-and-replace-defaults
  (testing "extra headers resolve {{secret.KEY}} and replace a default header case-insensitively"
    (let [cases [{:label "Azure-style api-key with no LLM API Key -> no Authorization header"
                  :settings {"llmExtraHeaders" "{\"api-key\":\"{{secret.AZURE_KEY}}\"}"}
                  :check (fn [headers]
                           (and (= "az-secret" (get headers "api-key"))
                                (not-any? #(= "authorization" (str/lower-case %)) (keys headers))))}
                 {:label "lower-case authorization replaces the Bearer default -> exactly one"
                  :settings {"llmApiKey" "sk-unused"
                             "llmExtraHeaders" "{\"authorization\":\"Token {{secret.AZURE_KEY}}\"}"}
                  :check (fn [headers]
                           (= [["authorization" "Token az-secret"]]
                              (filter #(= "authorization" (str/lower-case (key %))) headers)))}]]
      (async done
        (reset! secrets/vault-state {"AZURE_KEY" "az-secret"})
        (let [[calls restore!] (with-fetch! (fn [_ _] (js/Promise.resolve (response 200 reply-payload))))]
          (-> (run-cases!
                (fn [{:keys [label settings check]}]
                  (reset! calls [])
                  (let [inserted (install-logseq! (merge {"llmEndpoint" "https://gw.test/v1"} settings) nil)]
                    (-> (run-llm!)
                        (.then (fn [_]
                                 (is (check (:headers (first @calls)))
                                     (str label ": got " (:headers (first @calls))))
                                 (is (= "Gateway reply." (last @inserted)) label))))))
                cases)
              (.then (fn [_] (restore!) (secrets/reset-vault!) (done)))
              (.catch (fail! restore! done))))))))

(deftest test-direct-cors-hint-only-for-connection-failures
  (let [cases [{:label "CORS-blocked or unreachable (TypeError: Failed to fetch) -> names both transports"
                :error (js/TypeError. "Failed to fetch")
                :expect #(and (str/includes? % "https://gw.corp.test")
                              (str/includes? % "logseq-desktop")
                              (str/includes? % "hub-server"))}
                {:label "an invalid header value is not a CORS problem -> no transport advice"
                 :error (js/TypeError. "Failed to execute 'fetch' on 'Window': Invalid value")
                 :expect #(and (str/includes? % "Invalid value")
                               (not (str/includes? % "logseq-desktop")))}]]
    (async done
      (-> (run-cases!
            (fn [{:keys [label error expect]}]
              (let [inserted (install-logseq! {"llmApiKey" "sk-1" "llmEndpoint" "https://gw.corp.test/v1"} nil)
                    [_ restore!] (with-fetch! (fn [_ _] (js/Promise.reject error)))]
                (-> (run-llm!)
                    (.then (fn [_]
                             (restore!)
                             (is (expect (last @inserted)) (str label ": got " (last @inserted))))
                           (fn [err] (restore!) (throw err))))))
            cases)
          (.then (fn [_] (done)))
          (.catch (fn [err] (is false (str "rejected: " err)) (done)))))))

(deftest test-provider-error-echoing-a-credential-is-redacted-in-the-graph
  (testing "a gateway that echoes the resolved api-key in its error body never puts it in block text"
    (async done
      (reset! secrets/vault-state {"AZURE_KEY" "az-secret-value-9"})
      (let [inserted (install-logseq! {"llmEndpoint" "https://gw.test/v1"
                                       "llmExtraHeaders" "{\"api-key\":\"{{secret.AZURE_KEY}}\"}"}
                                      nil)
            [_ restore!] (with-fetch! (fn [_ _] (js/Promise.resolve
                                                  (response 401 {:error {:message "invalid api-key az-secret-value-9"}}))))]
        (-> (run-llm!)
            (.then (fn [_]
                     (restore!)
                     (is (str/includes? (last @inserted) "invalid api-key [redacted]"))
                     (is (not (str/includes? (last @inserted) "az-secret-value-9")))
                     (done)))
            (.catch (fail! restore! done)))))))

(deftest test-doctor-warns-when-credentials-would-cross-plain-http
  (let [cases [{:label "remote http endpoint" :endpoint "http://gw.corp.example/v1" :warn? true}
               {:label "loopback http endpoint" :endpoint "http://localhost:4000/v1" :warn? false}
               {:label "https endpoint" :endpoint "https://gw.corp.example/v1" :warn? false}]]
    (async done
      (-> (run-cases!
            (fn [{:keys [label endpoint warn?]}]
              (install-logseq! {"llmApiKey" "sk-1" "llmEndpoint" endpoint "webhookServerUrl" ""} nil)
              (let [[_ restore!] (with-fetch! (fn [_ _] (js/Promise.resolve (response 200 {:data []}))))]
                (-> (doctor/run-diagnostics!)
                    (.then (fn [report]
                             (restore!)
                             (is (= warn? (str/includes? report "⚠️ **LLM Transport** — credentials go to")) (str label ": got " report)))
                           (fn [err] (restore!) (throw err))))))
            cases)
          (.then (fn [_] (done)))
          (.catch (fn [err] (is false (str "rejected: " err)) (done)))))))

;; ---------------------------------------------------------------------------
;; Misconfiguration: named guidance, nothing sent
;; ---------------------------------------------------------------------------

(deftest test-misconfigured-transport-inserts-guidance-without-sending
  (let [cases [{:label "extra headers are not JSON"
                :settings {"llmApiKey" "sk-1" "llmExtraHeaders" "{api-key: x}"}
                :expect "LLM Extra Headers"}
               {:label "extra header references a secret that is not in the vault"
                :settings {"llmExtraHeaders" "{\"api-key\":\"{{secret.MISSING_KEY}}\"}"}
                :expect "MISSING_KEY"}
               {:label "a blank extra-header value is not a credential"
                :settings {"llmExtraHeaders" "{\"api-key\":\"   \"}"}
                :expect "is empty"}
               {:label "a key pasted with a zero-width space is refused, never sent"
                :settings {"llmApiKey" "sk-live\u200b123"}
                :expect "zero-width space"}
               {:label "hub-server in JWT mode without a relay token (the relay never verifies JWTs)"
                :settings {"llmTransport" "hub-server" "webhookServerUrl" "https://hub.test"
                           "authMode" "jwt" "jwtToken" "jwt-abc"}
                :expect "does not accept JWTs"}
               {:label "hub-server with no server URL"
                :settings {"llmTransport" "hub-server" "webhookServerUrl" "" "pluginApiToken" "t"}
                :expect "Webhook Server URL"}
               {:label "logseq-desktop outside the desktop app (no logseq.Request)"
                :settings {"llmTransport" "logseq-desktop" "llmApiKey" "sk-1"}
                :expect "Logseq desktop"}
               {:label "unknown transport value hand-edited into settings"
                :settings {"llmTransport" "carrier-pigeon" "llmApiKey" "sk-1"}
                :expect "carrier-pigeon"}]]
    (async done
      (let [[calls restore!] (with-fetch! (fn [_ _] (js/Promise.resolve (response 200 reply-payload))))]
        (-> (run-cases!
              (fn [{:keys [label settings expect]}]
                (let [inserted (install-logseq! settings nil)]
                  (-> (run-llm!)
                      (.then (fn [_]
                               (is (str/includes? (str (last @inserted)) expect)
                                   (str label ": got " (last @inserted))))))))
              cases)
            (.then (fn [_]
                     (restore!)
                     (is (empty? @calls) "a misconfiguration must never reach the network")
                     (done)))
            (.catch (fail! restore! done)))))))

;; ---------------------------------------------------------------------------
;; Doctor under hub-server: the hub's own errors vs statuses relayed from the gateway
;; ---------------------------------------------------------------------------

(deftest test-doctor-hub-server-names-the-credential-to-fix
  (let [cases [{:label "hub rejects the plugin token"
                :models (response 401 {:success false :error "Unauthorized"})
                :expect "PLUGIN_API_TOKEN"}
               {:label "gateway rejects the server's key, OpenAI-shaped"
                :models (response 401 {:error {:message "Incorrect API key"}})
                :expect "LLM_API_KEY"}
               {:label "gateway rejects the server's key, Kong-shaped (no error.message)"
                :models (response 401 {:message "Unauthorized"})
                :expect "LLM_API_KEY"
                :absent "PLUGIN_API_TOKEN"}
               {:label "hub has no LLM credentials"
                :models (response 503 {:error {:message "none" :type "relay_not_configured"}})
                :expect "no LLM credentials"}
               {:label "server disabled LLM because its own LLM settings are invalid"
                :models (response 503 {:error {:message "LLM is disabled on this server: LLM_EXTRA_HEADERS is not valid JSON"
                                               :type "relay_misconfigured"}})
                :expect "LLM_EXTRA_HEADERS is not valid JSON"
                :absent "no LLM credentials"}
               {:label "overloaded gateway's 503 is relayed, not a credentials problem"
                :models (response 503 {:error {:message "overloaded"}})
                :expect "overloaded"
                :absent "no LLM credentials"}
               {:label "gateway or model allowlist refuses the test completion"
                :models (response 400 {:error {:message "Model \"x\" is not allowed" :type "model_not_allowed"}})
                :expect "refused the test completion"}
               {:label "server without the relay route"
                :models (response 404 {:success false :error "Not found"})
                :expect "predates the LLM relay"}
               {:label "gateway 404 relayed through the hub is not a stale server"
                :models (response 404 {:detail "Not Found"})
                :expect "responded"
                :absent "predates"}
               {:label "relay reachable and authorised, gateway answers the test completion"
                :models (response 200 reply-payload)
                :expect "✅ **LLM** — plugin token accepted by https://hub.test/api/llm (AI Hub relay), and the gateway answered"}]]
    (async done
      (-> (run-cases!
            (fn [{:keys [label models expect absent]}]
              (install-logseq! {"llmTransport" "hub-server"
                                "webhookServerUrl" "https://hub.test"
                                "authMode" "token"
                                "pluginApiToken" "plugin-tok"}
                               nil)
              (let [[calls restore!]
                    (with-fetch! (fn [url _]
                                   (js/Promise.resolve
                                     (if (str/ends-with? url "/api/llm/chat/completions")
                                       models
                                       (response 200 {:agentApi {:pluginConnected true}})))))]
                (-> (doctor/run-diagnostics!)
                    (.then (fn [report]
                             (restore!)
                             (is (= "Bearer plugin-tok"
                                    (get-in (first (filter #(str/ends-with? (:url %) "/chat/completions") @calls))
                                            [:headers "Authorization"])))
                             (is (str/includes? report expect) (str label ": got " report))
                             (when absent
                               (is (not (str/includes? report absent)) (str label ": got " report))))
                           (fn [err] (restore!) (throw err))))))
            cases)
          (.then (fn [_] (done)))
          (.catch (fn [err] (is false (str "rejected: " err)) (done)))))))

(deftest test-doctor-checks-openrouter-keys-where-openrouter-checks-them
  (testing "OpenRouter's /models ignores auth, so the doctor probes /key, on both transports"
    (let [cases [{:label "direct, revoked key" :transport "direct"
                  :key-response (response 401 {:error {:message "User not found." :code 401}})
                  :expect "❌ **LLM** — provider rejected the credentials"}
                 {:label "direct, valid key" :transport "direct"
                  :key-response (response 200 {:data {:label "sk-or-v1-abc"}})
                  :expect "✅ **LLM** — key sk-or-…"}
                 {:label "Logseq 0.10 host (no status): code 401 in the body still reads as rejected"
                  :transport "logseq-desktop" :host-body "{\"error\":{\"message\":\"User not found.\",\"code\":401}}"
                  :expect "❌ **LLM** — provider rejected the credentials"}]]
      (async done
        (-> (run-cases!
              (fn [{:keys [label transport key-response host-body expect]}]
                (let [[client host-calls] (when host-body (fake-host :legacy 401 host-body))]
                  (install-logseq! {"llmTransport" transport "llmApiKey" "sk-or-v1-abcdefgh1234"
                                    "llmEndpoint" "https://openrouter.ai/api/v1" "webhookServerUrl" ""}
                                   client)
                  (let [[calls restore!] (with-fetch! (fn [url _]
                                                        (js/Promise.resolve
                                                          (if (str/ends-with? url "/key") key-response (response 200 {:data []})))))]
                    (-> (doctor/run-diagnostics!)
                        (.then (fn [report]
                                 (restore!)
                                 (let [urls (concat (map :url @calls) (map #(get-in % [:opts "url"]) (some-> host-calls deref)))]
                                   (is (some #(str/ends-with? % "/key") urls) (str label ": probed " (vec urls)))
                                   (is (not-any? #(str/ends-with? % "/models") urls) label))
                                 (is (str/includes? report expect) (str label ": got " report)))
                               (fn [err] (restore!) (throw err)))))))
              cases)
            (.then (fn [_] (done)))
            (.catch (fn [err] (is false (str "rejected: " err)) (done))))))))

(deftest test-logseq-desktop-names-the-real-cause-of-host-failures
  (let [cases [{:label "internal PKI / TLS inspection: Node in Logseq's main process ignores the OS store"
                :host (fn [_] (js/Promise.reject (js/Error. "FetchError: request to https://gw.corp.test/v1/chat/completions failed, reason: self-signed certificate in certificate chain")))
                :expect "system certificate store"}
               {:label "the SDK could not run the request (rejects with null)"
                :host (fn [_] (js/Promise.reject nil))
                :expect "needs the Logseq desktop app"}
               {:label "a host without the Electron IPC resolves null"
                :host (fn [_] (js/Promise.resolve nil))
                :expect "needs the Logseq desktop app"}]]
    (async done
      (-> (run-cases!
            (fn [{:keys [label host expect]}]
              (let [inserted (install-logseq! {"llmTransport" "logseq-desktop" "llmApiKey" "sk-1"
                                               "llmEndpoint" "https://gw.corp.test/v1"}
                                              #js {:_request host})]
                (-> (run-llm!)
                    (.then (fn [_] (is (str/includes? (str (last @inserted)) expect)
                                       (str label ": got " (last @inserted))))))))
            cases)
          (.then (fn [_] (done)))
          (.catch (fn [err] (is false (str "rejected: " err)) (done)))))))
