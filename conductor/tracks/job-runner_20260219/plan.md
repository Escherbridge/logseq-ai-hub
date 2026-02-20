# Implementation Plan: Job Runner System -- Autonomous Task Execution Engine

## Overview

This plan implements the job runner system across 9 phases, building from foundational pure functions through to the full autonomous execution engine with MCP connectivity and OpenClaw interop. Each phase ends with a verification checkpoint. All tasks follow the Red-Green-Refactor TDD cycle.

**Estimated total effort:** 35-50 hours across 62 tasks
**Branch:** `track/job-runner_20260219`
**Prerequisites:** core-arch_20260209 deliverables FR-1, FR-9, FR-10, NFR-2

---

## Phase 1: Data Model, Parsers, and Pure Utilities

**Goal:** Implement the pure-function foundation: property schema definitions, job/skill page parsers, template interpolation engine, and cron expression parser. These have zero Logseq API dependencies and are fully unit-testable.

### Tasks:

- [ ] **Task 1.1: Define job and skill property schemas as data**
  - Create `src/main/logseq_ai_hub/job_runner/schemas.cljs` with namespace `logseq-ai-hub.job-runner.schemas`
  - Define `job-properties-schema` as a map: `{:job-type {:required true :type :enum :values #{:autonomous :manual :scheduled :event-driven} :default nil} ...}` for all job properties
  - Define `skill-properties-schema` similarly for skill metadata properties
  - Define `step-properties-schema` for step block properties
  - Define `step-action-types` as a set of valid action keywords
  - Export validation function `(validate-properties schema props)` returning `{:valid true}` or `{:valid false :errors [...]}`
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/schemas_test.cljs` with tests for all validation cases (valid job, missing required fields, invalid enum values, default application). Green: Implement. Refactor: Clean up.
  - **Commit:** `feat(job-runner): add property schemas and validation`

- [ ] **Task 1.2: Implement block property parser**
  - Create `src/main/logseq_ai_hub/job_runner/parser.cljs` with namespace `logseq-ai-hub.job-runner.parser`
  - Implement `(parse-block-properties block-content)` that extracts `key:: value` pairs from a block content string into a map
  - Handle multiline property values (continuation lines without `::`)
  - Handle JSON string values (detect and parse)
  - Handle comma-separated values (split into vectors)
  - Trim whitespace from keys and values
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/parser_test.cljs` with tests for single properties, multiple properties, JSON values, comma-separated values, multiline values, edge cases (empty content, no properties). Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add block property parser`

- [ ] **Task 1.3: Implement job page parser (pure layer)**
  - In `parser.cljs`, implement `(parse-job-definition first-block-content child-block-contents page-name)`
  - Takes raw block content strings (not Logseq API objects)
  - Extracts job properties from first block, validates against schema
  - Parses child blocks as step definitions if present
  - Returns `{:job-id page-name :properties {...} :steps [...] :valid true}` or error map
  - **TDD:** Red: Add tests for valid job parsing, invalid job (missing type/status), job with steps, job with dependencies list, job with JSON input. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add job page parser`

- [ ] **Task 1.4: Implement skill page parser (pure layer)**
  - In `parser.cljs`, implement `(parse-skill-definition first-block-content child-block-contents page-name)`
  - Extracts skill metadata from first block, validates against schema
  - Parses child blocks as ordered steps, validates step-action types
  - Returns `{:skill-id page-name :properties {...} :steps [...] :valid true}` or error map
  - **TDD:** Red: Add tests for valid skill parsing, skill with multiple steps, invalid step action type, missing required properties, step ordering. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add skill page parser`

- [ ] **Task 1.5: Implement template interpolation engine**
  - Create `src/main/logseq_ai_hub/job_runner/interpolation.cljs` with namespace `logseq-ai-hub.job-runner.interpolation`
  - Implement `(interpolate template-str context)` where context is `{:inputs {...} :step-results {1 "..." 2 "..."} :variables {:today "..." :now "..." :job-id "..."}}`
  - Replace `{{key}}` with value from context lookup order: inputs, step-results (as `step-N-result`), variables
  - Missing variables replaced with `""` (log warning)
  - Single-pass replacement (no recursive resolution)
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/interpolation_test.cljs` with tests for simple variable, step result reference, built-in variables, missing variable, multiple variables in one string, no variables (passthrough), nested braces not resolved. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add template interpolation engine`

- [ ] **Task 1.6: Implement cron expression parser**
  - Create `src/main/logseq_ai_hub/job_runner/cron.cljs` with namespace `logseq-ai-hub.job-runner.cron`
  - Implement `(parse-cron expr)` returning a structured map `{:minute [...] :hour [...] :day-of-month [...] :month [...] :day-of-week [...]}`
  - Support: wildcards (`*`), specific values (`5`), ranges (`1-5`), lists (`1,3,5`), step values (`*/5`, `1-10/2`)
  - Implement `(matches-now? parsed-cron js-date)` returning boolean
  - Implement `(valid-cron? expr)` returning boolean
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/cron_test.cljs` with tests for every-minute, specific time, ranges, lists, step values, day-of-week, invalid expressions, matches-now with various dates. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add cron expression parser`

- [ ] **Task 1.7: Implement priority queue data structure**
  - Create `src/main/logseq_ai_hub/job_runner/queue.cljs` with namespace `logseq-ai-hub.job-runner.queue`
  - Implement pure queue operations on a sorted vector:
    - `(enqueue queue job-entry)` -- insert maintaining sort by `[:priority :created-at]`
    - `(dequeue queue running-set dependency-map)` -- return `[next-eligible-entry remaining-queue]` or `[nil queue]` if none eligible
    - `(remove-from-queue queue job-id)` -- remove by job-id
    - `(queue-size queue)` -- return count
  - Job entry: `{:job-id "..." :priority 3 :created-at "..." :depends-on #{}}`
  - Dependency check: a job is eligible only if all jobs in its `:depends-on` set have status `:completed` (status passed via a status-map argument)
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/queue_test.cljs` with tests for enqueue ordering, dequeue priority, dequeue with dependencies, remove, empty queue, concurrency limit respected. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add priority queue data structure`

- [ ] **Verification 1.8:** Run `yarn test` -- all new tests pass. No Logseq API dependencies in any Phase 1 module (grep for `js/logseq` should return zero hits in `job_runner/` files except for future phases). Pure functions verified. [checkpoint marker]

---

## Phase 2: Graph Integration Layer

**Goal:** Build the thin adapter layer between the pure parsers/queue and the Logseq API. This layer handles reading job/skill pages from the graph and writing status updates back.

### Tasks:

- [ ] **Task 2.1: Implement graph reader for job pages**
  - Create `src/main/logseq_ai_hub/job_runner/graph.cljs` with namespace `logseq-ai-hub.job-runner.graph`
  - Implement `(read-job-page page-name)` returning a Promise resolving to the parsed job definition (delegates to `logseq.Editor.getPageBlocksTree` then passes content to `parser/parse-job-definition`)
  - Implement `(scan-job-pages prefix)` that queries for all pages starting with the prefix and returns Promise resolving to a vector of parsed job definitions
  - Use Datalog query: find pages where `block/name` starts with lowercase prefix
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/graph_test.cljs` with tests using mocked `js/logseq.Editor` and `js/logseq.DB` (set up mock functions in test setup). Test: read valid job page, read nonexistent page returns nil, scan finds multiple jobs. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add graph reader for job pages`

- [ ] **Task 2.2: Implement graph reader for skill pages**
  - In `graph.cljs`, implement `(read-skill-page page-name)` returning Promise resolving to parsed skill definition
  - Implement `(scan-skill-pages prefix)` for discovering all skills
  - **TDD:** Red: Add tests for reading valid skill page, skill with steps parsed in order, nonexistent skill. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add graph reader for skill pages`

- [ ] **Task 2.3: Implement graph writer for job status updates**
  - In `graph.cljs`, implement `(update-job-status! page-name status)` that updates the `job-status::` property on the first block of the job page
  - Implement `(update-job-property! page-name property-key value)` for arbitrary property updates
  - Implement `(append-job-log! page-name log-entry)` that appends a child block with the log text and timestamp
  - Use `logseq.Editor.upsertBlockProperty` where available, fall back to content manipulation if needed
  - **TDD:** Red: Add tests with mocked Logseq Editor API verifying correct API calls are made. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add graph writer for job status updates`

- [ ] **Task 2.4: Implement graph write queue**
  - In `graph.cljs`, implement a serialized write queue to prevent concurrent graph writes from conflicting
  - `(queue-write! write-fn)` returns a Promise that resolves when the write completes
  - Writes are processed sequentially (each waits for the previous to complete)
  - Use a simple atom-based queue with recursive `.then` chaining
  - **TDD:** Red: Test that two concurrent writes are serialized (second waits for first). Test that write errors don't block subsequent writes. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add serialized graph write queue`

- [ ] **Verification 2.5:** Run `yarn test` -- all tests pass including mocked graph interaction tests. Manual test: load plugin, verify no console errors from new modules. [checkpoint marker]

---

## Phase 3: Step Executors

**Goal:** Implement the individual step action executors. Each executor handles one action type and returns a Promise with the result.

### Tasks:

- [ ] **Task 3.1: Create step executor registry and dispatcher**
  - Create `src/main/logseq_ai_hub/job_runner/executor.cljs` with namespace `logseq-ai-hub.job-runner.executor`
  - Implement `(defonce step-executors (atom {}))` -- registry of action-type -> handler-fn
  - Implement `(register-executor! action-type handler-fn)` to register a step executor
  - Implement `(execute-step step context)` that looks up the executor for `(:step-action step)` and calls it with `(handler-fn step context)`, returning a Promise
  - Unknown action types return an error result via the error handling foundation
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/executor_test.cljs` testing registry, dispatch to registered executor, unknown action error. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add step executor registry and dispatcher`

- [ ] **Task 3.2: Implement graph-query step executor**
  - Register executor for `:graph-query` action
  - Interpolates the query from `step-config` using the context
  - Calls `logseq.DB.datascriptQuery` with the interpolated query
  - Returns the query result set as a ClojureScript vector
  - **TDD:** Red: Test with mocked `datascriptQuery` -- correct query sent, results returned. Test with query error. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add graph-query step executor`

- [ ] **Task 3.3: Implement llm-call step executor**
  - Register executor for `:llm-call` action
  - Interpolates `step-prompt-template` using context
  - Resolves model from `step-model` (or default from settings)
  - Calls `agent/process-input` with the interpolated prompt and model ID
  - Returns the LLM response string
  - **TDD:** Red: Test with mocked `agent/process-input` -- correct prompt and model sent, response returned. Test with missing prompt template (error). Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add llm-call step executor`

- [ ] **Task 3.4: Implement block-insert and block-update step executors**
  - Register executors for `:block-insert` and `:block-update` actions
  - `:block-insert`: interpolate target page and content from `step-config`, call `logseq.Editor.appendBlockInPage`
  - `:block-update`: interpolate block UUID and new content, call `logseq.Editor.updateBlock`
  - Return the created/updated block reference
  - **TDD:** Red: Test with mocked Editor API -- correct params passed. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add block-insert and block-update step executors`

- [ ] **Task 3.5: Implement page-create step executor**
  - Register executor for `:page-create` action
  - Interpolate page name and initial content from `step-config`
  - Call `logseq.Editor.createPage`
  - Return the page reference
  - **TDD:** Red: Test with mocked Editor API. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add page-create step executor`

- [ ] **Task 3.6: Implement transform step executor**
  - Register executor for `:transform` action
  - Supports operations defined in `step-config`: `get-in` (path access on prior step result), `join` (string join), `split` (string split), `count`, `filter` (basic predicate)
  - The operation config specifies `{"op": "get-in", "path": ["key", "nested"], "input": "step-1-result"}`
  - **TDD:** Red: Test get-in on nested map, join on vector, split on string, count, error on unknown op. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add transform step executor`

- [ ] **Task 3.7: Implement conditional step executor**
  - Register executor for `:conditional` action
  - Evaluates a condition from `step-config`: `{"condition": "not-empty", "input": "step-1-result", "then-step": 3, "else-step": 5}`
  - Supported conditions: `not-empty`, `empty`, `equals`, `contains`, `greater-than`
  - Returns a control directive: `{:directive :jump :target-step N}` or `{:directive :continue}`
  - **TDD:** Red: Test each condition type, missing input, invalid condition. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add conditional step executor`

- [ ] **Task 3.8: Implement sub-skill step executor**
  - Register executor for `:sub-skill` action
  - Reads the referenced skill page via `graph/read-skill-page`
  - Constructs a sub-context with the specified inputs from `step-config`
  - Delegates to the skill execution engine (forward reference -- uses a dynamic var or callback to avoid circular dependency)
  - Returns the sub-skill's final result
  - **TDD:** Red: Test with mocked skill reader and execution callback. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add sub-skill step executor`

- [ ] **Task 3.9: Implement legacy-task step executor**
  - Register executor for `:legacy-task` action
  - Bridges to the existing `tasks/run-task!` function
  - Takes `task-id` and trigger data from `step-config`
  - Returns the task result
  - **TDD:** Red: Test with mocked `tasks/run-task!`. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add legacy-task step executor bridging to tasks.cljs`

- [ ] **Verification 3.10:** Run `yarn test` -- all step executor tests pass. Review that every executor uses the error handling foundation for error cases. [checkpoint marker]

---

## Phase 4: Skill Execution Engine

**Goal:** Build the engine that orchestrates step execution for a complete skill: sequential execution, context threading, conditional branching, retry logic, and progress logging.

### Tasks:

- [ ] **Task 4.1: Implement skill execution context**
  - Create `src/main/logseq_ai_hub/job_runner/engine.cljs` with namespace `logseq-ai-hub.job-runner.engine`
  - Define `(make-context job-id inputs)` that returns `{:job-id job-id :inputs inputs :step-results {} :variables {:today (today-str) :now (now-str) :job-id job-id}}`
  - Define `(update-context ctx step-order result)` that assocs the step result
  - Define `(resolve-input ctx input-ref)` that resolves an input reference (e.g., `"step-1-result"`) from the context
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/engine_test.cljs` testing context creation, update, and input resolution. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add skill execution context management`

- [ ] **Task 4.2: Implement sequential step runner**
  - In `engine.cljs`, implement `(run-steps steps context on-step-complete)` that chains steps via Promises
  - Each step: interpolate config, execute via `executor/execute-step`, update context, call `on-step-complete` callback with step result
  - Handle `:conditional` directives by jumping to the indicated step
  - Return Promise resolving to final context (with all step results)
  - **TDD:** Red: Test with mock executors -- 3 steps run in order, context accumulates results. Test conditional jump skips a step. Test step failure halts execution. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add sequential step runner with conditional branching`

- [ ] **Task 4.3: Implement skill execution entry point**
  - In `engine.cljs`, implement `(execute-skill skill-def inputs job-id on-progress)` that:
    1. Creates execution context from inputs
    2. Calls `run-steps` with the skill's steps
    3. Calls `on-progress` callback for each step completion (for logging to graph)
    4. Returns Promise resolving to `{:status :completed :result final-step-result :context ctx :duration-ms N}`
    5. On failure, returns `{:status :failed :error error-map :failed-step N :context ctx :duration-ms N}`
  - Track duration with `js/Date` timestamps
  - **TDD:** Red: Test full skill execution with mock steps. Test failure reporting. Test duration tracking. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add skill execution entry point`

- [ ] **Task 4.4: Implement retry logic for skill execution**
  - In `engine.cljs`, implement `(execute-skill-with-retries skill-def inputs job-id max-retries on-progress)` that wraps `execute-skill` with retry logic
  - On failure, if retries remain, re-execute starting from the failed step (not from the beginning)
  - Pass retry count in progress callbacks
  - Return final result after all retries exhausted or success
  - **TDD:** Red: Test retry on failure (mock executor fails once then succeeds). Test max retries exhausted. Test retry from failed step (not from start). Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add retry logic for skill execution`

- [ ] **Task 4.5: Wire sub-skill executor to engine**
  - Resolve the forward reference from Task 3.8: the sub-skill executor now calls `engine/execute-skill` directly
  - Set up the dynamic var or callback during engine initialization
  - Verify no circular dependency issues in the ClojureScript namespace graph
  - **TDD:** Red: Integration test -- a skill with a sub-skill step executes correctly end-to-end (mocked). Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): wire sub-skill executor to engine`

- [ ] **Verification 4.6:** Run `yarn test` -- all engine tests pass. Manual code review: verify Promise chains have `.catch` handlers, error maps used consistently, no uncaught exceptions possible. [checkpoint marker]

---

## Phase 5: MCP Client

**Goal:** Implement the MCP (Model Context Protocol) client supporting Streamable HTTP and SSE transports, connection management, and all four MCP operations: tools, resources, prompts, and initialization.

### Tasks:

- [ ] **Task 5.1: Implement JSON-RPC 2.0 message builder**
  - Create `src/main/logseq_ai_hub/job_runner/mcp/protocol.cljs` with namespace `logseq-ai-hub.job-runner.mcp.protocol`
  - Implement `(make-request method params id)` returning `{:jsonrpc "2.0" :method method :params params :id id}`
  - Implement `(make-notification method params)` returning `{:jsonrpc "2.0" :method method :params params}`
  - Implement `(parse-response json-str)` returning parsed response map with `:result` or `:error` extracted
  - Implement `(request-id!)` returning an incrementing integer ID
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/mcp/protocol_test.cljs` testing request creation, notification creation, response parsing (success and error cases), ID generation. Green: Implement. Refactor.
  - **Commit:** `feat(mcp): add JSON-RPC 2.0 protocol layer`

- [ ] **Task 5.2: Implement Streamable HTTP transport**
  - Create `src/main/logseq_ai_hub/job_runner/mcp/transport.cljs` with namespace `logseq-ai-hub.job-runner.mcp.transport`
  - Implement `(make-http-transport url auth-token)` returning a transport map with `:send!` and `:close!` functions
  - `:send!` posts a JSON-RPC request via `js/fetch` and returns a Promise resolving to the parsed response
  - Handle SSE upgrade responses (Content-Type: text/event-stream) by reading the stream
  - Handle direct JSON responses (Content-Type: application/json)
  - Include `Mcp-Session-Id` header if a session was established
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/mcp/transport_test.cljs` with mocked `js/fetch` -- test JSON response, test error response, test auth header sent. Green: Implement. Refactor.
  - **Commit:** `feat(mcp): add Streamable HTTP transport`

- [ ] **Task 5.3: Implement SSE transport (legacy)**
  - In `transport.cljs`, implement `(make-sse-transport url auth-token)` returning a transport map
  - Uses `EventSource` for receiving messages and `js/fetch` POST for sending
  - Parses the `endpoint` event to discover the POST URL
  - `:send!` posts to the discovered endpoint and correlates responses from the SSE stream by request ID
  - `:close!` closes the EventSource
  - **TDD:** Red: Test with mocked EventSource and fetch. Green: Implement. Refactor.
  - **Commit:** `feat(mcp): add SSE transport (legacy)`

- [ ] **Task 5.4: Implement MCP client connection manager**
  - Create `src/main/logseq_ai_hub/job_runner/mcp/client.cljs` with namespace `logseq-ai-hub.job-runner.mcp.client`
  - Implement `(defonce servers (atom {}))` -- server-id -> connection state
  - Implement `(connect-server! server-config)` that:
    1. Creates the appropriate transport based on config
    2. Sends `initialize` request with client info and capabilities
    3. Sends `initialized` notification
    4. Caches server capabilities
    5. Updates connection state to `:connected`
  - Implement `(disconnect-server! server-id)` for graceful shutdown
  - Implement `(list-servers)` returning all servers and their status
  - Implement auto-reconnect with exponential backoff (1s, 5s, 15s -- 3 retries)
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/mcp/client_test.cljs` testing connect with mocked transport, initialize handshake, disconnect, reconnect logic, server listing. Green: Implement. Refactor.
  - **Commit:** `feat(mcp): add client connection manager with auto-reconnect`

- [ ] **Task 5.5: Implement MCP tool operations**
  - In `client.cljs`, implement:
    - `(list-tools server-id)` -- sends `tools/list` request, caches and returns tool definitions
    - `(call-tool server-id tool-name arguments)` -- sends `tools/call` request, returns result
    - `(refresh-tools! server-id)` -- re-fetches tool list from server
  - Add basic argument validation against the tool's `inputSchema` (type checking only)
  - Add configurable timeout (default 30s) via `Promise.race` with a timeout promise
  - **TDD:** Red: Test list-tools caching, call-tool with correct params, timeout behavior, argument validation. Green: Implement. Refactor.
  - **Commit:** `feat(mcp): add tool listing and calling`

- [ ] **Task 5.6: Implement MCP resource operations**
  - In `client.cljs`, implement:
    - `(list-resources server-id)` -- sends `resources/list` request, returns resource definitions
    - `(read-resource server-id uri)` -- sends `resources/read` request, returns content with MIME type
    - `(subscribe-resource! server-id uri callback)` -- subscribes to resource changes (if server supports `resources/subscribe`)
  - Support resource templates by interpolating URI parameters
  - **TDD:** Red: Test list-resources, read-resource, subscription setup. Green: Implement. Refactor.
  - **Commit:** `feat(mcp): add resource listing and reading`

- [ ] **Task 5.7: Implement MCP prompt operations**
  - In `client.cljs`, implement:
    - `(list-prompts server-id)` -- sends `prompts/list` request, returns prompt definitions
    - `(get-prompt server-id prompt-name arguments)` -- sends `prompts/get` request, returns messages array
  - Validate arguments against prompt's argument schema
  - **TDD:** Red: Test list-prompts, get-prompt with arguments, argument validation. Green: Implement. Refactor.
  - **Commit:** `feat(mcp): add prompt listing and retrieval`

- [ ] **Task 5.8: Implement mcp-tool and mcp-resource step executors**
  - In `executor.cljs`, register executors for `:mcp-tool` and `:mcp-resource` actions
  - `:mcp-tool`: extract server ID and tool name from step properties, interpolate arguments from config, call `mcp-client/call-tool`
  - `:mcp-resource`: extract server ID and URI from step config, call `mcp-client/read-resource`
  - If specified server is not connected, return descriptive error
  - **TDD:** Red: Test mcp-tool executor with mocked MCP client. Test mcp-resource executor. Test disconnected server error. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add mcp-tool and mcp-resource step executors`

- [ ] **Verification 5.9:** Run `yarn test` -- all MCP tests pass. Manual review: verify all transports handle error cases, reconnect logic is bounded, no leaked EventSource connections. [checkpoint marker]

---

## Phase 6: Job Runner Core

**Goal:** Build the central job runner that ties together the queue, graph integration, and skill execution engine into a complete autonomous execution loop.

### Tasks:

- [ ] **Task 6.1: Implement runner state management**
  - Create `src/main/logseq_ai_hub/job_runner/runner.cljs` with namespace `logseq-ai-hub.job-runner.runner`
  - Implement `(defonce runner-state (atom {:status :stopped :queue [] :running #{} :completed {} :failed {} :scheduled {} :config {:max-concurrent 3 :poll-interval-ms 5000 :default-timeout-ms 300000}}))`
  - Implement `(runner-status)` returning a summary map of the runner state
  - Implement `(update-config! config-map)` for runtime config changes
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/runner_test.cljs` testing initial state, status summary, config updates. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add runner state management`

- [ ] **Task 6.2: Implement job lifecycle management**
  - In `runner.cljs`, implement:
    - `(enqueue-job! job-id)` -- reads job page, validates, adds to queue, updates graph status to `queued`
    - `(cancel-job! job-id)` -- removes from queue/running, updates graph status to `cancelled`
    - `(pause-job! job-id)` -- removes from queue, updates status to `paused`
    - `(resume-job! job-id)` -- re-enqueues a paused job
  - All operations go through the graph write queue for serialization
  - **TDD:** Red: Test enqueue (valid job, invalid job), cancel (queued job, running job), pause/resume cycle. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add job lifecycle management`

- [ ] **Task 6.3: Implement job execution orchestrator**
  - In `runner.cljs`, implement `(execute-job! job-id)` that:
    1. Reads the job page via graph reader
    2. Reads the referenced skill page
    3. Updates job status to `running`, sets `job-started-at`
    4. Calls `engine/execute-skill-with-retries` with skill def, job inputs, retry config
    5. On success: updates status to `completed`, sets `job-completed-at`, writes `job-result`
    6. On failure: updates status to `failed`, writes `job-error`. If retries remain, re-queues.
    7. Writes execution log as child blocks
  - Remove job-id from `running` set on completion/failure
  - **TDD:** Red: Test full execution cycle with mocked engine and graph. Test success path. Test failure path. Test retry-and-requeue path. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add job execution orchestrator`

- [ ] **Task 6.4: Implement polling loop**
  - In `runner.cljs`, implement `(start-runner!)` that:
    1. Scans graph for existing `Jobs/*` pages to rebuild the in-memory queue
    2. Starts a `setInterval` loop at the configured poll interval
    3. Each tick: call `queue/dequeue` to get next eligible job, if available and under concurrency limit, call `execute-job!`
    4. Sets runner status to `:running`
  - Implement `(stop-runner!)` that clears the interval and sets status to `:stopped`
  - Use `js/setTimeout` (not `setInterval`) with self-rescheduling for robustness
  - **TDD:** Red: Test start/stop lifecycle. Test that polling dequeues and executes. Test concurrency limit respected. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add polling loop with start/stop lifecycle`

- [ ] **Task 6.5: Implement dependency resolution in runner**
  - Enhance the polling loop to check job dependencies before execution
  - Build a status map of all known jobs (from in-memory state + graph)
  - Pass the status map to `queue/dequeue` for dependency filtering
  - Jobs with unmet dependencies stay in queue but are skipped
  - **TDD:** Red: Test that job with unmet dependency is skipped. Test that job with met dependency proceeds. Test circular dependency detection (warn and skip). Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add dependency resolution to runner`

- [ ] **Verification 6.6:** Run `yarn test` -- all runner tests pass. Manual test: create a `Jobs/test-echo` page with `job-type:: manual`, `job-status:: queued`, `job-skill:: Skills/echo-test`. Create `Skills/echo-test` with a simple echo step. Start runner. Verify job executes and status updates. [checkpoint marker]

---

## Phase 7: Job Scheduling

**Goal:** Add cron-based scheduling for recurring jobs, building on the cron parser from Phase 1.

### Tasks:

- [ ] **Task 7.1: Implement scheduler core**
  - Create `src/main/logseq_ai_hub/job_runner/scheduler.cljs` with namespace `logseq-ai-hub.job-runner.scheduler`
  - Implement `(defonce scheduler-state (atom {:status :stopped :registered {} :timer-id nil}))`
  - Implement `(register-schedule! job-id cron-expr)` that parses the cron expression and adds to `:registered`
  - Implement `(unregister-schedule! job-id)` that removes from `:registered`
  - Implement `(list-schedules)` returning all registered schedules with next expected fire time
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/scheduler_test.cljs` testing register/unregister, list. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add scheduler core with registration`

- [ ] **Task 7.2: Implement scheduler tick and job instance creation**
  - In `scheduler.cljs`, implement `(check-schedules! now-date)` that:
    1. Iterates registered schedules
    2. For each, checks if `cron/matches-now?` is true for the current minute
    3. If firing: checks if a previous instance is still running (skip if so)
    4. Creates a new job instance page `Jobs/<template-name>-<timestamp>` by cloning the template job page properties
    5. Sets the new instance to `job-status:: queued`
    6. Enqueues via the runner
  - Implement `(start-scheduler!)` and `(stop-scheduler!)` using `js/setTimeout` with 60-second interval
  - **TDD:** Red: Test check-schedules fires at correct time. Test skip when previous instance running. Test job instance page creation. Test start/stop. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add scheduler tick and job instance creation`

- [ ] **Task 7.3: Integrate scheduler with runner startup**
  - On runner start, scan for jobs with `job-type:: scheduled` and register their schedules
  - On runner stop, stop the scheduler
  - When a new scheduled job page is created, auto-register its schedule
  - **TDD:** Red: Test that runner startup registers scheduled jobs found in graph. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): integrate scheduler with runner startup`

- [ ] **Verification 7.4:** Run `yarn test` -- all scheduler tests pass. Manual test: create a scheduled job with `job-schedule:: * * * * *` (every minute), start runner, verify a new job instance is created and executed within 2 minutes. [checkpoint marker]

---

## Phase 8: OpenClaw Interoperability

**Goal:** Implement import and export of skill definitions in OpenClaw-compatible JSON format.

### Tasks:

- [ ] **Task 8.1: Define OpenClaw skill format mapping**
  - Create `src/main/logseq_ai_hub/job_runner/openclaw.cljs` with namespace `logseq-ai-hub.job-runner.openclaw`
  - Define `openclaw->logseq-mapping` as a data map describing how OpenClaw JSON fields map to Logseq skill properties
  - Define `logseq->openclaw-mapping` for the reverse direction
  - Implement `(validate-openclaw-json json-map)` that checks required OpenClaw fields are present
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/openclaw_test.cljs` testing validation of valid and invalid OpenClaw JSON. Green: Implement. Refactor.
  - **Commit:** `feat(openclaw): define format mapping and validation`

- [ ] **Task 8.2: Implement OpenClaw skill import**
  - In `openclaw.cljs`, implement `(import-skill json-str)` that:
    1. Parses JSON string
    2. Validates against OpenClaw schema
    3. Maps fields to Logseq skill properties
    4. Stores unmapped fields in `openclaw-meta::` property
    5. Returns a skill definition map ready for graph writing
  - Implement `(import-skill-to-graph! json-str)` that creates the Skill page via graph writer
  - Handle name collision detection (check if page exists)
  - **TDD:** Red: Test import of valid OpenClaw JSON producing correct skill map. Test unmapped fields preserved. Test invalid JSON error. Test name collision detection. Green: Implement. Refactor.
  - **Commit:** `feat(openclaw): implement skill import from JSON`

- [ ] **Task 8.3: Implement OpenClaw skill export**
  - In `openclaw.cljs`, implement `(export-skill skill-def)` that:
    1. Maps Logseq skill properties to OpenClaw JSON fields
    2. Merges `openclaw-meta::` back into the export
    3. Returns a JSON string
  - Implement `(export-skill-from-graph! skill-page-name)` that reads the skill page and exports
  - **TDD:** Red: Test export produces valid OpenClaw JSON. Test roundtrip (import then export produces equivalent JSON). Test export of skill without openclaw-meta. Green: Implement. Refactor.
  - **Commit:** `feat(openclaw): implement skill export to JSON`

- [ ] **Verification 8.4:** Run `yarn test` -- all OpenClaw tests pass. Manual test: create a sample OpenClaw skill JSON, import it via the programmatic API, verify the created Skill page, export it back, verify JSON equivalence. [checkpoint marker]

---

## Phase 9: Integration, Settings, Slash Commands, and Finalization

**Goal:** Wire everything together: settings schema, slash commands, core.cljs initialization, and end-to-end integration verification.

### Tasks:

- [ ] **Task 9.1: Extend settings schema for job runner**
  - In `core.cljs`, add settings:
    - `jobRunnerEnabled` (boolean, default: false)
    - `jobRunnerMaxConcurrent` (number, default: 3)
    - `jobRunnerPollInterval` (number, default: 5000)
    - `jobRunnerDefaultTimeout` (number, default: 300000)
    - `jobPagePrefix` (string, default: "Jobs/")
    - `skillPagePrefix` (string, default: "Skills/")
    - `mcpServers` (string, default: "[]") -- JSON array of MCP server configs
  - **TDD:** N/A (declarative settings data, verified manually)
  - **Commit:** `feat(core): add job runner and MCP settings`

- [ ] **Task 9.2: Implement job runner initialization in core.cljs**
  - Create `src/main/logseq_ai_hub/job_runner/init.cljs` with namespace `logseq-ai-hub.job-runner.init`
  - Implement `(init! settings)` that:
    1. Reads settings (enabled, prefixes, MCP configs)
    2. If enabled: initializes MCP connections from settings, starts the runner and scheduler
    3. Registers all step executors
    4. Registers slash commands
  - Implement `(shutdown!)` for cleanup
  - Wire `init!` into `core.cljs` main function
  - **TDD:** Red: Test init with enabled=true starts runner. Test init with enabled=false does not start runner. Test MCP connections initialized from settings. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add initialization module and wire into core`

- [ ] **Task 9.3: Implement slash commands**
  - Create `src/main/logseq_ai_hub/job_runner/commands.cljs` with namespace `logseq-ai-hub.job-runner.commands`
  - Implement and register all slash commands:
    - `/job:run` -- reads block content for job name, enqueues the job
    - `/job:status` -- inserts a block with queued/running/completed/failed counts and names
    - `/job:cancel` -- reads block content for job name, cancels it
    - `/job:create` -- creates a new job page from template
    - `/job:import-skill` -- reads block content as JSON, imports via openclaw module
    - `/job:export-skill` -- reads current page name, exports if it is a skill page
    - `/job:mcp-servers` -- inserts a block listing MCP servers and their status
    - `/job:mcp-tools` -- reads block content for server name, lists tools
  - Each command shows success/error via `logseq.App.showMsg`
  - **TDD:** Red: Write `src/test/logseq_ai_hub/job_runner/commands_test.cljs` testing command handler functions (not the registration, which requires Logseq API) with mocked dependencies. Green: Implement. Refactor.
  - **Commit:** `feat(job-runner): add slash commands for job runner`

- [ ] **Task 9.4: End-to-end integration test -- manual job**
  - Write an integration test (can be a documented manual test procedure) that:
    1. Creates `Skills/echo` skill page with one `llm-call` step
    2. Creates `Jobs/test-manual` job page referencing the skill
    3. Sets `job-status:: queued`
    4. Starts the runner
    5. Verifies the job completes and results are written
  - Automate as much as possible with mocked Logseq APIs
  - **TDD:** Integration test with mocks
  - **Commit:** `test(job-runner): add end-to-end integration test for manual job`

- [ ] **Task 9.5: End-to-end integration test -- MCP tool job**
  - Write an integration test that:
    1. Sets up a mock MCP server (mocked transport)
    2. Creates a skill with an `mcp-tool` step
    3. Creates and runs a job using this skill
    4. Verifies the MCP tool is called and result flows through
  - **TDD:** Integration test with mocks
  - **Commit:** `test(job-runner): add end-to-end integration test for MCP tool job`

- [ ] **Task 9.6: Code style audit and documentation**
  - Review all new files for:
    - Comment banners for sections
    - Docstrings on all public functions
    - `defonce` for all state atoms
    - JS interop isolated at module boundaries (graph.cljs, transport.cljs)
    - Consistent error handling via `logseq-ai-hub.util.errors`
  - Fix any violations
  - **TDD:** N/A (style review)
  - **Commit:** `refactor(job-runner): code style audit and documentation`

- [ ] **Task 9.7: Full integration verification**
  - Run `yarn test` -- all tests pass (existing + all new job runner tests)
  - Run `yarn release` -- production build succeeds without warnings
  - Load production build in Logseq:
    - Plugin settings panel shows job runner and MCP settings
    - `/job:status` command works (shows "Runner not started" or counts)
    - Enable job runner in settings, reload plugin
    - Create a simple skill and job, verify execution
    - `/job:mcp-servers` shows empty list (no servers configured)
    - All existing functionality (`/LLM`, messaging, memory) unaffected
    - No console errors on plugin load
  - **TDD:** N/A (manual integration test)
  - [checkpoint marker]

---

## Summary

| Phase | Tasks | Focus | Estimated Hours |
|-------|-------|-------|-----------------|
| 1. Data Model and Pure Utilities | 8 | Schemas, parsers, interpolation, cron, queue | 6-8 |
| 2. Graph Integration Layer | 5 | Logseq API adapters, read/write, serialization | 3-4 |
| 3. Step Executors | 10 | All action type executors | 5-7 |
| 4. Skill Execution Engine | 6 | Sequential runner, context, retry, branching | 4-5 |
| 5. MCP Client | 9 | Protocol, transports, client, all operations | 6-8 |
| 6. Job Runner Core | 6 | Runner, lifecycle, polling, dependencies | 4-6 |
| 7. Job Scheduling | 4 | Cron scheduler, integration | 2-3 |
| 8. OpenClaw Interop | 4 | Import/export, format mapping | 2-3 |
| 9. Integration and Finalization | 7 | Settings, commands, wiring, verification | 3-5 |
| **Total** | **59 + 3 verifications = 62** | | **35-49** |

## Dependencies

### External Dependencies
- **core-arch_20260209:** FR-1 (namespaces), FR-9 (error utils), FR-10 (test infra) must be complete before Phase 2+
- Phase 1 can proceed independently (pure functions, no external dependencies)

### Internal Phase Dependencies
```
Phase 1 (Pure Utilities)
  |
  v
Phase 2 (Graph Integration) -------> Phase 3 (Step Executors)
  |                                       |
  |                                       v
  |                                  Phase 4 (Execution Engine)
  |                                       |
  +--------> Phase 5 (MCP Client) --------+
  |               |                       |
  |               v                       v
  |          Phase 5.8 (MCP Executors)    |
  |                                       |
  +---------------------------------------+
  |                                       |
  v                                       v
Phase 6 (Job Runner Core) <-------- Phase 4 + Phase 5
  |
  +---> Phase 7 (Scheduling)
  |
  +---> Phase 8 (OpenClaw Interop)
  |
  v
Phase 9 (Integration)  <-------- All previous phases
```

### Parallel Work Opportunities
- Phases 3 and 5 can proceed in parallel after Phase 2
- Phase 7 and Phase 8 can proceed in parallel after Phase 6
- Within Phase 3, individual step executors are independent of each other
- Within Phase 5, tool/resource/prompt operations are independent of each other

## New Files Created

```
src/main/logseq_ai_hub/job_runner/
  schemas.cljs        -- Property schemas and validation
  parser.cljs         -- Block property and page parsers
  interpolation.cljs  -- Template variable interpolation
  cron.cljs           -- Cron expression parsing
  queue.cljs          -- Priority queue data structure
  graph.cljs          -- Logseq API adapter (read/write)
  executor.cljs       -- Step executor registry and implementations
  engine.cljs         -- Skill execution engine
  runner.cljs         -- Job runner core (polling, lifecycle)
  scheduler.cljs      -- Cron-based job scheduling
  openclaw.cljs       -- OpenClaw import/export
  commands.cljs       -- Slash command handlers
  init.cljs           -- Module initialization

src/main/logseq_ai_hub/job_runner/mcp/
  protocol.cljs       -- JSON-RPC 2.0 message construction
  transport.cljs      -- HTTP and SSE transports
  client.cljs         -- MCP client connection and operations

src/test/logseq_ai_hub/job_runner/
  schemas_test.cljs
  parser_test.cljs
  interpolation_test.cljs
  cron_test.cljs
  queue_test.cljs
  graph_test.cljs
  executor_test.cljs
  engine_test.cljs
  runner_test.cljs
  scheduler_test.cljs
  openclaw_test.cljs
  commands_test.cljs

src/test/logseq_ai_hub/job_runner/mcp/
  protocol_test.cljs
  transport_test.cljs
  client_test.cljs
```
