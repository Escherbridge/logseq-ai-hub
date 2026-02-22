# Specification: Claude Code Agent Session Management

## Overview

Enhance the server's agent interaction layer to support persistent, stateful sessions between Claude Code (or other external agents) and the Logseq AI Hub. Sessions maintain conversation history, active context (current project, relevant pages, working memory), and can span multiple MCP tool calls or agent chat interactions. This upgrades the current 20-message in-memory conversation store into a durable, context-rich session system.

## Background

The current system has:
- **Agent chat API** (`POST /api/agent/chat`) with in-memory conversation store (20-message cap, lost on restart).
- **MCP server** (from mcp-server track) for tool-based interaction.
- **Memory system** for persistent knowledge storage.

Limitations:
- Conversations are ephemeral — lost on server restart.
- No concept of "working context" — the agent doesn't know what project or pages are relevant.
- MCP tool calls are stateless — each call is independent with no session context.
- The 20-message cap is arbitrary and can lose important context.
- No way for an agent to "remember" previous interactions across sessions.

This track adds session management that makes agent interactions feel persistent and contextual.

## Dependencies

- **mcp-server_20260221**: MCP server for tool-based interaction.
- **webhook-agent-api_20260219**: Agent chat API and conversation store.

## Functional Requirements

### FR-1: Session Store (SQLite-Backed)

**Description:** Replace the in-memory conversation store with a SQLite-backed session store that persists across server restarts.

**Schema:**
```sql
CREATE TABLE sessions (
  id TEXT PRIMARY KEY,           -- UUID
  name TEXT,                     -- Human-readable session name
  agent_id TEXT,                 -- Identifier for the connecting agent (e.g., "claude-code")
  status TEXT DEFAULT 'active',  -- active, paused, archived
  context JSON,                  -- Working context (current focus, relevant pages, etc.)
  created_at TEXT,
  updated_at TEXT,
  last_active_at TEXT
);

CREATE TABLE session_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT REFERENCES sessions(id),
  role TEXT,                     -- system, user, assistant, tool
  content TEXT,                  -- Message content
  tool_calls JSON,              -- Tool calls made (if assistant message)
  tool_call_id TEXT,            -- Tool call ID (if tool result message)
  metadata JSON,                -- Additional metadata (tokens used, latency, etc.)
  created_at TEXT
);

CREATE INDEX idx_session_messages_session ON session_messages(session_id);
CREATE INDEX idx_sessions_agent ON sessions(agent_id);
CREATE INDEX idx_sessions_status ON sessions(status);
```

**Acceptance Criteria:**
- Sessions are created on first interaction (agent chat or explicit creation).
- Messages are stored with full role, content, and tool call information.
- No arbitrary message cap — messages are stored indefinitely (with session archival for cleanup).
- Sessions survive server restarts.
- Old sessions can be archived (moved to `archived` status, messages retained but excluded from active queries).

**Priority:** P0

### FR-2: Session Context

**Description:** Each session carries a mutable context object that stores the agent's working state.

**Context Structure:**
```json
{
  "focus": "deploying the new API endpoints",
  "relevant_pages": ["Skills/api-deploy", "Jobs/weekly-report"],
  "working_memory": [
    {"key": "last_error", "value": "Build failed on line 42"},
    {"key": "current_branch", "value": "feature/mcp-server"}
  ],
  "preferences": {
    "verbosity": "concise",
    "auto_approve": false
  }
}
```

**Acceptance Criteria:**
- Context is stored as JSON in the sessions table.
- Context can be updated via MCP tool (`session_update_context`) or agent chat.
- Context is included in the LLM system prompt for agent chat interactions.
- Context survives session pauses and resumes.
- The agent can set `focus` to tell the system what it's working on.
- `relevant_pages` are automatically fetched and included in the LLM context window.
- Pages listed in `relevant_pages` are auto-resolved via `graph-context/resolve-page-refs` when building session system prompts. This makes context enrichment declarative — page refs in working context are resolved at prompt-build time rather than requiring explicit Datalog graph-query calls.
- `working_memory` is a key-value scratchpad the agent can read/write.

**Priority:** P0

### FR-3: Session-Aware Agent Chat

**Description:** Update the agent chat API to use the session store and include session context in LLM calls.

**Acceptance Criteria:**
- `POST /api/agent/chat` accepts `{message, sessionId?}`.
- If `sessionId` is provided, loads the session and its message history.
- If no `sessionId`, creates a new session.
- The LLM system prompt includes:
  - Session context (focus, working memory)
  - Content of relevant pages (fetched via Agent Bridge)
  - Available tools from the registry
- Message history is loaded from SQLite (last N messages, configurable, default 50).
- Large histories are summarized: if > 50 messages, the oldest messages are summarized into a single system message.
- Response includes the sessionId for continued interaction.

**Priority:** P0

### FR-4: Session MCP Tools

**Description:** MCP tools for managing sessions.

**Tools:**

| Tool Name | Description | Parameters |
|---|---|---|
| `session_create` | Create a new named session | `{name?: string, context?: object}` |
| `session_get` | Get session details and recent history | `{sessionId: string, messageLimit?: number}` |
| `session_list` | List active sessions | `{status?: string, limit?: number}` |
| `session_update_context` | Update session context | `{sessionId: string, context: object}` |
| `session_set_focus` | Set what the agent is working on | `{sessionId: string, focus: string}` |
| `session_add_memory` | Add a key-value to working memory | `{sessionId: string, key: string, value: string}` |
| `session_archive` | Archive an old session | `{sessionId: string}` |

**Acceptance Criteria:**
- All tools require a valid session ID (except `session_create` and `session_list`).
- `session_update_context` does a merge (not replace) of the context object.
- `session_set_focus` is a convenience shortcut for updating the `focus` field.
- `session_add_memory` adds/updates a key in the working_memory array.
- Archived sessions are excluded from `session_list` unless explicitly requested.

**Priority:** P1

### FR-5: Session Handoff Between Interfaces

**Description:** A session started via agent chat can be continued via MCP and vice versa.

**Acceptance Criteria:**
- The same `sessionId` works across agent chat and MCP tool calls.
- When Claude Code invokes MCP tools, it can optionally pass a `sessionId` header/parameter to associate tool calls with a session.
- Tool call results are logged to the session's message history.
- This enables workflows like: start with agent chat ("What's my deployment status?"), then switch to MCP tools for direct actions.

**Priority:** P2

### FR-6: Session Auto-Context

**Description:** The system automatically enriches session context based on agent activity.

**Acceptance Criteria:**
- When a tool modifies a page, that page is added to `relevant_pages`.
- When a job is created or monitored, the job ID is added to `working_memory`.
- When an approval is pending, it's added to `working_memory` with status.
- Auto-context additions are capped (max 10 relevant pages, max 20 working memory items).
- Older auto-context items are evicted when caps are reached (LRU).
- Session context pages are resolved using the same `enriched/call` pipeline as `/LLM`, supporting BFS link traversal with `depth` and `max-tokens` options from plugin settings.

**Priority:** P2

## Non-Functional Requirements

### NFR-1: Performance

- Session load (with 50 messages) should complete within 50ms from SQLite.
- Context enrichment (fetching relevant pages) should complete within 2 seconds.
- Message storage should be non-blocking (write-behind pattern acceptable).

### NFR-2: Storage

- SQLite is sufficient for single-server deployment.
- Message content is stored as-is (no compression for v1).
- Archival removes sessions from active queries but retains data.
- Future: configurable retention policy (delete archived sessions after N days).

### NFR-3: Security

- Sessions are scoped by `agent_id` — an agent can only access its own sessions.
- Session context may contain sensitive information — never exposed in logs or SSE events.
- Auth token required for all session operations.

## User Stories

### US-1: Persistent agent conversations

**As** a developer using Claude Code,
**I want** my conversation with the AI Hub to persist across server restarts,
**So that** I don't lose context when the server is updated or crashes.

### US-2: Context-aware assistance

**As** a developer using Claude Code,
**I want** the agent to remember what I'm working on,
**So that** it provides relevant suggestions based on my current project focus.

### US-3: Multi-session workflows

**As** a developer,
**I want** to maintain separate sessions for different projects (e.g., "API refactor" and "deployment pipeline"),
**So that** context doesn't bleed between unrelated work streams.

### US-4: Session review

**As** a developer,
**I want** to review past session histories,
**So that** I can recall what decisions were made and what actions were taken.

## Technical Considerations

### Migration from In-Memory Store

The existing `ConversationStore` in `conversations.ts` should be replaced by the new `SessionStore`. The migration path:
1. Add the new SQLite tables alongside existing ones.
2. Update `agent-chat.ts` to use the new store.
3. Remove the old in-memory store.
4. The existing `conversationId` parameter maps to `sessionId`.

### Context Window Management

When including session context in LLM calls, the total token count must be managed:
1. System prompt (base + session context): ~2000 tokens
2. Relevant page content: ~4000 tokens (truncated if needed)
3. Message history: remaining tokens up to model limit
4. If history is too long, oldest messages are summarized by a separate LLM call.

### MCP Session Association

For MCP tool calls, the session can be associated via:
- A custom `X-Session-Id` header on the MCP HTTP request
- A `_sessionId` field in tool call arguments (convention)
- Auto-association: if only one active session exists for the agent, use it

## Out of Scope

- Multi-user sessions (single agent per session for v1).
- Real-time session sharing (no collaborative editing of session context).
- Session branching/forking.
- Automatic session creation based on heuristics.
- Token counting and cost tracking per session.
- Session export (JSON/markdown dump of full history).

## Open Questions

1. **Session identification for MCP:** How should Claude Code associate its MCP tool calls with a specific session? Recommendation: use a custom HTTP header `X-Session-Id` that the MCP transport layer can set.

2. **History summarization model:** Should the same model be used for summarization as for the main agent, or a cheaper/faster model? Recommendation: use a cheaper model (e.g., `anthropic/claude-haiku-4-5-20251001`) for summarization to save costs.

3. **Session lifecycle:** Should sessions auto-archive after inactivity? Recommendation: auto-archive after 7 days of inactivity, configurable via env var.
