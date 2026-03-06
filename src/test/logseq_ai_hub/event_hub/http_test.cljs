(ns logseq-ai-hub.event-hub.http-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.event-hub.http :as http]
            [logseq-ai-hub.job-runner.executor :as executor]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def fetch-calls (atom []))

(defn- setup-mocks!
  "Sets up js/logseq.settings and js/fetch mocks."
  [{:keys [allowlist fetch-response fetch-error]}]
  (reset! fetch-calls [])
  (set! js/logseq
    #js {:settings #js {"httpAllowlist" (or allowlist "[]")}})
  (if fetch-error
    (set! js/fetch
      (fn [url opts]
        (swap! fetch-calls conj {:url url :opts opts})
        (js/Promise.reject (js/Error. fetch-error))))
    (set! js/fetch
      (fn [url opts]
        (swap! fetch-calls conj {:url url :opts opts})
        (js/Promise.resolve
          (clj->js (merge {:status 200
                           :ok true
                           :headers (js/Map. (clj->js [["content-type" "application/json"]]))
                           :json (fn [] (js/Promise.resolve (clj->js (or fetch-response {}))))
                           :text (fn [] (js/Promise.resolve "text body"))}
                          fetch-response)))))))

(defn- make-fetch-response
  "Creates a mock fetch Response object with proper headers.forEach."
  [{:keys [status ok body content-type]
    :or {status 200 ok true content-type "application/json"}}]
  (let [headers-map #js {}
        _ (aset headers-map content-type content-type)]
    #js {:status status
         :ok ok
         :headers #js {:forEach (fn [callback]
                                  (callback content-type "content-type"))}
         :json (fn [] (js/Promise.resolve (clj->js (or body {}))))
         :text (fn [] (js/Promise.resolve (if (string? body) body "")))}))

;; ---------------------------------------------------------------------------
;; url-allowed? Tests
;; ---------------------------------------------------------------------------

(deftest test-url-allowed-wildcard-domain
  (testing "wildcard pattern matches subdomains"
    (is (true? (http/url-allowed? "https://api.example.com/path" ["*.example.com"])))
    (is (true? (http/url-allowed? "https://sub.example.com/path" ["*.example.com"])))
    (is (true? (http/url-allowed? "https://deep.sub.example.com/path" ["*.example.com"])))))

(deftest test-url-allowed-exact-match
  (testing "exact domain match"
    (is (true? (http/url-allowed? "https://api.github.com/repos" ["api.github.com"])))
    (is (false? (http/url-allowed? "https://evil.github.com/repos" ["api.github.com"])))))

(deftest test-url-allowed-rejects-unlisted-domain
  (testing "domain not in allowlist is rejected"
    (is (false? (http/url-allowed? "https://evil.com/steal" ["api.github.com" "*.example.com"])))))

(deftest test-url-allowed-empty-allowlist
  (testing "empty allowlist allows all HTTPS URLs"
    (is (true? (http/url-allowed? "https://any-domain.com/path" [])))
    (is (true? (http/url-allowed? "https://another.org/api" nil)))))

(deftest test-url-allowed-rejects-http-non-localhost
  (testing "http:// rejected for non-localhost"
    (is (false? (http/url-allowed? "http://api.example.com/path" [])))
    (is (false? (http/url-allowed? "http://evil.com/path" ["evil.com"])))))

(deftest test-url-allowed-http-localhost-allowed
  (testing "http://localhost and http://127.0.0.1 are allowed"
    (is (true? (http/url-allowed? "http://localhost:3000/api" [])))
    (is (true? (http/url-allowed? "http://127.0.0.1:8080/api" [])))))

(deftest test-url-allowed-invalid-url
  (testing "invalid URL returns false"
    (is (false? (http/url-allowed? "not-a-url" [])))
    (is (false? (http/url-allowed? "" [])))))

;; ---------------------------------------------------------------------------
;; interpolate-map Tests
;; ---------------------------------------------------------------------------

(deftest test-interpolate-map-nested
  (testing "interpolates string values in nested maps"
    (let [context {:inputs {"name" "Alice" "role" "admin"}
                   :step-results {}
                   :variables {}}
          m {"greeting" "Hello {{name}}"
             "nested" {"permission" "{{role}} access"
                       "count" 42}
             "static" "no-change"}
          result (http/interpolate-map m context)]
      (is (= "Hello Alice" (get result "greeting")))
      (is (= "admin access" (get-in result ["nested" "permission"])))
      (is (= 42 (get-in result ["nested" "count"])))
      (is (= "no-change" (get result "static"))))))

(deftest test-interpolate-map-nil
  (testing "nil map returns nil"
    (is (nil? (http/interpolate-map nil {})))))

;; ---------------------------------------------------------------------------
;; Full Executor Tests (with mocked fetch)
;; ---------------------------------------------------------------------------

(deftest test-http-executor-successful-get
  (setup-mocks! {:allowlist "[]"})
  ;; Override fetch with a proper response mock
  (set! js/fetch
    (fn [url opts]
      (swap! fetch-calls conj {:url url :opts (js->clj opts :keywordize-keys true)})
      (js/Promise.resolve
        (make-fetch-response {:status 200 :ok true :body {:result "success"}}))))
  (testing "successful GET request"
    (async done
      (-> (executor/execute-step
            {:step-action :http-request
             :step-config {"url" "https://api.example.com/data"
                           "method" "GET"}}
            {:job-id "test-job"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [result]
                   (is (= 200 (:status result)))
                   (is (true? (:ok result)))
                   (is (= {:result "success"} (:body result)))
                   ;; Verify fetch was called
                   (is (= 1 (count @fetch-calls)))
                   (let [{:keys [url opts]} (first @fetch-calls)]
                     (is (= "https://api.example.com/data" url))
                     (is (= "GET" (:method opts))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-http-executor-post-with-body
  (setup-mocks! {:allowlist "[]"})
  (set! js/fetch
    (fn [url opts]
      (swap! fetch-calls conj {:url url :opts (js->clj opts :keywordize-keys true)})
      (js/Promise.resolve
        (make-fetch-response {:status 201 :ok true :body {:id "new-123"}}))))
  (testing "POST request with interpolated body"
    (async done
      (-> (executor/execute-step
            {:step-action :http-request
             :step-config {"url" "https://api.example.com/items"
                           "method" "POST"
                           "headers" {"Content-Type" "application/json"
                                      "Authorization" "Bearer {{token}}"}
                           "body" "{\"name\": \"{{item_name}}\"}"}}
            {:job-id "test-job"
             :variables {}
             :inputs {"token" "sk-123" "item_name" "Widget"}
             :step-results {}})
          (.then (fn [result]
                   (is (= 201 (:status result)))
                   ;; Verify headers were interpolated
                   (let [{:keys [opts]} (first @fetch-calls)
                         headers (:headers opts)]
                     (is (= "Bearer sk-123" (:Authorization headers))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-http-executor-url-blocked
  (setup-mocks! {:allowlist "[\"api.github.com\"]"})
  (testing "URL blocked by allowlist rejects"
    (async done
      (-> (executor/execute-step
            {:step-action :http-request
             :step-config {"url" "https://evil.com/steal"
                           "method" "GET"}}
            {:job-id "test-job"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [_]
                   (is false "Should have been rejected")
                   (done)))
          (.catch (fn [err]
                    (is (= :http-url-blocked (:type err)))
                    (done)))))))

(deftest test-http-executor-http-rejected
  (setup-mocks! {:allowlist "[]"})
  (testing "http:// URL for non-localhost is rejected"
    (async done
      (-> (executor/execute-step
            {:step-action :http-request
             :step-config {"url" "http://api.example.com/data"
                           "method" "GET"}}
            {:job-id "test-job"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [_]
                   (is false "Should have been rejected")
                   (done)))
          (.catch (fn [err]
                    (is (= :http-url-blocked (:type err)))
                    (done)))))))

(deftest test-http-executor-no-url
  (setup-mocks! {:allowlist "[]"})
  (testing "missing URL rejects"
    (async done
      (-> (executor/execute-step
            {:step-action :http-request
             :step-config {"method" "GET"}}
            {:job-id "test-job"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [_]
                   (is false "Should have been rejected")
                   (done)))
          (.catch (fn [err]
                    (is (= :http-invalid-url (:type err)))
                    (done)))))))

(deftest test-http-executor-interpolates-url
  (setup-mocks! {:allowlist "[]"})
  (set! js/fetch
    (fn [url opts]
      (swap! fetch-calls conj {:url url})
      (js/Promise.resolve
        (make-fetch-response {:status 200 :ok true :body {}}))))
  (testing "URL is interpolated from context"
    (async done
      (-> (executor/execute-step
            {:step-action :http-request
             :step-config {"url" "https://api.example.com/users/{{user_id}}"
                           "method" "GET"}}
            {:job-id "test-job"
             :variables {}
             :inputs {"user_id" "42"}
             :step-results {}})
          (.then (fn [_]
                   (is (= "https://api.example.com/users/42"
                          (:url (first @fetch-calls))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-http-executor-default-method
  (setup-mocks! {:allowlist "[]"})
  (set! js/fetch
    (fn [url opts]
      (swap! fetch-calls conj {:url url :opts (js->clj opts :keywordize-keys true)})
      (js/Promise.resolve
        (make-fetch-response {:status 200 :ok true :body {}}))))
  (testing "default method is GET"
    (async done
      (-> (executor/execute-step
            {:step-action :http-request
             :step-config {"url" "https://api.example.com/data"}}
            {:job-id "test-job"
             :variables {}
             :inputs {}
             :step-results {}})
          (.then (fn [_]
                   (let [{:keys [opts]} (first @fetch-calls)]
                     (is (= "GET" (:method opts))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-http-executor-registered
  (testing ":http-request executor is registered"
    (is (some? (get @executor/step-executors :http-request)))))
