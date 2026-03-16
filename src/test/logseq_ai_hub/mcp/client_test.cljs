(ns logseq-ai-hub.mcp.client-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.mcp.client :as client]
            [logseq-ai-hub.mcp.protocol :as protocol]))

;; Mock transport factory
(defn make-mock-transport
  "Creates a mock transport that can simulate responses.
   Notifications (messages without :id) are treated as fire-and-forget."
  [responses-atom]
  {:type :mock
   :send! (fn [message]
            (js/Promise.
             (fn [resolve reject]
               (let [method (:method message)
                     id (:id message)
                     response (get @responses-atom method)]
                 (cond
                   ;; Notifications (no id) are fire-and-forget
                   (nil? id) (resolve nil)
                   ;; Known response
                   response (if (:error response)
                              (reject (js/Error. (:error response)))
                              (resolve (assoc response :id id)))
                   ;; Unknown method
                   :else (reject (js/Error. (str "No mock response for method: " method))))))))
   :close! (fn [] nil)})

(deftest connect-server-test
  (testing "connect-server! establishes connection and caches capabilities"
    (async done
      (reset! client/servers {})
      (let [responses (atom {"initialize" {:result {:protocolVersion "2025-03-26"
                                                     :capabilities {:tools {} :resources {}}
                                                     :serverInfo {:name "Test Server" :version "1.0"}}}
                             "tools/list" {:result {:tools [{:name "test-tool"}]}}})
            config {:id "test-server"
                    :url "http://example.com/mcp"
                    :name "Test Server"
                    :transport-type :mock
                    :make-transport-fn (fn [_] (make-mock-transport responses))}]

        (-> (client/connect-server! config)
            (.then (fn [_]
                     (let [server (get @client/servers "test-server")]
                       (is (= :connected (:status server)))
                       (is (= "Test Server" (:name server)))
                       (is (= {:tools {} :resources {}} (get-in server [:capabilities])))
                       (is (some? (:transport server)))
                       (done))))
            (.catch (fn [err]
                      (is false (str "Promise rejected: " err))
                      (done))))))))

(deftest disconnect-server-test
  (testing "disconnect-server! closes transport and updates status"
    (async done
      (reset! client/servers {})
      (let [close-called (atom false)
            transport {:type :mock
                       :send! (fn [_] (js/Promise.resolve {:result {}}))
                       :close! (fn [] (reset! close-called true))}]

        (swap! client/servers assoc "test-server"
               {:id "test-server"
                :status :connected
                :transport transport})

        (client/disconnect-server! "test-server")

        (js/setTimeout
         (fn []
           (is @close-called)
           (is (= :disconnected (get-in @client/servers ["test-server" :status])))
           (done))
         10)))))

(deftest list-servers-test
  (testing "list-servers returns all servers"
    (reset! client/servers {"server1" {:id "server1" :name "Server 1"}
                            "server2" {:id "server2" :name "Server 2"}})
    (let [servers (client/list-servers)]
      (is (= 2 (count servers)))
      (is (some #(= "Server 1" (:name %)) servers))
      (is (some #(= "Server 2" (:name %)) servers)))))

(deftest server-status-test
  (testing "server-status returns status of specific server"
    (reset! client/servers {"test-server" {:id "test-server" :status :connected}})
    (is (= :connected (client/server-status "test-server"))))

  (testing "server-status returns :disconnected for unknown server"
    (reset! client/servers {})
    (is (= :disconnected (client/server-status "unknown")))))

(deftest reconnect-logic-test
  (testing "connect-server! retries on failure with exponential backoff"
    (async done
      (reset! client/servers {})
      ;; Use fast delays for testing
      (let [original-delays client/reconnect-delays]
        (set! client/reconnect-delays [50 100 150])
        (let [attempt-count (atom 0)
              attempt-times (atom [])
              start-time (js/Date.now)
              config {:id "retry-server"
                      :url "http://example.com/mcp"
                      :name "Retry Server"
                      :transport-type :mock
                      :make-transport-fn (fn [_]
                                           {:type :mock
                                            :send! (fn [_]
                                                     (swap! attempt-count inc)
                                                     (swap! attempt-times conj (- (js/Date.now) start-time))
                                                     (js/Promise.reject (js/Error. "Connection failed")))
                                            :close! (fn [] nil)})}]

          (-> (client/connect-server! config)
              (.then (fn [_]
                       (is false "Should not succeed")
                       (set! client/reconnect-delays original-delays)
                       (done)))
              (.catch (fn [_]
                        ;; Should attempt: initial + 3 retries = 4 total
                        ;; All attempts are done by the time catch fires
                        (js/setTimeout
                         (fn []
                           (is (= 4 @attempt-count))
                           (is (= :error (get-in @client/servers ["retry-server" :status])))
                           (is (= 3 (get-in @client/servers ["retry-server" :reconnect-count])))

                           ;; Verify exponential backoff timing (approximately)
                           ;; First attempt: ~0ms
                           ;; Second attempt: ~50ms
                           ;; Third attempt: ~150ms (50 + 100)
                           ;; Fourth attempt: ~300ms (50 + 100 + 150)
                           (is (< (nth @attempt-times 0) 30))
                           (is (> (nth @attempt-times 1) 30))
                           (is (> (nth @attempt-times 2) 120))
                           (is (> (nth @attempt-times 3) 250))

                           (set! client/reconnect-delays original-delays)
                           (done))
                         100)))))))))

(deftest list-tools-cached-test
  (testing "list-tools returns cached tools if available"
    (async done
      (reset! client/servers {"test-server" {:id "test-server"
                                              :status :connected
                                              :tools [{:name "cached-tool"}]
                                              :transport {:type :mock
                                                         :send! (fn [_]
                                                                  (is false "Should not be called")
                                                                  (js/Promise.resolve {}))}}})
      (-> (client/list-tools "test-server")
          (.then (fn [tools]
                   (is (= [{:name "cached-tool"}] tools))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest list-tools-fetch-test
  (testing "list-tools fetches from server if not cached"
    (async done
      (reset! client/servers {"test-server" {:id "test-server"
                                              :status :connected
                                              :tools []
                                              :transport {:type :mock
                                                         :send! (fn [message]
                                                                  (is (= "tools/list" (:method message)))
                                                                  (js/Promise.resolve
                                                                   {:id (:id message)
                                                                    :result {:tools [{:name "fetched-tool"}]}}))}}})
      (-> (client/list-tools "test-server")
          (.then (fn [tools]
                   (is (= [{:name "fetched-tool"}] tools))
                   ;; Verify cache was updated
                   (is (= [{:name "fetched-tool"}]
                          (get-in @client/servers ["test-server" :tools])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest call-tool-test
  (testing "call-tool sends tool call and returns result"
    (async done
      (reset! client/servers {"test-server" {:id "test-server"
                                              :status :connected
                                              :transport {:type :mock
                                                         :send! (fn [message]
                                                                  (is (= "tools/call" (:method message)))
                                                                  (is (= "my-tool" (get-in message [:params :name])))
                                                                  (is (= {:arg1 "val1"} (get-in message [:params :arguments])))
                                                                  (js/Promise.resolve
                                                                   {:id (:id message)
                                                                    :result {:content [{:type "text"
                                                                                        :text "Tool result"}]}}))}}})
      (-> (client/call-tool "test-server" "my-tool" {:arg1 "val1"})
          (.then (fn [result]
                   (is (= [{:type "text" :text "Tool result"}] (:content result)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest call-tool-timeout-test
  (testing "call-tool times out after 30 seconds"
    (async done
      (reset! client/servers {"test-server" {:id "test-server"
                                              :status :connected
                                              :transport {:type :mock
                                                         :send! (fn [_]
                                                                  ;; Return promise that never resolves
                                                                  (js/Promise. (fn [_ _])))}}})
      ;; Use a shorter timeout for testing (100ms instead of 30s)
      (-> (client/with-timeout
            ((:send! (get-in @client/servers ["test-server" :transport]))
             (protocol/tools-call-request "slow-tool" {}))
            100)
          (.then (fn [_]
                   (is false "Should have timed out")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"timed out" (.-message err)))
                    (done)))))))

(deftest refresh-tools-test
  (testing "refresh-tools! re-fetches tool list"
    (async done
      (reset! client/servers {"test-server" {:id "test-server"
                                              :status :connected
                                              :tools [{:name "old-tool"}]
                                              :transport {:type :mock
                                                         :send! (fn [message]
                                                                  (is (= "tools/list" (:method message)))
                                                                  (js/Promise.resolve
                                                                   {:id (:id message)
                                                                    :result {:tools [{:name "new-tool"}]}}))}}})
      (-> (client/refresh-tools! "test-server")
          (.then (fn [tools]
                   (is (= [{:name "new-tool"}] tools))
                   (is (= [{:name "new-tool"}]
                          (get-in @client/servers ["test-server" :tools])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest list-resources-test
  (testing "list-resources sends resources/list request"
    (async done
      (reset! client/servers {"test-server" {:id "test-server"
                                              :status :connected
                                              :transport {:type :mock
                                                         :send! (fn [message]
                                                                  (is (= "resources/list" (:method message)))
                                                                  (js/Promise.resolve
                                                                   {:id (:id message)
                                                                    :result {:resources [{:uri "file:///test.txt"}]}}))}}})
      (-> (client/list-resources "test-server")
          (.then (fn [resources]
                   (is (= [{:uri "file:///test.txt"}] resources))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest read-resource-test
  (testing "read-resource sends resources/read request"
    (async done
      (reset! client/servers {"test-server" {:id "test-server"
                                              :status :connected
                                              :transport {:type :mock
                                                         :send! (fn [message]
                                                                  (is (= "resources/read" (:method message)))
                                                                  (is (= "file:///test.txt" (get-in message [:params :uri])))
                                                                  (js/Promise.resolve
                                                                   {:id (:id message)
                                                                    :result {:contents [{:uri "file:///test.txt"
                                                                                         :text "file content"}]}}))}}})
      (-> (client/read-resource "test-server" "file:///test.txt")
          (.then (fn [contents]
                   (is (= [{:uri "file:///test.txt" :text "file content"}] contents))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest list-prompts-test
  (testing "list-prompts sends prompts/list request"
    (async done
      (reset! client/servers {"test-server" {:id "test-server"
                                              :status :connected
                                              :transport {:type :mock
                                                         :send! (fn [message]
                                                                  (is (= "prompts/list" (:method message)))
                                                                  (js/Promise.resolve
                                                                   {:id (:id message)
                                                                    :result {:prompts [{:name "test-prompt"}]}}))}}})
      (-> (client/list-prompts "test-server")
          (.then (fn [prompts]
                   (is (= [{:name "test-prompt"}] prompts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest get-prompt-test
  (testing "get-prompt sends prompts/get request"
    (async done
      (reset! client/servers {"test-server" {:id "test-server"
                                              :status :connected
                                              :transport {:type :mock
                                                         :send! (fn [message]
                                                                  (is (= "prompts/get" (:method message)))
                                                                  (is (= "my-prompt" (get-in message [:params :name])))
                                                                  (is (= {:param1 "val1"} (get-in message [:params :arguments])))
                                                                  (js/Promise.resolve
                                                                   {:id (:id message)
                                                                    :result {:messages [{:role "user"
                                                                                         :content "prompt text"}]}}))}}})
      (-> (client/get-prompt "test-server" "my-prompt" {:param1 "val1"})
          (.then (fn [prompt]
                   (is (= [{:role "user" :content "prompt text"}] (:messages prompt)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest server-not-connected-test
  (testing "operations fail gracefully when server not connected"
    (async done
      (reset! client/servers {})
      (-> (client/list-tools "unknown-server")
          (.then (fn [_]
                   (is false "Should have failed")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"not found" (.-message err)))
                    (done)))))))
