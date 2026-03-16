(ns logseq-ai-hub.event-hub.commands-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.event-hub.commands :as commands]
            [logseq-ai-hub.registry.store :as store]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def insert-calls (atom []))
(def registered-commands (atom {}))

(defn- setup-mocks! []
  (reset! insert-calls [])
  (reset! registered-commands {})
  (store/init-store!)

  ;; Reset dynamic vars
  (set! commands/*fetch-recent-fn* nil)
  (set! commands/*fetch-sources-fn* nil)
  (set! commands/*publish-fn* nil)

  ;; Mock logseq API
  (set! js/logseq
    #js {:Editor #js {:insertBlock
                      (fn [uuid content opts]
                        (swap! insert-calls conj {:uuid uuid
                                                  :content content
                                                  :opts (js->clj opts :keywordize-keys true)})
                        (js/Promise.resolve nil))
                      :registerSlashCommand
                      (fn [cmd handler]
                        (swap! registered-commands assoc cmd handler))}
         :UI #js {:showMsg (fn [& _] nil)}}))

(defn- make-event [uuid]
  #js {:uuid uuid})

;; ---------------------------------------------------------------------------
;; event:recent Tests
;; ---------------------------------------------------------------------------

(deftest test-event-recent-with-events
  (setup-mocks!)
  (testing "event:recent inserts table with events"
    (set! commands/*fetch-recent-fn*
      (fn []
        (js/Promise.resolve
          [{:type "webhook.received"
            :source "webhook:grafana"
            :metadata {:severity "warning" :timestamp "2026-03-06T12:00:00Z"}}
           {:type "job.completed"
            :source "system:runner"
            :metadata {:severity "info" :timestamp "2026-03-06T12:01:00Z"}}])))
    (async done
      (-> (commands/handle-event-recent (make-event "block-1"))
          (.then (fn [_]
                   (is (= 1 (count @insert-calls)))
                   (let [{:keys [uuid content]} (first @insert-calls)]
                     (is (= "block-1" uuid))
                     (is (str/includes? content "| Type |"))
                     (is (str/includes? content "webhook.received"))
                     (is (str/includes? content "job.completed")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-event-recent-empty
  (setup-mocks!)
  (testing "event:recent inserts 'no events' when empty"
    (set! commands/*fetch-recent-fn*
      (fn [] (js/Promise.resolve [])))
    (async done
      (-> (commands/handle-event-recent (make-event "block-2"))
          (.then (fn [_]
                   (is (= 1 (count @insert-calls)))
                   (let [{:keys [content]} (first @insert-calls)]
                     (is (str/includes? content "No recent events")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-event-recent-no-fn
  (setup-mocks!)
  (testing "event:recent inserts fallback when *fetch-recent-fn* is nil"
    (commands/handle-event-recent (make-event "block-3"))
    ;; insertBlock is called synchronously when *fetch-recent-fn* is nil
    (is (= 1 (count @insert-calls)))
    (let [{:keys [content]} (first @insert-calls)]
      (is (str/includes? content "not available")))))

(deftest test-event-recent-error
  (setup-mocks!)
  (testing "event:recent handles fetch error gracefully"
    (set! commands/*fetch-recent-fn*
      (fn [] (js/Promise.reject (js/Error. "Network error"))))
    (async done
      (-> (commands/handle-event-recent (make-event "block-err"))
          (.then (fn [_]
                   (is (= 1 (count @insert-calls)))
                   (let [{:keys [content]} (first @insert-calls)]
                     (is (str/includes? content "Error")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; event:sources Tests
;; ---------------------------------------------------------------------------

(deftest test-event-sources-with-data
  (setup-mocks!)
  (testing "event:sources inserts list of sources"
    (set! commands/*fetch-sources-fn*
      (fn []
        (js/Promise.resolve
          ["webhook:grafana" "webhook:github" "system:job-runner"])))
    (async done
      (-> (commands/handle-event-sources (make-event "block-src"))
          (.then (fn [_]
                   (is (= 1 (count @insert-calls)))
                   (let [{:keys [content]} (first @insert-calls)]
                     (is (str/includes? content "webhook:grafana"))
                     (is (str/includes? content "webhook:github"))
                     (is (str/includes? content "system:job-runner")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-event-sources-empty
  (setup-mocks!)
  (testing "event:sources inserts 'no sources' when empty"
    (set! commands/*fetch-sources-fn*
      (fn [] (js/Promise.resolve [])))
    (async done
      (-> (commands/handle-event-sources (make-event "block-src2"))
          (.then (fn [_]
                   (is (= 1 (count @insert-calls)))
                   (let [{:keys [content]} (first @insert-calls)]
                     (is (str/includes? content "No active event sources")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

(deftest test-event-sources-no-fn
  (setup-mocks!)
  (testing "event:sources inserts fallback when *fetch-sources-fn* is nil"
    (commands/handle-event-sources (make-event "block-src3"))
    (is (= 1 (count @insert-calls)))
    (let [{:keys [content]} (first @insert-calls)]
      (is (str/includes? content "not available")))))

;; ---------------------------------------------------------------------------
;; event:test Tests
;; ---------------------------------------------------------------------------

(deftest test-event-test-publishes
  (setup-mocks!)
  (testing "event:test publishes test event and inserts confirmation"
    (let [publish-calls (atom [])]
      (set! commands/*publish-fn*
        (fn [event-map]
          (swap! publish-calls conj event-map)
          (js/Promise.resolve {:event-id "evt-test-123"})))
      (async done
        (-> (commands/handle-event-test (make-event "block-test"))
            (.then (fn [_]
                     ;; Verify publish was called with correct event
                     (is (= 1 (count @publish-calls)))
                     (let [published (first @publish-calls)]
                       (is (= "test.manual" (:type published)))
                       (is (= "user:slash-command" (:source published)))
                       (is (= {:triggered-by "user"} (:data published))))
                     ;; Verify confirmation block
                     (is (= 1 (count @insert-calls)))
                     (let [{:keys [content]} (first @insert-calls)]
                       (is (str/includes? content "Test event published"))
                       (is (str/includes? content "evt-test-123")))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Promise rejected: " err))
                      (done))))))))

(deftest test-event-test-no-fn
  (setup-mocks!)
  (testing "event:test inserts fallback when *publish-fn* is nil"
    (commands/handle-event-test (make-event "block-test2"))
    (is (= 1 (count @insert-calls)))
    (let [{:keys [content]} (first @insert-calls)]
      (is (str/includes? content "not available")))))

(deftest test-event-test-publish-error
  (setup-mocks!)
  (testing "event:test handles publish error gracefully"
    (set! commands/*publish-fn*
      (fn [_] (js/Promise.reject (js/Error. "Server down"))))
    (async done
      (-> (commands/handle-event-test (make-event "block-test-err"))
          (.then (fn [_]
                   (is (= 1 (count @insert-calls)))
                   (let [{:keys [content]} (first @insert-calls)]
                     (is (str/includes? content "Error")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Promise rejected: " err))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; event:list Tests
;; ---------------------------------------------------------------------------

(deftest test-event-list-with-subscriptions
  (setup-mocks!)
  (testing "event:list inserts formatted subscription list"
    (store/add-entry {:id "sub-1"
                      :type :event-subscription
                      :name "Alert Handler"
                      :event-pattern "webhook.*"
                      :event-action :skill
                      :event-severity-filter nil})
    (store/add-entry {:id "sub-2"
                      :type :event-subscription
                      :name "Error Notifier"
                      :event-pattern "job.failed"
                      :event-action :route
                      :event-severity-filter #{:error :critical}})
    (commands/handle-event-list (make-event "block-list"))
    (is (= 1 (count @insert-calls)))
    (let [{:keys [content]} (first @insert-calls)]
      (is (str/includes? content "Alert Handler"))
      (is (str/includes? content "webhook.*"))
      (is (str/includes? content "Error Notifier"))
      (is (str/includes? content "job.failed")))))

(deftest test-event-list-empty
  (setup-mocks!)
  (testing "event:list inserts 'no subscriptions' when empty"
    (commands/handle-event-list (make-event "block-list2"))
    (is (= 1 (count @insert-calls)))
    (let [{:keys [content]} (first @insert-calls)]
      (is (str/includes? content "No event subscriptions registered")))))

;; ---------------------------------------------------------------------------
;; Registration Test
;; ---------------------------------------------------------------------------

(deftest test-register-commands
  (setup-mocks!)
  (testing "register-commands! registers all 4 commands"
    (let [original-log js/console.log]
      ;; Suppress console.log during registration only
      (set! js/console.log (fn [& _] nil))
      (commands/register-commands!)
      ;; Restore console.log
      (set! js/console.log original-log))
    (is (some? (get @registered-commands "event:recent")))
    (is (some? (get @registered-commands "event:sources")))
    (is (some? (get @registered-commands "event:test")))
    (is (some? (get @registered-commands "event:list")))))
