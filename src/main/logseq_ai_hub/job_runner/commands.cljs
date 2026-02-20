(ns logseq-ai-hub.job-runner.commands
  "Slash command handlers for job runner functionality."
  (:require [logseq-ai-hub.job-runner.runner :as runner]
            [clojure.string :as str]))

;; =============================================================================
;; Dynamic Dependencies (to avoid circular refs)
;; =============================================================================

(def ^:dynamic openclaw-import-skill
  "Dynamic var for OpenClaw skill import function."
  nil)

(def ^:dynamic openclaw-export-skill-from-graph!
  "Dynamic var for OpenClaw skill export function."
  nil)

(def ^:dynamic mcp-list-servers
  "Dynamic var for MCP server listing function."
  nil)

(def ^:dynamic mcp-list-tools
  "Dynamic var for MCP tools listing function."
  nil)

(def ^:dynamic scheduler-list-schedules
  "Dynamic var for scheduler schedule listing function."
  nil)

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn- get-block-content
  "Gets the content of a block by UUID. Returns a Promise."
  [block-uuid]
  (-> (js/logseq.Editor.getBlock block-uuid)
      (.then (fn [block]
               (if block
                 (.-content block)
                 (throw (js/Error. "Block not found")))))))

(defn- get-current-page-name
  "Gets the name of the current page. Returns a Promise."
  []
  (-> (js/logseq.Editor.getCurrentPage)
      (.then (fn [page]
               (if page
                 (.-name page)
                 (throw (js/Error. "No current page")))))))

(defn- show-msg
  "Shows a message to the user."
  [msg & {:keys [status] :or {status "success"}}]
  (js/logseq.App.showMsg msg status))

(defn- insert-child-block
  "Inserts a child block under the given parent UUID."
  [parent-uuid content]
  (js/logseq.Editor.insertBlock parent-uuid content
    #js {:sibling false}))

;; =============================================================================
;; Job Commands
;; =============================================================================

(defn handle-job-run
  "Handler for /job:run slash command.
  Enqueues a job from the block content."
  [e]
  (let [block-uuid (.-uuid e)]
    (-> (get-block-content block-uuid)
        (.then (fn [job-name]
                 (runner/enqueue-job! job-name)))
        (.then (fn [result]
                 (show-msg (str "Job enqueued: " (:job-id result))
                          :status "success")))
        (.catch (fn [err]
                  (js/console.error "job:run error:" err)
                  (show-msg (str "Error: " (.-message err))
                           :status "error"))))))

(defn handle-job-status
  "Handler for /job:status slash command.
  Shows the current runner status."
  [e]
  (let [block-uuid (.-uuid e)]
    (try
      (let [status (runner/runner-status)
            status-text (str "Runner Status: " (name (:status status))
                           "\nQueued: " (:queued status)
                           "\nRunning: " (:running status)
                           "\nCompleted: " (:completed status)
                           "\nFailed: " (:failed status))]
        (insert-child-block block-uuid status-text))
      (catch js/Error err
        (js/console.error "job:status error:" err)
        (show-msg (str "Error: " (.-message err))
                 :status "error")))))

(defn handle-job-cancel
  "Handler for /job:cancel slash command.
  Cancels a job by name from block content."
  [e]
  (let [block-uuid (.-uuid e)]
    (-> (get-block-content block-uuid)
        (.then (fn [job-name]
                 (runner/cancel-job! job-name)))
        (.then (fn [_]
                 (show-msg "Job cancelled" :status "success")))
        (.catch (fn [err]
                  (js/console.error "job:cancel error:" err)
                  (show-msg (str "Error: " (.-message err))
                           :status "error"))))))

(defn handle-job-pause
  "Handler for /job:pause slash command.
  Pauses a running job by name from block content."
  [e]
  (let [block-uuid (.-uuid e)]
    (-> (get-block-content block-uuid)
        (.then (fn [job-name]
                 (runner/pause-job! job-name)))
        (.then (fn [_]
                 (show-msg "Job paused" :status "success")))
        (.catch (fn [err]
                  (js/console.error "job:pause error:" err)
                  (show-msg (str "Error: " (.-message err))
                           :status "error"))))))

(defn handle-job-resume
  "Handler for /job:resume slash command.
  Resumes a paused job by name from block content."
  [e]
  (let [block-uuid (.-uuid e)]
    (-> (get-block-content block-uuid)
        (.then (fn [job-name]
                 (runner/resume-job! job-name)))
        (.then (fn [_]
                 (show-msg "Job resumed" :status "success")))
        (.catch (fn [err]
                  (js/console.error "job:resume error:" err)
                  (show-msg (str "Error: " (.-message err))
                           :status "error"))))))

(defn handle-job-create
  "Handler for /job:create slash command.
  Creates a new job page with default properties."
  [e]
  (let [block-uuid (.-uuid e)]
    (-> (get-block-content block-uuid)
        (.then (fn [job-name]
                 (let [page-name (str "Jobs/" job-name)
                       properties (str "status:: queued\n"
                                     "created:: " (.toISOString (js/Date.)) "\n"
                                     "skill:: \n"
                                     "inputs:: {}\n"
                                     "max-retries:: 3")]
                   (js/logseq.Editor.createPage page-name properties))))
        (.then (fn [_]
                 (show-msg "Job page created" :status "success")))
        (.catch (fn [err]
                  (js/console.error "job:create error:" err)
                  (show-msg (str "Error: " (.-message err))
                           :status "error"))))))

;; =============================================================================
;; OpenClaw Import/Export Commands
;; =============================================================================

(defn handle-import-skill
  "Handler for /job:import-skill slash command.
  Imports an OpenClaw skill from JSON in block content."
  [e]
  (let [block-uuid (.-uuid e)]
    (-> (get-block-content block-uuid)
        (.then (fn [json-str]
                 (if openclaw-import-skill
                   (openclaw-import-skill json-str)
                   (throw (js/Error. "OpenClaw not initialized")))))
        (.then (fn [result]
                 (if (:ok result)
                   (show-msg (str "Skill imported: " (:ok result))
                            :status "success")
                   (show-msg (str "Import failed: " (:error result))
                            :status "error"))))
        (.catch (fn [err]
                  (js/console.error "job:import-skill error:" err)
                  (show-msg (str "Error: " (.-message err))
                           :status "error"))))))

(defn handle-export-skill
  "Handler for /job:export-skill slash command.
  Exports the current skill page to OpenClaw JSON format."
  [e]
  (let [block-uuid (.-uuid e)]
    (-> (get-current-page-name)
        (.then (fn [page-name]
                 (if (re-find #"^Skills/" page-name)
                   (if openclaw-export-skill-from-graph!
                     (openclaw-export-skill-from-graph! page-name)
                     (throw (js/Error. "OpenClaw not initialized")))
                   (throw (js/Error. "Current page is not a skill page")))))
        (.then (fn [json-str]
                 (insert-child-block block-uuid
                   (str "```json\n" json-str "\n```"))))
        (.catch (fn [err]
                  (js/console.error "job:export-skill error:" err)
                  (show-msg (str "Error: " (.-message err))
                           :status "error"))))))

;; =============================================================================
;; MCP Commands
;; =============================================================================

(defn handle-mcp-servers
  "Handler for /job:mcp-servers slash command.
  Lists all connected MCP servers."
  [e]
  (let [block-uuid (.-uuid e)]
    (try
      (if mcp-list-servers
        (let [servers (mcp-list-servers)
              server-list (if (empty? servers)
                           "No MCP servers connected"
                           (str "Connected MCP Servers:\n"
                                (apply str
                                  (map (fn [[id cfg]]
                                         (str "- " id ": " (:url cfg) "\n"))
                                       servers))))]
          (insert-child-block block-uuid server-list))
        (show-msg "MCP client not initialized" :status "error"))
      (catch js/Error err
        (js/console.error "job:mcp-servers error:" err)
        (show-msg (str "Error: " (.-message err))
                 :status "error")))))

(defn handle-mcp-tools
  "Handler for /job:mcp-tools slash command.
  Lists tools from the specified MCP server."
  [e]
  (let [block-uuid (.-uuid e)]
    (-> (get-block-content block-uuid)
        (.then (fn [server-id]
                 (if mcp-list-tools
                   (mcp-list-tools server-id)
                   (throw (js/Error. "MCP client not initialized")))))
        (.then (fn [tools]
                 (let [tool-list (if (empty? tools)
                                  "No tools available"
                                  (str "Tools from server:\n"
                                       (apply str
                                         (map #(str "- " (:name %)
                                                   ": " (:description %) "\n")
                                              tools))))]
                   (insert-child-block block-uuid tool-list))))
        (.catch (fn [err]
                  (js/console.error "job:mcp-tools error:" err)
                  (show-msg (str "Error: " (.-message err))
                           :status "error"))))))

(defn handle-mcp-resources
  "Handler for /job:mcp-resources slash command.
  Lists resources from the specified MCP server."
  [e]
  (let [block-uuid (.-uuid e)]
    (-> (get-block-content block-uuid)
        (.then (fn [server-id]
                 (if mcp-list-tools ;; Reusing for now, would be mcp-list-resources
                   (js/Promise.resolve []) ;; Placeholder
                   (throw (js/Error. "MCP client not initialized")))))
        (.then (fn [resources]
                 (let [resource-list (if (empty? resources)
                                      "No resources available"
                                      (str "Resources from server:\n"
                                           (apply str
                                             (map #(str "- " (:name %)
                                                       ": " (:uri %) "\n")
                                                  resources))))]
                   (insert-child-block block-uuid resource-list))))
        (.catch (fn [err]
                  (js/console.error "job:mcp-resources error:" err)
                  (show-msg (str "Error: " (.-message err))
                           :status "error"))))))

;; =============================================================================
;; Scheduler Commands
;; =============================================================================

(defn handle-job-schedules
  "Handler for /job:schedules slash command.
  Lists all active job schedules."
  [e]
  (let [block-uuid (.-uuid e)]
    (try
      (if scheduler-list-schedules
        (let [schedules (scheduler-list-schedules)
              schedule-list (if (empty? schedules)
                             "No active schedules"
                             (str "Active Schedules:\n"
                                  (apply str
                                    (map (fn [[job-id cron]]
                                           (str "- " job-id ": " cron "\n"))
                                         schedules))))]
          (insert-child-block block-uuid schedule-list))
        (show-msg "Scheduler not initialized" :status "error"))
      (catch js/Error err
        (js/console.error "job:schedules error:" err)
        (show-msg (str "Error: " (.-message err))
                 :status "error")))))

;; =============================================================================
;; Command Registration
;; =============================================================================

(def command-registry
  "Map of slash command names to handler functions."
  {"job:run" handle-job-run
   "job:status" handle-job-status
   "job:cancel" handle-job-cancel
   "job:pause" handle-job-pause
   "job:resume" handle-job-resume
   "job:create" handle-job-create
   "job:import-skill" handle-import-skill
   "job:export-skill" handle-export-skill
   "job:mcp-servers" handle-mcp-servers
   "job:mcp-tools" handle-mcp-tools
   "job:mcp-resources" handle-mcp-resources
   "job:schedules" handle-job-schedules})

(defn register-commands!
  "Registers all job runner slash commands with Logseq."
  []
  (doseq [[cmd-name handler] command-registry]
    (js/logseq.Editor.registerSlashCommand cmd-name handler)
    (js/console.log "Registered slash command:" cmd-name)))

(defn init-commands!
  "Initializes command handlers with dependencies.

  Args:
    deps - Map with optional keys:
      :openclaw-import - OpenClaw import function
      :openclaw-export - OpenClaw export function
      :mcp-list-servers - MCP server listing function
      :mcp-list-tools - MCP tools listing function
      :scheduler-list-schedules - Scheduler schedule listing function"
  [deps]
  (when-let [import-fn (:openclaw-import deps)]
    (set! openclaw-import-skill import-fn))
  (when-let [export-fn (:openclaw-export deps)]
    (set! openclaw-export-skill-from-graph! export-fn))
  (when-let [servers-fn (:mcp-list-servers deps)]
    (set! mcp-list-servers servers-fn))
  (when-let [tools-fn (:mcp-list-tools deps)]
    (set! mcp-list-tools tools-fn))
  (when-let [schedules-fn (:scheduler-list-schedules deps)]
    (set! scheduler-list-schedules schedules-fn))
  (js/console.log "Job runner commands initialized with dependencies"))
