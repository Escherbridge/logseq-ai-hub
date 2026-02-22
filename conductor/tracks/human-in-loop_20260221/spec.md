# Specification: Human-in-the-Loop Approval Flow

## Overview

Implement a human-in-the-loop (HITL) approval system that allows automated agents (Claude Code via MCP, job runner skills, or the agent chat API) to pause execution, send a question or approval request to a human via WhatsApp or Telegram, wait for the human's response, and resume execution with the response as input.

This is the "ask for permission to keep coding" capability — the bridge between autonomous agent execution and human oversight, delivered through the messaging channels the user is already connected to.

## Background

The system already has all the building blocks:
- **Outbound messaging**: `POST /api/send` sends messages to WhatsApp/Telegram contacts.
- **Inbound webhooks**: Messages arrive via `POST /webhook/whatsapp` and `POST /webhook/telegram`, are stored in SQLite, and broadcast via SSE.
- **Job runner**: Executes skill steps sequentially, supports conditional logic and context passing between steps.
- **Agent Bridge**: Proxies operations between server and plugin.
- **MCP server** (from mcp-server track): Claude Code can invoke tools.

What's missing is the **glue**: a way to send a message, register a pending approval, correlate the incoming webhook reply to that pending approval, and resolve it — all as an atomic operation that an MCP tool call or job runner step can `await`.

### Architecture

```
Claude Code ──MCP──▶ Tool: ask_human ──▶ Server ──▶ WhatsApp/Telegram
                                              │
                                              ├── Stores pending approval (UUID, timeout)
                                              │
                         ◀── webhook ─────────┤
                                              │
                    Correlates reply to pending approval
                                              │
                         ◀── MCP response ────┘
```

## Dependencies

- **mcp-server_20260221**: The MCP server must exist to expose `ask_human` as an MCP tool.
- **webhook-agent-api_20260219**: REST API, Agent Bridge, messaging infrastructure.

## Functional Requirements

### FR-1: Approval Request Store

**Description:** An in-memory (with optional SQLite persistence) store for pending approval requests. Each approval has a unique ID, target contact, question text, timeout, status, and response.

**Acceptance Criteria:**
- Create an approval request with: `{id, contactId, question, options?, timeout, createdAt, status: "pending"}`.
- `options` is an optional array of allowed responses (e.g., `["approve", "reject"]`). If provided, only matching responses resolve the approval. If omitted, any response resolves it.
- Approval requests are stored in-memory with a Map keyed by contact ID → list of pending approvals (FIFO).
- When a webhook message arrives from a contact with pending approvals, the oldest pending approval for that contact is resolved with the message content.
- Timed-out approvals are automatically rejected with `{status: "timeout", response: null}`.
- Resolved approvals are removed from the pending map.
- Maximum pending approvals per contact: 5 (configurable). Additional requests return an error.
- Approval state is accessible via `GET /api/approvals` for monitoring.

**Priority:** P0

### FR-2: MCP Tool — `ask_human`

**Description:** An MCP tool that sends a question to a human contact via messaging and waits for their reply.

**Tool Definition:**
```json
{
  "name": "ask_human",
  "description": "Send a question to a human via WhatsApp or Telegram and wait for their reply. Use this when you need human approval, input, or a decision before proceeding.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "contact": {"type": "string", "description": "Contact identifier (e.g., 'whatsapp:15551234567' or contact display name)"},
      "question": {"type": "string", "description": "The question or approval request to send"},
      "options": {"type": "array", "items": {"type": "string"}, "description": "Optional: specific allowed responses. If provided, only these responses will be accepted."},
      "timeout_seconds": {"type": "number", "default": 300, "description": "How long to wait for a response (default: 5 minutes, max: 3600)"}
    },
    "required": ["contact", "question"]
  }
}
```

**Acceptance Criteria:**
- The tool resolves the contact identifier (lookup by name in contacts DB if not in `platform:id` format).
- Sends the question as a message via the appropriate platform.
- If `options` are provided, appends them to the message (e.g., "Reply with: approve, reject").
- Creates a pending approval and waits (async/Promise) for the response.
- Returns `{status: "approved", response: "<human's reply>"}` on success.
- Returns `{status: "timeout", response: null}` on timeout.
- Returns an error if the contact is unknown or messaging fails.
- The MCP tool call blocks (from the client's perspective) until resolved or timed out.

**Priority:** P0

### FR-3: Webhook-to-Approval Correlation

**Description:** When an incoming webhook message arrives, check if the sender has any pending approval requests and resolve the oldest one.

**Acceptance Criteria:**
- After storing the incoming message in the normal flow (SQLite, SSE broadcast), check the approval store for pending approvals for this contact.
- If a pending approval exists:
  - If `options` were specified, check if the reply matches one of them (case-insensitive, trimmed). If not, send a follow-up message: "Please reply with one of: {options}". Do NOT resolve the approval.
  - If no `options` or reply matches, resolve the approval with `{status: "approved", response: messageContent}`.
- The normal message flow (SSE broadcast, Logseq ingestion) still happens regardless of whether an approval is resolved.
- If multiple approvals are pending for the same contact, resolve the oldest (FIFO).

**Priority:** P0

### FR-4: Job Runner Step — `ask-human`

**Description:** A new executor action type for the job runner that pauses skill execution, sends a question to a human, and resumes with the response.

**Acceptance Criteria:**
- New action type `:ask-human` registered in the executor.
- Step config: `{contact, question, options?, timeout?}`.
- The step sends the question via the server's messaging API and waits for the approval response.
- The step result contains the human's reply, which is available to subsequent steps via `{{step-N-result}}`.
- Timeout behavior is configurable per step (default: 5 minutes).
- On timeout, the step can be configured to either fail the job or continue with a default value.

**Priority:** P1

### FR-5: REST API — Approval Management

**Description:** REST endpoints for monitoring and manually managing approval requests.

**Endpoints:**

| Method | Path | Description |
|---|---|---|
| `GET /api/approvals` | List all pending approvals | Returns `{approvals: [{id, contactId, question, options, createdAt, timeout}]}` |
| `POST /api/approvals/:id/resolve` | Manually resolve an approval | Accepts `{response: string}` — for admin/override use |
| `DELETE /api/approvals/:id` | Cancel a pending approval | Removes without resolving (approval Promise rejects) |

**Acceptance Criteria:**
- All endpoints require Bearer auth.
- Manual resolution works the same as webhook resolution.
- Cancelled approvals cause the waiting tool call/job step to receive an error.

**Priority:** P2

### FR-6: Approval Notifications via SSE

**Description:** Broadcast SSE events when approvals are created, resolved, or timeout.

**Event Types:**
- `approval_created` — `{approvalId, contactId, question, timeout}`
- `approval_resolved` — `{approvalId, contactId, response, resolvedBy: "webhook" | "manual"}`
- `approval_timeout` — `{approvalId, contactId}`

**Acceptance Criteria:**
- Events are broadcast to all SSE clients.
- The Logseq plugin can optionally display notifications for approval events.
- The SSEEvent type union is extended.

**Priority:** P2

## Non-Functional Requirements

### NFR-1: Reliability

- Approval requests must survive server memory pressure (cap total pending at 100).
- Timeout cleanup must be deterministic (no orphaned approvals).
- If the server restarts, in-memory approvals are lost. Future: SQLite persistence for critical approvals.

### NFR-2: Security

- Only authenticated clients can create approval requests (MCP tool calls require auth).
- Webhook messages are not authenticated (they come from WhatsApp/Telegram APIs), but approval correlation uses the contact ID from the verified webhook payload.
- Rate limit: max 5 pending approvals per contact to prevent spam.

### NFR-3: User Experience

- Messages sent to humans should be clear and actionable: "Claude Code is asking: {question}. Reply with: {options}"
- If options are provided and the user replies with something else, a helpful follow-up is sent.
- Timeout duration should be visible in the message: "Please reply within 5 minutes."

### NFR-4: Testability

- Approval store has unit tests for create, resolve, timeout, and FIFO ordering.
- Webhook correlation has integration tests with mock webhook payloads.
- MCP tool has end-to-end test: tool call → message sent → mock webhook reply → tool returns.
- Job runner `:ask-human` step has tests similar to other executor action types.

## User Stories

### US-1: Claude Code asks for deployment approval

**As** a developer using Claude Code,
**I want** Claude Code to send me a WhatsApp message asking "Deploy to production? (approve/reject)",
**So that** I can approve or reject from my phone while Claude Code waits for my response.

### US-2: Job runner requests human input

**As** a user with a scheduled summarization job,
**I want** the job to ask me "Should I email this summary to the team?" via Telegram before sending,
**So that** I maintain control over automated actions that affect others.

### US-3: Admin monitors pending approvals

**As** a system administrator,
**I want** to see all pending approval requests via the REST API,
**So that** I can identify stuck workflows and manually resolve them if needed.

## Technical Considerations

### Promise-Based Blocking

The `ask_human` MCP tool call needs to block until the approval is resolved. This is implemented as:
1. Create approval → store a `{resolve, reject}` Promise pair in the approval map
2. Send the message
3. Return the Promise (the MCP handler awaits it)
4. When webhook arrives → look up the approval → call `resolve(response)`
5. Timeout → call `reject(timeoutError)`

This is the same pattern used by the Agent Bridge (`sendRequest` → `resolveRequest`). The approval store can potentially reuse the same infrastructure.

### Contact Resolution

The `contact` parameter in `ask_human` should support both:
- Exact ID: `whatsapp:15551234567`
- Display name: `"John Doe"` — resolved via the contacts table (`SELECT id FROM contacts WHERE display_name = ?`)

If multiple contacts match a display name, return an error asking for the specific ID.

### Message Formatting

The outgoing message should be formatted for readability on mobile:

```
🤖 Automated Request

{question}

{if options: "Reply with one of: " + options.join(", ")}
{if timeout: "⏱ Please reply within {timeout} minutes"}
```

### Webhook Processing Order

The approval check must happen AFTER the message is stored in SQLite and BEFORE (or in parallel with) the SSE broadcast. This ensures the message is never lost even if approval processing fails.

## Out of Scope

- Multi-step approval chains (approval A must complete before approval B is created) — can be built with job runner conditional steps.
- Approval escalation (if person A doesn't respond, ask person B) — future feature.
- Rich message formats (buttons, quick replies) — WhatsApp/Telegram support these but they require platform-specific handling.
- Approval persistence to SQLite — in-memory only for v1.
- Group chat approvals — 1:1 conversations only for v1.
- Approval audit log — future feature.

## Open Questions

1. **Concurrent approvals to same contact:** If two different agents ask the same person a question simultaneously, should the replies be ordered FIFO (oldest approval gets the next reply) or should each message include a reference ID? Recommendation: FIFO for v1 with a note in the message that there are N pending questions.

2. **Approval via plugin UI:** Should the Logseq plugin also show a notification/dialog for pending approvals, allowing the user to respond from the desktop? Recommendation: yes as a P2 enhancement — display a Logseq notification with approve/reject buttons that call the manual resolve API.

3. **Smart contact matching:** Should `ask_human` accept partial names or fuzzy matching? Recommendation: exact match for v1 to avoid accidental messages to the wrong person.
