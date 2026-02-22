# Specification: MCP Server Transport — Expose Plugin as MCP Server

## Overview

Add a Model Context Protocol (MCP) server implementation to the Bun webhook server, exposing the Logseq plugin's full capabilities as MCP tools and resources. This enables Claude Code (or any MCP-compliant client) to connect to the system and interact with the Logseq knowledge graph, job runner, messaging, and memory systems through the standard MCP protocol.

The MCP server wraps the existing Agent Bridge infrastructure — every MCP tool call translates to an Agent Bridge operation that is relayed to the plugin via SSE. This means the MCP server adds zero new graph-touching logic; it is a protocol translation layer on top of the existing REST API / Agent Bridge.

## Background

The Logseq AI Hub already has:
- An **MCP client** in the plugin that connects *to* external MCP servers (filesystem, web, etc.)
- A **REST API** with 21+ endpoints for job management, skill CRUD, messaging, memory, and agent chat
- An **SSE bridge** (Agent Bridge) that proxies all graph-touching operations from the server to the plugin
- A **conversational agent** that uses LLM tool-calling to interpret natural language into operations

What's missing is the inverse: exposing the plugin's capabilities **as** an MCP server so that external agents (particularly Claude Code) can discover and invoke them through the standard protocol.

### Why MCP (Not Just REST)

Claude Code and other agentic coding tools natively speak MCP. By implementing the MCP server protocol, the system becomes instantly discoverable and usable by any MCP client without custom integration code. Tools, resources, and prompts are self-describing — the client can enumerate what's available and invoke operations with structured schemas.

### Architecture

```
Claude Code (MCP Client)
    │
    │  JSON-RPC 2.0 over Streamable HTTP
    ▼
Bun Server — MCP Server Transport Layer
    │
    │  Translates MCP tool calls → Agent Bridge operations
    ▼
Agent Bridge (SSE request-response)
    │
    ▼
Logseq Plugin (executes on graph, calls back)
```

The MCP server runs on the same Bun server instance, mounted at `/mcp`. It shares the existing config, auth, and Agent Bridge. No new services or databases are needed.

## Dependencies

- **webhook-agent-api_20260219**: The Agent Bridge, REST API, and all plugin-side handlers must be operational. The MCP server delegates to these.
- **job-runner_20260219**: Plugin-side job/skill/MCP operations that the Agent Bridge calls.
- **foundation-hardening_20260221**: Router performance fix (clean route integration for `/mcp`), correlation IDs (trace MCP tool calls through Agent Bridge).

## Functional Requirements

### FR-1: MCP Server Protocol Implementation

**Description:** Implement the MCP server protocol (JSON-RPC 2.0) with Streamable HTTP transport, mounted at `POST /mcp` on the existing Bun server. Use the official `@modelcontextprotocol/sdk` package — do NOT implement JSON-RPC 2.0 parsing manually.

**Acceptance Criteria:**
- Uses `@modelcontextprotocol/sdk` with `StreamableHTTPServerTransport` for protocol compliance, session management, and capability negotiation.
- The server accepts JSON-RPC 2.0 requests at `POST /mcp`.
- Supports the `initialize` method: returns server info, protocol version (`2025-03-26`), and declared capabilities (`tools`, `resources`, `prompts`).
- Supports `notifications/initialized` (client acknowledgment, no response needed).
- Supports `tools/list` — returns all available tools with names, descriptions, and JSON Schema input definitions.
- Supports `tools/call` — dispatches to the appropriate handler and returns structured results.
- Supports `resources/list` — returns available resources (knowledge base pages, memory tags, job statuses).
- Supports `resources/read` — fetches the content of a specific resource by URI.
- Supports `prompts/list` — returns available prompt templates.
- Supports `prompts/get` — returns a specific prompt with arguments filled in.
- Returns proper JSON-RPC error responses for unknown methods, invalid params, and internal errors.
- Auth: Requires `Authorization: Bearer <PLUGIN_API_TOKEN>` header (same token as REST API).
- Content-Type: Accepts `application/json`, returns `application/json`.

**Priority:** P0

### FR-2: Tool Definitions — Knowledge Graph Operations

**Description:** Expose Logseq graph operations as MCP tools.

**Tools:**

| Tool Name | Description | Parameters | Agent Bridge Operation |
|---|---|---|---|
| `graph_query` | Run a Datalog query against the Logseq graph | `{query: string}` | New: `graph_query` |
| `graph_search` | Full-text search across all pages | `{query: string, limit?: number}` | New: `graph_search` |
| `page_read` | Read the full content of a Logseq page | `{name: string}` | New: `page_read` |
| `page_create` | Create a new page with content | `{name: string, content: string, properties?: object}` | New: `page_create` |
| `page_list` | List pages matching a pattern | `{pattern?: string, limit?: number}` | New: `page_list` |
| `block_append` | Append a block to a page | `{page: string, content: string, properties?: object}` | New: `block_append` |
| `block_update` | Update an existing block's content | `{uuid: string, content: string}` | New: `block_update` |

**Acceptance Criteria:**
- Each tool has a complete JSON Schema for its input parameters.
- Tool calls are dispatched through the Agent Bridge to the plugin.
- Plugin-side handlers are added to the `operation-handlers` dispatch map in `agent_bridge.cljs`.
- Results are returned as MCP `TextContent` or `EmbeddedResource` content types.
- Errors from the plugin are propagated as MCP tool call errors.

**Priority:** P0

### FR-3: Tool Definitions — Job Runner Operations

**Description:** Expose job runner operations as MCP tools.

**Tools:**

| Tool Name | Description | Parameters | Agent Bridge Operation |
|---|---|---|---|
| `job_create` | Create a new job | `{name, type, skill?, priority?, schedule?, input?}` | Existing: `create_job` |
| `job_list` | List jobs with optional filters | `{status?, limit?, offset?}` | Existing: `list_jobs` |
| `job_get` | Get job details | `{jobId: string}` | Existing: `get_job` |
| `job_start` | Start/enqueue a job | `{jobId: string}` | Existing: `start_job` |
| `job_cancel` | Cancel a job | `{jobId: string}` | Existing: `cancel_job` |
| `job_pause` | Pause a running job | `{jobId: string}` | Existing: `pause_job` |
| `job_resume` | Resume a paused job | `{jobId: string}` | Existing: `resume_job` |
| `skill_list` | List available skills | `{}` | Existing: `list_skills` |
| `skill_get` | Get skill details | `{skillId: string}` | Existing: `get_skill` |
| `skill_create` | Create a new skill definition | `{name, type, description, steps, ...}` | Existing: `create_skill` |

**Acceptance Criteria:**
- All existing Agent Bridge operations are exposed as MCP tools.
- Parameter schemas match the existing REST API validation rules.
- No new plugin-side handlers needed (reuses existing `operation-handlers`).

**Priority:** P0

### FR-4: Tool Definitions — Messaging Operations

**Description:** Expose messaging capabilities as MCP tools.

**Tools:**

| Tool Name | Description | Parameters | Agent Bridge Operation |
|---|---|---|---|
| `message_send` | Send a message via WhatsApp or Telegram | `{platform, recipient, content}` | Direct server (no bridge) |
| `message_list` | Query message history | `{contact_id?, limit?, since?}` | Direct server (no bridge) |
| `contact_list` | List known contacts | `{platform?: string}` | Direct server (DB query) |

**Acceptance Criteria:**
- `message_send` calls the existing WhatsApp/Telegram send logic directly (no Agent Bridge needed).
- `message_list` queries SQLite directly.
- `contact_list` queries the contacts table.
- These tools do NOT require the plugin to be connected (server-side only).

**Priority:** P1

### FR-5: Tool Definitions — Memory Operations

**Description:** Expose the AI memory system as MCP tools.

**Tools:**

| Tool Name | Description | Parameters | Agent Bridge Operation |
|---|---|---|---|
| `memory_store` | Store a memory with a tag | `{tag: string, content: string}` | New: `store_memory` |
| `memory_recall` | Recall memories by tag | `{tag: string}` | New: `recall_memory` |
| `memory_search` | Full-text search across memories | `{query: string}` | New: `search_memory` |
| `memory_list_tags` | List all memory tags/pages | `{}` | New: `list_memory_tags` |

**Acceptance Criteria:**
- Memory operations are proxied to the plugin via Agent Bridge.
- Plugin-side handlers call the existing `memory.cljs` functions.
- New operation handlers added to `agent_bridge.cljs`.

**Priority:** P1

### FR-6: Resource Definitions

**Description:** Expose Logseq graph content as MCP resources for read-only access.

**Resources:**

| URI Pattern | Description |
|---|---|
| `logseq://pages/{name}` | Content of a specific Logseq page |
| `logseq://jobs` | Current job statuses summary |
| `logseq://skills` | Available skill definitions |
| `logseq://memory/{tag}` | Memories under a specific tag |
| `logseq://contacts` | Known contacts list |

**Acceptance Criteria:**
- `resources/list` returns all available resource URIs with descriptions and MIME types.
- `resources/read` fetches the resource content via the appropriate mechanism (Agent Bridge for graph data, direct DB query for contacts/messages).
- Resources return `text/plain` or `application/json` content.
- Dynamic resources (pages, memories) are resolved at read time, not cached.

**Priority:** P2

### FR-7: Prompt Templates

**Description:** Expose reusable prompt templates as MCP prompts that Claude Code can discover and use.

**Prompts:**

| Prompt Name | Description | Arguments |
|---|---|---|
| `summarize_page` | Summarize a Logseq page | `{page: string}` |
| `create_skill_from_description` | Generate a skill definition from natural language | `{description: string}` |
| `analyze_knowledge_base` | Analyze the knowledge base for patterns | `{focus?: string}` |
| `draft_message` | Draft a message for a contact | `{contact: string, context: string}` |

**Acceptance Criteria:**
- `prompts/list` returns all prompt definitions with names, descriptions, and argument schemas.
- `prompts/get` returns the prompt messages (system + user) with arguments interpolated.
- Prompts are statically defined in the server code (not stored in the graph).

**Priority:** P2

### FR-8: MCP Session Management

**Description:** Handle MCP session lifecycle including initialization, capability negotiation, and graceful shutdown.

**Acceptance Criteria:**
- The `initialize` response includes the server's name (`logseq-ai-hub`), version (from package.json), and protocol version.
- Capabilities declared: `tools` (with `listChanged` if dynamic tool registration is supported), `resources` (with `listChanged`), `prompts`.
- The server tracks active MCP sessions (for future multi-client support).
- If the Logseq plugin disconnects (Agent Bridge unavailable), graph-dependent tools return appropriate errors but messaging/DB tools continue to work.
- Health endpoint (`GET /health`) includes MCP server status (active sessions, available tools count).

**Priority:** P1

### FR-9: Claude Code Configuration Helper

**Description:** Provide a helper endpoint and documentation for configuring Claude Code to connect to this MCP server.

**Acceptance Criteria:**
- `GET /mcp/config` returns a JSON object suitable for adding to Claude Code's MCP config:
  ```json
  {
    "mcpServers": {
      "logseq-ai-hub": {
        "type": "url",
        "url": "http://localhost:3000/mcp",
        "headers": {"Authorization": "Bearer <token>"}
      }
    }
  }
  ```
- The response uses the server's actual URL and includes a placeholder for the token.
- README documentation includes setup instructions for Claude Code integration.

**Priority:** P1

## Non-Functional Requirements

### NFR-1: Performance

- MCP tool calls should have the same latency as the corresponding REST API calls (30-700ms for graph operations).
- The JSON-RPC parsing/routing overhead should be under 1ms.
- The MCP server should handle concurrent tool calls (limited by Agent Bridge concurrency, not MCP layer).

### NFR-2: Security

- Same auth model as REST API: Bearer token required on all requests.
- MCP tool calls go through the same validation as REST endpoints.
- No new attack surface beyond what the REST API already exposes.
- Tool input schemas enforce types and constraints.

### NFR-3: Compatibility

- Implement MCP protocol version `2025-03-26` (latest stable).
- Use Streamable HTTP transport (the standard for server-hosted MCP servers).
- Compatible with Claude Code, Cursor, and any MCP-compliant client.
- Graceful degradation: if the plugin is disconnected, server-side tools still work, graph tools return clear errors.

### NFR-4: Testability

- MCP protocol layer has unit tests for JSON-RPC parsing, routing, and error handling.
- Tool dispatch has integration tests with mocked Agent Bridge.
- End-to-end test: MCP client → server → Agent Bridge → mock plugin response.
- Test coverage target: 80% for MCP-specific code.

## User Stories

### US-1: Claude Code discovers available tools

**As** a developer using Claude Code,
**I want** Claude Code to automatically discover all available Logseq AI Hub tools when I configure the MCP server,
**So that** I can interact with my knowledge graph through natural conversation.

### US-2: Claude Code queries the knowledge graph

**As** a developer using Claude Code,
**I want** to ask Claude Code to "find my notes about the Q3 roadmap",
**So that** it searches my Logseq graph and returns relevant content without me opening Logseq.

### US-3: Claude Code creates automation

**As** a developer using Claude Code,
**I want** to tell Claude Code to "set up a daily summary job that runs at 9am",
**So that** it creates the skill definition and scheduled job in my Logseq graph through MCP tools.

### US-4: Claude Code sends messages on my behalf

**As** a developer using Claude Code,
**I want** to tell Claude Code to "send John a WhatsApp message about the meeting",
**So that** it composes and sends the message through the hub's messaging integration.

### US-5: Claude Code reads resources

**As** a developer using Claude Code,
**I want** Claude Code to access my knowledge base as MCP resources,
**So that** it can provide context-aware assistance based on my actual notes and workflows.

## Technical Considerations

### MCP SDK Usage

Use the official `@modelcontextprotocol/sdk` TypeScript package (`bun add @modelcontextprotocol/sdk`). This provides:
- **`McpServer`** class — handles JSON-RPC 2.0 protocol parsing, method routing, capability negotiation, and session lifecycle.
- **`StreamableHTTPServerTransport`** — the transport adapter for server-hosted MCP over HTTP POST.
- **Schema validation** — automatic input validation against JSON Schema definitions.
- **Protocol compliance** — the SDK tracks the MCP spec as it evolves, reducing maintenance burden.

Integration approach:
1. Create an `McpServer` instance with tool/resource/prompt registrations.
2. In the Bun route handler for `POST /mcp`, create a `StreamableHTTPServerTransport` and connect it to the `McpServer`.
3. The SDK handles JSON-RPC parsing, routing, and response formatting. Our code only needs to implement tool handlers.

Do NOT implement JSON-RPC 2.0 parsing, capability negotiation, or protocol-level error handling manually — the SDK handles all of this.

### Transport Choice: Streamable HTTP

Streamable HTTP is the recommended transport for server-hosted MCP servers. It uses standard HTTP POST for requests and can stream responses via SSE when needed. This integrates naturally with the existing Bun.serve() infrastructure — the MCP endpoint is just another route handler.

We do NOT need stdio transport (that's for local process-based servers) or the legacy SSE transport.

### Tool Registration Architecture

Tools should be defined as a registry (similar to the plugin's executor registry). Each tool definition includes:
- `name`, `description`, `inputSchema` (JSON Schema)
- A `handler` function that takes validated params and returns MCP content
- A `requiresPlugin` boolean (false for server-side-only tools like messaging)

This allows new tools to be added by registering them in the registry without modifying the MCP protocol layer.

### Mapping MCP Tool Calls to Agent Bridge

Most MCP tools map 1:1 to Agent Bridge operations. The MCP handler:
1. Validates input against the tool's JSON Schema
2. Calls `agentBridge.sendRequest(operation, params)` (or direct DB/service call for server-side tools)
3. Wraps the response in MCP content format (`TextContent` or `EmbeddedResource`)
4. Returns the JSON-RPC response

### New Plugin-Side Handlers Needed

The following new operations must be added to `agent_bridge.cljs`:
- `graph_query` — execute a Datalog query via `logseq.DB.datascriptQuery`
- `graph_search` — full-text search via `logseq.DB.q`
- `page_read` — read page blocks via `logseq.Editor.getPageBlocksTree`
- `page_create` — create page via `logseq.Editor.createPage`
- `page_list` — list pages via Datalog query
- `block_append` — append block via `logseq.Editor.insertBlock`
- `block_update` — update block via `logseq.Editor.updateBlock`
- `store_memory` / `recall_memory` / `search_memory` / `list_memory_tags` — delegate to `memory.cljs`

These follow the existing dispatch map pattern and are straightforward additions.

## Out of Scope

- SSE or stdio MCP transports (Streamable HTTP only for v1).
- Dynamic tool registration at runtime (tools are fixed at server startup).
- MCP sampling (server requesting LLM completions from the client).
- MCP roots (client providing workspace root URIs).
- Resource subscriptions (real-time resource change notifications).
- Multi-tenant MCP (single shared token for v1).
- Tool pagination (all tools returned in single list for v1).

## Open Questions

1. **Tool granularity:** Should graph operations be fine-grained (separate tools for query, search, page read, block append) or coarse-grained (single `graph` tool with sub-commands)? Recommendation: fine-grained, as it gives better discoverability and more specific schemas for LLM tool calling.

2. **Resource caching:** Should resource reads be cached on the server side to reduce Agent Bridge round trips? Recommendation: no caching for v1; resources are live reads to ensure freshness. Caching can be added later with TTL.

3. **Tool result format:** Should tool results be plain text, JSON, or both? Recommendation: JSON for structured data (job lists, skill definitions), plain text for page content. Use `TextContent` for text and `TextContent` with JSON stringified for structured data.
