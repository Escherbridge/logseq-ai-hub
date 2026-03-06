(ns logseq-ai-hub.agent-bridge
  "Handles agent request events from the server.
   Dispatches operations to job runner modules and sends results back via callback."
  (:require [logseq-ai-hub.job-runner.runner :as runner]
            [logseq-ai-hub.job-runner.graph :as graph]
            [logseq-ai-hub.mcp.client :as mcp-client]
            [logseq-ai-hub.secrets :as secrets]
            [logseq-ai-hub.memory :as memory]
            [logseq-ai-hub.registry.bridge :as registry-bridge]
            [logseq-ai-hub.code-repo.bridge :as code-repo-bridge]
            [logseq-ai-hub.code-repo.adr :as adr]
            [logseq-ai-hub.code-repo.lessons :as lessons]
            [logseq-ai-hub.code-repo.safeguard :as safeguard]
            [logseq-ai-hub.code-repo.work :as work]
            [logseq-ai-hub.code-repo.tasks :as tasks]
            [logseq-ai-hub.code-repo.pi-agents :as pi-agents]
            [clojure.string :as str]))

;; =============================================================================
;; Callback Client
;; =============================================================================

(defn- get-server-url []
  (aget js/logseq.settings "webhookServerUrl"))

(defn- get-api-token []
  (aget js/logseq.settings "pluginApiToken"))

(defn send-callback!
  "Sends the result of an agent request back to the server."
  ([request-id success data error]
   (send-callback! request-id success data error nil))
  ([request-id success data error trace-id]
   (let [server-url (get-server-url)
         token (get-api-token)
         url (str server-url "/api/agent/callback")
         body (cond-> {:requestId request-id
                       :success success}
                data (assoc :data data)
                error (assoc :error error)
                trace-id (assoc :traceId trace-id))]
     (-> (js/fetch url
           (clj->js {:method "POST"
                     :headers {"Content-Type" "application/json"
                               "Authorization" (str "Bearer " token)}
                     :body (js/JSON.stringify (clj->js body))}))
         (.then (fn [response]
                  (when-not (.-ok response)
                    (js/console.error "Agent callback failed:" (.-status response) "traceId:" trace-id))))
         (.catch (fn [err]
                   (js/console.error "Agent callback error:" err "traceId:" trace-id)))))))

;; =============================================================================
;; Operation Handlers
;; =============================================================================

(defn- handle-create-job [params]
  (let [name (get params "name")
        job-type (get params "type")
        priority (or (get params "priority") 3)
        schedule (get params "schedule")
        skill (get params "skill")
        input (get params "input")
        page-name (str "Jobs/" name)
        properties (cond-> {:job-type job-type
                            :job-status "queued"
                            :job-priority priority
                            :job-created-at (.toISOString (js/Date.))}
                     skill (assoc :job-skill skill)
                     schedule (assoc :job-schedule schedule)
                     input (assoc :job-input (js/JSON.stringify (clj->js input))))]
    (-> (js/logseq.Editor.createPage page-name
          (clj->js (into {} (map (fn [[k v]] [(clojure.core/name k) v]) properties)))
          (clj->js {:createFirstBlock false}))
        (.then (fn [_page]
                 (-> (runner/enqueue-job! page-name)
                     (.then (fn [_]
                              {:jobId page-name :name name :status "queued"})))))
        (.catch (fn [err]
                  (js/Promise.reject (str "Failed to create job: " (.-message err))))))))

(defn- handle-list-jobs [params]
  (let [prefix (or (aget js/logseq.settings "jobPagePrefix") "Jobs/")
        status-filter (get params "status")]
    (-> (graph/scan-job-pages prefix)
        (.then (fn [job-defs]
                 (let [filtered (if status-filter
                                  (filter #(= (name (:job-status %)) status-filter) job-defs)
                                  job-defs)
                       limit (or (get params "limit") 50)
                       offset (or (get params "offset") 0)
                       page (take limit (drop offset filtered))]
                   {:jobs (mapv (fn [j]
                                  {:jobId (:job-id j)
                                   :name (str/replace (or (:job-id j) "") #"^Jobs/" "")
                                   :status (name (or (:job-status j) :unknown))
                                   :type (name (or (:job-type j) :unknown))
                                   :priority (or (:job-priority j) 3)
                                   :createdAt (or (:job-created-at j) "")})
                                page)
                    :total (count filtered)}))))))

(defn- handle-get-job [params]
  (let [job-id (get params "jobId")
        full-id (if (str/starts-with? job-id "Jobs/") job-id (str "Jobs/" job-id))]
    (-> (graph/read-job-page full-id)
        (.then (fn [job-def]
                 (if job-def
                   {:jobId full-id
                    :name (str/replace full-id #"^Jobs/" "")
                    :status (name (or (:job-status job-def) :unknown))
                    :type (name (or (:job-type job-def) :unknown))
                    :priority (or (:job-priority job-def) 3)
                    :skill (:job-skill job-def)
                    :input (:job-input job-def)
                    :schedule (:job-schedule job-def)
                    :createdAt (or (:job-created-at job-def) "")
                    :startedAt (:job-started-at job-def)
                    :completedAt (:job-completed-at job-def)
                    :result (:job-result job-def)
                    :error (:job-error job-def)}
                   (js/Promise.reject (str "Job not found: " job-id))))))))

(defn- handle-start-job [params]
  (let [job-id (get params "jobId")
        full-id (if (str/starts-with? job-id "Jobs/") job-id (str "Jobs/" job-id))]
    (-> (runner/enqueue-job! full-id)
        (.then (fn [_]
                 {:jobId full-id :status "queued"})))))

(defn- handle-cancel-job [params]
  (let [job-id (get params "jobId")
        full-id (if (str/starts-with? job-id "Jobs/") job-id (str "Jobs/" job-id))]
    (runner/cancel-job! full-id)
    (js/Promise.resolve {:jobId full-id :status "cancelled"})))

(defn- handle-pause-job [params]
  (let [job-id (get params "jobId")
        full-id (if (str/starts-with? job-id "Jobs/") job-id (str "Jobs/" job-id))]
    (runner/pause-job! full-id)
    (js/Promise.resolve {:jobId full-id :status "paused"})))

(defn- handle-resume-job [params]
  (let [job-id (get params "jobId")
        full-id (if (str/starts-with? job-id "Jobs/") job-id (str "Jobs/" job-id))]
    (-> (runner/resume-job! full-id)
        (.then (fn [_]
                 {:jobId full-id :status "queued"})))))

(defn- handle-list-skills [_params]
  (let [prefix (or (aget js/logseq.settings "skillPagePrefix") "Skills/")]
    (-> (graph/scan-skill-pages prefix)
        (.then (fn [skill-defs]
                 {:skills (mapv (fn [s]
                                  {:skillId (:skill-id s)
                                   :name (str/replace (or (:skill-id s) "") #"^Skills/" "")
                                   :type (name (or (:skill-type s) :unknown))
                                   :description (or (:skill-description s) "")
                                   :inputs (or (:skill-inputs s) [])
                                   :outputs (or (:skill-outputs s) [])
                                   :tags (:skill-tags s)})
                                skill-defs)})))))

(defn- handle-get-skill [params]
  (let [skill-id (get params "skillId")
        full-id (if (str/starts-with? skill-id "Skills/") skill-id (str "Skills/" skill-id))]
    (-> (graph/read-skill-page full-id)
        (.then (fn [skill-def]
                 (if skill-def
                   {:skillId full-id
                    :name (str/replace full-id #"^Skills/" "")
                    :type (name (or (:skill-type skill-def) :unknown))
                    :description (or (:skill-description skill-def) "")
                    :inputs (or (:skill-inputs skill-def) [])
                    :outputs (or (:skill-outputs skill-def) [])
                    :tags (:skill-tags skill-def)
                    :steps (or (:steps skill-def) [])
                    :version (:skill-version skill-def)}
                   (js/Promise.reject (str "Skill not found: " skill-id))))))))

(defn- handle-create-skill [params]
  (let [name (get params "name")
        skill-type (get params "type")
        description (get params "description")
        page-name (str "Skills/" name)]
    (-> (js/logseq.Editor.createPage page-name
          (clj->js {"skill-type" skill-type
                    "skill-description" description
                    "skill-version" "1"
                    "skill-inputs" (str/join ", " (or (get params "inputs") []))
                    "skill-outputs" (str/join ", " (or (get params "outputs") []))})
          (clj->js {:createFirstBlock false}))
        (.then (fn [_page]
                 {:skillId page-name :name name})))))

(defn- handle-list-mcp-servers [_params]
  (if mcp-client/list-servers
    (let [servers (mcp-client/list-servers)]
      (js/Promise.resolve
        {:servers (mapv (fn [[id cfg]]
                          {:id (name id)
                           :url (or (:url cfg) "")
                           :status "connected"})
                        servers)}))
    (js/Promise.resolve {:servers []})))

(defn- handle-list-mcp-tools [params]
  (let [server-id (get params "serverId")]
    (if mcp-client/list-tools
      (-> (mcp-client/list-tools server-id)
          (.then (fn [tools]
                   {:tools (mapv (fn [t]
                                   {:name (:name t)
                                    :description (or (:description t) "")
                                    :inputSchema (:inputSchema t)})
                                 tools)})))
      (js/Promise.resolve {:tools []}))))

(defn- handle-list-mcp-resources [_params]
  ;; MCP resources listing not fully implemented yet
  (js/Promise.resolve {:resources []}))

;; =============================================================================
;; Secrets Operation Handlers
;; =============================================================================

(defn- handle-list-secret-keys [_params]
  (js/Promise.resolve {:keys (secrets/list-keys)}))

(defn- handle-set-secret [params]
  (let [key (get params "key")
        value (get params "value")]
    (if (and key value)
      (-> (secrets/set-secret! key value)
          (.then (fn [_] {:success true :key key}))
          (.catch (fn [err]
                    (js/Promise.reject (str "Failed to set secret: " (.-message err))))))
      (js/Promise.reject "Missing required parameters: key and value"))))

(defn- handle-remove-secret [params]
  (let [key (get params "key")]
    (if key
      (-> (secrets/remove-secret! key)
          (.then (fn [_] {:success true :key key}))
          (.catch (fn [err]
                    (js/Promise.reject (str "Failed to remove secret: " (.-message err))))))
      (js/Promise.reject "Missing required parameter: key"))))

;; =============================================================================
;; Graph Operation Handlers
;; =============================================================================

(defn- handle-graph-query [params]
  (let [query (get params "query")]
    (if (str/blank? query)
      (js/Promise.reject "Missing required parameter: query")
      (-> (js/logseq.DB.datascriptQuery query)
          (.then (fn [results]
                   {:results (js->clj results :keywordize-keys true)}))))))

(defn- handle-graph-search [params]
  (let [query (get params "query")
        limit (or (get params "limit") 50)]
    (if (str/blank? query)
      (js/Promise.reject "Missing required parameter: query")
      (-> (js/logseq.DB.q query)
          (.then (fn [results]
                   (let [converted (js->clj results :keywordize-keys true)]
                     {:results (take limit converted)
                      :total (count converted)})))))))

(defn- handle-page-read [params]
  (let [page-name (get params "name")]
    (if (str/blank? page-name)
      (js/Promise.reject "Missing required parameter: name")
      (-> (js/logseq.Editor.getPageBlocksTree page-name)
          (.then (fn [blocks]
                   (if blocks
                     {:page page-name
                      :blocks (js->clj blocks :keywordize-keys true)}
                     (js/Promise.reject (str "Page not found: " page-name)))))))))

(defn- handle-page-create [params]
  (let [page-name (get params "name")
        content (get params "content")
        properties (or (get params "properties") {})]
    (if (str/blank? page-name)
      (js/Promise.reject "Missing required parameter: name")
      (-> (js/logseq.Editor.createPage page-name
            (clj->js properties)
            (clj->js {:createFirstBlock (boolean content)}))
          (.then (fn [_page]
                   (if content
                     (-> (js/logseq.Editor.appendBlockInPage page-name content)
                         (.then (fn [_] {:page page-name :created true})))
                     (js/Promise.resolve {:page page-name :created true}))))))))

(defn- handle-page-list [params]
  (let [pattern (or (get params "pattern") "")
        limit (or (get params "limit") 100)
        query (if (str/blank? pattern)
                "[:find (pull ?p [:block/name :block/original-name])
                  :where [?p :block/name _]]"
                (str "[:find (pull ?p [:block/name :block/original-name])
                       :where [?p :block/name ?name]
                       [(clojure.string/includes? ?name \"" (memory/escape-datalog-string (str/lower-case pattern)) "\")]]"))]
    (-> (js/logseq.DB.datascriptQuery query)
        (.then (fn [results]
                 (let [converted (js->clj results :keywordize-keys true)
                       pages (mapv (fn [r] {:name (:block/name (first r))
                                            :originalName (:block/original-name (first r))})
                                   converted)]
                   {:pages (take limit pages)
                    :total (count pages)}))))))

(defn- handle-block-append [params]
  (let [page (get params "page")
        content (get params "content")
        properties (get params "properties")]
    (if (or (str/blank? page) (str/blank? content))
      (js/Promise.reject "Missing required parameters: page and content")
      (-> (js/logseq.Editor.appendBlockInPage
            page
            (if properties
              (str content "\n" (str/join "\n" (map (fn [[k v]] (str (name k) ":: " v)) properties)))
              content))
          (.then (fn [block]
                   {:page page
                    :blockUuid (when block (.-uuid block))}))))))

(defn- handle-block-update [params]
  (let [uuid (get params "uuid")
        content (get params "content")]
    (if (or (str/blank? uuid) (str/blank? content))
      (js/Promise.reject "Missing required parameters: uuid and content")
      (-> (js/logseq.Editor.updateBlock uuid content)
          (.then (fn [_]
                   {:uuid uuid :updated true}))))))

;; =============================================================================
;; Memory Operation Handlers
;; =============================================================================

(defn- handle-store-memory [params]
  (let [tag (get params "tag")
        content (get params "content")]
    (if (or (str/blank? tag) (str/blank? content))
      (js/Promise.reject "Missing required parameters: tag and content")
      (-> (memory/store-memory! tag content)
          (.then (fn [block]
                   {:tag tag :stored true
                    :blockUuid (when block (.-uuid block))}))))))

(defn- handle-recall-memory [params]
  (let [tag (get params "tag")]
    (if (str/blank? tag)
      (js/Promise.reject "Missing required parameter: tag")
      (-> (memory/retrieve-by-tag tag)
          (.then (fn [blocks]
                   {:tag tag
                    :memories (mapv (fn [b]
                                      {:content (:content b)
                                       :uuid (:uuid b)})
                                    blocks)
                    :count (count blocks)}))))))

(defn- handle-search-memory [params]
  (let [query (get params "query")]
    (if (str/blank? query)
      (js/Promise.reject "Missing required parameter: query")
      (-> (memory/retrieve-memories query)
          (.then (fn [blocks]
                   {:query query
                    :results (mapv (fn [b]
                                     {:content (:block/content b)
                                      :page (:block/page b)})
                                   blocks)
                    :count (count blocks)}))))))

(defn- handle-list-memory-tags [_params]
  (let [prefix (str/lower-case (get-in @memory/state [:config :page-prefix]))
        query (str "[:find (pull ?p [:block/name :block/original-name])
                     :where [?p :block/name ?name]
                     [(clojure.string/starts-with? ?name \"" (memory/escape-datalog-string prefix) "\")]]")]
    (-> (js/logseq.DB.datascriptQuery query)
        (.then (fn [results]
                 (let [converted (js->clj results :keywordize-keys true)
                       tags (mapv (fn [r]
                                    (let [full-name (:block/name (first r))
                                          tag (subs full-name (count prefix))]
                                      {:tag tag :page full-name}))
                                  converted)]
                   {:tags tags :count (count tags)}))))))

;; =============================================================================
;; Dispatch Map
;; =============================================================================

(def operation-handlers
  "Map of operation names to handler functions."
  {;; Job operations
   "create_job"         handle-create-job
   "list_jobs"          handle-list-jobs
   "get_job"            handle-get-job
   "start_job"          handle-start-job
   "cancel_job"         handle-cancel-job
   "pause_job"          handle-pause-job
   "resume_job"         handle-resume-job
   ;; Skill operations
   "list_skills"        handle-list-skills
   "get_skill"          handle-get-skill
   "create_skill"       handle-create-skill
   ;; MCP client operations
   "list_mcp_servers"   handle-list-mcp-servers
   "list_mcp_tools"     handle-list-mcp-tools
   "list_mcp_resources" handle-list-mcp-resources
   ;; Secrets operations
   "list_secret_keys"   handle-list-secret-keys
   "set_secret"         handle-set-secret
   "remove_secret"      handle-remove-secret
   ;; Graph operations (for MCP server)
   "graph_query"        handle-graph-query
   "graph_search"       handle-graph-search
   "page_read"          handle-page-read
   "page_create"        handle-page-create
   "page_list"          handle-page-list
   "block_append"       handle-block-append
   "block_update"       handle-block-update
   ;; Memory operations (for MCP server)
   "store_memory"       handle-store-memory
   "recall_memory"      handle-recall-memory
   "search_memory"      handle-search-memory
   "list_memory_tags"   handle-list-memory-tags
   ;; Registry operations (for MCP server)
   "registry_list"      registry-bridge/handle-registry-list
   "registry_get"       registry-bridge/handle-registry-get
   "registry_search"    registry-bridge/handle-registry-search
   "registry_refresh"   registry-bridge/handle-registry-refresh
   "execute_skill"      registry-bridge/handle-execute-skill
   ;; Code repository operations (for MCP server)
   "project_list"       code-repo-bridge/handle-project-list
   "project_get"        code-repo-bridge/handle-project-get
   ;; ADR operations (for MCP server)
   "adr_list"           adr/handle-adr-list
   "adr_create"         adr/handle-adr-create
   ;; Lesson operations (for MCP server)
   "lesson_store"       lessons/handle-lesson-store
   "lesson_search"      lessons/handle-lesson-search
   ;; Safeguard operations (for MCP server)
   "safeguard_policy_get"    safeguard/handle-safeguard-policy-get
   "safeguard_audit_append"  safeguard/handle-safeguard-audit-append
   ;; Work log operations (for MCP server)
   "work_log"               work/handle-work-log
   ;; Track/task operations (for MCP server)
   "track_create"           tasks/handle-track-create
   "track_list"             tasks/handle-track-list
   "track_update"           tasks/handle-track-update
   "task_add"               tasks/handle-task-add
   "task_update"            tasks/handle-task-update
   "task_list"              tasks/handle-task-list
   "project_dashboard"      tasks/handle-project-dashboard
   ;; Pi.dev agent profile operations (for MCP server)
   "pi_agent_list"          pi-agents/handle-pi-agent-list
   "pi_agent_get"           pi-agents/handle-pi-agent-get
   "pi_agent_create"        pi-agents/handle-pi-agent-create
   "pi_agent_update"        pi-agents/handle-pi-agent-update})

;; =============================================================================
;; Event Dispatcher
;; =============================================================================

(defn dispatch-agent-request
  "Dispatches an agent request to the appropriate handler and sends the result back."
  [event-data]
  (let [request-id (get event-data "requestId")
        operation (get event-data "operation")
        params (or (get event-data "params") {})
        trace-id (get event-data "traceId")
        handler (get operation-handlers operation)]
    (js/console.log "Agent request:" operation "id:" request-id "traceId:" trace-id)
    (if handler
      (-> (js/Promise.resolve (handler params))
          (.then (fn [result]
                   (send-callback! request-id true (clj->js result) nil trace-id)))
          (.catch (fn [err]
                    (let [error-msg (if (string? err) err (.-message err))]
                      (js/console.error "Agent handler error:" operation error-msg "traceId:" trace-id)
                      (send-callback! request-id false nil error-msg trace-id)))))
      (do
        (js/console.warn "Unknown agent operation:" operation "traceId:" trace-id)
        (send-callback! request-id false nil (str "Unknown operation: " operation) trace-id)))))

;; =============================================================================
;; SSE Event Registration
;; =============================================================================

(defn register-agent-handler!
  "Registers the agent_request SSE event handler on an EventSource instance."
  [event-source]
  (when event-source
    (.addEventListener event-source "agent_request"
      (fn [event]
        (try
          (let [data (js->clj (js/JSON.parse (.-data event)))]
            (dispatch-agent-request data))
          (catch js/Error e
            (js/console.error "Failed to parse agent_request event:" e)))))))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn init!
  "Initializes the agent bridge.
   Should be called after messaging SSE connection is established."
  []
  (js/console.log "Agent bridge: initialized"))
