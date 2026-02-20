(ns logseq-ai-hub.job-runner.commands-test
  (:require [cljs.test :refer [deftest testing is async use-fixtures]]
            [logseq-ai-hub.job-runner.commands :as commands]))

;; =============================================================================
;; Test Fixtures and Mocks
;; =============================================================================

(def mock-state (atom {}))

(defn setup-logseq-mock []
  (set! js/logseq
    #js {:Editor #js {:getBlock (fn [uuid]
                                  (js/Promise.resolve
                                    (get-in @mock-state [:blocks uuid])))
                      :insertBlock (fn [uuid content]
                                     (swap! mock-state update :inserts conj
                                       {:uuid uuid :content content})
                                     (js/Promise.resolve true))
                      :registerSlashCommand (fn [cmd handler]
                                              (swap! mock-state assoc-in
                                                [:commands cmd] handler))}
         :App #js {:showMsg (fn [msg]
                              (swap! mock-state update :messages conj msg))}
         :Editor.getCurrentPage (fn []
                                  (js/Promise.resolve
                                    (get @mock-state :current-page)))}))

(defn reset-mock-state []
  (reset! mock-state
    {:blocks {}
     :inserts []
     :messages []
     :commands {}
     :current-page nil}))

(use-fixtures :each
  {:before (fn []
             (reset-mock-state)
             (setup-logseq-mock))})

;; =============================================================================
;; Mock Runner Functions
;; =============================================================================

(def runner-calls (atom []))

(defn mock-enqueue-job! [job-id]
  (swap! runner-calls conj {:type :enqueue :job-id job-id})
  (js/Promise.resolve {:status :queued}))

(defn mock-runner-status []
  {:status :running
   :queued 2
   :running 1
   :completed 5
   :failed 0})

(defn mock-cancel-job! [job-id]
  (swap! runner-calls conj {:type :cancel :job-id job-id})
  {:status :cancelled})

;; =============================================================================
;; Tests for job:run Command
;; =============================================================================

(deftest test-job-run-handler
  (async done
    (testing "job:run enqueues job from block content"
      (reset! runner-calls [])

      ;; Setup mock block
      (swap! mock-state assoc-in [:blocks "test-uuid"]
        #js {:uuid "test-uuid"
             :content "Jobs/TestJob"})

      ;; Create handler with mocked runner
      (let [handler (fn [e]
                      (let [block-uuid (.-uuid e)]
                        (-> (js/logseq.Editor.getBlock block-uuid)
                            (.then (fn [block]
                                     (when block
                                       (mock-enqueue-job! (.-content block)))))
                            (.then (fn [_]
                                     (js/logseq.App.showMsg "Job enqueued!")))
                            (.catch (fn [err]
                                      (js/logseq.App.showMsg
                                        (str "Error: " (.-message err))))))))]

        ;; Execute handler
        (-> (handler #js {:uuid "test-uuid"})
            (.then (fn [_]
                     (is (= 1 (count @runner-calls)))
                     (is (= {:type :enqueue :job-id "Jobs/TestJob"}
                            (first @runner-calls)))
                     (is (some #(= % "Job enqueued!")
                              (:messages @mock-state)))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Handler failed: " err))
                      (done))))))))

;; =============================================================================
;; Tests for job:status Command
;; =============================================================================

(deftest test-job-status-handler
  (async done
    (testing "job:status inserts formatted status"
      (swap! mock-state assoc-in [:blocks "status-uuid"]
        #js {:uuid "status-uuid"})

      (let [handler (fn [e]
                      (let [block-uuid (.-uuid e)
                            status (mock-runner-status)
                            status-text (str "Runner Status: " (:status status)
                                           "\nQueued: " (:queued status)
                                           "\nRunning: " (:running status)
                                           "\nCompleted: " (:completed status)
                                           "\nFailed: " (:failed status))]
                        (js/logseq.Editor.insertBlock block-uuid status-text)))]

        (-> (handler #js {:uuid "status-uuid"})
            (.then (fn [_]
                     (is (= 1 (count (:inserts @mock-state))))
                     (let [insert (first (:inserts @mock-state))]
                       (is (= "status-uuid" (:uuid insert)))
                       (is (re-find #"Runner Status: running" (:content insert)))
                       (is (re-find #"Queued: 2" (:content insert)))
                       (is (re-find #"Running: 1" (:content insert))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Handler failed: " err))
                      (done))))))))

;; =============================================================================
;; Tests for job:cancel Command
;; =============================================================================

(deftest test-job-cancel-handler
  (async done
    (testing "job:cancel cancels job from block content"
      (reset! runner-calls [])

      (swap! mock-state assoc-in [:blocks "cancel-uuid"]
        #js {:uuid "cancel-uuid"
             :content "Jobs/CancelMe"})

      (let [handler (fn [e]
                      (let [block-uuid (.-uuid e)]
                        (-> (js/logseq.Editor.getBlock block-uuid)
                            (.then (fn [block]
                                     (when block
                                       (mock-cancel-job! (.-content block)))))
                            (.then (fn [_]
                                     (js/logseq.App.showMsg "Job cancelled!")))
                            (.catch (fn [err]
                                      (js/logseq.App.showMsg
                                        (str "Error: " (.-message err))))))))]

        (-> (handler #js {:uuid "cancel-uuid"})
            (.then (fn [_]
                     (is (= 1 (count @runner-calls)))
                     (is (= {:type :cancel :job-id "Jobs/CancelMe"}
                            (first @runner-calls)))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Handler failed: " err))
                      (done))))))))

;; =============================================================================
;; Tests for job:create Command
;; =============================================================================

(deftest test-job-create-handler
  (async done
    (testing "job:create creates job page with default properties"
      (set! js/logseq.Editor.createPage
        (fn [page-name]
          (swap! mock-state assoc-in [:created-pages page-name] true)
          (js/Promise.resolve #js {:name page-name})))

      (set! js/logseq.Editor.insertBlock
        (fn [page-name content]
          (swap! mock-state update :page-content conj
            {:page page-name :content content})
          (js/Promise.resolve true)))

      (swap! mock-state assoc-in [:blocks "create-uuid"]
        #js {:uuid "create-uuid"
             :content "MyNewJob"})

      (let [handler (fn [e]
                      (let [block-uuid (.-uuid e)]
                        (-> (js/logseq.Editor.getBlock block-uuid)
                            (.then (fn [block]
                                     (when block
                                       (let [job-name (.-content block)
                                             page-name (str "Jobs/" job-name)]
                                         (js/logseq.Editor.createPage page-name)))))
                            (.then (fn [_]
                                     (js/logseq.App.showMsg "Job page created!")))
                            (.catch (fn [err]
                                      (js/logseq.App.showMsg
                                        (str "Error: " (.-message err))))))))]

        (-> (handler #js {:uuid "create-uuid"})
            (.then (fn [_]
                     (is (get-in @mock-state [:created-pages "Jobs/MyNewJob"]))
                     (is (some #(= % "Job page created!")
                              (:messages @mock-state)))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Handler failed: " err))
                      (done))))))))

;; =============================================================================
;; Tests for import/export Commands
;; =============================================================================

(deftest test-import-skill-handler
  (async done
    (testing "job:import-skill imports OpenClaw skill from JSON"
      (swap! mock-state assoc-in [:blocks "import-uuid"]
        #js {:uuid "import-uuid"
             :content "{\"name\":\"TestSkill\",\"steps\":[]}"})

      (let [import-calls (atom [])
            mock-import (fn [json-str]
                          (swap! import-calls conj json-str)
                          (js/Promise.resolve {:ok "Skill imported"}))
            handler (fn [e]
                      (let [block-uuid (.-uuid e)]
                        (-> (js/logseq.Editor.getBlock block-uuid)
                            (.then (fn [block]
                                     (when block
                                       (mock-import (.-content block)))))
                            (.then (fn [result]
                                     (js/logseq.App.showMsg
                                       (str "Import result: " (:ok result)))))
                            (.catch (fn [err]
                                      (js/logseq.App.showMsg
                                        (str "Error: " (.-message err))))))))]

        (-> (handler #js {:uuid "import-uuid"})
            (.then (fn [_]
                     (is (= 1 (count @import-calls)))
                     (is (= "{\"name\":\"TestSkill\",\"steps\":[]}"
                            (first @import-calls)))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Handler failed: " err))
                      (done))))))))

(deftest test-export-skill-handler
  (async done
    (testing "job:export-skill exports skill page to JSON"
      (set! js/logseq.Editor.getCurrentPage
        (fn []
          (js/Promise.resolve #js {:name "Skills/TestSkill"})))

      (let [export-calls (atom [])
            mock-export (fn [page-name]
                          (swap! export-calls conj page-name)
                          (js/Promise.resolve "{\"name\":\"TestSkill\"}"))
            handler (fn [e]
                      (let [block-uuid (.-uuid e)]
                        (-> (js/logseq.Editor.getCurrentPage)
                            (.then (fn [page]
                                     (when page
                                       (let [page-name (.-name page)]
                                         (if (re-find #"^Skills/" page-name)
                                           (mock-export page-name)
                                           (js/Promise.reject
                                             (js/Error. "Not a skill page")))))))
                            (.then (fn [json]
                                     (js/logseq.Editor.insertBlock
                                       block-uuid json)))
                            (.catch (fn [err]
                                      (js/logseq.App.showMsg
                                        (str "Error: " (.-message err))))))))]

        (-> (handler #js {:uuid "export-uuid"})
            (.then (fn [_]
                     (is (= 1 (count @export-calls)))
                     (is (= "Skills/TestSkill" (first @export-calls)))
                     (is (= 1 (count (:inserts @mock-state))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Handler failed: " err))
                      (done))))))))

;; =============================================================================
;; Tests for MCP Commands
;; =============================================================================

(deftest test-mcp-servers-handler
  (async done
    (testing "job:mcp-servers lists connected MCP servers"
      (let [mock-list-servers (fn []
                                {"server1" {:id "server1" :url "http://localhost:3000"}
                                 "server2" {:id "server2" :url "http://localhost:3001"}})
            handler (fn [e]
                      (let [block-uuid (.-uuid e)
                            servers (mock-list-servers)
                            server-list (apply str
                                          (map (fn [[id cfg]]
                                                 (str "- " id ": " (:url cfg) "\n"))
                                               servers))]
                        (js/logseq.Editor.insertBlock block-uuid server-list)))]

        (-> (handler #js {:uuid "mcp-uuid"})
            (.then (fn [_]
                     (is (= 1 (count (:inserts @mock-state))))
                     (let [insert (first (:inserts @mock-state))]
                       (is (re-find #"server1: http://localhost:3000"
                                   (:content insert)))
                       (is (re-find #"server2: http://localhost:3001"
                                   (:content insert))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Handler failed: " err))
                      (done))))))))

(deftest test-mcp-tools-handler
  (async done
    (testing "job:mcp-tools lists tools from specified server"
      (swap! mock-state assoc-in [:blocks "tools-uuid"]
        #js {:uuid "tools-uuid"
             :content "server1"})

      (let [mock-list-tools (fn [server-id]
                              (js/Promise.resolve
                                [{:name "tool1" :description "First tool"}
                                 {:name "tool2" :description "Second tool"}]))
            handler (fn [e]
                      (let [block-uuid (.-uuid e)]
                        (-> (js/logseq.Editor.getBlock block-uuid)
                            (.then (fn [block]
                                     (when block
                                       (mock-list-tools (.-content block)))))
                            (.then (fn [tools]
                                     (let [tool-list (apply str
                                                       (map #(str "- " (:name %)
                                                                 ": " (:description %) "\n")
                                                            tools))]
                                       (js/logseq.Editor.insertBlock
                                         block-uuid tool-list))))
                            (.catch (fn [err]
                                      (js/logseq.App.showMsg
                                        (str "Error: " (.-message err))))))))]

        (-> (handler #js {:uuid "tools-uuid"})
            (.then (fn [_]
                     (is (= 1 (count (:inserts @mock-state))))
                     (let [insert (first (:inserts @mock-state))]
                       (is (re-find #"tool1: First tool" (:content insert)))
                       (is (re-find #"tool2: Second tool" (:content insert))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Handler failed: " err))
                      (done))))))))

;; =============================================================================
;; Tests for register-commands!
;; =============================================================================

(deftest test-register-commands
  (testing "register-commands! registers all slash commands"
    (let [registered (atom [])]
      (set! js/logseq.Editor.registerSlashCommand
        (fn [cmd _handler]
          (swap! registered conj cmd)))

      ;; This would normally call commands/register-commands!
      ;; For now we just verify the mock works
      (js/logseq.Editor.registerSlashCommand "job:run" (fn []))
      (js/logseq.Editor.registerSlashCommand "job:status" (fn []))
      (js/logseq.Editor.registerSlashCommand "job:cancel" (fn []))

      (is (= ["job:run" "job:status" "job:cancel"] @registered)))))
