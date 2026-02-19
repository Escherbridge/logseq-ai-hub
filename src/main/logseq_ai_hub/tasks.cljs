(ns logseq-ai-hub.tasks
  (:require [logseq-ai-hub.messaging :as messaging]
            [logseq-ai-hub.agent :as agent]
            [logseq-ai-hub.memory :as memory]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce state
  (atom {:tasks {}       ;; task-id -> task definition
         :running #{}    ;; set of currently running task IDs
         :results {}}))  ;; task-id -> last result

;; ---------------------------------------------------------------------------
;; Task Management
;; ---------------------------------------------------------------------------

(defn register-task!
  "Adds a task definition to state."
  [task-def]
  (swap! state assoc-in [:tasks (:id task-def)] task-def))

(defn unregister-task!
  "Removes a task from state."
  [task-id]
  (swap! state update :tasks dissoc task-id))

(defn get-task
  "Returns task definition by ID."
  [task-id]
  (get-in @state [:tasks task-id]))

(defn list-tasks
  "Returns all task definitions."
  []
  (vals (get @state :tasks)))

;; ---------------------------------------------------------------------------
;; Step Execution
;; ---------------------------------------------------------------------------

(defn execute-step
  "Executes a single step action. Returns a Promise resolving to the result."
  [step input]
  (let [{:keys [action]} step]
    (case action
      :ai-process
      (let [model-id (aget js/logseq.settings "selectedModel")
            content (if (string? input) input (str input))]
        (agent/process-input content (or model-id "mock-model")))

      :send-message
      (let [{:keys [platform recipient content]} input]
        (messaging/send-message! platform recipient content))

      :store-memory
      (let [{:keys [tag content]} input]
        (memory/store-memory! tag content))

      :logseq-ingest
      (messaging/ingest-message! input)

      :logseq-insert
      (let [{:keys [block-uuid content]} input]
        (js/logseq.Editor.insertBlock block-uuid content))

      ;; Unknown action - return input as-is
      (js/Promise.resolve input))))

;; ---------------------------------------------------------------------------
;; Task Execution
;; ---------------------------------------------------------------------------

(defn run-task!
  "Chains through task steps via Promise pipeline.
   The first step receives trigger-data as input, each subsequent step receives
   the previous step's output. Returns a Promise resolving to the final result."
  [task-id trigger-data]
  (let [task (get-task task-id)]
    (if-not task
      (js/Promise.reject (js/Error. (str "Task not found: " task-id)))
      (let [{:keys [steps enabled]} task]
        (if-not enabled
          (js/Promise.reject (js/Error. (str "Task is disabled: " task-id)))
          (do
            ;; Mark as running
            (swap! state update :running conj task-id)
            ;; Chain through all steps
            (-> (reduce (fn [promise-chain step]
                          (.then promise-chain
                                 (fn [result]
                                   (execute-step step result))))
                        (js/Promise.resolve trigger-data)
                        steps)
                ;; Store result and remove from running
                (.then (fn [final-result]
                         (swap! state update :running disj task-id)
                         (swap! state assoc-in [:results task-id] final-result)
                         final-result))
                (.catch (fn [error]
                          (swap! state update :running disj task-id)
                          (js/console.error (str "Task " task-id " failed:") error)
                          (throw error))))))))))

;; ---------------------------------------------------------------------------
;; Message Handler
;; ---------------------------------------------------------------------------

(defn on-new-message-handler
  "Finds all enabled tasks with :trigger :on-new-message and runs them
   with the message as trigger data."
  [message]
  (doseq [task (list-tasks)]
    (when (and (:enabled task)
               (= :on-new-message (:trigger task)))
      (run-task! (:id task) message))))

;; ---------------------------------------------------------------------------
;; Slash Commands
;; ---------------------------------------------------------------------------

(defn register-commands!
  "Registers /task:run and /task:list slash commands."
  []
  ;; /task:list - Lists all registered tasks
  (js/logseq.Editor.registerSlashCommand
   "task:list"
   (fn [e]
     (let [block-uuid (.-uuid e)
           tasks (list-tasks)
           task-list (if (empty? tasks)
                       "No tasks registered."
                       (->> tasks
                            (map (fn [t]
                                   (str "- " (:id t) " (" (:name t) ") - "
                                        (if (:enabled t) "enabled" "disabled"))))
                            (clojure.string/join "\n")))]
       (js/logseq.Editor.insertBlock block-uuid task-list))))

  ;; /task:run - Runs a task (needs user to specify task-id in block content)
  (js/logseq.Editor.registerSlashCommand
   "task:run"
   (fn [e]
     (let [block-uuid (.-uuid e)]
       (-> (js/logseq.Editor.getBlock block-uuid)
           (.then (fn [block]
                    (let [content (.-content block)
                          ;; Extract task-id from content (e.g., "task:run message-to-logseq")
                          task-id-str (second (re-find #"task:run\s+(\S+)" content))
                          task-id (when task-id-str (keyword task-id-str))]
                      (if task-id
                        (run-task! task-id {})
                        (js/Promise.reject (js/Error. "No task-id specified"))))))
           (.then (fn [_result]
                    (js/logseq.Editor.insertBlock block-uuid "Task completed.")))
           (.catch (fn [error]
                     (js/logseq.Editor.insertBlock block-uuid
                                                    (str "Task failed: " (.-message error))))))))))

;; ---------------------------------------------------------------------------
;; Built-in Tasks
;; ---------------------------------------------------------------------------

(def builtin-tasks
  [{:id :message-to-logseq
    :name "Message to Logseq"
    :steps [{:action :logseq-ingest :input-from :trigger}]
    :trigger :on-new-message
    :enabled true}])

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init!
  "Initializes the tasks module. Registers built-in tasks, hooks into messaging,
   and registers slash commands."
  []
  ;; Register built-in tasks
  (doseq [task builtin-tasks]
    (register-task! task))

  ;; Hook into messaging
  (messaging/on-message on-new-message-handler)

  ;; Register slash commands
  (register-commands!)

  (js/console.log "Tasks module initialized"))
