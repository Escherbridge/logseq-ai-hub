(ns logseq-ai-hub.event-hub.init-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [logseq-ai-hub.event-hub.init :as event-hub-init]
            [logseq-ai-hub.event-hub.dispatcher :as dispatcher]
            [logseq-ai-hub.messaging :as messaging]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def sse-listeners (atom []))

(defn- setup-mocks!
  "Sets up mocks for init testing."
  [& {:keys [enabled?] :or {enabled? true}}]
  (reset! sse-listeners [])
  (reset! event-hub-init/initialized? false)

  ;; Clean up dynamic vars
  (set! dispatcher/*enqueue-job-fn* nil)
  (set! dispatcher/*read-skill-fn* nil)
  (set! dispatcher/*send-message-fn* nil)

  ;; Mock logseq
  (set! js/logseq
    #js {:settings #js {"eventHubEnabled" enabled?
                         "webhookServerUrl" "http://localhost:3000"
                         "pluginApiToken" "test-token"}})

  ;; Mock console
  (set! js/console.log (fn [& _] nil))

  ;; Mock messaging state with a fake EventSource
  (let [fake-es #js {:addEventListener
                     (fn [event-type handler]
                       (swap! sse-listeners conj {:event-type event-type
                                                   :handler handler}))}]
    (reset! messaging/state {:event-source fake-es
                              :server-url "http://localhost:3000"
                              :api-token "test-token"
                              :connected? true
                              :message-handlers []
                              :intentional-disconnect? false
                              :reconnect-attempt 0
                              :reconnect-timer nil})))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest test-init-wires-dynamic-vars
  (setup-mocks!)
  (testing "init! wires dispatcher dynamic vars"
    (event-hub-init/init!)
    (is (some? dispatcher/*enqueue-job-fn*)
        "enqueue-job-fn should be wired")
    (is (some? dispatcher/*read-skill-fn*)
        "read-skill-fn should be wired")
    (is (some? dispatcher/*send-message-fn*)
        "send-message-fn should be wired")))

(deftest test-init-registers-sse-listener
  (setup-mocks!)
  (testing "init! registers hub_event SSE listener"
    (event-hub-init/init!)
    (is (= 1 (count @sse-listeners)))
    (is (= "hub_event" (:event-type (first @sse-listeners))))))

(deftest test-init-sets-initialized-flag
  (setup-mocks!)
  (testing "init! sets initialized? to true"
    (is (false? @event-hub-init/initialized?))
    (event-hub-init/init!)
    (is (true? @event-hub-init/initialized?))))

(deftest test-init-idempotent
  (setup-mocks!)
  (testing "init! is idempotent -- second call is a no-op"
    (event-hub-init/init!)
    (is (= 1 (count @sse-listeners)))
    ;; Second call should not register another listener
    (event-hub-init/init!)
    (is (= 1 (count @sse-listeners)))))

(deftest test-init-disabled
  (setup-mocks! :enabled? false)
  (testing "init! does nothing when eventHubEnabled is false"
    (event-hub-init/init!)
    (is (false? @event-hub-init/initialized?))
    (is (= 0 (count @sse-listeners)))
    (is (nil? dispatcher/*enqueue-job-fn*))))

(deftest test-init-no-event-source
  (testing "init! handles missing EventSource gracefully"
    (reset! event-hub-init/initialized? false)
    (reset! sse-listeners [])
    (set! dispatcher/*enqueue-job-fn* nil)
    (set! dispatcher/*read-skill-fn* nil)
    (set! dispatcher/*send-message-fn* nil)
    (set! js/logseq
      #js {:settings #js {"eventHubEnabled" true}})
    (set! js/console.log (fn [& _] nil))
    ;; Set messaging state with nil event-source
    (reset! messaging/state {:event-source nil
                              :server-url nil
                              :api-token nil
                              :connected? false
                              :message-handlers []
                              :intentional-disconnect? false
                              :reconnect-attempt 0
                              :reconnect-timer nil})
    (event-hub-init/init!)
    ;; Should still wire vars but no listener since no ES
    (is (true? @event-hub-init/initialized?))
    (is (some? dispatcher/*enqueue-job-fn*))
    (is (= 0 (count @sse-listeners)))))
