# Specification: Job Runner System -- Autonomous Task Execution Engine

## Overview

Add a job runner system to Logseq AI Hub that transforms Logseq into a local-first AI agent platform. The system provides autonomous task execution using the Logseq knowledge graph as context, reusable skill definitions stored as Logseq pages, connectivity to external MCP (Model Context Protocol) servers for extended capabilities, and a task queue with scheduling, priority, dependencies, and retry logic. Skill definitions can be imported/exported in OpenClaw-compatible JSON format.

## Background

The existing codebase has a `tasks.cljs` module that implements a step-based execution engine with promise chains, a task registry atom, and message-triggered task execution. The `agent.cljs` module provides a model registry with `process-input` dispatch to registered handlers (echo, reverse, OpenAI). These modules provide the foundation but lack: autonomous job execution from graph-defined jobs, reusable skill composition, external tool connectivity via MCP, queue management with scheduling, and interoperability with the OpenClaw ecosystem.

The job runner extends the existing task orchestration into a full autonomous execution engine where jobs are first-class citizens in the Logseq graph -- defined as pages with structured properties, executed by the runner, and with results written back into the graph.

### Dependencies on core-arch_20260209

This track depends on the following deliverables from the `core-arch_20260209` track:

- **FR-1 (Namespace Migration):** All namespaces must be under `logseq-ai-hub.*`
- **FR-9 (Error Handling Foundation):** The `logseq-ai-hub.util.errors` utility namespace must exist with `make-error`, `error?`, `make-error-promise`, and `wrap-promise-errors`
- **FR-10 (Testing Infrastructure):** The `:node-test` build target and `yarn test` script must be operational
- **NFR-2 (Hot-Reload Compatibility):** All state atoms use `defonce`

If core-arch is not yet complete, Phase 1 of the implementation plan can proceed in parallel (pure data model and utility functions), but Phases 2+ require the error handling foundation and testing infrastructure.

## Data Model

### Job Page Model

Jobs are Logseq pages following the naming convention `Jobs/<job-name>`. The page title serves as the job identifier. Job metadata is defined via Logseq block properties on the first block of the page. Subsequent child blocks define the job steps.

```
Page: Jobs/summarize-daily-notes

First block (job definition):
  job-type:: autonomous
  job-status:: queued
  job-priority:: 3
  job-schedule:: 0 9 * * *
  job-depends-on:: Jobs/fetch-daily-notes
  job-max-retries:: 3
  job-retry-count:: 0
  job-skill:: Skills/summarize
  job-input:: {"query": "today's journal"}
  job-created-at:: 2026-02-19T10:00:00Z
  job-started-at::
  job-completed-at::
  job-error::

  Child blocks (step results / execution log):
    Step 1: Queried graph for today's journal -- found 12 blocks
    Step 2: Sent to LLM for summarization
    Step 3: Result written to [[Daily Summaries/2026-02-19]]
```

**Job properties schema:**

| Property | Type | Required | Description |
|---|---|---|---|
| `job-type` | enum: `autonomous`, `manual`, `scheduled`, `event-driven` | Yes | Execution trigger type |
| `job-status` | enum: `draft`, `queued`, `running`, `completed`, `failed`, `cancelled`, `paused` | Yes | Current lifecycle state |
| `job-priority` | integer 1-5 (1=highest) | No (default: 3) | Queue priority |
| `job-schedule` | cron expression string | No | For scheduled jobs |
| `job-depends-on` | comma-separated page refs | No | Jobs that must complete first |
| `job-max-retries` | integer | No (default: 0) | Maximum retry attempts |
| `job-retry-count` | integer | No (default: 0) | Current retry count |
| `job-skill` | page ref to a Skill page | No | Skill to execute |
| `job-input` | JSON string | No | Input parameters for the skill |
| `job-created-at` | ISO-8601 timestamp | Yes | When the job was created |
| `job-started-at` | ISO-8601 timestamp | No | When execution began |
| `job-completed-at` | ISO-8601 timestamp | No | When execution finished |
| `job-error` | string | No | Error message if failed |
| `job-result` | JSON string or page ref | No | Output/result reference |

### Skill Page Model

Skills are Logseq pages following the naming convention `Skills/<skill-name>`. A skill defines a reusable capability with inputs, outputs, and execution logic. The first block contains skill metadata. Child blocks define the execution steps.

```
Page: Skills/summarize

First block (skill definition):
  skill-type:: llm-chain
  skill-version:: 1
  skill-description:: Summarizes content using an LLM with configurable detail level
  skill-inputs:: query, detail-level
  skill-outputs:: summary, source-blocks
  skill-tags:: summarization, llm

  Child block 1 (step: graph-query):
    step-order:: 1
    step-action:: graph-query
    step-config:: {"query": "{{query}}", "limit": 50}

  Child block 2 (step: llm-call):
    step-order:: 2
    step-action:: llm-call
    step-prompt-template::
      Summarize the following content at {{detail-level}} detail level:

      {{step-1-result}}

      Provide a clear, structured summary.
    step-model:: openai-model
    step-config:: {"temperature": 0.3}

  Child block 3 (step: write-result):
    step-order:: 3
    step-action:: block-insert
    step-config:: {"target-page": "Summaries/{{today}}", "content": "{{step-2-result}}"}
```

**Skill properties schema:**

| Property | Type | Required | Description |
|---|---|---|---|
| `skill-type` | enum: `llm-chain`, `tool-chain`, `composite`, `mcp-tool` | Yes | Execution strategy |
| `skill-version` | integer | Yes | Version for compatibility tracking |
| `skill-description` | string | Yes | Human-readable description |
| `skill-inputs` | comma-separated names | Yes | Expected input parameter names |
| `skill-outputs` | comma-separated names | Yes | Output parameter names |
| `skill-tags` | comma-separated strings | No | Categorization tags |

**Step properties schema:**

| Property | Type | Required | Description |
|---|---|---|---|
| `step-order` | integer | Yes | Execution order (1-based) |
| `step-action` | enum (see below) | Yes | Action type |
| `step-config` | JSON string | No | Action-specific configuration |
| `step-prompt-template` | multiline string | No | Prompt with `{{variable}}` interpolation |
| `step-model` | string (model registry ID) | No | Model to use for LLM steps |
| `step-mcp-server` | string | No | MCP server ID for MCP tool steps |
| `step-mcp-tool` | string | No | MCP tool name |

**Step action types:**

- `graph-query` -- Execute a Datalog query against the Logseq graph
- `llm-call` -- Send a prompt to an LLM via the agent model registry
- `block-insert` -- Insert a block into a page
- `block-update` -- Update an existing block
- `page-create` -- Create a new page
- `mcp-tool` -- Call a tool on a connected MCP server
- `mcp-resource` -- Read a resource from an MCP server
- `transform` -- Apply a pure data transformation (JSON path, string ops)
- `conditional` -- Branch execution based on a condition
- `sub-skill` -- Execute another skill as a sub-routine

### MCP Connection Model

MCP server connections are managed via plugin settings and an in-memory registry. Each connection represents an MCP server the plugin can communicate with.

```
Settings entry (per MCP server):
  mcp-server-<id>-url: "http://localhost:3001"
  mcp-server-<id>-name: "filesystem"
  mcp-server-<id>-transport: "streamable-http"
  mcp-server-<id>-auth-token: "optional-token"
```

**In-memory MCP connection state:**

```clojure
{:servers {"filesystem" {:id "filesystem"
                         :url "http://localhost:3001"
                         :name "Filesystem Server"
                         :transport :streamable-http  ;; :streamable-http | :sse
                         :status :connected           ;; :disconnected | :connecting | :connected | :error
                         :auth-token "..."
                         :capabilities {:tools true :resources true :prompts true}
                         :tools []                    ;; cached tool definitions
                         :resources []                ;; cached resource list
                         :prompts []}}}               ;; cached prompt list
```

### Task Queue Model

The task queue manages job execution ordering, concurrency, and scheduling.

```clojure
{:queue []              ;; priority-sorted vector of job-ids awaiting execution
 :running #{}           ;; set of currently executing job-ids
 :scheduled {}          ;; cron-id -> {:job-id :cron :next-run}
 :config {:max-concurrent 3
          :poll-interval-ms 5000
          :default-timeout-ms 300000}}  ;; 5 minute default timeout
```

## Functional Requirements

### FR-1: Job Page Parser

**Description:** Parse Logseq pages matching the `Jobs/*` naming convention into structured job definition maps. Read block properties to extract job metadata and child blocks for step definitions.

**Acceptance Criteria:**
- Given a page named `Jobs/my-job` with valid block properties, when the parser reads it, then it returns a map matching the job properties schema
- Given a page with missing required properties (`job-type`, `job-status`), the parser returns an error map using the error handling foundation
- Given a page with `job-input` containing a JSON string, the parser deserializes it into a ClojureScript map
- Given a page with `job-depends-on` containing comma-separated page refs, the parser returns a vector of dependency job IDs
- The parser handles pages that do not exist by returning nil
- The parser is a pure function (given block data as input) with a thin Logseq API wrapper for fetching

**Priority:** P0

### FR-2: Skill Page Parser

**Description:** Parse Logseq pages matching the `Skills/*` naming convention into structured skill definition maps, including ordered step definitions from child blocks.

**Acceptance Criteria:**
- Given a page named `Skills/summarize` with valid properties and child blocks, when the parser reads it, then it returns a skill definition map with metadata and an ordered vector of steps
- Steps are ordered by their `step-order` property
- `step-prompt-template` values are preserved as raw strings (interpolation happens at execution time)
- `step-config` JSON strings are deserialized into ClojureScript maps
- Missing required skill properties produce an error map
- The parser validates that `step-action` values are from the known action types enum

**Priority:** P0

### FR-3: Template Interpolation Engine

**Description:** Resolve `{{variable}}` placeholders in prompt templates and config strings at execution time, substituting job inputs, step results, and built-in variables.

**Acceptance Criteria:**
- `{{variable-name}}` is replaced with the corresponding value from the execution context
- `{{step-N-result}}` is replaced with the output of step N from the current execution
- Built-in variables supported: `{{today}}` (current date), `{{now}}` (current ISO timestamp), `{{job-id}}` (current job page name)
- Nested variable references (e.g., `{{step-1-result}}` containing further `{{...}}` markers) are not recursively resolved (single-pass interpolation)
- Missing variables are replaced with an empty string and a warning is logged
- The interpolation function is pure (context map in, string out)

**Priority:** P0

### FR-4: Step Executor

**Description:** Execute individual skill steps by dispatching on `step-action` type. Each step executor receives the step definition, execution context (inputs, prior results), and returns a Promise resolving to the step result.

**Acceptance Criteria:**
- `graph-query` steps execute a Datalog query via `logseq.DB.datascriptQuery` and return the result set
- `llm-call` steps interpolate the prompt template, send it via `agent/process-input`, and return the LLM response
- `block-insert` steps insert a block into the specified page via `logseq.Editor.appendBlockInPage`
- `block-update` steps update an existing block via `logseq.Editor.updateBlock`
- `page-create` steps create a new page via `logseq.Editor.createPage`
- `mcp-tool` steps call a tool on the specified MCP server and return the result
- `mcp-resource` steps read a resource from the specified MCP server and return its contents
- `transform` steps apply data transformations (get-in paths, string operations) to prior step results
- `conditional` steps evaluate a condition against the context and return a directive indicating which branch to take
- `sub-skill` steps load and execute another skill definition, returning its final result
- All step executors return Promises
- All step executors wrap errors using the error handling foundation
- Unknown action types produce an error result (not an exception)

**Priority:** P0

### FR-5: Skill Execution Engine

**Description:** Execute a complete skill by running its steps in order, threading the execution context (inputs and accumulated step results) through the step chain.

**Acceptance Criteria:**
- Given a parsed skill definition and an input map, the engine executes steps sequentially via Promise chaining
- Each step receives the full execution context: `{:inputs {...} :step-results {1 result-1, 2 result-2, ...} :job-id "..." :variables {...}}`
- Step results are accumulated in the context under their `step-order` key
- If a step fails and the job has retries remaining, the engine retries from the failed step
- If a step fails with no retries remaining, the engine halts and returns an error result with the failing step identified
- `conditional` steps can skip subsequent steps or branch to a different step number
- The engine writes execution progress as child blocks under the job page (step-by-step log)
- Total execution time is tracked and available in the result

**Priority:** P0

### FR-6: Job Runner Core

**Description:** The central orchestrator that picks up queued jobs, resolves their skill definitions, executes them via the skill execution engine, and writes results back to the graph.

**Acceptance Criteria:**
- The runner polls for jobs with `job-status:: queued` at a configurable interval
- Jobs are executed in priority order (lower `job-priority` number = higher priority)
- Before executing a job, the runner checks `job-depends-on` and skips jobs whose dependencies are not yet `completed`
- When a job starts: `job-status` is updated to `running`, `job-started-at` is set
- When a job completes: `job-status` is updated to `completed`, `job-completed-at` is set, `job-result` is written
- When a job fails: `job-status` is updated to `failed`, `job-error` is set. If retries remain, `job-retry-count` is incremented and `job-status` is reset to `queued`
- The runner respects `max-concurrent` and does not start new jobs if the limit is reached
- The runner can be started and stopped via API calls
- The runner logs all state transitions to the console

**Priority:** P0

### FR-7: Task Queue Management

**Description:** Priority queue with dependency resolution, concurrency control, and lifecycle management.

**Acceptance Criteria:**
- Jobs are enqueued by setting `job-status:: queued` on a job page (detected by polling or explicit API call)
- The queue sorts by `job-priority` (ascending), then by `job-created-at` (ascending, FIFO within same priority)
- `enqueue-job!` accepts a job ID and validates the job page exists and has required properties
- `dequeue-job!` returns the next eligible job (not blocked by dependencies, not exceeding concurrency)
- `cancel-job!` sets `job-status:: cancelled` and removes the job from the queue
- `pause-job!` sets `job-status:: paused` and removes from queue without cancelling
- `resume-job!` sets a paused job back to `queued`
- Queue state is entirely in-memory (rebuilt from graph on plugin reload)

**Priority:** P0

### FR-8: Job Scheduling

**Description:** Cron-based scheduling for recurring jobs. Jobs with a `job-schedule` property are automatically queued at the specified intervals.

**Acceptance Criteria:**
- Jobs with `job-type:: scheduled` and a valid `job-schedule` cron expression are registered with the scheduler on runner startup
- The scheduler uses `setInterval` polling (not a full cron library) to check every 60 seconds which scheduled jobs should fire
- When a scheduled job fires, a new job instance is created (cloned from the template job page) with `job-status:: queued`
- Scheduled job instances are named `Jobs/<template-name>-<timestamp>`
- The scheduler can be started and stopped independently of the runner
- Invalid cron expressions are detected at registration time and produce a warning

**Priority:** P1

### FR-9: MCP Client -- Connection Management

**Description:** Connect to MCP servers using the Streamable HTTP and/or SSE transports. Manage connection lifecycle, capability discovery, and reconnection.

**Acceptance Criteria:**
- The MCP client supports the Streamable HTTP transport (primary) and legacy SSE transport (fallback)
- `connect-server!` establishes a connection to an MCP server given URL, transport type, and optional auth token
- On connection, the client calls `initialize` to perform capability negotiation and caches the server's capabilities
- `disconnect-server!` gracefully closes a connection
- `list-servers` returns all configured servers with their connection status
- Connection errors are handled gracefully and surfaced via the error handling foundation
- Auto-reconnect attempts occur on connection loss (3 retries with exponential backoff: 1s, 5s, 15s)
- Server configurations are persisted in plugin settings; connections are re-established on plugin reload

**Priority:** P0

### FR-10: MCP Client -- Tool Operations

**Description:** List and call tools on connected MCP servers.

**Acceptance Criteria:**
- `list-tools` returns the cached tool definitions for a given server (name, description, input schema)
- `call-tool` sends a `tools/call` request to the specified server with the given tool name and arguments
- Tool arguments are validated against the tool's input schema before sending (basic type checking)
- Tool call results are returned as ClojureScript maps
- Tool call errors (server-side) are captured and returned as error maps
- Tool calls have a configurable timeout (default: 30 seconds)
- Tool definitions are refreshed on connection and can be manually refreshed via `refresh-tools!`

**Priority:** P0

### FR-11: MCP Client -- Resource Operations

**Description:** List and read resources from connected MCP servers.

**Acceptance Criteria:**
- `list-resources` returns available resources from a server (URI, name, description, MIME type)
- `read-resource` fetches the content of a specific resource by URI
- Resource contents are returned with their MIME type metadata
- Resource templates (parameterized URIs) are supported
- Resources can be subscribed to for change notifications (if the server supports it)

**Priority:** P1

### FR-12: MCP Client -- Prompt Operations

**Description:** List and use prompts from connected MCP servers.

**Acceptance Criteria:**
- `list-prompts` returns available prompt templates from a server (name, description, arguments)
- `get-prompt` retrieves a specific prompt with arguments filled in, returning the message array
- Prompt arguments are validated against the prompt's argument schema
- Retrieved prompts can be fed directly into the skill execution engine as LLM call inputs

**Priority:** P1

### FR-13: MCP Integration with Step Executor

**Description:** The step executor's `mcp-tool` and `mcp-resource` actions delegate to the MCP client.

**Acceptance Criteria:**
- `mcp-tool` steps specify `step-mcp-server` and `step-mcp-tool` properties, with arguments from `step-config` (interpolated)
- `mcp-resource` steps specify `step-mcp-server` and a resource URI in `step-config`
- If the specified MCP server is not connected, the step fails with a descriptive error
- MCP step results are added to the execution context like any other step result

**Priority:** P0

### FR-14: OpenClaw Skill Import

**Description:** Import skill definitions from OpenClaw-compatible JSON format into Logseq Skill pages.

**Acceptance Criteria:**
- Given a JSON file (or JSON string) in OpenClaw skill format, the importer creates a `Skills/*` page with the equivalent Logseq block properties and step blocks
- The importer maps OpenClaw fields to the Logseq skill properties schema
- Fields that have no direct mapping are stored as `openclaw-meta::` JSON on the skill page
- If a skill page with the same name already exists, the importer prompts for overwrite or creates a versioned name
- Import is available via slash command `/job:import-skill` and programmatic API
- Import validates the JSON structure and reports specific errors for malformed input

**Priority:** P2

### FR-15: OpenClaw Skill Export

**Description:** Export Logseq Skill pages to OpenClaw-compatible JSON format.

**Acceptance Criteria:**
- Given a `Skills/*` page, the exporter produces a JSON string in OpenClaw skill format
- The exporter maps Logseq skill properties to OpenClaw fields
- `openclaw-meta::` properties are merged back into the exported JSON
- Export is available via slash command `/job:export-skill` and programmatic API
- The exported JSON is valid according to the OpenClaw skill schema

**Priority:** P2

### FR-16: Slash Commands

**Description:** Register slash commands for interacting with the job runner system.

**Acceptance Criteria:**
- `/job:run <job-name>` -- Enqueue and run a specific job
- `/job:status` -- Show status of all queued/running jobs as an inserted block
- `/job:cancel <job-name>` -- Cancel a running or queued job
- `/job:create` -- Create a new job page from a template with prompted inputs
- `/job:import-skill` -- Import a skill from JSON (reads from current block content or prompts for file)
- `/job:export-skill` -- Export the current skill page to JSON (inserts JSON as a code block)
- `/job:mcp-servers` -- List connected MCP servers and their status
- `/job:mcp-tools <server>` -- List available tools on a specified MCP server
- All commands provide user feedback via `logseq.App.showMsg` for success/error states

**Priority:** P1

### FR-17: Settings Schema Extension

**Description:** Extend the plugin settings to include job runner and MCP configuration.

**Acceptance Criteria:**
- `jobRunnerEnabled` (boolean, default: false) -- Master switch for the job runner
- `jobRunnerMaxConcurrent` (number, default: 3) -- Maximum concurrent job executions
- `jobRunnerPollInterval` (number, default: 5000) -- Queue poll interval in milliseconds
- `jobRunnerDefaultTimeout` (number, default: 300000) -- Default job timeout in milliseconds
- `jobPagePrefix` (string, default: "Jobs/") -- Page prefix for job definitions
- `skillPagePrefix` (string, default: "Skills/") -- Page prefix for skill definitions
- `mcpServers` (string, default: "[]") -- JSON array of MCP server configurations
- All settings have descriptive titles and descriptions
- Settings render correctly in the Logseq plugin settings panel

**Priority:** P1

## Non-Functional Requirements

### NFR-1: Local-First Execution

- All job execution happens within the Logseq plugin browser context
- No data is sent to external servers except through explicitly configured MCP connections and the existing OpenAI/LLM endpoints
- The job queue and scheduler operate entirely in-memory with graph persistence
- The system must function without any network connectivity (except for LLM and MCP calls)

### NFR-2: Performance

- Job queue polling must not degrade Logseq UI responsiveness (use `requestIdleCallback` or `setTimeout` with yielding)
- Graph queries for job/skill page parsing should be cached where possible
- MCP tool/resource lists should be cached and refreshed only on connection or explicit request
- Maximum memory footprint for queue state: proportional to number of jobs, not job content (content read on demand)

### NFR-3: Error Resilience

- A failing job must not crash the runner or affect other running jobs
- MCP connection failures must not prevent the runner from executing non-MCP jobs
- All Promise chains must have `.catch` handlers
- Unhandled exceptions in step executors are caught and converted to error results

### NFR-4: Extensibility

- New step action types can be added by registering a handler function (similar to the model registry pattern)
- New MCP servers can be added at runtime via settings changes
- Users can create their own skill pages without modifying plugin code
- The skill/job page property schemas are documented for user reference

### NFR-5: Observability

- Job state transitions are logged to the console with timestamps
- Execution progress is written as child blocks under the job page
- MCP connection events are logged
- A summary of runner state (queued, running, completed, failed counts) is available via slash command

### NFR-6: Test Coverage

- Target: 80% coverage for all new modules
- All pure functions (parsers, interpolation, queue logic) have unit tests
- Step executors are tested with mocked Logseq API and MCP calls
- Integration tests verify the full job lifecycle with mock data

## User Stories

### US-1: User creates and runs an autonomous job

**As** a Logseq user,
**I want** to create a job page with properties and have the runner execute it,
**So that** I can automate knowledge work within my graph.

**Scenarios:**
- **Given** I create a page `Jobs/summarize-today` with `job-type:: manual`, `job-status:: queued`, `job-skill:: Skills/summarize`, `job-input:: {"query": "today"}`, **When** the runner picks it up, **Then** the skill executes, results are written back, and `job-status` becomes `completed`.
- **Given** I create a job with `job-status:: draft`, **When** the runner polls, **Then** the job is not picked up until I change the status to `queued`.

### US-2: User defines a reusable skill

**As** a power user,
**I want** to define skills as Logseq pages with steps and prompt templates,
**So that** I can reuse and share automation logic.

**Scenarios:**
- **Given** I create `Skills/translate` with steps for `graph-query` and `llm-call`, **When** a job references this skill, **Then** the steps execute in order with template interpolation.
- **Given** a skill has a `sub-skill` step referencing `Skills/summarize`, **When** the skill executes, **Then** the sub-skill runs and its result is available to subsequent steps.

### US-3: User connects an MCP server

**As** a developer,
**I want** to connect MCP servers to extend my job runner's capabilities,
**So that** my jobs can use external tools like file system access or web search.

**Scenarios:**
- **Given** I configure an MCP server URL in settings, **When** the plugin loads, **Then** the MCP client connects and discovers available tools.
- **Given** a skill step uses `step-action:: mcp-tool` with `step-mcp-server:: filesystem` and `step-mcp-tool:: read_file`, **When** the step executes, **Then** the MCP client calls the tool and returns the file content.

### US-4: User imports an OpenClaw skill

**As** a user familiar with OpenClaw,
**I want** to import skill definitions from OpenClaw JSON format,
**So that** I can leverage existing skill libraries.

**Scenarios:**
- **Given** I paste OpenClaw skill JSON into a block and run `/job:import-skill`, **When** the import completes, **Then** a new `Skills/*` page is created with the equivalent properties and steps.
- **Given** the imported skill uses tools not available locally, **When** the import runs, **Then** it completes successfully and notes the missing tool dependencies in a warning block.

### US-5: User schedules recurring jobs

**As** a knowledge worker,
**I want** to schedule jobs to run at specific times,
**So that** routine tasks happen automatically.

**Scenarios:**
- **Given** a job page with `job-type:: scheduled` and `job-schedule:: 0 9 * * *`, **When** the scheduler fires at 9:00 AM, **Then** a new job instance is created and queued.
- **Given** a scheduled job is running and the schedule fires again, **When** the scheduler checks, **Then** it skips creating a duplicate if the previous instance is still `running`.

### US-6: User monitors job execution

**As** a user,
**I want** to see what jobs are running and their progress,
**So that** I can understand system behavior and troubleshoot issues.

**Scenarios:**
- **Given** jobs are queued and running, **When** I run `/job:status`, **Then** a block is inserted showing counts and names of queued, running, completed, and failed jobs.
- **Given** a job is running, **When** I navigate to its page, **Then** I see child blocks appearing as each step completes.

## Technical Considerations

### Browser Context Constraints

The plugin runs in a browser context within Logseq. This means:
- No direct filesystem access (must use MCP for file operations)
- No native cron library (use `setInterval` with manual cron expression matching)
- `fetch` API is available for HTTP requests (MCP Streamable HTTP transport)
- `EventSource` API is available (MCP SSE transport, already used by messaging module)
- Long-running operations must yield to the UI thread

### MCP Protocol Implementation

The MCP client implements the Model Context Protocol specification:
- **Streamable HTTP transport:** POST requests to the server endpoint with JSON-RPC 2.0 payloads. Responses may be direct JSON-RPC responses or upgrade to SSE streams for long-running operations.
- **SSE transport (legacy):** GET request opens an SSE stream; messages sent via POST to a separate endpoint.
- **Initialization:** Send `initialize` request with client capabilities, receive server capabilities.
- **JSON-RPC 2.0:** All messages follow JSON-RPC 2.0 format with `method`, `params`, `id` for requests.
- The implementation should be a standalone `mcp.cljs` module that can be tested independently.

### Cron Expression Parsing

A minimal cron parser is needed for the scheduler. Support the standard 5-field format: `minute hour day-of-month month day-of-week`. Full cron features (ranges, lists, step values) should be supported. This is a pure function suitable for thorough unit testing.

### Relationship to Existing tasks.cljs

The existing `tasks.cljs` module has a simpler step execution model (`:action` keyword dispatch, message-triggered). The job runner does not replace it but builds alongside it:
- The existing `execute-step` actions (`:ai-process`, `:send-message`, `:store-memory`, `:logseq-ingest`, `:logseq-insert`) become available as step action types in the skill execution engine
- The existing `run-task!` can be invoked as a step action type (`:legacy-task`)
- Over time, functionality may migrate from `tasks.cljs` into the job runner, but backward compatibility is maintained

### Graph as State Store

Job and skill state lives in the Logseq graph. This means:
- State is persisted across plugin reloads (the graph is the source of truth)
- The in-memory queue is rebuilt by scanning `Jobs/*` pages on startup
- Property updates require `logseq.Editor.upsertBlockProperty` or block content manipulation
- There may be latency between property writes and subsequent reads (eventual consistency within the graph)

## Out of Scope

- Real-time collaborative job execution (multi-user)
- Job runner web UI beyond Logseq slash commands and in-graph status pages
- MCP server implementation (we are only an MCP client)
- Custom UI panels or sidebars (Logseq plugin API limitations)
- Job execution history analytics or dashboards
- Authentication/authorization for job execution (all local, single user)
- Distributed job execution across multiple Logseq instances
- Full OpenClaw runtime compatibility (we support data format interop only)
- Streaming LLM responses within skill steps (full response only)

## Open Questions

1. **Graph write latency:** When the runner updates `job-status` via `logseq.Editor.upsertBlockProperty`, is the change immediately visible to subsequent `logseq.DB.datascriptQuery` calls? If not, the runner should rely on in-memory state and treat graph writes as persistence, not as the authoritative queue state. (Recommendation: use in-memory state as primary, graph as persistence.)

2. **MCP transport selection:** Should the plugin auto-detect which MCP transport a server supports (try Streamable HTTP first, fall back to SSE), or require explicit configuration? (Recommendation: auto-detect with explicit override option.)

3. **Concurrent graph writes:** If multiple jobs write to the graph simultaneously, are there race conditions with `logseq.Editor` operations? (Recommendation: serialize graph write operations through a single write queue to avoid conflicts.)

4. **Skill page editing during execution:** If a user edits a skill page while a job using that skill is running, should the running execution use the original or updated skill? (Recommendation: parse the skill at job start time and use the snapshot throughout execution.)

5. **OpenClaw format version:** Which version of the OpenClaw skill definition format should we target? (Recommendation: target the current stable format and document the mapping.)
