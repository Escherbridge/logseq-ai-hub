# API Reference

All API routes (except webhooks and health) require authentication. Every request is assigned a correlation ID (`X-Trace-Id` header) for end-to-end tracing.

## Authentication

- **REST API**: `Authorization: Bearer <PLUGIN_API_TOKEN>` header
- **SSE**: `?token=<PLUGIN_API_TOKEN>` query parameter (EventSource doesn't support custom headers)

## Health & Events

### `GET /health`
Returns server status. No auth required.
```json
{
  "status": "ok",
  "uptime": 123,
  "sseClients": 1,
  "agentApi": {
    "enabled": true,
    "pluginConnected": true,
    "pendingRequests": 0
  }
}
```

### `GET /events`
SSE stream. Requires `?token=` query param.

**Events emitted:**
| Event | Description |
|-------|------------|
| `connected` | Initial connection acknowledgement |
| `new_message` | Incoming message via webhook |
| `message_sent` | Outgoing message sent via API |
| `agent_request` | Operation request for the plugin |
| `job_created` | Job page created |
| `job_started` | Job execution began |
| `job_completed` | Job finished successfully |
| `job_failed` | Job execution failed |
| `job_cancelled` | Job was cancelled |
| `skill_created` | Skill page created |
| `heartbeat` | Keep-alive (every 30 seconds) |

## Webhook Endpoints

### `GET /webhook/whatsapp`
WhatsApp webhook verification (challenge-response). No auth.

### `POST /webhook/whatsapp`
WhatsApp message ingestion. Parses Cloud API payload, upserts contact, stores message (deduplicates by `external_id`), broadcasts SSE event.

### `POST /webhook/telegram`
Telegram update handling. Parses Update object, extracts sender and message text, stores and broadcasts.

## Messaging API

### `POST /api/send`
Send an outgoing message. Bearer auth required.
```json
{
  "platform": "whatsapp",
  "recipient": "15551234567",
  "content": "Hello!"
}
```

### `GET /api/messages`
Query message history. Bearer auth required.

**Query params:**
| Param | Default | Description |
|-------|---------|------------|
| `contact_id` | — | Filter by contact (e.g. `whatsapp:15551234567`) |
| `limit` | 50 | Max results |
| `offset` | 0 | Pagination offset |
| `since` | — | ISO timestamp filter |

## Job Runner API

All job runner endpoints require Bearer auth. Operations that touch the Logseq graph are proxied to the plugin via the SSE bridge (30s timeout).

### `POST /api/jobs` — Create Job
```json
{
  "name": "daily-summary",
  "type": "scheduled",
  "skill": "Skills/summarize",
  "schedule": "0 9 * * *",
  "priority": 2,
  "input": {"query": "today"}
}
```
Returns `201` with `{success: true, data: {jobId, name, status}}`.

Valid types: `autonomous`, `manual`, `scheduled`, `event-driven`.

### `GET /api/jobs` — List Jobs
**Query params:** `status` (filter), `limit` (default 50), `offset` (default 0).

### `GET /api/jobs/:id` — Get Job Details
Returns full job details including step results, timestamps, and error info.

### `PUT /api/jobs/:id/start` — Start Job
Enqueues the job for execution.

### `PUT /api/jobs/:id/cancel` — Cancel Job

### `PUT /api/jobs/:id/pause` — Pause Job

### `PUT /api/jobs/:id/resume` — Resume Job

## Skills API

### `GET /api/skills` — List Skills
Returns all skill definitions with name, type, description, inputs, and outputs.

### `GET /api/skills/:id` — Get Skill Details
Returns full skill definition including all step configurations.

### `POST /api/skills` — Create Skill
```json
{
  "name": "summarize",
  "type": "llm-chain",
  "description": "Summarizes text",
  "inputs": ["query"],
  "outputs": ["summary"],
  "steps": [
    {
      "order": 1,
      "action": "llm-call",
      "promptTemplate": "Summarize: {{query}}"
    }
  ]
}
```

## MCP API

### `GET /api/mcp/servers` — List MCP Servers
Returns connected MCP servers with status.

### `GET /api/mcp/servers/:id/tools` — List Server Tools
Returns tools from the specified MCP server.

### `GET /api/mcp/servers/:id/resources` — List Server Resources

## Agent Chat API

### `POST /api/agent/chat` — Chat with Agent
```json
{
  "message": "Create a job that summarizes my notes every morning at 9am",
  "conversationId": "optional-uuid"
}
```
Returns:
```json
{
  "success": true,
  "data": {
    "conversationId": "uuid",
    "response": "I've created a scheduled job...",
    "actions": [...]
  }
}
```
The agent uses an LLM (via OpenRouter) to interpret natural language and execute job runner operations through tool calling (up to 5 rounds).

### `POST /api/agent/callback` — Plugin Callback
Used internally by the plugin to report operation results back to the server. Not called by external clients.
