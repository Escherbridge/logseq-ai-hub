(ns logseq-ai-hub.event-hub.publish-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.event-hub.publish :as publish]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def fetch-calls (atom []))
(def warn-calls (atom []))

(defn- setup-mocks!
  "Sets up js/logseq.settings, js/fetch, and js/console.warn mocks.
   Uses set! (not with-redefs) for async safety."
  [{:keys [server-url token fetch-response]}]
  (reset! fetch-calls [])
  (reset! warn-calls [])
  (set! js/logseq
    #js {:settings #js {"webhookServerUrl" server-url
                         "pluginApiToken" token}})
  (set! js/console.warn
    (fn [& args]
      (swap! warn-calls conj (vec args))))
  (when fetch-response
    (set! js/fetch
      (fn [url opts]
        (let [body (js->clj (js/JSON.parse (.-body opts)) :keywordize-keys true)]
          (swap! fetch-calls conj {:url url :body body}))
        (js/Promise.resolve
          #js {:json (fn [] (js/Promise.resolve (clj->js fetch-response)))})))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest test-successful-publish-returns-event-id
  (setup-mocks! {:server-url "http://localhost:3000"
                 :token "test-token"
                 :fetch-response {:success true :eventId "evt-abc-123"}})
  (testing "successful publish returns {:event-id ...}"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event"
             :source "plugin"
             :data {:key "value"}
             :metadata {:severity "info"}})
          (.then (fn [result]
                   (is (= {:event-id "evt-abc-123"} result)
                       "Should return map with :event-id")
                   ;; Verify fetch was called with correct URL and body
                   (is (= 1 (count @fetch-calls)))
                   (let [{:keys [url body]} (first @fetch-calls)]
                     (is (= "http://localhost:3000/api/events/publish" url))
                     (is (= "test.event" (:type body)))
                     (is (= "plugin" (:source body)))
                     (is (= {:key "value"} (:data body)))
                     (is (= {:severity "info"} (:metadata body))))
                   (done)))))))

(deftest test-server-error-logs-warning-returns-nil
  (setup-mocks! {:server-url "http://localhost:3000"
                 :token "test-token"
                 :fetch-response nil})
  ;; Override fetch to simulate a network error
  (set! js/fetch
    (fn [_url _opts]
      (js/Promise.reject (js/Error. "Network failure"))))
  (testing "server error logs warning and returns nil"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event"
             :source "plugin"
             :data {}})
          (.then (fn [result]
                   (is (nil? result)
                       "Should return nil on error")
                   (is (pos? (count @warn-calls))
                       "Should have logged a warning")
                   (let [first-warn (first @warn-calls)]
                     (is (= "[EventHub] Failed to publish event:" (first first-warn))))
                   (done)))))))

(deftest test-missing-server-url-returns-nil
  (setup-mocks! {:server-url nil
                 :token "test-token"
                 :fetch-response nil})
  (testing "missing server URL returns nil without calling fetch"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event"
             :source "plugin"
             :data {}})
          (.then (fn [result]
                   (is (nil? result)
                       "Should return nil when server URL is missing")
                   (is (zero? (count @fetch-calls))
                       "Should not call fetch")
                   (is (pos? (count @warn-calls))
                       "Should log a warning about missing config")
                   (done)))))))

(deftest test-missing-token-returns-nil
  (setup-mocks! {:server-url "http://localhost:3000"
                 :token nil
                 :fetch-response nil})
  (testing "missing token returns nil without calling fetch"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event"
             :source "plugin"
             :data {}})
          (.then (fn [result]
                   (is (nil? result)
                       "Should return nil when token is missing")
                   (is (zero? (count @fetch-calls))
                       "Should not call fetch")
                   (is (pos? (count @warn-calls))
                       "Should log a warning about missing config")
                   (done)))))))
