# Implementation Plan: Claude Code Agent Session Management

## Overview

This plan implements persistent, context-rich agent sessions across 6 phases. The current in-memory `ConversationStore` (20-message cap, lost on restart) is replaced with a SQLite-backed `SessionStore` that persists conversations, carries mutable working context (focus, relevant pages, working memory), and integrates with the MCP server for tool-based session management.

The implementation follows a bottom-up approach: build the SQLite schema and data access layer first, then the session context system, then rewire agent chat to use sessions, then expose MCP tools, session handoff, and finally auto-context enrichment. Each phase builds on the previous one and ends with a verification checkpoint.

**Estimated effort:** 18-24 hours across 38 tasks.

**Key files created/modified:**

Server types:
- `server/src/types/session.ts` (new)

Server database:
- `server/src/db/schema.ts` (modified -- add sessions + session_messages tables)
- `server/src/db/sessions.ts` (new -- session data access functions)

Server services:
- `server/src/services/session-store.ts` (new -- SessionStore class)
- `server/src/services/session-context.ts` (new -- context resolution and prompt building)
- `server/src/services/conversations.ts` (deprecated then removed)
- `server/src/services/agent.ts` (modified -- session-aware system prompt)
- `server/src/services/llm.ts` (modified -- summarization support)
- `server/src/services/mcp/session-tools.ts` (new -- 7 MCP tools)
- `server/src/services/mcp/index.ts` (modified -- register session tools)

Server routes:
- `server/src/routes/api/agent-chat.ts` (modified -- use SessionStore)

Server infra:
- `server/src/router.ts` (modified -- RouteContext gets sessionStore)
- `server/src/index.ts` (modified -- create SessionStore, pass to router and MCP)
- `server/src/config.ts` (modified -- new config fields)
- `server/src/types/mcp.ts` (modified -- add sessionStore to McpToolContext)

Server tests:
- `server/tests/session-store.test.ts` (new)
- `server/tests/session-context.test.ts` (new)
- `server/tests/agent-chat-sessions.test.ts` (new)
- `server/tests/session-tools.test.ts` (new)
- `server/tests/session-handoff.test.ts` (new)
- `server/tests/session-auto-context.test.ts` (new)
- `server/tests/helpers.ts` (modified -- add session seed helpers)

---

## Phase 1: SQLite Schema and Session Store Core

**Goal:** Create the SQLite tables for sessions and session messages, build the `SessionStore` class with CRUD operations for sessions and messages. This replaces the in-memory `ConversationStore` at the data layer.

### Task 1.1: Define Session Types [x] [2f0c287]

**TDD Cycle:** Type definitions -- validate via compilation.

**Description:** Create `server/src/types/session.ts` with all session-related interfaces.

**Types to define:**
- `SessionStatus`: `"active" | "paused" | "archived"`
- `SessionContext`: `{ focus?: string; relevant_pages?: string[]; working_memory?: WorkingMemoryEntry[]; preferences?: SessionPreferences }`
- `WorkingMemoryEntry`: `{ key: string; value: string; addedAt: string; source?: "manual" | "auto" }`
- `SessionPreferences`: `{ verbosity?: "concise" | "normal" | "verbose"; auto_approve?: boolean }`
- `Session`: `{ id: string; name: string | null; agent_id: string; status: SessionStatus; context: SessionContext; created_at: string; updated_at: string; last_active_at: string }`
- `SessionMessage`: `{ id: number; session_id: string; role: "system" | "user" | "assistant" | "tool"; content: string; tool_calls: any[] | null; tool_call_id: string | null; metadata: Record<string, unknown> | null; created_at: string }`
- `CreateSessionParams`: `{ name?: string; agent_id: string; context?: SessionContext }`
- `AddMessageParams`: `{ session_id: string; role: SessionMessage["role"]; content: string; tool_calls?: any[]; tool_call_id?: string; metadata?: Record<string, unknown> }`

**File:** `server/src/types/session.ts`

**Commit:** `feat(session): define session and message type interfaces`

---

### Task 1.2: Add Session Tables to SQLite Schema [x] [0dbab6b]

**TDD Cycle:**
- RED: Write tests in `server/tests/session-store.test.ts` that create a test DB and verify the `sessions` and `session_messages` tables exist (query `sqlite_master`).
- GREEN: Add `CREATE TABLE IF NOT EXISTS sessions (...)` and `CREATE TABLE IF NOT EXISTS session_messages (...)` plus indexes to `server/src/db/schema.ts`, following the spec's schema exactly.
- REFACTOR: Ensure the schema uses `datetime('now')` defaults consistent with existing tables.

**Schema to add (from spec):**
```sql
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  name TEXT,
  agent_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active',
  context TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  last_active_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS session_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  tool_calls TEXT,
  tool_call_id TEXT,
  metadata TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_session_messages_session ON session_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_sessions_agent ON sessions(agent_id);
CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status);
```

**Test expectations:**
- `sessions` table exists with correct columns
- `session_messages` table exists with correct columns
- Indexes are created
- Foreign key from `session_messages.session_id` to `sessions.id` is enforced

**Files:**
- `server/tests/session-store.test.ts` (new)
- `server/src/db/schema.ts` (modified)

**Commit:** `feat(session): add sessions and session_messages tables to schema`

---

### Task 1.3: Implement Session Data Access -- Create and Get [x] [642e85a]

**TDD Cycle:**
- RED: Write tests for `createSession(db, params)` and `getSession(db, id)`.
- GREEN: Implement in `server/src/db/sessions.ts` following the pattern from `server/src/db/character-sessions.ts`. Use `crypto.randomUUID()` for IDs. Store context as JSON string.
- REFACTOR: Extract row-to-session parsing into a `parseSessionRow` helper.

**Test expectations:**
- `createSession(db, { agent_id: "claude-code" })` returns a `Session` with UUID id, status "active", empty context `{}`
- `createSession(db, { agent_id: "claude-code", name: "API Refactor" })` stores the name
- `createSession(db, { agent_id: "claude-code", context: { focus: "testing" } })` stores context JSON
- `getSession(db, validId)` returns the session with parsed context
- `getSession(db, "nonexistent")` returns `null`
- `created_at`, `updated_at`, `last_active_at` are populated ISO timestamps

**Files:**
- `server/tests/session-store.test.ts` (extended)
- `server/src/db/sessions.ts` (new)

**Commit:** `feat(session): implement createSession and getSession data access`

---

### Task 1.4: Implement Session Data Access -- List and Update [x] [965fc1e]

**TDD Cycle:**
- RED: Write tests for `listSessions(db, agentId, opts)` and `updateSession(db, id, updates)`.
- GREEN: Implement `listSessions` with filtering by agent_id, status, and limit/offset. Implement `updateSession` for name, status, context, last_active_at.
- REFACTOR: Ensure `updateSession` also bumps `updated_at`.

**Test expectations:**
- `listSessions(db, "claude-code")` returns all active sessions for that agent, ordered by `last_active_at` DESC
- `listSessions(db, "claude-code", { status: "archived" })` returns only archived sessions
- `listSessions(db, "claude-code", { limit: 2 })` respects the limit
- `updateSession(db, id, { name: "New Name" })` updates the name and bumps `updated_at`
- `updateSession(db, id, { status: "archived" })` changes status
- `updateSession(db, id, { context: { focus: "new focus" } })` replaces the entire context JSON
- `updateSession(db, "nonexistent", ...)` returns false (no rows affected)

**Files:**
- `server/tests/session-store.test.ts` (extended)
- `server/src/db/sessions.ts` (extended)

**Commit:** `feat(session): implement listSessions and updateSession data access`

---

### Task 1.5: Implement Message Data Access -- Add and Load [x] [80ccf5b]

**TDD Cycle:**
- RED: Write tests for `addSessionMessage(db, params)` and `loadSessionMessages(db, sessionId, opts)`.
- GREEN: Implement message insertion with JSON serialization for `tool_calls` and `metadata`. Implement message loading with ordering by `id ASC` and configurable `limit` (default 50).
- REFACTOR: Extract JSON parsing into a `parseMessageRow` helper.

**Test expectations:**
- `addSessionMessage(db, { session_id, role: "user", content: "Hello" })` inserts a row and returns the message with auto-incremented id
- `addSessionMessage` with `tool_calls` and `tool_call_id` stores them correctly
- `addSessionMessage` with `metadata: { tokens: 150 }` stores JSON metadata
- `addSessionMessage` also updates the session's `last_active_at` via a trigger or explicit UPDATE
- `loadSessionMessages(db, sessionId)` returns messages ordered by id ASC
- `loadSessionMessages(db, sessionId, { limit: 10 })` returns the last 10 messages
- `loadSessionMessages(db, sessionId, { limit: 10 })` still returns messages in ASC order (i.e., select the last N, then order ASC)
- `loadSessionMessages(db, "nonexistent")` returns empty array

**Files:**
- `server/tests/session-store.test.ts` (extended)
- `server/src/db/sessions.ts` (extended)

**Commit:** `feat(session): implement addSessionMessage and loadSessionMessages`

---

### Task 1.6: Implement SessionStore Class [x] [0dd0d45]

**TDD Cycle:**
- RED: Write tests for `SessionStore` class that wraps the data access functions, managing the DB instance and providing a clean API.
- GREEN: Implement `SessionStore` as a class that takes a `Database` instance. Methods: `create(params)`, `get(id)`, `list(agentId, opts)`, `update(id, updates)`, `addMessage(params)`, `getMessages(sessionId, opts)`, `archive(id)`, `touchActivity(id)`.
- REFACTOR: `archive(id)` is a convenience for `update(id, { status: "archived" })`.

**Test expectations:**
- `store.create({ agent_id: "claude-code" })` creates and returns a session
- `store.get(id)` retrieves a session
- `store.list("claude-code")` returns active sessions only by default
- `store.addMessage(params)` stores a message and updates `last_active_at`
- `store.getMessages(sessionId)` returns ordered messages
- `store.archive(id)` sets status to "archived"
- `store.archive(id)` causes the session to be excluded from `list()` default results
- `store.list("claude-code", { status: "archived" })` returns archived sessions

**Files:**
- `server/tests/session-store.test.ts` (extended)
- `server/src/services/session-store.ts` (new)

**Commit:** `feat(session): implement SessionStore class wrapping data access layer`

---

### Task 1.7: Add Test Helpers for Sessions [x] [2de71fc]

**TDD Cycle:** No test for helpers themselves -- verify by using them in subsequent tests.

**Description:** Add `seedTestSession` and `seedTestSessionMessage` to `server/tests/helpers.ts`.

**Helpers:**
- `seedTestSession(db, agentId?, name?, context?)` -- creates a session, returns the Session object
- `seedTestSessionMessage(db, sessionId, role?, content?)` -- creates a message, returns the message id

**Files:**
- `server/tests/helpers.ts` (modified)

**Commit:** `test(session): add session seed helpers for tests`

---

### Verification 1

- [ ] All `server/tests/session-store.test.ts` tests pass (`cd server && bun test tests/session-store.test.ts`)
- [ ] Sessions persist in SQLite (survive DB re-open in test)
- [ ] Messages are correctly ordered and associated with sessions
- [ ] JSON context is round-tripped correctly (store and retrieve)
- [ ] Foreign key cascade: deleting a session deletes its messages
- [ ] No regressions in existing tests (`cd server && bun test`)

[checkpoint marker]

---

## Phase 2: Session Context Management

**Goal:** Build the session context system that resolves `relevant_pages` into actual page content, manages working memory with key-value operations, and produces enriched system prompts that include session context.

### Task 2.1: Implement Context Merge Logic [x] [9bd37b2]

**TDD Cycle:**
- RED: Write tests for `mergeSessionContext(existing, updates)` -- a deep merge that handles the context structure correctly.
- GREEN: Implement in `server/src/services/session-context.ts`. The merge should: replace `focus` if provided, union `relevant_pages` (deduplicate), merge `working_memory` by key (update existing, add new), merge `preferences` shallowly.
- REFACTOR: Handle edge cases (null fields, empty arrays).

**Test expectations:**
- Merging `{ focus: "old" }` with `{ focus: "new" }` gives `{ focus: "new" }`
- Merging `{ relevant_pages: ["A"] }` with `{ relevant_pages: ["B"] }` gives `{ relevant_pages: ["A", "B"] }`
- Merging with duplicate pages deduplicates: `["A", "B"]` + `["B", "C"]` = `["A", "B", "C"]`
- Merging working_memory by key: `[{key: "a", value: "1"}]` + `[{key: "a", value: "2"}]` = `[{key: "a", value: "2", ...}]`
- Merging working_memory adds new keys: `[{key: "a", value: "1"}]` + `[{key: "b", value: "2"}]` has both
- Merging preferences: `{ verbosity: "concise" }` + `{ auto_approve: true }` = `{ verbosity: "concise", auto_approve: true }`
- Merging with empty/undefined fields preserves existing values

**Files:**
- `server/tests/session-context.test.ts` (new)
- `server/src/services/session-context.ts` (new)

**Commit:** `feat(session): implement session context merge logic`

---

### Task 2.2: Implement Working Memory Helpers [x] [9fa3b60]

**TDD Cycle:**
- RED: Write tests for `addWorkingMemory(context, key, value, source?)` and `removeWorkingMemory(context, key)`.
- GREEN: Implement in `server/src/services/session-context.ts`. `addWorkingMemory` adds or updates a key-value entry with timestamp and optional source tag. Cap at 20 items with LRU eviction (evict oldest `addedAt` when at cap).
- REFACTOR: Extract LRU logic into a helper.

**Test expectations:**
- `addWorkingMemory(ctx, "branch", "main")` adds an entry with `addedAt` timestamp
- `addWorkingMemory(ctx, "branch", "develop")` updates existing entry's value and `addedAt`
- `addWorkingMemory(ctx, "key", "val", "auto")` sets `source: "auto"`
- Adding 21st item evicts the oldest entry (by `addedAt`)
- `removeWorkingMemory(ctx, "branch")` removes the entry
- `removeWorkingMemory(ctx, "nonexistent")` returns context unchanged

**Files:**
- `server/tests/session-context.test.ts` (extended)
- `server/src/services/session-context.ts` (extended)

**Commit:** `feat(session): implement working memory add/remove with LRU eviction`

---

### Task 2.3: Implement Relevant Pages Management [x] [5a3b268]

**TDD Cycle:**
- RED: Write tests for `addRelevantPage(context, pageName)` and `removeRelevantPage(context, pageName)`.
- GREEN: Implement in `server/src/services/session-context.ts`. Cap at 10 pages with LRU eviction (need to track order -- use array position as proxy, evict from front).
- REFACTOR: Case-insensitive deduplication (Logseq page names are case-insensitive).

**Test expectations:**
- `addRelevantPage(ctx, "Skills/api-deploy")` adds the page
- Adding a duplicate (case-insensitive) does not create a second entry but moves it to the end (MRU)
- Adding 11th page evicts the oldest (first in array)
- `removeRelevantPage(ctx, "Skills/api-deploy")` removes it
- Page names are stored as-provided (not lowercased), but comparison is case-insensitive

**Files:**
- `server/tests/session-context.test.ts` (extended)
- `server/src/services/session-context.ts` (extended)

**Commit:** `feat(session): implement relevant pages management with LRU cap`

---

### Task 2.4: Build Session System Prompt [x] [151be5b]

**TDD Cycle:**
- RED: Write tests for `buildSessionSystemPrompt(session, pageContents?)` that produces a system prompt string enriched with session context.
- GREEN: Implement in `server/src/services/session-context.ts`. The prompt includes: base agent system prompt (from `agent.ts`), session focus, working memory key-values, relevant page content (passed in as pre-resolved strings). Format each section clearly with markdown headers.
- REFACTOR: Keep total prompt under ~2000 tokens for the session context portion (truncate if needed).

**Test expectations:**
- Session with no context produces the base system prompt only
- Session with `focus: "deploying API"` includes a "Current Focus" section with the focus text
- Session with working_memory entries includes a "Working Memory" section with key-value pairs
- Session with relevant_pages and corresponding pageContents includes a "Relevant Pages" section
- Long page content is truncated (e.g., max 4000 chars per page, 12000 total for all pages)
- The prompt is well-structured and parseable

**Files:**
- `server/tests/session-context.test.ts` (extended)
- `server/src/services/session-context.ts` (extended)

**Commit:** `feat(session): build session-aware system prompt with context sections`

---

### Task 2.5: Implement Page Content Resolution via Agent Bridge [x] [dd6e10e]

**TDD Cycle:**
- RED: Write tests for `resolveRelevantPages(bridge, pageNames)` that calls the Agent Bridge to fetch page content for each page name.
- GREEN: Implement in `server/src/services/session-context.ts`. For each page name, call `bridge.sendRequest("page_read", { name: pageName })`. Return a map of page name to content string. Handle failures gracefully (skip pages that fail to load).
- REFACTOR: Add parallelism with `Promise.allSettled` for concurrent page fetches. Add a timeout per page (2 seconds).

**Test expectations:**
- Resolves a single page name to its content via the bridge
- Resolves multiple pages in parallel
- Failed page reads are skipped (not included in result), no thrown error
- Returns `Map<string, string>` with page name as key
- If bridge is not connected, returns empty map

**Files:**
- `server/tests/session-context.test.ts` (extended)
- `server/src/services/session-context.ts` (extended)

**Commit:** `feat(session): implement page content resolution via agent bridge`

---

### Verification 2

- [ ] All `server/tests/session-context.test.ts` tests pass
- [ ] Context merge correctly handles all field types (focus, pages, memory, preferences)
- [ ] Working memory LRU eviction works at cap of 20
- [ ] Relevant pages LRU eviction works at cap of 10
- [ ] System prompt is well-structured and includes all context sections
- [ ] Page resolution is fault-tolerant (skips failures)

[checkpoint marker]

---

## Phase 3: Session-Aware Agent Chat

**Goal:** Rewire the `POST /api/agent/chat` endpoint to use the `SessionStore` instead of the in-memory `ConversationStore`. Add session context to LLM calls and return `sessionId` in responses.

### Task 3.1: Update Agent Chat to Create/Load Sessions [x] [1345ee1]

**TDD Cycle:**
- RED: Write tests for the updated `handleAgentChat` that accepts `{ message, sessionId? }` and uses `SessionStore`. Test: no sessionId creates a new session; providing a valid sessionId loads it; invalid sessionId creates a new session.
- GREEN: Modify `server/src/routes/api/agent-chat.ts` to accept `SessionStore` instead of `ConversationStore`. If `sessionId` is provided, load the session. If not found or not provided, create a new one with `agent_id` derived from the auth token or a default "agent-chat". Load message history from SQLite.
- REFACTOR: Update the function signature and imports.

**Test expectations:**
- Request with no `sessionId` creates a new session and returns `sessionId` in the response
- Request with valid `sessionId` loads the existing session and its message history
- Request with invalid `sessionId` creates a new session (graceful fallback)
- Response shape changes from `{ conversationId, response }` to `{ sessionId, response, actions? }`
- User message is stored in `session_messages` table
- Assistant response is stored in `session_messages` table

**Files:**
- `server/tests/agent-chat-sessions.test.ts` (new)
- `server/src/routes/api/agent-chat.ts` (modified)

**Commit:** `feat(session): update agent chat to use SessionStore for session management`

---

### Task 3.2: Include Session Context in LLM System Prompt [x] [1345ee1]

**TDD Cycle:**
- RED: Write tests verifying that the system prompt sent to the LLM includes session context (focus, working memory, page content).
- GREEN: In `handleAgentChat`, after loading the session, call `resolveRelevantPages` and `buildSessionSystemPrompt` to produce a context-enriched system prompt. Use this as the first message in the LLM call.
- REFACTOR: Only rebuild the system prompt if the session context has changed (optimization -- defer to later).

**Test expectations:**
- Session with `focus: "deploying API"` produces a system prompt containing "deploying API"
- Session with `relevant_pages: ["Skills/api"]` triggers page resolution and includes page content in prompt
- Session with `working_memory` entries includes them in the prompt
- System prompt is stored as a system message in the session (or regenerated each time -- decide here: regenerate each time to keep fresh)
- When bridge is not connected, page resolution is skipped but other context is still included

**Files:**
- `server/tests/agent-chat-sessions.test.ts` (extended)
- `server/src/routes/api/agent-chat.ts` (modified)

**Commit:** `feat(session): enrich LLM system prompt with session context and page content`

---

### Task 3.3: Implement Configurable History Loading [x] [36e13f2]

**TDD Cycle:**
- RED: Write tests for loading the last N messages from a session, with N configurable (default 50).
- GREEN: In `handleAgentChat`, load messages via `store.getMessages(sessionId, { limit: config.sessionMessageLimit || 50 })`. The system prompt is generated fresh each time (not loaded from history). Past messages are loaded from SQLite in order.
- REFACTOR: Add `sessionMessageLimit` to `Config` interface and `loadConfig()`.

**Test expectations:**
- A session with 100 messages loads only the most recent 50 by default
- The loaded messages are in chronological order (ASC by id)
- The system message is NOT loaded from history -- it is regenerated from current session context
- Config env var `SESSION_MESSAGE_LIMIT` overrides the default

**Files:**
- `server/tests/agent-chat-sessions.test.ts` (extended)
- `server/src/routes/api/agent-chat.ts` (modified)
- `server/src/config.ts` (modified -- add `sessionMessageLimit`)

**Commit:** `feat(session): implement configurable message history loading for agent chat`

---

### Task 3.4: Store Tool Calls in Session Messages [x] [1345ee1]

**TDD Cycle:**
- RED: Write tests verifying that tool calls and tool results are stored as session messages with correct metadata.
- GREEN: In the tool call loop of `handleAgentChat`, store each assistant message (with tool_calls) and each tool result message via `store.addMessage()`. Include `tool_calls` JSON and `tool_call_id`.
- REFACTOR: Ensure the message format is compatible with the LLM API format (role, content, tool_calls, tool_call_id).

**Test expectations:**
- Assistant message with tool calls is stored with `role: "assistant"`, `tool_calls: [...]`
- Tool result message is stored with `role: "tool"`, `tool_call_id: "..."`, `content: "..."`
- After a multi-tool interaction, all messages are persisted in the session
- Reloading the session and continuing the conversation works correctly (messages are in the right format for the LLM)

**Files:**
- `server/tests/agent-chat-sessions.test.ts` (extended)
- `server/src/routes/api/agent-chat.ts` (modified)

**Commit:** `feat(session): persist tool calls and results in session messages`

---

### Task 3.5: Wire SessionStore into Router and Index [x] [1bb921d]

**TDD Cycle:**
- RED: Verify that the router creates the route with `SessionStore` instead of `ConversationStore`.
- GREEN: Update `RouteContext` to add `sessionStore?: SessionStore` (alongside `conversations` for backward compatibility). Update `server/src/index.ts` to create `SessionStore` with the DB instance. Update the agent chat route handler to pass `sessionStore`. Update `createRouter` to accept and use `sessionStore`.
- REFACTOR: Remove `ConversationStore` import and usage from `index.ts` once the migration is complete.

**Implementation details:**
- `SessionStore` needs the `Database` instance (already available as `db`)
- Create `const sessionStore = new SessionStore(db)` in `index.ts`
- Add `sessionStore` to `RouteContext` and pass it when creating the router
- Update the agent chat route handler to use `sessionStore` instead of `conversations`
- Keep `ConversationStore` alive temporarily for backward compat (remove in Phase 3.6)

**Files:**
- `server/src/router.ts` (modified)
- `server/src/index.ts` (modified)
- `server/src/routes/api/agent-chat.ts` (modified -- accept sessionStore parameter)

**Commit:** `feat(session): wire SessionStore into router and server index`

---

### Task 3.6: Remove Legacy ConversationStore [x] [1bb921d]

**TDD Cycle:**
- RED: Verify that all references to `ConversationStore` are removed and tests still pass.
- GREEN: Remove `ConversationStore` from `RouteContext`, `index.ts`, and `router.ts`. Remove `conversations` field from `RouteContext`. Update `handleAgentChat` to only accept `SessionStore`. Clean up imports.
- REFACTOR: Remove `server/src/services/conversations.ts` entirely (or mark as deprecated with a comment -- prefer removal).

**Test expectations:**
- All existing agent chat tests pass with `SessionStore`
- No imports of `ConversationStore` remain in production code
- The `conversations` field is removed from `RouteContext`
- `server/src/services/conversations.ts` is deleted or has a deprecation notice

**Files:**
- `server/src/router.ts` (modified)
- `server/src/index.ts` (modified)
- `server/src/routes/api/agent-chat.ts` (modified)
- `server/src/services/conversations.ts` (deleted or deprecated)

**Commit:** `refactor(session): remove legacy ConversationStore in favor of SessionStore`

---

### Verification 3

- [ ] All `server/tests/agent-chat-sessions.test.ts` tests pass
- [ ] `POST /api/agent/chat` with no sessionId creates a new session and returns sessionId
- [ ] `POST /api/agent/chat` with a valid sessionId continues an existing session
- [ ] Session context is included in the LLM system prompt
- [ ] Messages are persisted in SQLite across "restarts" (re-load from DB)
- [ ] Tool calls and results are stored in session messages
- [ ] No regressions in other tests (`cd server && bun test`)
- [ ] `ConversationStore` is removed from production code

[checkpoint marker]

---

## Phase 4: Session MCP Tools

**Goal:** Expose 7 MCP tools for session management so that Claude Code (via MCP) can create, list, update, and archive sessions, as well as set focus and manage working memory.

### Task 4.1: Implement `session_create` MCP Tool [x] [80c8b9c]

**TDD Cycle:**
- RED: Write tests for the `session_create` tool registration and handler.
- GREEN: Create `server/src/services/mcp/session-tools.ts` with `registerSessionTools(server, getContext)`. Implement `session_create` tool: accepts `{ name?: string, context?: object }`, creates a session via `SessionStore`, returns the created session.
- REFACTOR: Follow the pattern from `memory-tools.ts` for tool registration.

**Tool schema:**
- `name` (optional string): Human-readable session name
- `context` (optional object): Initial session context

**Test expectations:**
- Tool `session_create` is registered on the MCP server
- Calling with no params creates a session with auto-generated ID
- Calling with `{ name: "API Refactor" }` creates a named session
- Calling with `{ context: { focus: "testing" } }` creates a session with initial context
- Returns the full session object including ID, status, timestamps

**Files:**
- `server/tests/session-tools.test.ts` (new)
- `server/src/services/mcp/session-tools.ts` (new)

**Commit:** `feat(session): implement session_create MCP tool`

---

### Task 4.2: Implement `session_get` and `session_list` MCP Tools [x] [80c8b9c]

**TDD Cycle:**
- RED: Write tests for `session_get` and `session_list` tool handlers.
- GREEN: Implement both tools.
  - `session_get`: accepts `{ sessionId: string, messageLimit?: number }`, returns session details plus recent messages.
  - `session_list`: accepts `{ status?: string, limit?: number }`, returns list of sessions for the current agent.
- REFACTOR: Default agent_id to "claude-code" for MCP tool calls (extracted from context or defaulted).

**Test expectations:**
- `session_get` with valid ID returns session + messages
- `session_get` with invalid ID returns error content
- `session_get` with `messageLimit: 5` returns only 5 messages
- `session_list` returns active sessions by default
- `session_list` with `status: "archived"` returns archived sessions
- `session_list` with `limit: 3` limits results
- `session_list` excludes archived sessions by default

**Files:**
- `server/tests/session-tools.test.ts` (extended)
- `server/src/services/mcp/session-tools.ts` (extended)

**Commit:** `feat(session): implement session_get and session_list MCP tools`

---

### Task 4.3: Implement `session_update_context` MCP Tool [x] [80c8b9c]

**TDD Cycle:**
- RED: Write tests for the `session_update_context` tool that does a merge (not replace) of the context object.
- GREEN: Implement the tool. It loads the session, calls `mergeSessionContext(existing, updates)`, then saves the merged result via `store.update(id, { context: merged })`. Returns the updated session.
- REFACTOR: Validate that `sessionId` exists before attempting the merge.

**Test expectations:**
- Updating context merges with existing context (does not replace)
- Setting `focus` via context update works
- Adding `relevant_pages` unions with existing pages
- Adding `working_memory` entries merges by key
- Invalid sessionId returns error
- Empty context update is a no-op (returns current session)

**Files:**
- `server/tests/session-tools.test.ts` (extended)
- `server/src/services/mcp/session-tools.ts` (extended)

**Commit:** `feat(session): implement session_update_context MCP tool with merge semantics`

---

### Task 4.4: Implement `session_set_focus` and `session_add_memory` MCP Tools [x] [80c8b9c]

**TDD Cycle:**
- RED: Write tests for convenience tools `session_set_focus` and `session_add_memory`.
- GREEN: Implement both as shortcuts.
  - `session_set_focus`: accepts `{ sessionId: string, focus: string }`, updates only the `focus` field in context.
  - `session_add_memory`: accepts `{ sessionId: string, key: string, value: string }`, adds/updates a key in working_memory.
- REFACTOR: Both should delegate to the context merge and working memory helpers.

**Test expectations:**
- `session_set_focus` updates only the focus, leaving other context unchanged
- `session_set_focus` with empty string clears the focus
- `session_add_memory` adds a new key-value pair
- `session_add_memory` updates an existing key's value
- Both return the updated session
- Both return error for invalid sessionId

**Files:**
- `server/tests/session-tools.test.ts` (extended)
- `server/src/services/mcp/session-tools.ts` (extended)

**Commit:** `feat(session): implement session_set_focus and session_add_memory MCP tools`

---

### Task 4.5: Implement `session_archive` MCP Tool [x] [80c8b9c]

**TDD Cycle:**
- RED: Write tests for `session_archive` tool.
- GREEN: Implement `session_archive` that sets the session status to "archived". Returns confirmation with session ID.
- REFACTOR: Validate session exists and is not already archived.

**Test expectations:**
- Archiving an active session sets status to "archived"
- Archiving an already-archived session returns an error or idempotent success
- Archived session no longer appears in `session_list` default results
- Archived session is still accessible via `session_get`
- Invalid sessionId returns error

**Files:**
- `server/tests/session-tools.test.ts` (extended)
- `server/src/services/mcp/session-tools.ts` (extended)

**Commit:** `feat(session): implement session_archive MCP tool`

---

### Task 4.6: Register Session Tools in MCP Server [x] [c00dff6]

**TDD Cycle:**
- RED: Verify `registerAllMcpHandlers` includes session tools.
- GREEN: Add `registerSessionTools(server, getContext)` call to `server/src/services/mcp/index.ts`. Add `sessionStore?: SessionStore` to `McpToolContext` in `server/src/types/mcp.ts`. Pass `sessionStore` in the context factory in `server/src/index.ts`.
- REFACTOR: Group session tools with a comment block.

**Files:**
- `server/src/services/mcp/index.ts` (modified)
- `server/src/types/mcp.ts` (modified)
- `server/src/index.ts` (modified)

**Commit:** `feat(session): register session tools in MCP server and wire context`

---

### Verification 4

- [ ] All `server/tests/session-tools.test.ts` tests pass
- [ ] All 7 session tools are registered (`session_create`, `session_get`, `session_list`, `session_update_context`, `session_set_focus`, `session_add_memory`, `session_archive`)
- [ ] Context merge semantics are correct (merge, not replace)
- [ ] Working memory and relevant pages respect caps (20 and 10)
- [ ] All tools validate sessionId and return proper errors
- [ ] Full `bun test` passes (all server tests)

[checkpoint marker]

---

## Phase 5: Session Handoff Between Interfaces

**Goal:** Enable a session started via agent chat to be continued via MCP tool calls and vice versa. The same `sessionId` works across both interfaces. MCP tool calls can be associated with a session via header or parameter.

### Task 5.1: Extract Session ID from MCP Requests [x] [b233870]

**TDD Cycle:**
- RED: Write tests for a helper that extracts a session ID from MCP tool call arguments or request headers.
- GREEN: Implement `extractSessionId(args, headers?)` in `server/src/services/session-context.ts` (or a new file). It checks: (1) `_sessionId` field in tool call arguments, (2) `X-Session-Id` header on the HTTP request if available, (3) auto-association if only one active session exists for the agent.
- REFACTOR: Prioritize explicit `_sessionId` over header over auto-association.

**Test expectations:**
- `_sessionId` in tool args takes priority
- `X-Session-Id` header is used if `_sessionId` is absent
- Auto-association: if agent has exactly one active session, use it
- Auto-association: if agent has 0 or 2+ active sessions, return null
- Returns null if no session can be determined

**Files:**
- `server/tests/session-handoff.test.ts` (new)
- `server/src/services/session-context.ts` (extended)

**Commit:** `feat(session): implement session ID extraction from MCP requests`

---

### Task 5.2: Log MCP Tool Calls to Session History [x] [01a93dc]

**TDD Cycle:**
- RED: Write tests verifying that when a session ID is associated with an MCP tool call, the tool call and its result are logged to the session's message history.
- GREEN: Add a wrapper or middleware in the MCP tool execution path that checks for a session ID. If present, log a message with `role: "tool"` containing the tool name, args, and result. Use `store.addMessage()`.
- REFACTOR: The logged message should be concise -- tool name, input summary, output summary.

**Test expectations:**
- MCP tool call with `_sessionId` logs a tool message to the session
- The logged message includes tool name, input parameters, and result summary
- Tool calls without a session ID are not logged to any session
- Multiple tool calls in sequence are all logged to the same session
- Session's `last_active_at` is updated when tool calls are logged

**Files:**
- `server/tests/session-handoff.test.ts` (extended)
- `server/src/services/mcp/session-tools.ts` (extended -- add logging helper)
- `server/src/services/session-context.ts` (extended if needed)

**Commit:** `feat(session): log MCP tool calls to session message history`

---

### Task 5.3: Implement Cross-Interface Session Continuity Test [x] [628149c]

**TDD Cycle:**
- RED: Write an integration test that: (1) creates a session via `session_create` MCP tool, (2) sends a message via `POST /api/agent/chat` with that sessionId, (3) verifies the agent chat loaded the session context, (4) makes an MCP tool call with `_sessionId`, (5) verifies the tool call is logged in the session, (6) sends another agent chat message and verifies the tool call is visible in history.
- GREEN: Ensure all the wiring from previous tasks makes this test pass. Fix any gaps.
- REFACTOR: This is primarily a verification test -- no new production code expected.

**Test expectations:**
- Session created via MCP can be used in agent chat
- Session created via agent chat can be referenced by MCP tools
- Message history is shared across both interfaces
- Context set via MCP tools is visible in agent chat system prompt

**Files:**
- `server/tests/session-handoff.test.ts` (extended)

**Commit:** `test(session): integration test for cross-interface session continuity`

---

### Verification 5

- [ ] All `server/tests/session-handoff.test.ts` tests pass
- [ ] Session ID extraction works for all three methods (arg, header, auto)
- [ ] MCP tool calls are logged to session history when session ID is present
- [ ] Cross-interface continuity works (MCP <-> agent chat)
- [ ] Full `bun test` passes

[checkpoint marker]

---

## Phase 6: Auto-Context Enrichment

**Goal:** Automatically enrich session context based on agent activity. When tools modify pages, create jobs, or trigger approvals, the session context is updated automatically.

### Task 6.1: Implement Auto-Context Event Handlers [x] [a1773ec]

**TDD Cycle:**
- RED: Write tests for `autoEnrichContext(sessionStore, sessionId, event)` that updates session context based on different event types.
- GREEN: Implement in `server/src/services/session-context.ts`. Event types:
  - `page_modified`: adds page name to `relevant_pages`
  - `job_created`: adds `{ key: "job:<jobId>", value: "<jobName> (created)" }` to `working_memory`
  - `approval_pending`: adds `{ key: "approval:<id>", value: "pending: <question>" }` to `working_memory`
  - `approval_resolved`: updates the working memory entry with result
- REFACTOR: Use the existing `addRelevantPage` and `addWorkingMemory` helpers.

**Test expectations:**
- `page_modified` event adds page to relevant_pages (respects cap of 10)
- `job_created` event adds job info to working_memory
- `approval_pending` event adds approval info to working_memory
- `approval_resolved` event updates the existing approval entry
- Multiple events of the same type are handled correctly
- Caps are respected (10 pages, 20 memory items)
- Auto-added items have `source: "auto"`

**Files:**
- `server/tests/session-auto-context.test.ts` (new)
- `server/src/services/session-context.ts` (extended)

**Commit:** `feat(session): implement auto-context enrichment event handlers`

---

### Task 6.2: Hook Auto-Context into Agent Chat Tool Execution [x] [a1773ec]

**TDD Cycle:**
- RED: Write tests verifying that when the agent chat executes a tool that modifies a page (e.g., `page_create`, `block_append`), the session's relevant_pages is updated.
- GREEN: After each tool execution in `handleAgentChat`, check if the operation modifies a page (`page_create`, `page_update`, `block_append`, `block_update`) and call `autoEnrichContext` with the appropriate event. Extract the page name from tool arguments.
- REFACTOR: Map operation names to event types in a lookup table.

**Operations that trigger auto-context:**
- `page_create` / `page_read` -> `page_modified` with page name
- `block_append` -> `page_modified` with page name from `page` param
- `block_update` -> no auto-context (block UUID doesn't easily map to a page)
- `create_job` -> `job_created` with job ID and name
- The approval auto-context is already handled by the approval store SSE events (if wired)

**Test expectations:**
- `page_create` tool call adds the created page to `relevant_pages`
- `block_append` tool call adds the target page to `relevant_pages`
- `create_job` tool call adds job info to `working_memory`
- Non-modifying operations (e.g., `graph_search`) do not trigger auto-context
- Auto-context does not throw errors even if the session is missing

**Files:**
- `server/tests/session-auto-context.test.ts` (extended)
- `server/src/routes/api/agent-chat.ts` (modified)

**Commit:** `feat(session): hook auto-context into agent chat tool execution`

---

### Task 6.3: Hook Auto-Context into MCP Tool Execution [x] [a1773ec]

**TDD Cycle:**
- RED: Write tests verifying that MCP tool calls with a session ID trigger auto-context enrichment.
- GREEN: In the MCP tool logging wrapper (from Phase 5), add auto-context enrichment calls based on the tool name and arguments. Reuse the same `autoEnrichContext` function and operation-to-event mapping.
- REFACTOR: Centralize the operation-to-event mapping so it is shared between agent chat and MCP paths.

**Test expectations:**
- MCP `graph_page_create` (or equivalent operation) with `_sessionId` adds page to session context
- MCP `job_create` with `_sessionId` adds job info to session working memory
- MCP tool calls without `_sessionId` do not trigger auto-context
- Auto-context enrichment does not affect tool execution (fire-and-forget pattern)

**Files:**
- `server/tests/session-auto-context.test.ts` (extended)
- `server/src/services/mcp/session-tools.ts` (extended)

**Commit:** `feat(session): hook auto-context into MCP tool execution path`

---

### Task 6.4: Implement History Summarization [x] [9c4bf15]

**TDD Cycle:**
- RED: Write tests for `summarizeOldMessages(messages, config)` that takes an array of old messages and produces a summary string via an LLM call.
- GREEN: Implement in `server/src/services/session-context.ts`. When loading messages for a session with more than the configured limit (e.g., 50), the oldest messages beyond the limit are passed to a summarization LLM call. The summary is returned as a single system message prepended to the recent messages. Use a cheaper model (configurable, default `anthropic/claude-haiku-4-5-20251001`).
- REFACTOR: Add `summarizationModel` to `Config`. The summarization prompt should be concise: "Summarize the following conversation history in 2-3 paragraphs, preserving key decisions, actions taken, and important context."

**Test expectations:**
- Messages array with <= 50 items returns no summary (pass-through)
- Messages array with > 50 items triggers a summarization LLM call with the older messages
- The summarization call uses the configured cheaper model
- The returned summary is a string suitable for a system message
- If the LLM call fails, returns a fallback like "[Earlier conversation history unavailable]"
- The summarization prompt includes the old messages in a readable format

**Files:**
- `server/tests/session-auto-context.test.ts` (extended)
- `server/src/services/session-context.ts` (extended)
- `server/src/config.ts` (modified -- add `summarizationModel`)

**Commit:** `feat(session): implement history summarization for large sessions`

---

### Task 6.5: Integrate History Summarization into Agent Chat [x] [a1773ec]

**TDD Cycle:**
- RED: Write tests verifying that agent chat with a long session (>50 messages) uses summarization.
- GREEN: In `handleAgentChat`, when loading messages, check total count. If > limit, load all messages, split into old (beyond limit) and recent (within limit), summarize old messages, prepend summary as a system message before recent messages.
- REFACTOR: Cache the summary in the session context (e.g., `context.history_summary`) to avoid re-summarizing on every request. Only re-summarize when the message count has grown significantly since the last summary.

**Test expectations:**
- Session with 30 messages does not trigger summarization
- Session with 80 messages triggers summarization of the oldest 30 messages
- The summary is prepended as a system message before the 50 most recent messages
- Subsequent requests reuse the cached summary if message count has not grown significantly
- The system prompt includes both the session context and the history summary

**Files:**
- `server/tests/session-auto-context.test.ts` (extended)
- `server/src/routes/api/agent-chat.ts` (modified)

**Commit:** `feat(session): integrate history summarization into agent chat flow`

---

### Verification 6

- [ ] All `server/tests/session-auto-context.test.ts` tests pass
- [ ] Auto-context enrichment works for page modifications, job creation, approvals
- [ ] Caps are respected (10 pages, 20 working memory items)
- [ ] Auto-context items are tagged with `source: "auto"`
- [ ] History summarization triggers for large sessions (>50 messages)
- [ ] Summarization uses a cheaper model and caches results
- [ ] Full `bun test` passes (all server tests)
- [ ] No regressions in any other test suite

[checkpoint marker]

---

## Summary

| Phase | Tasks | Focus | Priority |
|-------|-------|-------|----------|
| 1 | 7 | SQLite schema and SessionStore core | P0 |
| 2 | 5 | Session context management and prompt building | P0 |
| 3 | 6 | Session-aware agent chat (replaces ConversationStore) | P0 |
| 4 | 6 | Session MCP tools (7 tools) | P1 |
| 5 | 3 | Session handoff between agent chat and MCP | P2 |
| 6 | 5 | Auto-context enrichment and history summarization | P2 |

**Total: 32 tasks across 6 phases**

### Dependency Chain

```
Phase 1 (SQLite + SessionStore) --> Phase 2 (Context Management)
                                --> Phase 3 (Session-Aware Agent Chat)
                                --> Phase 4 (MCP Tools)

Phase 2 + Phase 3 --> Phase 5 (Session Handoff)
Phase 2 + Phase 3 + Phase 4 --> Phase 6 (Auto-Context + Summarization)
```

Phases 2, 3, and 4 all depend on Phase 1 but are largely independent of each other. Phase 5 requires both Phase 3 (agent chat) and Phase 4 (MCP tools) to be complete. Phase 6 requires all previous phases.

### Config Changes

New environment variables introduced:
- `SESSION_MESSAGE_LIMIT` (default: 50) -- max messages loaded per session
- `SESSION_SUMMARIZATION_MODEL` (default: `anthropic/claude-haiku-4-5-20251001`) -- cheaper model for summarization
- `SESSION_AUTO_ARCHIVE_DAYS` (default: 7) -- days of inactivity before auto-archive (deferred to future implementation)
