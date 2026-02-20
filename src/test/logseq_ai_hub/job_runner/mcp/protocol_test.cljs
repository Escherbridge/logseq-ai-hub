(ns logseq-ai-hub.job-runner.mcp.protocol-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.job-runner.mcp.protocol :as protocol]))

(deftest request-id-test
  (testing "request-id! generates unique sequential IDs"
    (reset! protocol/request-counter 0)
    (is (= 1 (protocol/request-id!)))
    (is (= 2 (protocol/request-id!)))
    (is (= 3 (protocol/request-id!)))))

(deftest make-request-test
  (testing "make-request creates valid JSON-RPC 2.0 request"
    (reset! protocol/request-counter 0)
    (let [req (protocol/make-request "test/method" {:foo "bar"})]
      (is (= "2.0" (:jsonrpc req)))
      (is (= "test/method" (:method req)))
      (is (= {:foo "bar"} (:params req)))
      (is (= 1 (:id req)))))

  (testing "make-request with nil params"
    (reset! protocol/request-counter 10)
    (let [req (protocol/make-request "test/method" nil)]
      (is (= nil (:params req)))
      (is (= 11 (:id req))))))

(deftest make-notification-test
  (testing "make-notification creates JSON-RPC 2.0 notification without ID"
    (let [notif (protocol/make-notification "test/notification" {:data 123})]
      (is (= "2.0" (:jsonrpc notif)))
      (is (= "test/notification" (:method notif)))
      (is (= {:data 123} (:params notif)))
      (is (nil? (:id notif)))))

  (testing "make-notification with empty params"
    (let [notif (protocol/make-notification "empty/notification" {})]
      (is (= {} (:params notif))))))

(deftest encode-message-test
  (testing "encode-message converts map to JSON string"
    (let [msg {:jsonrpc "2.0" :method "test" :id 1}
          json (protocol/encode-message msg)]
      (is (string? json))
      (is (re-find #"\"jsonrpc\":\"2\.0\"" json))
      (is (re-find #"\"method\":\"test\"" json))
      (is (re-find #"\"id\":1" json))))

  (testing "encode-message with nested params"
    (let [msg {:jsonrpc "2.0" :method "test" :params {:nested {:value 42}}}
          json (protocol/encode-message msg)]
      (is (re-find #"\"nested\"" json))
      (is (re-find #"\"value\":42" json)))))

(deftest parse-response-test
  (testing "parse-response handles successful response"
    (let [json "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"ok\"}}"
          parsed (protocol/parse-response json)]
      (is (= 1 (:id parsed)))
      (is (= {:status "ok"} (:result parsed)))
      (is (nil? (:error parsed)))))

  (testing "parse-response handles error response"
    (let [json "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32600,\"message\":\"Invalid request\",\"data\":\"extra\"}}"
          parsed (protocol/parse-response json)]
      (is (= 2 (:id parsed)))
      (is (nil? (:result parsed)))
      (is (= -32600 (get-in parsed [:error :code])))
      (is (= "Invalid request" (get-in parsed [:error :message])))
      (is (= "extra" (get-in parsed [:error :data])))))

  (testing "parse-response handles invalid JSON"
    (let [json "not valid json {]"
          parsed (protocol/parse-response json)]
      (is (true? (:parse-error parsed)))
      (is (= json (:raw parsed)))))

  (testing "parse-response with null result"
    (let [json "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":null}"
          parsed (protocol/parse-response json)]
      (is (= 3 (:id parsed)))
      (is (nil? (:result parsed))))))

(deftest initialize-request-test
  (testing "initialize-request creates proper MCP initialize message"
    (reset! protocol/request-counter 0)
    (let [client-info {:name "test-client" :version "1.0.0"}
          capabilities {:tools true :resources false}
          req (protocol/initialize-request client-info capabilities)]
      (is (= "2.0" (:jsonrpc req)))
      (is (= "initialize" (:method req)))
      (is (= 1 (:id req)))
      (is (= "2025-03-26" (get-in req [:params :protocolVersion])))
      (is (= client-info (get-in req [:params :clientInfo])))
      (is (= capabilities (get-in req [:params :capabilities]))))))

(deftest initialized-notification-test
  (testing "initialized-notification creates proper MCP initialized message"
    (let [notif (protocol/initialized-notification)]
      (is (= "2.0" (:jsonrpc notif)))
      (is (= "notifications/initialized" (:method notif)))
      (is (= {} (:params notif)))
      (is (nil? (:id notif))))))

(deftest tools-list-request-test
  (testing "tools-list-request creates proper request"
    (reset! protocol/request-counter 100)
    (let [req (protocol/tools-list-request)]
      (is (= "tools/list" (:method req)))
      (is (= {} (:params req)))
      (is (= 101 (:id req))))))

(deftest tools-call-request-test
  (testing "tools-call-request creates proper request with arguments"
    (reset! protocol/request-counter 200)
    (let [req (protocol/tools-call-request "my-tool" {:arg1 "value1" :arg2 42})]
      (is (= "tools/call" (:method req)))
      (is (= "my-tool" (get-in req [:params :name])))
      (is (= {:arg1 "value1" :arg2 42} (get-in req [:params :arguments])))
      (is (= 201 (:id req))))))

(deftest resources-list-request-test
  (testing "resources-list-request creates proper request"
    (let [req (protocol/resources-list-request)]
      (is (= "resources/list" (:method req)))
      (is (= {} (:params req))))))

(deftest resources-read-request-test
  (testing "resources-read-request creates proper request with URI"
    (let [req (protocol/resources-read-request "file:///path/to/resource.txt")]
      (is (= "resources/read" (:method req)))
      (is (= "file:///path/to/resource.txt" (get-in req [:params :uri]))))))

(deftest prompts-list-request-test
  (testing "prompts-list-request creates proper request"
    (let [req (protocol/prompts-list-request)]
      (is (= "prompts/list" (:method req)))
      (is (= {} (:params req))))))

(deftest prompts-get-request-test
  (testing "prompts-get-request creates proper request with name and arguments"
    (let [req (protocol/prompts-get-request "my-prompt" {:param1 "test" :param2 123})]
      (is (= "prompts/get" (:method req)))
      (is (= "my-prompt" (get-in req [:params :name])))
      (is (= {:param1 "test" :param2 123} (get-in req [:params :arguments]))))))
