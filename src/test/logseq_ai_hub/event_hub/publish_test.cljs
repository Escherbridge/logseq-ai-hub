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
  [{:keys [server-url token fetch-response fetch-ok fetch-status auth-mode jwt-token]
    :or {fetch-ok true fetch-status 200}}]
  (reset! fetch-calls [])
  (reset! warn-calls [])
  (set! js/logseq
    #js {:settings #js {"webhookServerUrl" server-url
                         "pluginApiToken" token
                         "authMode" auth-mode
                         "jwtToken" jwt-token}})
  (set! js/console.warn
    (fn [& args]
      (swap! warn-calls conj (vec args))))
  (when fetch-response
    (set! js/fetch
      (fn [url opts]
        (let [body (js->clj (js/JSON.parse (.-body opts)) :keywordize-keys true)
              authorization (aget opts "headers" "Authorization")]
          (swap! fetch-calls conj {:url url :body body :authorization authorization}))
        (js/Promise.resolve
          #js {:ok fetch-ok
               :status fetch-status
               :json (fn [] (js/Promise.resolve (clj->js fetch-response)))})))))

;; ---------------------------------------------------------------------------
;; Tests -- publish-to-server! must ALWAYS resolve (never reject, callers
;; depend on that for fire-and-forget use) with a tagged result that makes a
;; real publish distinguishable from every failure mode: unconfigured,
;; unauthorized, unreachable, or rejected by the server.
;; ---------------------------------------------------------------------------

(deftest test-successful-publish-returns-ok-and-event-id
  (setup-mocks! {:server-url "http://localhost:3000"
                 :token "test-token"
                 :fetch-response {:success true :eventId "evt-abc-123"}})
  (testing "successful publish returns {:ok true :event-id ...}"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event"
             :source "plugin"
             :data {:key "value"}
             :metadata {:severity "info"}})
          (.then (fn [result]
                   (is (= {:ok true :event-id "evt-abc-123"} result)
                       "Should return a tagged success map")
                   (is (= 1 (count @fetch-calls)))
                   (let [{:keys [url body]} (first @fetch-calls)]
                     (is (= "http://localhost:3000/api/events/publish" url))
                     (is (= "test.event" (:type body)))
                     (is (= "plugin" (:source body)))
                     (is (= {:key "value"} (:data body)))
                     (is (= {:severity "info"} (:metadata body))))
                   (done)))))))

(deftest test-publish-uses-jwt-when-auth-mode-jwt
  (setup-mocks! {:server-url "http://localhost:3000"
                 :token "test-token"
                 :auth-mode "jwt"
                 :jwt-token "jwt-xyz-789"
                 :fetch-response {:success true :eventId "evt-jwt-1"}})
  (testing "publish-to-server! sends Authorization: Bearer <jwtToken> when authMode is jwt"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event"
             :source "plugin"
             :data {:key "value"}})
          (.then (fn [_result]
                   (is (= 1 (count @fetch-calls)))
                   (let [{:keys [authorization]} (first @fetch-calls)]
                     (is (= "Bearer jwt-xyz-789" authorization)
                         "Authorization header should carry the jwtToken, not pluginApiToken"))
                   (done)))))))

(deftest test-unauthorized-response-is-a-distinguishable-failure
  (setup-mocks! {:server-url "http://localhost:3000"
                 :token "bad-token"
                 :fetch-response {}
                 :fetch-ok false
                 :fetch-status 401})
  (testing "a 401 response body must never be read as a successful publish"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event" :source "plugin" :data {}})
          (.then (fn [result]
                   (is (false? (:ok result))
                       "a 401 must never parse as :ok true")
                   (is (= :unauthorized (:reason result)))
                   (is (re-find #"(?i)plugin api token" (:detail result))
                       "must name the Plugin API Token setting")
                   (done)))))))

(deftest test-success-flag-false-is-a-distinguishable-failure
  (setup-mocks! {:server-url "http://localhost:3000"
                 :token "test-token"
                 :fetch-response {:success false :error "duplicate event"}})
  (testing "a 2xx response with success:false is a failure, not a published event"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event" :source "plugin" :data {}})
          (.then (fn [result]
                   (is (false? (:ok result)))
                   (is (= :rejected (:reason result)))
                   (done)))))))

(deftest test-network-error-resolves-unreachable-never-rejects
  (setup-mocks! {:server-url "http://localhost:3000"
                 :token "test-token"
                 :fetch-response nil})
  ;; Override fetch to simulate a network error
  (set! js/fetch
    (fn [_url _opts]
      (js/Promise.reject (js/Error. "Network failure"))))
  (testing "a network-level failure resolves a tagged :unreachable failure, and still logs a warning"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event"
             :source "plugin"
             :data {}})
          (.then (fn [result]
                   (is (false? (:ok result))
                       "Should resolve :ok false, not throw")
                   (is (= :unreachable (:reason result)))
                   (is (pos? (count @warn-calls))
                       "Should have logged a warning")
                   (done)))
          (.catch (fn [err]
                    (is false
                        (str "publish-to-server! must never reject -- fire-and-forget callers rely on that: " err))
                    (done)))))))

(deftest test-missing-server-url-resolves-no-server-failure
  (setup-mocks! {:server-url nil
                 :token "test-token"
                 :fetch-response nil})
  (testing "missing server URL resolves a :no-server failure naming the setting, without calling fetch"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event"
             :source "plugin"
             :data {}})
          (.then (fn [result]
                   (is (false? (:ok result))
                       "Should return :ok false when server URL is missing")
                   (is (= :no-server (:reason result)))
                   (is (re-find #"(?i)webhook server url" (:detail result))
                       "must name the 'Webhook Server URL' setting")
                   (is (zero? (count @fetch-calls))
                       "Should not call fetch")
                   (is (pos? (count @warn-calls))
                       "Should log a warning about missing config")
                   (done)))))))

(deftest test-missing-token-resolves-no-server-failure
  (setup-mocks! {:server-url "http://localhost:3000"
                 :token nil
                 :fetch-response nil})
  (testing "missing token resolves a :no-server failure without calling fetch"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event"
             :source "plugin"
             :data {}})
          (.then (fn [result]
                   (is (false? (:ok result))
                       "Should return :ok false when token is missing")
                   (is (= :no-server (:reason result)))
                   (is (zero? (count @fetch-calls))
                       "Should not call fetch")
                   (done)))))))

(deftest test-blank-token-string-treated-as-missing
  (setup-mocks! {:server-url "http://localhost:3000"
                 :token ""
                 :fetch-response nil})
  (testing "a blank (cleared) token string is treated as missing -- not as a valid empty token"
    (async done
      (-> (publish/publish-to-server!
            {:type "test.event"
             :source "plugin"
             :data {}})
          (.then (fn [result]
                   (is (false? (:ok result)))
                   (is (= :no-server (:reason result)))
                   (is (zero? (count @fetch-calls))
                       "a blank string must not be forwarded to fetch as a real token")
                   (done)))))))
