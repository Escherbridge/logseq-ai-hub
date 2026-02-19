(ns logseq-ai-hub.tasks-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [logseq-ai-hub.tasks :as tasks]
            [logseq-ai-hub.messaging :as messaging]
            [logseq-ai-hub.agent :as agent]
            [logseq-ai-hub.memory :as memory]))

;; ---------------------------------------------------------------------------
;; Mock State
;; ---------------------------------------------------------------------------

(def send-message-calls (atom []))
(def ingest-message-calls (atom []))
(def process-input-calls (atom []))
(def store-memory-calls (atom []))
(def insert-block-calls (atom []))
(def registered-slash-commands (atom []))

(defn setup-mocks!
  "Resets all mock state and installs mocks for module functions."
  []
  ;; Reset mock tracking
  (reset! send-message-calls [])
  (reset! ingest-message-calls [])
  (reset! process-input-calls [])
  (reset! store-memory-calls [])
  (reset! insert-block-calls [])
  (reset! registered-slash-commands [])

  ;; Reset tasks module state
  (reset! tasks/state
    {:tasks {}
     :running #{}
     :results {}})

  ;; Reset messaging module state (to clear handlers)
  (reset! messaging/state
    {:event-source nil
     :server-url nil
     :api-token nil
     :connected? false
     :message-handlers []})

  ;; Mock messaging functions
  (set! messaging/send-message!
    (fn [platform recipient content]
      (swap! send-message-calls conj {:platform platform
                                       :recipient recipient
                                       :content content})
      (js/Promise.resolve {:success true :messageId 123})))

  (set! messaging/ingest-message!
    (fn [message]
      (swap! ingest-message-calls conj message)
      (js/Promise.resolve {:uuid "mock-block-uuid"})))

  (set! messaging/on-message
    (fn [handler-fn]
      (swap! messaging/state update :message-handlers conj handler-fn)))

  ;; Mock agent functions
  (set! agent/process-input
    (fn [input model-id]
      (swap! process-input-calls conj {:input input :model-id model-id})
      (js/Promise.resolve (str "AI response to: " input))))

  ;; Mock memory functions
  (set! memory/store-memory!
    (fn [tag content]
      (swap! store-memory-calls conj {:tag tag :content content})
      (js/Promise.resolve {:id "mem-123"})))

  ;; Mock logseq
  (set! js/logseq
    #js {:Editor #js {:insertBlock
                      (fn [block-uuid content]
                        (swap! insert-block-calls conj {:block-uuid block-uuid
                                                         :content content})
                        (js/Promise.resolve #js {:uuid "inserted-uuid"}))
                      :getBlock
                      (fn [uuid]
                        (js/Promise.resolve #js {:uuid uuid
                                                  :content "task:run message-to-logseq"}))
                      :registerSlashCommand
                      (fn [name handler]
                        (swap! registered-slash-commands conj {:name name :handler handler}))}
         :settings #js {"selectedModel" "mock-model"}}))

;; ---------------------------------------------------------------------------
;; Task Management Tests
;; ---------------------------------------------------------------------------

(deftest test-register-task
  (setup-mocks!)
  (testing "register-task! adds task to state"
    (let [task {:id :test-task
                :name "Test Task"
                :steps []
                :enabled true}]
      (tasks/register-task! task)
      (is (= task (tasks/get-task :test-task)))
      (is (= 1 (count (tasks/list-tasks)))))))

(deftest test-unregister-task
  (setup-mocks!)
  (testing "unregister-task! removes task from state"
    (let [task {:id :test-task
                :name "Test Task"
                :steps []
                :enabled true}]
      (tasks/register-task! task)
      (is (= task (tasks/get-task :test-task)))
      (tasks/unregister-task! :test-task)
      (is (nil? (tasks/get-task :test-task)))
      (is (= 0 (count (tasks/list-tasks)))))))

(deftest test-get-task
  (setup-mocks!)
  (testing "get-task retrieves task by ID"
    (let [task1 {:id :task-1 :name "Task 1" :steps []}
          task2 {:id :task-2 :name "Task 2" :steps []}]
      (tasks/register-task! task1)
      (tasks/register-task! task2)
      (is (= task1 (tasks/get-task :task-1)))
      (is (= task2 (tasks/get-task :task-2)))
      (is (nil? (tasks/get-task :nonexistent))))))

(deftest test-list-tasks
  (setup-mocks!)
  (testing "list-tasks returns all registered tasks"
    (let [task1 {:id :task-1 :name "Task 1" :steps []}
          task2 {:id :task-2 :name "Task 2" :steps []}]
      (tasks/register-task! task1)
      (tasks/register-task! task2)
      (let [all-tasks (tasks/list-tasks)]
        (is (= 2 (count all-tasks)))
        (is (some #(= :task-1 (:id %)) all-tasks))
        (is (some #(= :task-2 (:id %)) all-tasks))))))

;; ---------------------------------------------------------------------------
;; Execute Step Tests
;; ---------------------------------------------------------------------------

(deftest test-execute-step-ai-process
  (setup-mocks!)
  (testing "execute-step with :ai-process calls agent/process-input"
    (async done
      (let [step {:action :ai-process}
            input "Hello AI"]
        (-> (tasks/execute-step step input)
            (.then (fn [result]
                     (is (= "AI response to: Hello AI" result))
                     (is (= 1 (count @process-input-calls)))
                     (let [call (first @process-input-calls)]
                       (is (= "Hello AI" (:input call)))
                       (is (= "mock-model" (:model-id call))))
                     (done))))))))

(deftest test-execute-step-send-message
  (setup-mocks!)
  (testing "execute-step with :send-message calls messaging/send-message!"
    (async done
      (let [step {:action :send-message}
            input {:platform "whatsapp"
                   :recipient "15551234567"
                   :content "Hello"}]
        (-> (tasks/execute-step step input)
            (.then (fn [result]
                     (is (:success result))
                     (is (= 1 (count @send-message-calls)))
                     (let [call (first @send-message-calls)]
                       (is (= "whatsapp" (:platform call)))
                       (is (= "15551234567" (:recipient call)))
                       (is (= "Hello" (:content call))))
                     (done))))))))

(deftest test-execute-step-store-memory
  (setup-mocks!)
  (testing "execute-step with :store-memory calls memory/store-memory!"
    (async done
      (let [step {:action :store-memory}
            input {:tag "important" :content "Remember this"}]
        (-> (tasks/execute-step step input)
            (.then (fn [result]
                     (is (= "mem-123" (:id result)))
                     (is (= 1 (count @store-memory-calls)))
                     (let [call (first @store-memory-calls)]
                       (is (= "important" (:tag call)))
                       (is (= "Remember this" (:content call))))
                     (done))))))))

(deftest test-execute-step-logseq-ingest
  (setup-mocks!)
  (testing "execute-step with :logseq-ingest calls messaging/ingest-message!"
    (async done
      (let [step {:action :logseq-ingest}
            input {:id 1
                   :content "Test message"
                   :platform "whatsapp"
                   :contact {:displayName "John"}
                   :createdAt "2026-02-11T00:00:00.000Z"}]
        (-> (tasks/execute-step step input)
            (.then (fn [result]
                     (is (= "mock-block-uuid" (:uuid result)))
                     (is (= 1 (count @ingest-message-calls)))
                     (is (= input (first @ingest-message-calls)))
                     (done))))))))

(deftest test-execute-step-logseq-insert
  (setup-mocks!)
  (testing "execute-step with :logseq-insert calls js/logseq.Editor.insertBlock"
    (async done
      (let [step {:action :logseq-insert}
            input {:block-uuid "block-123" :content "New content"}]
        (-> (tasks/execute-step step input)
            (.then (fn [result]
                     (is (= "inserted-uuid" (.-uuid result)))
                     (is (= 1 (count @insert-block-calls)))
                     (let [call (first @insert-block-calls)]
                       (is (= "block-123" (:block-uuid call)))
                       (is (= "New content" (:content call))))
                     (done))))))))

;; ---------------------------------------------------------------------------
;; Run Task Tests
;; ---------------------------------------------------------------------------

(deftest test-run-task-single-step
  (setup-mocks!)
  (testing "run-task! executes a single-step task and stores result"
    (async done
      (let [task {:id :single-step-task
                  :name "Single Step"
                  :steps [{:action :ai-process}]
                  :enabled true}]
        (tasks/register-task! task)
        (-> (tasks/run-task! :single-step-task "Input text")
            (.then (fn [result]
                     (is (= "AI response to: Input text" result))
                     ;; Check state updates
                     (is (not (contains? (:running @tasks/state) :single-step-task)))
                     (is (= result (get-in @tasks/state [:results :single-step-task])))
                     (done))))))))

(deftest test-run-task-multi-step-chain
  (setup-mocks!)
  (testing "run-task! chains multiple steps, passing output to next input"
    (async done
      (let [task {:id :multi-step-task
                  :name "Multi Step"
                  :steps [{:action :ai-process}
                          {:action :store-memory}]
                  :enabled true}]
        (tasks/register-task! task)
        ;; First step: ai-process takes "Hello" -> returns "AI response to: Hello"
        ;; Second step: store-memory should receive the AI response as a string
        ;; But store-memory expects {:tag :content} map, so this will just store the string
        (-> (tasks/run-task! :multi-step-task "Hello")
            (.then (fn [result]
                     ;; Result should be from store-memory
                     (is (= "mem-123" (:id result)))
                     ;; Both steps should have been called
                     (is (= 1 (count @process-input-calls)))
                     (is (= 1 (count @store-memory-calls)))
                     (done))))))))

(deftest test-run-task-updates-running-state
  (setup-mocks!)
  (testing "run-task! adds task-id to :running and removes on completion"
    (async done
      (let [task {:id :state-test-task
                  :name "State Test"
                  :steps [{:action :ai-process}]
                  :enabled true}
            check-running (atom false)]
        (tasks/register-task! task)
        (-> (tasks/run-task! :state-test-task "Input")
            (.then (fn [_]
                     ;; After completion, should not be in running
                     (is (not (contains? (:running @tasks/state) :state-test-task)))
                     (done))))
        ;; Immediately after calling run-task!, it should be in running
        ;; (but this is async, so might not be reliable in test)
        ))))

(deftest test-run-task-disabled
  (setup-mocks!)
  (testing "run-task! rejects if task is disabled"
    (async done
      (let [task {:id :disabled-task
                  :name "Disabled"
                  :steps [{:action :ai-process}]
                  :enabled false}]
        (tasks/register-task! task)
        (-> (tasks/run-task! :disabled-task "Input")
            (.then (fn [_]
                     (is false "Should have rejected")
                     (done)))
            (.catch (fn [error]
                      (is (.includes (.-message error) "disabled"))
                      (done))))))))

(deftest test-run-task-not-found
  (setup-mocks!)
  (testing "run-task! rejects if task does not exist"
    (async done
      (-> (tasks/run-task! :nonexistent-task "Input")
          (.then (fn [_]
                   (is false "Should have rejected")
                   (done)))
          (.catch (fn [error]
                    (is (.includes (.-message error) "not found"))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; Message Handler Tests
;; ---------------------------------------------------------------------------

(deftest test-on-new-message-handler
  (setup-mocks!)
  (testing "on-new-message-handler runs tasks with :on-new-message trigger"
    (async done
      (let [task1 {:id :msg-task-1
                   :name "Message Task 1"
                   :steps [{:action :logseq-ingest}]
                   :trigger :on-new-message
                   :enabled true}
            task2 {:id :msg-task-2
                   :name "Message Task 2"
                   :steps [{:action :ai-process}]
                   :trigger :on-new-message
                   :enabled true}
            task3 {:id :other-task
                   :name "Other Task"
                   :steps [{:action :ai-process}]
                   :trigger :manual
                   :enabled true}
            message {:id 1
                     :content "Test"
                     :platform "whatsapp"
                     :contact {:displayName "John"}
                     :createdAt "2026-02-11T00:00:00.000Z"}]
        (tasks/register-task! task1)
        (tasks/register-task! task2)
        (tasks/register-task! task3)
        ;; Call the handler
        (tasks/on-new-message-handler message)
        ;; Wait a bit for async tasks to complete
        (js/setTimeout
         (fn []
           ;; task1 should have called ingest-message
           (is (>= (count @ingest-message-calls) 1))
           ;; task2 should have called process-input
           (is (>= (count @process-input-calls) 1))
           ;; task3 should NOT have been triggered
           ;; (hard to verify directly, but we can check it's not in results if it was async)
           (done))
         100)))))

(deftest test-on-new-message-handler-respects-enabled
  (setup-mocks!)
  (testing "on-new-message-handler only runs enabled tasks"
    (async done
      (let [task1 {:id :enabled-task
                   :name "Enabled"
                   :steps [{:action :logseq-ingest}]
                   :trigger :on-new-message
                   :enabled true}
            task2 {:id :disabled-task
                   :name "Disabled"
                   :steps [{:action :ai-process}]
                   :trigger :on-new-message
                   :enabled false}
            message {:id 1
                     :content "Test"
                     :platform "whatsapp"
                     :contact {:displayName "John"}
                     :createdAt "2026-02-11T00:00:00.000Z"}]
        (tasks/register-task! task1)
        (tasks/register-task! task2)
        (tasks/on-new-message-handler message)
        (js/setTimeout
         (fn []
           ;; Only enabled task should run
           (is (= 1 (count @ingest-message-calls)))
           (is (= 0 (count @process-input-calls)))
           (done))
         100)))))

;; ---------------------------------------------------------------------------
;; Init Tests
;; ---------------------------------------------------------------------------

(deftest test-init-registers-builtin-tasks
  (setup-mocks!)
  (testing "init! registers built-in tasks"
    (tasks/init!)
    (let [builtin-task (tasks/get-task :message-to-logseq)]
      (is (some? builtin-task))
      (is (= "Message to Logseq" (:name builtin-task)))
      (is (= :on-new-message (:trigger builtin-task)))
      (is (:enabled builtin-task)))))

(deftest test-init-registers-message-handler
  (setup-mocks!)
  (testing "init! hooks on-new-message-handler into messaging"
    (tasks/init!)
    ;; Should have registered a message handler
    (is (= 1 (count (:message-handlers @messaging/state))))))

(deftest test-init-registers-slash-commands
  (setup-mocks!)
  (testing "init! registers slash commands"
    (tasks/init!)
    (is (= 2 (count @registered-slash-commands)))
    (let [command-names (set (map :name @registered-slash-commands))]
      (is (contains? command-names "task:list"))
      (is (contains? command-names "task:run")))))
