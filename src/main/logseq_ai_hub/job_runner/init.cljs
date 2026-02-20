(ns logseq-ai-hub.job-runner.init
  "Initialization and wiring for the job runner system."
  (:require [logseq-ai-hub.job-runner.runner :as runner]
            [logseq-ai-hub.job-runner.scheduler :as scheduler]
            [logseq-ai-hub.job-runner.engine :as engine]
            [logseq-ai-hub.job-runner.executor :as executor]
            [logseq-ai-hub.job-runner.graph :as graph]
            [logseq-ai-hub.job-runner.mcp.client :as mcp-client]
            [logseq-ai-hub.job-runner.openclaw :as openclaw]
            [logseq-ai-hub.job-runner.commands :as commands]
            [cljs.reader :as reader]))

;; =============================================================================
;; State Management
;; =============================================================================

;; Holds the initialized system state.
(defonce system-state
  (atom {:initialized? false
         :runner-started? false
         :scheduler-started? false
         :mcp-servers []}))

;; =============================================================================
;; Settings Management
;; =============================================================================

(defn- read-settings
  "Reads job runner settings from Logseq settings.
  Returns a map with all configuration values."
  []
  (let [settings js/logseq.settings]
    {:enabled (or (aget settings "jobRunnerEnabled") false)
     :max-concurrent (or (aget settings "jobRunnerMaxConcurrent") 3)
     :poll-interval (or (aget settings "jobRunnerPollInterval") 5000)
     :default-timeout (or (aget settings "jobRunnerDefaultTimeout") 300000)
     :job-page-prefix (or (aget settings "jobPagePrefix") "Jobs/")
     :skill-page-prefix (or (aget settings "skillPagePrefix") "Skills/")
     :mcp-servers-json (or (aget settings "mcpServers") "[]")}))

(defn- parse-mcp-servers
  "Parses MCP server configuration from JSON string.
  Returns vector of server config maps or empty vector on error."
  [json-str]
  (try
    (let [parsed (js/JSON.parse json-str)]
      (js->clj parsed :keywordize-keys true))
    (catch js/Error e
      (js/console.error "Failed to parse MCP servers config:" e)
      [])))

;; =============================================================================
;; System Initialization
;; =============================================================================

(defn- wire-engine-executor!
  "Wires the engine to use the executor for step execution."
  []
  (engine/set-executor-execute-step! executor/execute-step)
  (js/console.log "Job runner: Engine wired to executor"))

(defn- init-runner!
  "Initializes the job runner with dependencies."
  [settings]
  (let [deps {:graph {:read-job-page graph/read-job-page
                      :read-skill-page graph/read-skill-page
                      :scan-job-pages graph/scan-job-pages
                      :scan-skill-pages graph/scan-skill-pages
                      :update-job-status! graph/update-job-status!
                      :update-job-property! graph/update-job-property!
                      :append-job-log! graph/append-job-log!}
              :engine {:execute-skill engine/execute-skill
                       :execute-skill-with-retries engine/execute-skill-with-retries}
              :queue {:enqueue (fn [job-id] (js/Promise.resolve job-id))
                      :dequeue (fn [] (js/Promise.resolve nil))}}]
    (runner/init-runner! deps)
    (js/console.log "Job runner: Runner initialized with dependencies")))

(defn- init-scheduler!
  "Initializes the job scheduler with dependencies."
  [settings]
  (let [deps {:runner {:enqueue-job! runner/enqueue-job!}
              :graph {:scan-job-pages graph/scan-job-pages
                      :read-job-page graph/read-job-page}}]
    (scheduler/init-scheduler! deps)
    (js/console.log "Job runner: Scheduler initialized with dependencies")))

(defn- init-commands!
  "Initializes slash commands with dependencies."
  []
  (let [deps {:openclaw-import openclaw/import-skill-to-graph!
              :openclaw-export openclaw/export-skill-from-graph!
              :mcp-list-servers mcp-client/list-servers
              :mcp-list-tools mcp-client/list-tools
              :scheduler-list-schedules scheduler/list-schedules}]
    (commands/init-commands! deps)
    (commands/register-commands!)
    (js/console.log "Job runner: Commands initialized and registered")))

(defn- start-runner!
  "Starts the job runner with configuration from settings."
  [settings]
  (when-not (:runner-started? @system-state)
    (runner/update-config! {:max-concurrent (:max-concurrent settings)
                           :poll-interval (:poll-interval settings)
                           :default-timeout (:default-timeout settings)
                           :job-page-prefix (:job-page-prefix settings)
                           :skill-page-prefix (:skill-page-prefix settings)})
    (-> (runner/start-runner!)
        (.then (fn [_]
                 (swap! system-state assoc :runner-started? true)
                 (js/console.log "Job runner: Runner started")))
        (.catch (fn [err]
                  (js/console.error "Failed to start runner:" err))))))

(defn- start-scheduler!
  "Starts the job scheduler and scans for scheduled jobs."
  [settings]
  (when-not (:scheduler-started? @system-state)
    (scheduler/start-scheduler!)
    (-> (scheduler/scan-and-register-schedules! (:job-page-prefix settings))
        (.then (fn [count]
                 (swap! system-state assoc :scheduler-started? true)
                 (js/console.log "Job runner: Scheduler started with"
                               count "schedules")))
        (.catch (fn [err]
                  (js/console.error "Failed to start scheduler:" err))))))

(defn- connect-mcp-servers!
  "Connects to MCP servers from settings configuration."
  [settings]
  (let [servers (parse-mcp-servers (:mcp-servers-json settings))]
    (when (seq servers)
      (doseq [server servers]
        (-> (mcp-client/connect-server! server)
            (.then (fn [_]
                     (swap! system-state update :mcp-servers conj server)
                     (js/console.log "Job runner: Connected to MCP server"
                                   (:id server))))
            (.catch (fn [err]
                      (js/console.error "Failed to connect to MCP server"
                                      (:id server) err))))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn init!
  "Initializes the job runner system.

  This function:
  1. Reads settings from Logseq
  2. Wires the engine to the executor
  3. Initializes runner, scheduler, and commands
  4. If enabled in settings:
     - Starts the runner
     - Starts the scheduler
     - Connects to MCP servers

  Safe to call multiple times (will only initialize once)."
  []
  (when-not (:initialized? @system-state)
    (try
      (js/console.log "Job runner: Starting initialization...")
      (let [settings (read-settings)]

        ;; Wire dependencies
        (wire-engine-executor!)

        ;; Initialize subsystems
        (init-runner! settings)
        (init-scheduler! settings)
        (init-commands!)

        ;; Mark as initialized
        (swap! system-state assoc :initialized? true)
        (js/console.log "Job runner: Initialization complete")

        ;; Start if enabled
        (when (:enabled settings)
          (js/console.log "Job runner: Starting subsystems (enabled in settings)")
          (start-runner! settings)
          (start-scheduler! settings)
          (connect-mcp-servers! settings))

        (when-not (:enabled settings)
          (js/console.log "Job runner: System initialized but not started (disabled in settings)")))

      (catch js/Error e
        (js/console.error "Job runner: Initialization failed:" e)
        (throw e)))))

(defn shutdown!
  "Shuts down the job runner system.

  This function:
  1. Stops the runner
  2. Stops the scheduler
  3. Disconnects all MCP servers
  4. Resets system state"
  []
  (when (:initialized? @system-state)
    (try
      (js/console.log "Job runner: Starting shutdown...")

      ;; Stop runner
      (when (:runner-started? @system-state)
        (runner/stop-runner!)
        (js/console.log "Job runner: Runner stopped"))

      ;; Stop scheduler
      (when (:scheduler-started? @system-state)
        (scheduler/stop-scheduler!)
        (js/console.log "Job runner: Scheduler stopped"))

      ;; Disconnect MCP servers
      (doseq [server (:mcp-servers @system-state)]
        (mcp-client/disconnect-server! (:id server))
        (js/console.log "Job runner: Disconnected from MCP server" (:id server)))

      ;; Reset state
      (reset! system-state {:initialized? false
                           :runner-started? false
                           :scheduler-started? false
                           :mcp-servers []})

      (js/console.log "Job runner: Shutdown complete")

      (catch js/Error e
        (js/console.error "Job runner: Shutdown failed:" e)
        (throw e)))))

(defn restart!
  "Restarts the job runner system.
  Equivalent to calling shutdown! followed by init!."
  []
  (shutdown!)
  (init!))

(defn get-status
  "Returns the current system status.

  Returns a map with:
    :initialized? - Whether the system is initialized
    :runner-started? - Whether the runner is started
    :scheduler-started? - Whether the scheduler is started
    :mcp-servers - List of connected MCP servers"
  []
  @system-state)
