(ns logseq-ai-hub.mcp.transport-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.mcp.transport :as transport]
            [logseq-ai-hub.mcp.protocol :as protocol]))

;; Mock helpers
(defn mock-fetch-json-response
  "Creates a mock fetch response for JSON"
  [status body session-id]
  (js/Promise.resolve
   #js {:ok (= 200 status)
        :status status
        :headers #js {:get (fn [name]
                             (cond
                               (= name "Content-Type") "application/json"
                               (= name "Mcp-Session-Id") session-id
                               :else nil))}
        :text (fn [] (js/Promise.resolve body))}))

(defn mock-fetch-sse-response
  "Creates a mock fetch response for SSE stream"
  [status sse-data session-id]
  (js/Promise.resolve
   #js {:ok (= 200 status)
        :status status
        :headers #js {:get (fn [name]
                             (cond
                               (= name "Content-Type") "text/event-stream"
                               (= name "Mcp-Session-Id") session-id
                               :else nil))}
        :body #js {:getReader (fn []
                                 (let [sent (atom false)]
                                   #js {:read (fn []
                                                (if @sent
                                                  (js/Promise.resolve #js {:done true})
                                                  (do
                                                    (reset! sent true)
                                                    (js/Promise.resolve
                                                     #js {:done false
                                                          :value (js/Uint8Array.from
                                                                  (map #(.charCodeAt % 0) sse-data))}))))}))}}))

(deftest make-http-transport-test
  (testing "make-http-transport creates valid transport map"
    (let [transport (transport/make-http-transport "http://example.com" "token123")]
      (is (= :streamable-http (:type transport)))
      (is (= "http://example.com" (:url transport)))
      (is (some? (:session-id transport)))
      (is (fn? (:send! transport)))
      (is (fn? (:close! transport)))))

  (testing "make-http-transport without auth token"
    (let [transport (transport/make-http-transport "http://example.com" nil)]
      (is (= :streamable-http (:type transport))))))

(deftest http-transport-send-json-test
  (testing "HTTP transport sends request and receives JSON response"
    (async done
      (let [original-fetch js/fetch
            fetch-calls (atom [])
            mock-response (protocol/encode-message
                           {:jsonrpc "2.0" :id 1 :result {:status "ok"}})
            transport (transport/make-http-transport "http://example.com/mcp" "token123")]

        (set! js/fetch
              (fn [url opts]
                (swap! fetch-calls conj {:url url :opts (js->clj opts :keywordize-keys true)})
                (mock-fetch-json-response 200 mock-response "session-abc")))

        (-> ((:send! transport) {:jsonrpc "2.0" :method "test" :id 1})
            (.then (fn [response]
                     (is (= 1 (:id response)))
                     (is (= {:status "ok"} (:result response)))

                     ;; Verify fetch was called correctly
                     (let [call (first @fetch-calls)]
                       (is (= "http://example.com/mcp" (:url call)))
                       (is (= "POST" (get-in call [:opts :method])))
                       (is (= "application/json" (get-in call [:opts :headers :Content-Type])))
                       (is (= "Bearer token123" (get-in call [:opts :headers :Authorization]))))

                     ;; Verify session ID was stored
                     (is (= "session-abc" @(:session-id transport)))

                     (set! js/fetch original-fetch)
                     (done)))
            (.catch (fn [err]
                      (set! js/fetch original-fetch)
                      (is false (str "Promise rejected: " err))
                      (done))))))))

(deftest http-transport-send-with-session-test
  (testing "HTTP transport includes session ID in subsequent requests"
    (async done
      (let [original-fetch js/fetch
            fetch-calls (atom [])
            transport (transport/make-http-transport "http://example.com/mcp" nil)]

        ;; Set session ID
        (reset! (:session-id transport) "existing-session")

        (set! js/fetch
              (fn [url opts]
                (swap! fetch-calls conj {:url url :opts (js->clj opts :keywordize-keys true)})
                (mock-fetch-json-response 200
                                         (protocol/encode-message
                                          {:jsonrpc "2.0" :id 2 :result {}})
                                         nil)))

        (-> ((:send! transport) {:jsonrpc "2.0" :method "test" :id 2})
            (.then (fn [_]
                     (let [call (first @fetch-calls)]
                       (is (= "existing-session" (get-in call [:opts :headers :Mcp-Session-Id]))))
                     (set! js/fetch original-fetch)
                     (done)))
            (.catch (fn [err]
                      (set! js/fetch original-fetch)
                      (is false (str "Promise rejected: " err))
                      (done))))))))

(deftest http-transport-sse-stream-test
  (testing "HTTP transport handles SSE stream response"
    (async done
      (let [original-fetch js/fetch
            sse-data "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[]}}\n\n"
            transport (transport/make-http-transport "http://example.com/mcp" nil)]

        (set! js/fetch
              (fn [_ _]
                (mock-fetch-sse-response 200 sse-data "session-sse")))

        (-> ((:send! transport) {:jsonrpc "2.0" :method "tools/list" :id 3})
            (.then (fn [response]
                     (is (= 3 (:id response)))
                     (is (= {:tools []} (:result response)))
                     (is (= "session-sse" @(:session-id transport)))
                     (set! js/fetch original-fetch)
                     (done)))
            (.catch (fn [err]
                      (set! js/fetch original-fetch)
                      (is false (str "Promise rejected: " err))
                      (done))))))))

(deftest http-transport-close-test
  (testing "HTTP transport close does nothing but succeeds"
    (let [transport (transport/make-http-transport "http://example.com" nil)]
      (is (nil? ((:close! transport)))))))

(deftest make-sse-transport-test
  (testing "make-sse-transport creates valid transport map"
    (let [transport (transport/make-sse-transport "http://example.com/events" "token123")]
      (is (= :sse (:type transport)))
      (is (= "http://example.com/events" (:url transport)))
      (is (some? (:event-source transport)))
      (is (some? (:post-url transport)))
      (is (some? (:pending-requests transport)))
      (is (fn? (:send! transport)))
      (is (fn? (:close! transport)))
      (is (fn? (:connect! transport))))))

(deftest sse-transport-connect-test
  (testing "SSE transport connect establishes EventSource"
    (let [original-eventsource js/EventSource
          created-sources (atom [])
          mock-source (atom nil)
          transport (transport/make-sse-transport "http://example.com/events" "token123")]

      (set! js/EventSource
            (fn [url]
              (let [source #js {:addEventListener
                                (fn [event-type handler]
                                  (when (= event-type "endpoint")
                                    ;; Simulate endpoint event
                                    (js/setTimeout
                                     #(handler #js {:data "http://example.com/post"})
                                     10)))
                                :close (fn [])}]
                (swap! created-sources conj {:url url})
                (reset! mock-source source)
                source)))

      ((:connect! transport))

      ;; Wait for async event
      (js/setTimeout
       (fn []
         (is (= 1 (count @created-sources)))
         (is (= "http://example.com/events" (:url (first @created-sources))))
         (is (= "http://example.com/post" @(:post-url transport)))
         (is (= @mock-source @(:event-source transport)))
         (set! js/EventSource original-eventsource))
       50))))

(deftest sse-transport-send-test
  (testing "SSE transport sends via POST and resolves on message event"
    (async done
      (let [original-fetch js/fetch
            original-eventsource js/EventSource
            message-handler (atom nil)
            transport (transport/make-sse-transport "http://example.com/events" nil)]

        (set! js/EventSource
              (fn [_]
                #js {:addEventListener
                     (fn [event-type handler]
                       (cond
                         (= event-type "endpoint")
                         (js/setTimeout #(handler #js {:data "http://example.com/post"}) 10)
                         (= event-type "message")
                         (reset! message-handler handler)))
                     :close (fn [])}))

        (set! js/fetch
              (fn [_ _]
                ;; Simulate SSE message response after POST
                (js/setTimeout
                 (fn []
                   (@message-handler
                    #js {:data (protocol/encode-message
                                {:jsonrpc "2.0" :id 1 :result {:value 42}})}))
                 20)
                (js/Promise.resolve #js {:ok true :status 202})))

        ((:connect! transport))

        (js/setTimeout
         (fn []
           (-> ((:send! transport) {:jsonrpc "2.0" :method "test" :id 1})
               (.then (fn [response]
                        (is (= 1 (:id response)))
                        (is (= {:value 42} (:result response)))
                        (set! js/fetch original-fetch)
                        (set! js/EventSource original-eventsource)
                        (done)))
               (.catch (fn [err]
                         (set! js/fetch original-fetch)
                         (set! js/EventSource original-eventsource)
                         (is false (str "Promise rejected: " err))
                         (done)))))
         50)))))

(deftest sse-transport-close-test
  (testing "SSE transport close calls EventSource.close"
    (let [original-eventsource js/EventSource
          close-called (atom false)
          transport (transport/make-sse-transport "http://example.com/events" nil)]

      (set! js/EventSource
            (fn [_]
              #js {:addEventListener (fn [_ _])
                   :close (fn [] (reset! close-called true))}))

      ((:connect! transport))
      ((:close! transport))

      (js/setTimeout
       (fn []
         (is @close-called)
         (set! js/EventSource original-eventsource))
       10))))

(deftest make-transport-auto-detect-test
  (testing "make-transport defaults to streamable-http"
    (let [transport (transport/make-transport {:url "http://example.com"})]
      (is (= :streamable-http (:type transport)))))

  (testing "make-transport creates streamable-http when specified"
    (let [transport (transport/make-transport {:transport :streamable-http
                                               :url "http://example.com"
                                               :auth-token "token"})]
      (is (= :streamable-http (:type transport)))
      (is (= "http://example.com" (:url transport)))))

  (testing "make-transport creates SSE when specified"
    (let [transport (transport/make-transport {:transport :sse
                                               :url "http://example.com/events"
                                               :auth-token "token"})]
      (is (= :sse (:type transport)))
      (is (= "http://example.com/events" (:url transport))))))
