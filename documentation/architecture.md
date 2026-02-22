# Architecture

Logseq AI Hub is a two-part system: a **ClojureScript plugin** running inside the Logseq desktop app and a **Bun/TypeScript server** that handles external integrations.

## System Overview

```
External World                    Bun Server (Railway)              Logseq Plugin (Browser)
--------------                    --------------------              -----------------------
WhatsApp Cloud API ─── webhook ─▶ SQLite (contacts, msgs)   SSE ──▶ messaging.cljs
Telegram Bot API   ─── webhook ─▶ SSE event bus            ◀── HTTP  agent_bridge.cljs
Claude Code        ─── REST/MCP ─▶ Agent Bridge              ─────▶  job_runner/
External MCP       ◀──────────────────────────────────────────── MCP   mcp/client.cljs
OpenRouter LLM     ◀──────────────────────────────────────────── HTTP  agent.cljs
```

## The Agent Bridge (Architectural Keystone)

The Logseq graph lives inside the browser plugin context — there is no external API to query or modify it directly. The **Agent Bridge** solves this with an SSE request-response pattern:

1. **Server sends `agent_request`** over SSE to the plugin, carrying a `requestId`, `operation`, `params`, and `traceId`
2. **Plugin receives the event** in `agent_bridge.cljs`, dispatches to the appropriate handler (jobs, skills, MCP, graph ops)
3. **Plugin executes** the operation against the Logseq graph API
4. **Plugin POSTs the result** back to `/api/agent/callback` with the `requestId` for correlation

This pattern has a 30-second timeout. All graph-touching operations from external clients flow through this bridge.

## Plugin Architecture

The plugin is organized as independent modules initialized in order by `core.cljs`:

```
core.cljs (entry point, settings, init)
├── agent.cljs           — LLM model registry + dispatch
├── messaging.cljs       — SSE client, message ingestion
├── memory.cljs          — AI memory store/retrieve system
├── tasks.cljs           — Task pipeline orchestration (legacy)
├── sub_agents.cljs      — Dynamic sub-agent system
├── agent_bridge.cljs    — Agent request dispatch + callback
├── settings_writer.cljs — Serialized settings write queue
├── util/errors.cljs     — Error handling utilities
└── job_runner/          — Autonomous job execution engine
    ├── schemas.cljs     — Property schemas + validation
    ├── parser.cljs      — Job/skill page parser
    ├── interpolation.cljs — Template {{variable}} engine
    ├── cron.cljs        — 5-field cron expression parser
    ├── queue.cljs       — Priority queue + dependency resolution
    ├── graph.cljs       — Logseq graph adapter + write queue
    ├── executor.cljs    — Step executor registry (11 action types)
    ├── engine.cljs      — Sequential execution engine + retries
    ├── runner.cljs      — Job lifecycle + polling loop
    ├── scheduler.cljs   — Cron-based job scheduling
    ├── openclaw.cljs    — OpenClaw skill import/export
    ├── commands.cljs    — 12 slash commands
    ├── init.cljs        — System initialization + wiring
    └── mcp/             — Model Context Protocol client
        ├── protocol.cljs  — JSON-RPC 2.0 codec
        ├── transport.cljs — HTTP + SSE transports
        └── client.cljs    — MCP server manager
```

### Key Design Patterns

**Dynamic Var Dependency Injection**: The executor and engine use `def ^:dynamic` vars (`*agent-process-input-fn*`, `*execute-skill-fn*`, `*call-mcp-tool-fn*`) to break circular dependencies and enable testing. Important: use `set!` instead of `with-redefs` for async code — `with-redefs` restores vars when the lexical scope exits, but Promise callbacks fire after.

**Executor Registry**: Step action types are registered in an atom map via `register-executor!`. Adding a new step type requires no modifications to the engine or runner.

**Serialized Write Queues**: Both graph writes (`graph.cljs`) and settings writes (`settings_writer.cljs`) use Promise-chain queues to prevent concurrent write corruption.

**Logseq-as-Database**: All state — jobs, skills, memories, agents, procedures — is stored as Logseq pages and blocks, making everything browsable, editable, and searchable within the PKM tool itself.

## Server Architecture

The Bun server handles external integrations and provides a REST API:

```
server/src/
├── index.ts             — Bun.serve() entry point
├── router.ts            — Route table with path param matching
├── config.ts            — Environment variable loading
├── types.ts             — Shared TypeScript interfaces
├── types/agent.ts       — Agent API type definitions
├── db/                  — SQLite data layer (contacts, messages)
├── middleware/auth.ts   — Bearer token authentication
├── helpers/responses.ts — Standardized response builders
├── router/match.ts      — Route pattern matching utility
├── validation/          — Request validation (jobs, skills)
├── routes/
│   ├── health.ts        — Health check (with agent status)
│   ├── events.ts        — SSE streaming
│   ├── api/
│   │   ├── send.ts          — Send outgoing messages
│   │   ├── messages.ts      — Query message history
│   │   ├── jobs.ts          — Job CRUD + lifecycle control
│   │   ├── skills.ts        — Skill CRUD
│   │   ├── mcp.ts           — MCP server/tool/resource queries
│   │   ├── agent-chat.ts    — LLM-powered agent chat
│   │   └── agent-callback.ts — Plugin callback endpoint
│   └── webhooks/        — WhatsApp + Telegram handlers
└── services/
    ├── sse.ts           — SSE event manager
    ├── agent-bridge.ts  — SSE request-response bridge
    ├── agent.ts         — Agent system prompt + tool schemas
    ├── conversations.ts — In-memory conversation store
    ├── llm.ts           — OpenRouter LLM client
    ├── job-events.ts    — Job lifecycle event broadcasting
    ├── whatsapp.ts      — WhatsApp API client
    └── telegram.ts      — Telegram API client
```

### Database Schema

**contacts** — `id` (PK, format: `platform:user_id`), `platform`, `platform_user_id`, `display_name`, `metadata`, timestamps

**messages** — `id` (auto PK), `external_id` (unique, dedup), `contact_id` (FK), `platform`, `direction`, `content`, `media_type`, `media_url`, `status`, `raw_payload`, `created_at`

## Data Flow Examples

### Incoming WhatsApp Message
```
WhatsApp Cloud API → POST /webhook/whatsapp → Server validates → Upserts contact in SQLite →
Stores message (dedup by external_id) → Broadcasts SSE "new_message" →
Plugin messaging.cljs receives event → Creates/updates page "AI Hub/WhatsApp/John Doe" →
Appends block with content + metadata properties
```

### Agent Chat Request
```
Client → POST /api/agent/chat → Server LLM call (OpenRouter) → Tool call response →
Server sends SSE "agent_request" to plugin → Plugin dispatches operation →
Plugin executes against Logseq graph → Plugin POSTs to /api/agent/callback →
Server receives result → Feeds back to LLM for next round → Final response to client
```

### Scheduled Job Execution
```
Scheduler tick (60s loop) → Cron expression matches → Creates timestamped job instance page →
Runner dequeues from priority queue → Reads skill definition from graph →
Engine executes steps sequentially → Executor dispatches each step action →
Results/logs written back to job page
```

## Correlation IDs

Every request is assigned a correlation ID (`X-Trace-Id` header) that flows through the entire lifecycle:

```
Client request → Server generates UUID → SSE event carries traceId →
Plugin bridge propagates traceId → Callback includes traceId →
Response includes X-Trace-Id header
```

Clients can send their own trace ID via the `X-Trace-Id` request header; if absent, the server generates one.

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Plugin language | ClojureScript |
| Plugin build | shadow-cljs |
| Plugin tests | cljs.test (Node.js runner) |
| Server runtime | Bun |
| Server language | TypeScript |
| Server database | SQLite (via bun:sqlite) |
| LLM provider | OpenRouter (provider-agnostic) |
| Default model | anthropic/claude-sonnet-4 |
| Deployment | Railway (Docker) |
| Protocol | MCP (Model Context Protocol) |
