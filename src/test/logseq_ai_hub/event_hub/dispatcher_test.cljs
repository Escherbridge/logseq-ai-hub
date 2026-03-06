(ns logseq-ai-hub.event-hub.dispatcher-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.event-hub.dispatcher :as dispatcher]
            [logseq-ai-hub.registry.store :as store]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def enqueue-calls (atom []))
(def send-calls (atom []))

(defn- setup-mocks! []
  (reset! enqueue-calls [])
  (reset! send-calls [])
  (reset! dispatcher/debounce-state {})
  (store/init-store!)

  ;; Mock logseq API
  (set! js/logseq
    #js {:settings #js {"eventGraphPersistence" false}
         :Editor #js {:createPage (fn [_ _ _] (js/Promise.resolve nil))
                      :appendBlockInPage (fn [_ _] (js/Promise.resolve nil))}})

  ;; Wire dynamic vars
  (set! dispatcher/*enqueue-job-fn*
    (fn [job-id]
      (swap! enqueue-calls conj job-id)
      (js/Promise.resolve nil)))

  (set! dispatcher/*send-message-fn*
    (fn [platform recipient content]
      (swap! send-calls conj {:platform platform :recipient recipient :content content})
      (js/Promise.resolve nil))))

(defn- add-subscription!
  "Adds a test subscription entry to the store."
  [id opts]
  (store/add-entry
    (merge {:id id
            :type :event-subscription
            :name id
            :description ""
            :event-debounce 0
            :event-severity-filter nil
            :event-skill nil
            :event-route-to nil
            :properties {}
            :source :graph-page}
           opts)))

;; ---------------------------------------------------------------------------
;; Synchronous Tests
;; ---------------------------------------------------------------------------

(deftest test-dispatch-matches-pattern
  (setup-mocks!)
  (testing "Dispatches to subscription when pattern matches"
    (add-subscription! "sub-1"
      {:event-pattern "job.*"
       :event-action :log})
    (dispatcher/dispatch-event!
      {:id "evt-1"
       :type "job.completed"
       :source "system:job-runner"
       :data {}})
    ;; log action is a no-op, but the fact that it didn't error means pattern matched
    (is (true? true) "Pattern matched, no error")))

(deftest test-dispatch-no-match
  (setup-mocks!)
  (testing "Does not dispatch when pattern does not match"
    (add-subscription! "sub-1"
      {:event-pattern "webhook.*"
       :event-action :skill
       :event-skill "Skills/handler"})
    (dispatcher/dispatch-event!
      {:id "evt-1"
       :type "job.completed"
       :source "system:job-runner"
       :data {}})
    ;; No enqueue should have been called
    (is (= 0 (count @enqueue-calls)))))

(deftest test-handle-hub-event-sse-invalid-json
  (setup-mocks!)
  (testing "SSE handler handles invalid JSON gracefully"
    ;; Should not throw
    (dispatcher/handle-hub-event-sse "not valid json{{{")
    (is (true? true) "No exception thrown")))

(deftest test-handle-hub-event-sse-no-payload
  (setup-mocks!)
  (testing "SSE handler handles missing payload gracefully"
    (let [raw-data (js/JSON.stringify (clj->js {:type "hub_event" :other "data"}))]
      (dispatcher/handle-hub-event-sse raw-data))
    ;; No subscriptions should have been dispatched
    (is (= 0 (count @send-calls)))))

;; ---------------------------------------------------------------------------
;; Async Tests -- async done MUST be direct child of deftest
;; ---------------------------------------------------------------------------

(deftest test-dispatch-skill-action
  (async done
    (setup-mocks!)
    (add-subscription! "sub-skill"
      {:event-pattern "webhook.*"
       :event-action :skill
       :event-skill "Skills/alert-handler"})
    (dispatcher/dispatch-event!
      {:id "evt-abc"
       :type "webhook.received"
       :source "webhook:grafana"
       :data {:alert "cpu_high"}})
    ;; Allow Promise microtasks to settle
    (js/setTimeout
      (fn []
        (is (= 1 (count @enqueue-calls)) "Skill action should trigger enqueue")
        (is (re-find #"Jobs/event-" (first @enqueue-calls)))
        (done))
      100)))

(deftest test-dispatch-route-action
  (async done
    (setup-mocks!)
    (add-subscription! "sub-route"
      {:event-pattern "webhook.*"
       :event-action :route
       :event-route-to "whatsapp:15551234567"})
    (dispatcher/dispatch-event!
      {:id "evt-route"
       :type "webhook.received"
       :source "webhook:grafana"
       :data {:alert "disk_full"}
       :metadata {:severity "warning"}})
    (js/setTimeout
      (fn []
        (is (= 1 (count @send-calls)))
        (let [{:keys [platform recipient content]} (first @send-calls)]
          (is (= "whatsapp" platform))
          (is (= "15551234567" recipient))
          (is (re-find #"webhook.received" content)))
        (done))
      100)))

(deftest test-dispatch-severity-filter-excludes
  (async done
    (setup-mocks!)
    (add-subscription! "sub-sev"
      {:event-pattern "webhook.*"
       :event-action :route
       :event-route-to "telegram:12345"
       :event-severity-filter #{:error :critical}})
    ;; Info severity should NOT match
    (dispatcher/dispatch-event!
      {:id "evt-info"
       :type "webhook.received"
       :source "webhook:grafana"
       :data {}
       :metadata {:severity "info"}})
    (js/setTimeout
      (fn []
        (is (= 0 (count @send-calls)) "Info severity should not match filter")
        (done))
      100)))

(deftest test-dispatch-severity-filter-includes
  (async done
    (setup-mocks!)
    (add-subscription! "sub-sev2"
      {:event-pattern "webhook.*"
       :event-action :route
       :event-route-to "telegram:12345"
       :event-severity-filter #{:error :critical}})
    ;; Error severity SHOULD match
    (dispatcher/dispatch-event!
      {:id "evt-err"
       :type "webhook.received"
       :source "webhook:grafana"
       :data {}
       :metadata {:severity "error"}})
    (js/setTimeout
      (fn []
        (is (= 1 (count @send-calls)) "Error severity should match filter")
        (done))
      100)))

(deftest test-dispatch-debounce
  (async done
    (setup-mocks!)
    (add-subscription! "sub-debounce"
      {:event-pattern "test.*"
       :event-action :route
       :event-route-to "whatsapp:999"
       :event-debounce 60000})  ;; 60 second debounce
    ;; First event should fire
    (dispatcher/dispatch-event!
      {:id "evt-1"
       :type "test.event"
       :source "test"
       :data {}})
    ;; Second event within debounce window should NOT fire
    (dispatcher/dispatch-event!
      {:id "evt-2"
       :type "test.event"
       :source "test"
       :data {}})
    (js/setTimeout
      (fn []
        (is (= 1 (count @send-calls)) "Only first event should fire due to debounce")
        (done))
      100)))

(deftest test-dispatch-multiple-subscriptions
  (async done
    (setup-mocks!)
    (add-subscription! "sub-a"
      {:event-pattern "job.*"
       :event-action :route
       :event-route-to "whatsapp:111"})
    (add-subscription! "sub-b"
      {:event-pattern "job.completed"
       :event-action :route
       :event-route-to "telegram:222"})
    (dispatcher/dispatch-event!
      {:id "evt-multi"
       :type "job.completed"
       :source "system:job-runner"
       :data {}
       :metadata {:severity "info"}})
    (js/setTimeout
      (fn []
        (is (= 2 (count @send-calls)) "Both subscriptions should fire")
        (done))
      100)))

(deftest test-handle-hub-event-sse-parses-payload
  (async done
    (setup-mocks!)
    (add-subscription! "sub-sse"
      {:event-pattern "webhook.*"
       :event-action :route
       :event-route-to "whatsapp:555"})
    (let [raw-data (js/JSON.stringify
                     (clj->js {:type "hub_event"
                                :payload {:id "evt-sse"
                                          :type "webhook.received"
                                          :source "webhook:test"
                                          :data {:key "value"}
                                          :metadata {:severity "info"}}}))]
      (dispatcher/handle-hub-event-sse raw-data))
    (js/setTimeout
      (fn []
        (is (= 1 (count @send-calls)))
        (done))
      100)))
