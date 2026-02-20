# Specification: Webhook Server Agent Interaction Layer

## Overview

Add an agent interaction layer to the existing TypeScript/Bun webhook server that exposes REST API endpoints for remote management of the job runner system. External clients (mobile apps, CLI tools, other services, chat interfaces) can create and manage jobs, query available skills, list MCP server connectors, and interact through a conversational agent that translates natural language into job runner operations. The server acts as a proxy, relaying commands to the Logseq plugin via SSE events and receiving results back via REST callbacks.

## Background

The Logseq AI Hub currently has a webhook server (TypeScript/Bun, deployed on Railway) that handles WhatsApp/Telegram webhooks, SSE event broadcasting, and basic messaging APIs. The Logseq plugin connects to this server via SSE for real-time events and REST for outbound actions.

A separate track (`job-runner_20260219`) is implementing the job runner system within the plugin. The job runner manages jobs and skills as Logseq pages, executes skills with step-based logic, connects to MCP servers, and runs a priority queue with scheduling. All job runner state lives in the Logseq graph and is managed by the plugin.

This track bridges the gap between external clients and the plugin-resident job runner by adding a server-side API layer. The server does not execute jobs itself -- it acts as a relay/proxy. For operations that require graph access (listing jobs, creating skills, executing graph queries), the server sends an SSE event to the connected plugin, which performs the operation and reports the result back to the server via a callback endpoint. For the conversational agent, the server hosts an LLM-powered interpreter that translates natural language into structured API calls.

### Architecture: Request-Response via SSE Bridge

The core architectural challenge is that the job runner state lives in the Logseq plugin (browser context), not in the server. The solution is a request-response bridge:

1. External client sends REST request to the server (e.g., `POST /api/jobs`).
2. Server generates a unique request ID and broadcasts an SSE event to the plugin (e.g., `agent_request` with operation details).
3. Plugin receives the SSE event, performs the operation on the graph, and calls `POST /api/agent/callback` on the server with the request ID and result.
4. Server resolves the pending request and returns the response to the external client.

For operations that can be fulfilled server-side (health, listing cached state), the server responds directly without the SSE bridge.

### Dependencies

- **job-runner_20260219**: The job runner must be operational for job/skill management to function. The plugin-side SSE event handlers for agent requests depend on the job runner's queue, parser, and executor modules.
- **core-arch_20260209**: Existing server infrastructure (router, SSE, auth, database).

## Functional Requirements

### FR-1: Authentication Middleware

**Description:** Extract the authentication logic from individual route handlers into reusable middleware. All new API endpoints require Bearer token authentication using the existing `pluginApiToken`.

**Acceptance Criteria:**
- Given a request with a valid `Authorization: Bearer <token>` header, the request is authenticated and proceeds to the handler.
- Given a request with a missing or invalid token, a 401 response is returned with `{"success": false, "error": "Unauthorized"}`.
- Given a request to a public endpoint (GET /health), no authentication is required.
- The middleware is a reusable function that can wrap any route handler.
- Existing route handlers (send, messages) are refactored to use the shared middleware.

**Priority:** P0

### FR-2: Request-Response Bridge (SSE Relay)

**Description:** Implement a server-side mechanism for sending operation requests to the plugin via SSE and awaiting responses via a callback endpoint. This is the foundational pattern for all proxy operations.

**Acceptance Criteria:**
- The bridge generates a unique request ID (UUID) for each outgoing operation.
- The bridge broadcasts an SSE event of type `agent_request` containing `{requestId, operation, params}`.
- The bridge stores pending requests in-memory with a configurable timeout (default: 30 seconds).
- `POST /api/agent/callback` accepts `{requestId, success, data, error}` from the plugin and resolves the corresponding pending request.
- If the plugin does not respond within the timeout, the pending request is rejected with a timeout error.
- If no plugin client is connected via SSE when a request is made, the server returns a 503 "Plugin not connected" error immediately.
- Pending requests are cleaned up on timeout (no memory leaks).
- The callback endpoint is authenticated with the same Bearer token.

**Priority:** P0

### FR-3: Job Management API

**Description:** REST endpoints for creating, listing, and controlling jobs. All operations that touch the Logseq graph are proxied through the SSE bridge.

**Acceptance Criteria:**

**POST /api/jobs** (Create Job):
- Accepts a JSON body with `{name, type, priority?, schedule?, skill?, input?, dependsOn?}`.
- Validates required fields (`name`, `type`).
- Validates `type` is one of: `autonomous`, `manual`, `scheduled`, `event-driven`.
- Validates `priority` is 1-5 if provided (defaults to 3).
- Validates `schedule` is present when type is `scheduled`.
- Sends `agent_request` with operation `create_job` to the plugin.
- Plugin creates the job page in the graph and reports back.
- Returns 201 with `{success: true, data: {jobId, name, status: "queued"}}`.
- Returns 400 for validation errors.
- Returns 504 if plugin does not respond within timeout.

**GET /api/jobs** (List Jobs):
- Accepts optional query params: `status` (filter), `limit` (default 50), `offset` (default 0).
- Sends `agent_request` with operation `list_jobs` to the plugin.
- Plugin queries `Jobs/*` pages from the graph and reports back.
- Returns 200 with `{success: true, data: {jobs: [...], total: N}}`.

**GET /api/jobs/:id** (Get Job Details):
- Sends `agent_request` with operation `get_job` and `{jobId}`.
- Plugin reads the specific job page and reports back.
- Returns 200 with full job details including step results.
- Returns 404 if job not found.

**PUT /api/jobs/:id/start** (Start Job):
- Sends `agent_request` with operation `start_job` and `{jobId}`.
- Plugin sets `job-status:: queued` (enqueues the job).
- Returns 200 with updated job status.
- Returns 404 if job not found.
- Returns 409 if job is already running or completed.

**PUT /api/jobs/:id/cancel** (Cancel Job):
- Sends `agent_request` with operation `cancel_job` and `{jobId}`.
- Plugin sets `job-status:: cancelled`.
- Returns 200 with updated job status.
- Returns 404 if job not found.

**PUT /api/jobs/:id/pause** (Pause Job):
- Sends `agent_request` with operation `pause_job` and `{jobId}`.
- Plugin sets `job-status:: paused`.
- Returns 200 with updated job status.
- Returns 404 if job not found.
- Returns 409 if job is not running.

**PUT /api/jobs/:id/resume** (Resume Job):
- Sends `agent_request` with operation `resume_job` and `{jobId}`.
- Plugin sets `job-status:: queued`.
- Returns 200 with updated job status.
- Returns 404 if job not found.
- Returns 409 if job is not paused.

**Priority:** P0

### FR-4: Skills API

**Description:** REST endpoints for listing and managing skill definitions. Skills are read from and written to the Logseq graph via the SSE bridge.

**Acceptance Criteria:**

**GET /api/skills** (List Skills):
- Sends `agent_request` with operation `list_skills` to the plugin.
- Plugin queries `Skills/*` pages and reports back with name, description, type, inputs, outputs, and tags.
- Returns 200 with `{success: true, data: {skills: [...]}}`.

**GET /api/skills/:id** (Get Skill Details):
- Sends `agent_request` with operation `get_skill` and `{skillId}`.
- Plugin reads the specific skill page including all step definitions.
- Returns 200 with full skill details.
- Returns 404 if skill not found.

**POST /api/skills** (Create Skill):
- Accepts a JSON body with `{name, type, description, inputs, outputs, tags?, steps}`.
- Validates required fields.
- Validates `type` is one of: `llm-chain`, `tool-chain`, `composite`, `mcp-tool`.
- Validates each step has `order` and `action` with valid action type.
- Sends `agent_request` with operation `create_skill` to the plugin.
- Plugin creates the skill page with properties and step blocks.
- Returns 201 with `{success: true, data: {skillId, name}}`.

**Priority:** P1

### FR-5: MCP Connections API

**Description:** REST endpoints for querying MCP server connections and their capabilities. These read from the plugin's in-memory MCP client state.

**Acceptance Criteria:**

**GET /api/mcp/servers** (List MCP Servers):
- Sends `agent_request` with operation `list_mcp_servers` to the plugin.
- Plugin reads its MCP connection registry and reports server list with status.
- Returns 200 with `{success: true, data: {servers: [{id, name, url, status, capabilities}]}}`.

**GET /api/mcp/servers/:id/tools** (List Server Tools):
- Sends `agent_request` with operation `list_mcp_tools` and `{serverId}`.
- Plugin reads cached tools for the specified MCP server.
- Returns 200 with `{success: true, data: {tools: [{name, description, inputSchema}]}}`.
- Returns 404 if server not found.

**GET /api/mcp/servers/:id/resources** (List Server Resources):
- Sends `agent_request` with operation `list_mcp_resources` and `{serverId}`.
- Plugin reads cached resources for the specified MCP server.
- Returns 200 with `{success: true, data: {resources: [{uri, name, description, mimeType}]}}`.
- Returns 404 if server not found.

**Priority:** P1

### FR-6: Agent Chat API

**Description:** A conversational agent interface that accepts natural language messages and translates them into job runner operations. The agent uses an LLM to interpret user intent and executes the corresponding API operations.

**Acceptance Criteria:**

**POST /api/agent/chat** (Chat with Agent):
- Accepts `{message: string, conversationId?: string}`.
- The server maintains conversation history per `conversationId` (in-memory, limited to last 20 messages).
- If no `conversationId` is provided, a new one is generated and returned.
- The agent sends the message to an LLM with a system prompt describing available operations.
- The LLM responds with either:
  - A direct text response (informational).
  - A structured action request (create job, list skills, etc.) which the agent executes via the internal API functions.
- After executing actions, the agent composes a natural language summary of what it did.
- Returns 200 with `{success: true, data: {conversationId, response: string, actions?: [{operation, result}]}}`.
- Returns 400 if message is empty.
- Returns 503 if LLM service is unavailable.

**System prompt capabilities:**
- The agent knows about jobs, skills, MCP servers, and their schemas.
- The agent can create jobs, list jobs, check status, start/cancel/pause/resume jobs.
- The agent can list skills, describe skills, create new skills.
- The agent can list MCP servers and their tools/resources.
- The agent asks clarifying questions when the user's intent is ambiguous.

**Priority:** P1

### FR-7: Server-Side Configuration Extension

**Description:** Extend the server configuration to support the new agent API features.

**Acceptance Criteria:**
- `OPENAI_API_KEY` environment variable for the agent chat LLM.
- `OPENAI_ENDPOINT` environment variable (default: `https://api.openai.com/v1`).
- `AGENT_MODEL` environment variable (default: `gpt-4o-mini`).
- `AGENT_REQUEST_TIMEOUT` environment variable (default: `30000` ms).
- Configuration validation warns if agent-related env vars are missing but does not fail startup (agent endpoints return 503 instead).
- Existing configuration and validation are preserved.

**Priority:** P0

### FR-8: Plugin-Side SSE Event Handlers

**Description:** The Logseq plugin must handle new SSE event types from the server and execute the requested operations on the graph.

**Acceptance Criteria:**
- Plugin registers handlers for `agent_request` SSE events.
- On receiving an `agent_request` event, the plugin dispatches on the `operation` field to the appropriate handler.
- Supported operations: `create_job`, `list_jobs`, `get_job`, `start_job`, `cancel_job`, `pause_job`, `resume_job`, `list_skills`, `get_skill`, `create_skill`, `list_mcp_servers`, `list_mcp_tools`, `list_mcp_resources`.
- Each handler performs the graph operation using the job runner modules and Logseq API.
- On completion, the handler sends the result to `POST /api/agent/callback` on the server with the `requestId` and result.
- On error, the handler sends an error response to the callback endpoint.
- All handlers are non-blocking and do not freeze the Logseq UI.

**Priority:** P0

### FR-9: Job Lifecycle SSE Events

**Description:** The plugin broadcasts SSE events to the server when job lifecycle state changes occur, enabling real-time monitoring by external clients.

**Acceptance Criteria:**
- When a job transitions to `running`, the plugin sends an SSE-compatible POST to the server's event ingestion (or the server detects via callback).
- Event types: `job_created`, `job_started`, `job_completed`, `job_failed`, `job_cancelled`, `skill_created`.
- Events include: `{jobId, name, status, timestamp, error?}`.
- External SSE clients connected to `GET /events` receive these events in real time.
- The SSEEvent type union is extended to include the new event types.

**Priority:** P2

### FR-10: Router Extension

**Description:** Extend the server router to handle all new endpoints with proper HTTP method matching, path parameter extraction, and CORS support.

**Acceptance Criteria:**
- All new routes are registered in the router.
- Path parameters (`:id`) are extracted correctly for `/api/jobs/:id/*` and `/api/skills/:id` and `/api/mcp/servers/:id/*` routes.
- CORS headers include `PUT` in allowed methods (currently only GET, POST, OPTIONS).
- The router supports the new PUT method for job control endpoints.
- Route matching is clean and maintainable (consider a pattern-based approach if the if/else chain becomes unwieldy).
- 404 responses include the attempted path for debugging.

**Priority:** P0

## Non-Functional Requirements

### NFR-1: Performance

- SSE bridge request-response round trips should complete within 5 seconds for simple operations (list, get).
- The server should handle up to 10 concurrent pending bridge requests without degradation.
- Agent chat LLM calls should have a 30-second timeout.
- Conversation history is capped at 20 messages per conversation to limit memory usage.
- In-memory pending request map is cleaned up on timeout to prevent memory leaks.

### NFR-2: Security

- All API endpoints (except GET /health) require Bearer token authentication.
- The agent callback endpoint validates that the request ID exists in the pending requests map (prevents spoofed callbacks).
- The OPENAI_API_KEY is never exposed in API responses or SSE events.
- Rate limiting is not required for v1 but should be noted as a future consideration.
- Input validation on all POST/PUT endpoints to prevent injection via malformed JSON.

### NFR-3: Error Handling

- All endpoints return consistent `{success: boolean, data?: any, error?: string}` response format.
- SSE bridge timeouts return 504 Gateway Timeout.
- Plugin not connected returns 503 Service Unavailable.
- LLM service unavailable returns 503 with descriptive error.
- Invalid request bodies return 400 with specific field errors.
- Internal server errors return 500 with generic message (detailed errors logged server-side only).

### NFR-4: Testability

- All route handlers are pure functions that accept `(req, config, db)` and return `Response`.
- The SSE bridge is injectable/mockable for testing without real SSE connections.
- Agent chat LLM calls are mockable for testing without real API keys.
- Test coverage target: 80% for all new server-side code.
- Integration tests verify the full request-response bridge flow with mocked plugin.

### NFR-5: Observability

- All agent requests are logged with request ID, operation, and duration.
- Agent chat conversations are logged at debug level.
- Bridge timeouts are logged as warnings.
- SSE client connect/disconnect events are logged.

## User Stories

### US-1: External client creates a job remotely

**As** a developer using a CLI tool,
**I want** to create a job on my Logseq AI Hub via REST API,
**So that** I can trigger automation workflows without opening Logseq.

**Scenarios:**
- **Given** the Logseq plugin is connected via SSE, **When** I POST to `/api/jobs` with `{name: "summarize-today", type: "manual", skill: "Skills/summarize", input: {query: "today"}}`, **Then** I receive a 201 response with the created job details, and the job page appears in my Logseq graph.
- **Given** the Logseq plugin is not connected, **When** I POST to `/api/jobs`, **Then** I receive a 503 response indicating the plugin is offline.

### US-2: External client monitors job status

**As** a monitoring dashboard,
**I want** to list and query job status via REST API,
**So that** I can display the current state of the automation system.

**Scenarios:**
- **Given** there are 5 jobs in various states, **When** I GET `/api/jobs`, **Then** I receive all 5 jobs with their current status, priority, and timestamps.
- **Given** I know a specific job ID, **When** I GET `/api/jobs/summarize-today`, **Then** I receive the full job details including step execution results.

### US-3: User interacts via conversational agent

**As** a non-technical user,
**I want** to manage my job runner through natural language chat,
**So that** I do not need to know the API schema or job page property format.

**Scenarios:**
- **Given** I send "Create a job that summarizes my daily notes every morning at 9am", **When** the agent processes this, **Then** it creates a scheduled job with the correct cron expression (`0 9 * * *`), references the summarize skill, and responds with a confirmation message.
- **Given** I send "What jobs are currently running?", **When** the agent processes this, **Then** it lists the running jobs with their names and start times in natural language.
- **Given** I send "Cancel the daily summary job", **When** the agent processes this, **Then** it identifies the correct job, cancels it, and confirms the cancellation.

### US-4: Developer explores available skills

**As** a developer building integrations,
**I want** to discover what skills are available via the API,
**So that** I can build jobs that reference the right skills.

**Scenarios:**
- **Given** there are 3 skills defined in the graph, **When** I GET `/api/skills`, **Then** I receive all 3 skills with name, description, type, inputs, and outputs.
- **Given** I want to see the full step breakdown of a skill, **When** I GET `/api/skills/summarize`, **Then** I receive the complete skill definition including all step configurations.

### US-5: Developer queries MCP capabilities

**As** a developer,
**I want** to see what MCP servers are connected and what tools they offer,
**So that** I can build skills that leverage external tools.

**Scenarios:**
- **Given** 2 MCP servers are connected, **When** I GET `/api/mcp/servers`, **Then** I receive both servers with their names, URLs, connection status, and capabilities.
- **Given** the filesystem MCP server has 5 tools, **When** I GET `/api/mcp/servers/filesystem/tools`, **Then** I receive all 5 tool definitions with names, descriptions, and input schemas.

## Technical Considerations

### SSE Bridge Timing

The SSE bridge introduces latency because every proxied request requires:
1. Server receives REST request (~0ms)
2. Server broadcasts SSE event (~1ms)
3. Plugin receives SSE event (~10-100ms depending on connection)
4. Plugin performs graph operation (~10-500ms depending on complexity)
5. Plugin calls callback endpoint (~10-100ms)
6. Server resolves pending request (~0ms)

Total expected latency: 30-700ms for simple operations. The 30-second timeout provides ample margin. Clients should be designed to handle async responses.

### Agent Chat LLM Integration

The agent chat uses the server's OpenAI API key (separate from the plugin's key). The system prompt should be structured to produce function-call-style outputs when the user's intent maps to an API operation. The implementation should use OpenAI's function calling / tool use feature if available, falling back to structured output parsing.

The agent should maintain a mapping of operation names to their parameter schemas so the LLM can generate well-formed operation requests.

### Router Refactoring

The current router uses an if/else chain. Adding 15+ new routes to this pattern will make it unwieldy. The implementation should introduce a lightweight route matching pattern (regex-based path matching with parameter extraction) while maintaining the existing handler function signatures.

### TypeScript Type Extensions

New types needed:
- `AgentRequest`: `{requestId: string, operation: string, params: Record<string, unknown>}`
- `AgentCallback`: `{requestId: string, success: boolean, data?: unknown, error?: string}`
- `JobCreateRequest`, `SkillCreateRequest`: Input validation types
- `AgentChatRequest`: `{message: string, conversationId?: string}`
- `AgentChatResponse`: `{conversationId: string, response: string, actions?: AgentAction[]}`
- Extended `SSEEvent.type` union to include new event types

### Plugin-Side Handler Architecture

The plugin-side agent request handler should use a dispatch map pattern (operation string to handler function) for extensibility. Each handler is an async function that receives the operation params and returns a result. The dispatch is registered during plugin initialization alongside the existing SSE event handlers.

## Out of Scope

- WebSocket support (SSE is sufficient for the current architecture).
- Multi-user authentication (single shared API token for v1).
- Streaming responses for agent chat (full response only).
- Agent memory/context beyond per-conversation history (no persistent agent memory).
- Server-side job execution (all execution happens in the plugin).
- GraphQL API (REST only for v1).
- API versioning (implicit v1, versioning can be added later).
- Rate limiting (noted as future consideration).
- OpenAPI/Swagger documentation generation (can be added later).
- Agent tool execution without plugin (server cannot run jobs standalone).

## Open Questions

1. **LLM provider flexibility:** Should the agent chat support providers other than OpenAI (e.g., Anthropic, local models)? For v1, OpenAI-compatible endpoints are sufficient since the `OPENAI_ENDPOINT` config allows pointing to any compatible API.

2. **Conversation persistence:** Should agent chat conversations be persisted to SQLite or kept in-memory only? In-memory is simpler but conversations are lost on server restart. Recommendation: in-memory for v1 with a note for future persistence.

3. **SSE bridge fallback:** What should happen if the plugin connects, receives a request, but crashes before calling the callback? The timeout mechanism handles this, but should the server retry the request? Recommendation: no retry for v1; the client can retry the REST call.

4. **Multi-plugin connections:** What if multiple Logseq plugin instances connect via SSE? The current SSE manager broadcasts to all clients. Should agent requests be targeted to a specific client or broadcast to all (with only one responding via callback)? Recommendation: broadcast to all, first callback wins, reject duplicates by request ID.

5. **Job ID format:** The job-runner track uses page names like `Jobs/summarize-today` as identifiers. Should the API use the full page name or a sanitized slug? Recommendation: use the page name (without the `Jobs/` prefix) as the API identifier, with the server handling the prefix mapping.
