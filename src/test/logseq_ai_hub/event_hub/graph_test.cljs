(ns logseq-ai-hub.event-hub.graph-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.event-hub.graph :as event-graph]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def created-pages (atom []))
(def appended-blocks (atom []))

(defn- setup-mocks! []
  (reset! created-pages [])
  (reset! appended-blocks [])
  (set! js/logseq
    #js {:Editor #js {:createPage
                      (fn [page-name _props _opts]
                        (swap! created-pages conj page-name)
                        (js/Promise.resolve #js {:name page-name}))
                      :appendBlockInPage
                      (fn [page-name content]
                        (swap! appended-blocks conj {:page page-name :content content})
                        (js/Promise.resolve #js {:uuid "block-123"}))}}))

;; ---------------------------------------------------------------------------
;; Tests -- async done as direct child of deftest
;; ---------------------------------------------------------------------------

(deftest test-persist-to-graph-creates-page-and-block
  (async done
    (setup-mocks!)
    (-> (event-graph/persist-to-graph!
          {:id "evt-123"
           :type "webhook.received"
           :source "webhook:grafana"
           :timestamp "2026-03-05T10:00:00Z"
           :data {:alert "cpu_high" :value 95}
           :metadata {:severity "warning"}})
        (.then (fn [_]
                 ;; Page should have been created
                 (is (= 1 (count @created-pages)))
                 (is (= "Events/webhook-grafana" (first @created-pages)))

                 ;; Block should have been appended
                 (is (= 1 (count @appended-blocks)))
                 (let [{:keys [page content]} (first @appended-blocks)]
                   (is (= "Events/webhook-grafana" page))
                   (is (re-find #"webhook.received" content))
                   (is (re-find #"evt-123" content))
                   (is (re-find #"webhook:grafana" content))
                   (is (re-find #"warning" content)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Error: " err))
                  (done))))))

(deftest test-persist-to-graph-sanitizes-source
  (async done
    (setup-mocks!)
    (-> (event-graph/persist-to-graph!
          {:id "evt-456"
           :type "job.completed"
           :source "system:job-runner"
           :timestamp "2026-03-05T10:00:00Z"
           :data {}
           :metadata {}})
        (.then (fn [_]
                 (is (= "Events/system-job-runner" (first @created-pages)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Error: " err))
                  (done))))))

(deftest test-persist-to-graph-page-already-exists
  (async done
    (reset! created-pages [])
    (reset! appended-blocks [])
    ;; Simulate createPage rejecting (page already exists)
    (set! js/logseq
      #js {:Editor #js {:createPage
                        (fn [_page-name _props _opts]
                          (js/Promise.reject (js/Error. "Page already exists")))
                        :appendBlockInPage
                        (fn [page-name content]
                          (swap! appended-blocks conj {:page page-name :content content})
                          (js/Promise.resolve #js {:uuid "block-456"}))}})
    (-> (event-graph/persist-to-graph!
          {:id "evt-789"
           :type "test.event"
           :source "test"
           :timestamp "2026-03-05T10:00:00Z"
           :data {:key "value"}
           :metadata {:severity "info"}})
        (.then (fn [_]
                 ;; Should still append the block even if createPage fails
                 (is (= 1 (count @appended-blocks)))
                 (is (= "Events/test" (:page (first @appended-blocks))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Error: " err))
                  (done))))))

(deftest test-persist-to-graph-formats-block-content
  (async done
    (setup-mocks!)
    (-> (event-graph/persist-to-graph!
          {:id "evt-fmt"
           :type "custom.deploy"
           :source "skill:deploy-job"
           :timestamp "2026-03-05T12:30:00Z"
           :data {:env "production" :version "1.2.3"}
           :metadata {:severity "info"}})
        (.then (fn [_]
                 (let [content (:content (first @appended-blocks))]
                   (is (re-find #"custom.deploy" content))
                   (is (re-find #"event-id:: evt-fmt" content))
                   (is (re-find #"event-type:: custom.deploy" content))
                   (is (re-find #"event-source:: skill:deploy-job" content))
                   (is (re-find #"event-severity:: info" content)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Error: " err))
                  (done))))))
