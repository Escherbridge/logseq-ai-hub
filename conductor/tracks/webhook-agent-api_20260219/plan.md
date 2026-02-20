# Implementation Plan: Webhook Server Agent Interaction Layer

## Overview

This plan is structured in 7 phases, progressing from foundational infrastructure through to the conversational agent and plugin-side integration. Each phase builds on the previous, with verification checkpoints at the end.

**Total estimated effort:** 25-35 hours (48 tasks across 7 phases)

**Branch:** `track/webhook-agent-api_20260219`

**Dependencies:** `job-runner_20260219` (for plugin-side handlers in Phases 5-7), existing server from `core-arch_20260209`

**All work is in the TypeScript/Bun server (`server/`) unless explicitly noted as plugin-side (ClojureScript).**

---

## Phase 1: Server Infrastructure -- Config, Types, Auth Middleware, Router Refactor

**Goal:** Extend server configuration, add new TypeScript types, extract auth middleware, and refactor the router to support path parameters and additional HTTP methods.

**Estimated effort:** 3-4 hours (8 tasks)

### Tasks:

- [ ] **Task 1.1: Extend Config** (TDD)
  - Test: `server/tests/config.test.ts` -- Verify `loadConfig()` reads `OPENAI_API_KEY`, `OPENAI_ENDPOINT` (default `https://api.openai.com/v1`), `AGENT_MODEL` (default `gpt-4o-mini`), `AGENT_REQUEST_TIMEOUT` (default `30000`). Verify `validateConfig()` returns warnings (not errors) for missing agent env vars.
  - Implement: Update `server/src/config.ts` with new fields in `Config` interface and `loadConfig()`. Add `validateAgentConfig()` that returns warnings.
  - Refactor: Ensure existing validation still works, new agent fields are optional.

- [ ] **Task 1.2: Add Agent Types** (TDD)
  - Test: `server/tests/types.test.ts` -- Type-level tests (compile-time checks). Verify type exports are available and structurally correct.
  - Implement: Create `server/src/types/agent.ts` with: `AgentRequest`, `AgentCallback`, `JobCreateRequest`, `JobSummary`, `JobDetail`, `SkillSummary`, `SkillDetail`, `SkillCreateRequest`, `MCPServerSummary`, `MCPToolSummary`, `MCPResourceSummary`, `AgentChatRequest`, `AgentChatResponse`, `AgentAction`, `PendingRequest`.
  - Refactor: Keep existing `server/src/types.ts` and extend `SSEEvent.type` union with new event types: `agent_request`, `agent_callback`, `job_created`, `job_started`, `job_completed`, `job_failed`, `job_cancelled`, `skill_created`.

- [ ] **Task 1.3: Extract Auth Middleware** (TDD)
  - Test: `server/tests/middleware/auth.test.ts` -- Test `authenticate(req, config)` returns true/false for valid/invalid/missing Bearer tokens. Test `withAuth(handler, config)` wrapper returns 401 for unauthorized, delegates to handler for authorized.
  - Implement: Create `server/src/middleware/auth.ts` with `authenticate()` and `withAuth()` higher-order function.
  - Refactor: Update `server/src/routes/api/send.ts` and `server/src/routes/api/messages.ts` to remove inline auth checks and use the middleware.

- [ ] **Task 1.4: Path Parameter Extraction Utility** (TDD)
  - Test: `server/tests/router.test.ts` -- Test `matchRoute(pattern, pathname)` returns params for `/api/jobs/:id` matching `/api/jobs/my-job` => `{id: "my-job"}`. Test non-matching paths return null. Test multi-param paths like `/api/mcp/servers/:id/tools`.
  - Implement: Create `server/src/router/match.ts` with `matchRoute(pattern: string, pathname: string): Record<string, string> | null`.
  - Refactor: Ensure the matcher handles edge cases (trailing slashes, encoded characters).

- [ ] **Task 1.5: Router Refactor -- Route Table Pattern** (TDD)
  - Test: `server/tests/router.test.ts` -- Test that a route table with `{method, pattern, handler}` entries correctly dispatches requests to handlers. Test CORS preflight handling. Test 404 for unmatched routes.
  - Implement: Refactor `server/src/router.ts` to use a route table array with `matchRoute()` instead of the if/else chain. Preserve all existing routes.
  - Refactor: Verify all existing tests still pass with the refactored router.

- [ ] **Task 1.6: CORS Extension** (TDD)
  - Test: `server/tests/router.test.ts` -- Test that CORS headers include `PUT, DELETE` in `Access-Control-Allow-Methods`. Test OPTIONS preflight returns the extended methods.
  - Implement: Update `corsHeaders()` in the router to include PUT and DELETE methods.
  - Refactor: Minimal change, verify existing CORS tests pass.

- [ ] **Task 1.7: Route Context Extension** (TDD)
  - Test: Verify `RouteContext` now includes optional `agentBridge` field alongside existing `config` and `db`.
  - Implement: Update `RouteContext` interface to include `agentBridge?: AgentBridge` (interface defined but implementation deferred to Phase 2).
  - Refactor: Existing route handlers are unaffected by the optional field.

- [ ] **Task 1.8: Phase 1 Verification**
  - Run `cd server && bun test` -- all existing tests pass.
  - Run new tests -- config, auth middleware, router all pass.
  - Verify `bun run dev` starts the server without errors.
  - [checkpoint marker]

---

## Phase 2: SSE Request-Response Bridge

**Goal:** Implement the core mechanism for proxying requests from REST API to the plugin via SSE events and receiving responses via callbacks.

**Estimated effort:** 4-5 hours (7 tasks)

### Tasks:

- [ ] **Task 2.1: AgentBridge Class -- Core Structure** (TDD)
  - Test: `server/tests/services/agent-bridge.test.ts` -- Test `AgentBridge` constructor accepts timeout config. Test `hasPendingRequest(requestId)` returns false initially. Test `pendingCount` is 0 initially.
  - Implement: Create `server/src/services/agent-bridge.ts` with `AgentBridge` class skeleton: `private pendingRequests: Map<string, PendingRequest>`, constructor with timeout config.
  - Refactor: Keep the class focused; SSE integration comes next.

- [ ] **Task 2.2: AgentBridge -- Send Request** (TDD)
  - Test: Test `sendRequest(operation, params)` returns a Promise. Test it stores a pending request with a generated UUID. Test `hasPendingRequest()` returns true after sending. Test the returned Promise does not resolve immediately.
  - Implement: `sendRequest(operation: string, params: Record<string, unknown>): Promise<unknown>` -- generates UUID, stores pending request with resolve/reject callbacks, broadcasts SSE event via `sseManager`.
  - Refactor: Ensure the Promise is properly constructed with external resolve/reject capture.

- [ ] **Task 2.3: AgentBridge -- Resolve Request** (TDD)
  - Test: Test `resolveRequest(requestId, {success: true, data: {...}})` resolves the pending Promise with the data. Test `resolveRequest` for a non-existent requestId is a no-op (returns false). Test that after resolution, `hasPendingRequest()` returns false. Test error resolution: `resolveRequest(requestId, {success: false, error: "msg"})` rejects the Promise.
  - Implement: `resolveRequest(requestId: string, result: AgentCallback): boolean`.
  - Refactor: Ensure cleanup of the pending request entry.

- [ ] **Task 2.4: AgentBridge -- Timeout Handling** (TDD)
  - Test: Test that a pending request is automatically rejected after the configured timeout. Use a short timeout (100ms) for testing. Test that the timeout error message is descriptive. Test that cleanup occurs on timeout.
  - Implement: Add `setTimeout` in `sendRequest` that rejects the pending Promise on expiry and cleans up the entry.
  - Refactor: Ensure `clearTimeout` is called on normal resolution to prevent leaks.

- [ ] **Task 2.5: AgentBridge -- Plugin Connection Check** (TDD)
  - Test: Test `isPluginConnected()` returns false when no SSE clients are connected. Test it returns true when at least one SSE client exists. Test `sendRequest` throws/rejects immediately if no plugin is connected.
  - Implement: `isPluginConnected(): boolean` checks `sseManager.clientCount > 0`. Update `sendRequest` to check connection before creating pending request.
  - Refactor: Consider extracting the SSE manager dependency for testability.

- [ ] **Task 2.6: Callback Route Handler** (TDD)
  - Test: `server/tests/routes/api/agent-callback.test.ts` -- Test POST `/api/agent/callback` with valid `{requestId, success, data}` returns 200. Test with invalid/missing requestId returns 404. Test unauthorized request returns 401. Test missing fields returns 400.
  - Implement: Create `server/src/routes/api/agent-callback.ts` with `handleAgentCallback(req, config, bridge)`. Register route in router.
  - Refactor: Use auth middleware from Phase 1.

- [ ] **Task 2.7: Phase 2 Verification**
  - Run all tests -- bridge unit tests and callback route tests pass.
  - Integration test: Create a mock scenario where `sendRequest` is called, then `resolveRequest` is called, verifying the Promise resolves.
  - Test timeout scenario manually.
  - [checkpoint marker]

---

## Phase 3: Job Management API

**Goal:** Implement all job CRUD and control endpoints that proxy through the SSE bridge.

**Estimated effort:** 4-5 hours (8 tasks)

### Tasks:

- [ ] **Task 3.1: Job Validation Utility** (TDD)
  - Test: `server/tests/validation/jobs.test.ts` -- Test `validateJobCreate({name, type})` passes. Test missing `name` returns error. Test missing `type` returns error. Test invalid `type` returns error. Test `priority` outside 1-5 returns error. Test `type: "scheduled"` without `schedule` returns error. Test valid full payload passes.
  - Implement: Create `server/src/validation/jobs.ts` with `validateJobCreate(body: unknown): {valid: true, data: JobCreateRequest} | {valid: false, errors: string[]}`.
  - Refactor: Keep validation pure and reusable.

- [ ] **Task 3.2: POST /api/jobs -- Create Job** (TDD)
  - Test: `server/tests/routes/api/jobs.test.ts` -- Test valid create returns 201 with job data (mock bridge). Test invalid body returns 400. Test unauthorized returns 401. Test plugin not connected returns 503. Test bridge timeout returns 504.
  - Implement: Create `server/src/routes/api/jobs.ts` with `handleCreateJob(req, config, bridge)`. Validates input, calls `bridge.sendRequest("create_job", ...)`, returns result.
  - Refactor: Consistent error response format.

- [ ] **Task 3.3: GET /api/jobs -- List Jobs** (TDD)
  - Test: Test list returns 200 with jobs array (mock bridge). Test optional `status` query param is forwarded to plugin. Test `limit` and `offset` defaults. Test unauthorized returns 401.
  - Implement: `handleListJobs(req, config, bridge)` in same file. Extracts query params, calls `bridge.sendRequest("list_jobs", ...)`.
  - Refactor: Share query param extraction logic.

- [ ] **Task 3.4: GET /api/jobs/:id -- Get Job** (TDD)
  - Test: Test valid job ID returns 200 with job details (mock bridge). Test non-existent job returns 404 (bridge returns not-found error). Test unauthorized returns 401.
  - Implement: `handleGetJob(req, config, bridge, params)` where `params.id` is the job identifier.
  - Refactor: Standardize the params passing pattern for route handlers.

- [ ] **Task 3.5: PUT /api/jobs/:id/start** (TDD)
  - Test: Test valid start returns 200. Test non-existent job returns 404. Test already running job returns 409. Test unauthorized returns 401.
  - Implement: `handleStartJob(req, config, bridge, params)`.
  - Refactor: Consider a shared job action handler pattern for start/cancel/pause/resume.

- [ ] **Task 3.6: PUT /api/jobs/:id/cancel, pause, resume** (TDD)
  - Test: Test cancel returns 200 for valid job. Test pause returns 409 for non-running job. Test resume returns 409 for non-paused job. Test each returns 404 for missing job.
  - Implement: `handleCancelJob`, `handlePauseJob`, `handleResumeJob` in the same file.
  - Refactor: Extract shared pattern for state-transition endpoints.

- [ ] **Task 3.7: Register Job Routes in Router** (TDD)
  - Test: Test that all job routes are reachable via the router (method + path matching). Test path parameter extraction for `:id` routes.
  - Implement: Add all job routes to the route table in `router.ts`.
  - Refactor: Verify no conflicts with existing routes.

- [ ] **Task 3.8: Phase 3 Verification**
  - Run all tests -- all job route tests pass with mocked bridge.
  - Manual test: Start server, send curl requests to each endpoint, verify proper error responses (503 because no plugin connected).
  - [checkpoint marker]

---

## Phase 4: Skills API and MCP Connections API

**Goal:** Implement skills and MCP endpoints following the same bridge pattern established in Phase 3.

**Estimated effort:** 3-4 hours (7 tasks)

### Tasks:

- [ ] **Task 4.1: Skill Validation Utility** (TDD)
  - Test: `server/tests/validation/skills.test.ts` -- Test `validateSkillCreate()` with valid payload passes. Test missing `name`, `type`, `description`, `inputs`, `outputs` returns errors. Test invalid `type` returns error. Test `steps` validation: each step needs `order` and `action`, `action` must be a known type.
  - Implement: Create `server/src/validation/skills.ts` with `validateSkillCreate(body: unknown)`.
  - Refactor: Keep consistent with job validation pattern.

- [ ] **Task 4.2: Skills API Routes** (TDD)
  - Test: `server/tests/routes/api/skills.test.ts` -- Test GET `/api/skills` returns 200 with skills (mock bridge). Test GET `/api/skills/:id` returns 200 or 404. Test POST `/api/skills` with valid body returns 201 (mock bridge). Test POST with invalid body returns 400. Test auth on all endpoints.
  - Implement: Create `server/src/routes/api/skills.ts` with `handleListSkills`, `handleGetSkill`, `handleCreateSkill`.
  - Refactor: Follow job route handler pattern exactly.

- [ ] **Task 4.3: MCP Connections API Routes** (TDD)
  - Test: `server/tests/routes/api/mcp.test.ts` -- Test GET `/api/mcp/servers` returns 200 (mock bridge). Test GET `/api/mcp/servers/:id/tools` returns 200 or 404. Test GET `/api/mcp/servers/:id/resources` returns 200 or 404. Test auth on all endpoints.
  - Implement: Create `server/src/routes/api/mcp.ts` with `handleListMCPServers`, `handleListMCPTools`, `handleListMCPResources`.
  - Refactor: Consistent bridge call pattern.

- [ ] **Task 4.4: Register Skills and MCP Routes** (TDD)
  - Test: Test all new routes are reachable via the router. Test path param extraction for skills and MCP routes.
  - Implement: Add routes to the route table in `router.ts`.
  - Refactor: Verify route table is well-organized (group by API area).

- [ ] **Task 4.5: Route Handler Index** (TDD)
  - Test: Verify all route handler modules export consistently.
  - Implement: Create `server/src/routes/api/index.ts` as a barrel export for all API route handlers.
  - Refactor: Update router imports to use the barrel export.

- [ ] **Task 4.6: Error Response Standardization** (TDD)
  - Test: `server/tests/helpers/responses.test.ts` -- Test `errorResponse(status, message)` returns correct JSON. Test `successResponse(data, status?)` returns correct JSON with default 200.
  - Implement: Create `server/src/helpers/responses.ts` with standardized response builders.
  - Refactor: Update all route handlers to use the standardized response builders.

- [ ] **Task 4.7: Phase 4 Verification**
  - Run all tests -- skills and MCP route tests pass.
  - Run full test suite -- no regressions.
  - Manual test: curl all new endpoints, verify 503 when plugin not connected.
  - [checkpoint marker]

---

## Phase 5: Agent Chat API

**Goal:** Implement the conversational agent that uses an LLM to interpret natural language and execute job runner operations.

**Estimated effort:** 5-6 hours (8 tasks)

### Tasks:

- [ ] **Task 5.1: Conversation Store** (TDD)
  - Test: `server/tests/services/conversations.test.ts` -- Test `createConversation()` returns a new conversation with UUID. Test `addMessage(conversationId, role, content)` appends to history. Test history is capped at 20 messages (oldest dropped). Test `getConversation(id)` returns null for non-existent. Test `deleteConversation(id)`.
  - Implement: Create `server/src/services/conversations.ts` with in-memory `ConversationStore` class.
  - Refactor: Keep simple Map-based storage.

- [ ] **Task 5.2: Agent System Prompt** (TDD)
  - Test: `server/tests/services/agent.test.ts` -- Test `buildSystemPrompt()` returns a string containing descriptions of all available operations. Test it includes job, skill, and MCP operation descriptions. Test it includes parameter schemas for each operation.
  - Implement: Create `server/src/services/agent.ts` with `buildSystemPrompt()` that describes the available operations as tools/functions the agent can call.
  - Refactor: Keep the prompt modular so new operations can be added easily.

- [ ] **Task 5.3: LLM Client Service** (TDD)
  - Test: `server/tests/services/llm.test.ts` -- Test `chatCompletion(messages, tools?, config)` calls the OpenAI-compatible endpoint with correct headers and body. Test it handles non-200 responses with descriptive errors. Test it returns parsed response content. Test timeout handling. Mock `fetch` for all tests.
  - Implement: Create `server/src/services/llm.ts` with `chatCompletion()` that calls the configured OpenAI-compatible endpoint. Support function/tool calling format in the request.
  - Refactor: Make the service configurable via Config (endpoint, model, API key).

- [ ] **Task 5.4: Agent Operation Dispatcher** (TDD)
  - Test: Test `executeOperation(operation, params, bridge)` dispatches to the correct bridge operation. Test it handles bridge errors gracefully. Test it formats results into human-readable summaries.
  - Implement: Add `executeOperation()` to `server/src/services/agent.ts`. Maps operation names to bridge calls and formats results.
  - Refactor: Use a dispatch map pattern for extensibility.

- [ ] **Task 5.5: Agent Chat Handler -- Basic Flow** (TDD)
  - Test: `server/tests/routes/api/agent-chat.test.ts` -- Test POST `/api/agent/chat` with `{message: "hello"}` returns 200 with a text response (mock LLM). Test empty message returns 400. Test auth required. Test new conversationId is generated when not provided. Test existing conversationId is reused.
  - Implement: Create `server/src/routes/api/agent-chat.ts` with `handleAgentChat(req, config, bridge, conversations, llm)`. Builds conversation history, calls LLM, returns response.
  - Refactor: Ensure clean separation between LLM call and operation execution.

- [ ] **Task 5.6: Agent Chat Handler -- Tool/Function Calling** (TDD)
  - Test: Test that when LLM returns a tool call (e.g., `create_job`), the handler executes the operation via the bridge and includes the result in the response. Test multiple tool calls in one response. Test tool call failure is handled gracefully. Mock both LLM and bridge.
  - Implement: Extend `handleAgentChat` to parse tool calls from LLM response, execute them via `executeOperation`, feed results back to LLM for final response, and include actions in the API response.
  - Refactor: Handle the multi-turn LLM conversation (user message -> tool call -> tool result -> final response).

- [ ] **Task 5.7: Register Agent Routes** (TDD)
  - Test: Test POST `/api/agent/chat` is reachable via router. Test it receives the correct dependencies (bridge, conversations, llm).
  - Implement: Add agent chat route to route table. Wire up dependencies in `index.ts`.
  - Refactor: Ensure conversation store and LLM service are initialized at server startup.

- [ ] **Task 5.8: Phase 5 Verification**
  - Run all tests -- agent chat tests pass with mocked LLM.
  - Manual test: Set OPENAI_API_KEY, start server, curl agent chat endpoint with a test message.
  - Verify conversation continuity across multiple messages.
  - [checkpoint marker]

---

## Phase 6: Plugin-Side Agent Request Handlers (ClojureScript)

**Goal:** Implement the plugin-side event handlers that receive agent requests via SSE, perform operations on the Logseq graph, and send results back to the server.

**Estimated effort:** 4-5 hours (8 tasks)

**Note:** This phase works in the ClojureScript plugin code, not the TypeScript server. It depends on `job-runner_20260219` modules being available.

### Tasks:

- [ ] **Task 6.1: Agent Request Dispatcher** (TDD)
  - Test: `src/test/logseq_ai_hub/agent_bridge_test.cljs` -- Test `dispatch-agent-request` dispatches to the correct handler based on `operation` field. Test unknown operations return an error result. Test the dispatcher calls the callback URL with the result.
  - Implement: Create `src/main/logseq_ai_hub/agent_bridge.cljs` with `dispatch-agent-request` that uses a dispatch map of operation -> handler-fn.
  - Refactor: Keep the dispatcher generic; handlers are registered separately.

- [ ] **Task 6.2: Job Operation Handlers** (TDD)
  - Test: Test `handle-create-job` creates a Logseq page with correct properties (mock `logseq.Editor`). Test `handle-list-jobs` queries `Jobs/*` pages (mock `logseq.DB`). Test `handle-get-job` returns job details. Test `handle-start-job`, `handle-cancel-job`, `handle-pause-job`, `handle-resume-job` update job status.
  - Implement: Add job operation handlers to `agent_bridge.cljs`. Each handler calls the job-runner parser/queue modules and Logseq API.
  - Refactor: Reuse job-runner's `parser/parse-job-page` and `queue/enqueue-job!` where available.

- [ ] **Task 6.3: Skill Operation Handlers** (TDD)
  - Test: Test `handle-list-skills` queries `Skills/*` pages (mock `logseq.DB`). Test `handle-get-skill` returns skill details with steps. Test `handle-create-skill` creates a Logseq page with properties and step child blocks.
  - Implement: Add skill operation handlers. Use the skill parser from job-runner modules.
  - Refactor: Share parsing logic with job-runner's `parser/parse-skill-page`.

- [ ] **Task 6.4: MCP Operation Handlers** (TDD)
  - Test: Test `handle-list-mcp-servers` reads from the MCP client registry atom. Test `handle-list-mcp-tools` returns cached tools for a server. Test `handle-list-mcp-resources` returns cached resources.
  - Implement: Add MCP operation handlers that read from the MCP client's state atom.
  - Refactor: These are read-only handlers; no graph mutations needed.

- [ ] **Task 6.5: Callback HTTP Client** (TDD)
  - Test: Test `send-callback!` sends a POST to the server's callback URL with the correct payload. Test it handles network errors gracefully. Test it includes the auth token.
  - Implement: Add `send-callback!` function that uses `js/fetch` to call `POST /api/agent/callback` on the configured webhook server URL.
  - Refactor: Read `webhookServerUrl` and `pluginApiToken` from Logseq settings.

- [ ] **Task 6.6: SSE Event Registration** (TDD)
  - Test: Test that the `agent_request` event type is handled by the dispatcher. Test that the event payload is correctly parsed and forwarded to `dispatch-agent-request`.
  - Implement: Register the `agent_request` SSE event handler in the plugin's SSE connection setup (likely in `messaging.cljs` or a new `agent_bridge.cljs` init function).
  - Refactor: Integrate with the existing `messaging/init!` SSE setup or create a separate `agent-bridge/init!` called from `core.cljs`.

- [ ] **Task 6.7: Plugin Initialization** (TDD)
  - Test: Test that `agent-bridge/init!` registers all operation handlers and the SSE event listener.
  - Implement: Add `init!` function to `agent_bridge.cljs`. Call it from `core.cljs` `main` function.
  - Refactor: Ensure initialization order is correct (agent bridge initializes after messaging/SSE is connected).

- [ ] **Task 6.8: Phase 6 Verification**
  - Run plugin tests.
  - Manual test: Start server, start Logseq with plugin, verify SSE connection.
  - Send a curl request to `POST /api/jobs`, verify the plugin receives the SSE event, performs the operation, and the server responds.
  - [checkpoint marker]

---

## Phase 7: Integration Testing and Job Lifecycle Events

**Goal:** End-to-end integration tests, job lifecycle SSE events, and final polish.

**Estimated effort:** 3-4 hours (6 tasks)

### Tasks:

- [ ] **Task 7.1: Job Lifecycle Event Broadcasting** (TDD)
  - Test: `server/tests/services/job-events.test.ts` -- Test `broadcastJobEvent(type, data)` calls `sseManager.broadcast` with the correct event structure. Test all event types: `job_created`, `job_started`, `job_completed`, `job_failed`, `job_cancelled`, `skill_created`.
  - Implement: Create `server/src/services/job-events.ts` with `broadcastJobEvent()`. Update job route handlers to broadcast events after successful operations.
  - Refactor: Ensure events are broadcast after the bridge callback resolves (not before).

- [ ] **Task 7.2: Plugin-Side Event Emission** (TDD)
  - Test: Test that when the plugin completes a job operation, it notifies the server of the state change via a POST to a server event ingestion endpoint (or the callback data includes the event type for the server to broadcast).
  - Implement: Update plugin callback responses to include an `event` field that the server uses to broadcast lifecycle events.
  - Refactor: Keep the event emission non-blocking.

- [ ] **Task 7.3: Server Integration Test -- Full Bridge Flow** (TDD)
  - Test: `server/tests/integration-agent.test.ts` -- Test the complete flow: create a mock SSE client, send a job create request, simulate the plugin callback, verify the response. Test timeout flow. Test plugin-not-connected flow.
  - Implement: Write integration tests using the test helpers to simulate the full bridge round-trip.
  - Refactor: Use test helpers to create mock bridge instances.

- [ ] **Task 7.4: Server Integration Test -- Agent Chat Flow** (TDD)
  - Test: Test agent chat with mocked LLM that returns a tool call. Verify the tool call is executed via the bridge. Verify the final response includes the action summary.
  - Implement: Integration test that exercises the full chat -> LLM -> tool call -> bridge -> callback -> response pipeline.
  - Refactor: Mock LLM responses to be deterministic.

- [ ] **Task 7.5: Server Index Update and Startup Wiring** (TDD)
  - Test: Verify server starts with all new services initialized. Verify health endpoint reports agent capabilities.
  - Implement: Update `server/src/index.ts` to initialize `AgentBridge`, `ConversationStore`, and `LLMService`. Pass them through `RouteContext` to the router. Update health endpoint to report agent API status.
  - Refactor: Clean up initialization order and error handling.

- [ ] **Task 7.6: Phase 7 Verification -- Full System Test**
  - Run complete server test suite: `cd server && bun test`.
  - Run plugin tests.
  - Manual end-to-end test:
    1. Start server with all env vars configured.
    2. Start Logseq with plugin connected via SSE.
    3. Curl `POST /api/jobs` to create a job -- verify it appears in Logseq.
    4. Curl `GET /api/jobs` -- verify the job is listed.
    5. Curl `GET /api/skills` -- verify skills are returned.
    6. Curl `GET /api/mcp/servers` -- verify MCP servers are returned.
    7. Curl `POST /api/agent/chat` with "What jobs are running?" -- verify natural language response.
    8. Curl `POST /api/agent/chat` with "Create a daily summary job at 9am" -- verify job creation.
  - Verify no regressions in existing messaging/webhook tests.
  - [checkpoint marker]

---

## Summary

| Phase | Focus | Tasks | Est. Hours |
|-------|-------|-------|------------|
| 1 | Server Infrastructure | 8 | 3-4 |
| 2 | SSE Request-Response Bridge | 7 | 4-5 |
| 3 | Job Management API | 8 | 4-5 |
| 4 | Skills + MCP API | 7 | 3-4 |
| 5 | Agent Chat API | 8 | 5-6 |
| 6 | Plugin-Side Handlers | 8 | 4-5 |
| 7 | Integration + Events | 6 | 3-4 |
| **Total** | | **52** | **26-33** |

### Key Risk Mitigations

1. **Dependency on job-runner track:** Phases 1-5 (server-side) can proceed in parallel with the job runner implementation. Only Phase 6 (plugin-side handlers) requires job runner modules to exist. Mock the plugin responses during server testing.

2. **SSE bridge reliability:** The timeout mechanism prevents stuck requests. The plugin connection check prevents requests when no plugin is available. Integration tests in Phase 7 verify the full round-trip.

3. **LLM function calling compatibility:** The agent chat implementation uses standard OpenAI function calling format. If the configured endpoint does not support function calling, the agent falls back to structured text parsing. This is tested in Phase 5.

4. **Router complexity:** The route table refactor in Phase 1 keeps the router maintainable as routes grow from 7 to 22+. The pattern-based matching supports path parameters without regex complexity.
