# Roadmap

## Completed

### Core Plugin Architecture
Modular ClojureScript plugin with messaging, memory, tasks, sub-agents, and settings infrastructure. 275+ tests.

### Job Runner System
Autonomous task execution engine with skill pages, 11 step action types, priority queue, cron scheduling, MCP client, and OpenClaw interoperability. 181 tests.

### Webhook Server Agent API
REST API for remote job management, skills discovery, MCP connector listing, and a conversational agent interface. SSE bridge for graph operations. 67 tests.

### Foundation Hardening
Cross-cutting infrastructure fixes: LLM settings rename with migration, router static table (no per-request rebuild), correlation IDs (`X-Trace-Id`) threaded through Agent Bridge, SSE auto-reconnection with exponential backoff, and settings write serialization queue.

## Planned

### P0 — Next Up

**Secrets Manager**
Secure key-value vault for API keys and credentials. `{{secret.KEY_NAME}}` template interpolation, slash commands (`/secrets:list`, `/secrets:set`, `/secrets:remove`), Agent Bridge operations, MCP tools, value redaction. ~8-12 hours.

**MCP Server** (critical path)
Expose the full system as an MCP server using `@modelcontextprotocol/sdk`. ~25 MCP tools across 5 categories (graph ops, job runner, messaging, memory, MCP passthrough), MCP resources for knowledge base browsing, and prompt templates. Enables Claude Code native connectivity. ~20-30 hours.

**Human-in-the-Loop**
Bidirectional approval flow: agents send questions to humans via WhatsApp/Telegram, await replies, resume execution. `ask_human` MCP tool, `:ask-human` job runner step, structured choice support, approval monitoring. ~12-18 hours.

### P1 — Intelligence Layer

**Knowledge Base as Living Tool Registry**
Logseq pages become discoverable capabilities. Tagged pages (`logseq-ai-hub-tool`, `logseq-ai-hub-prompt`, `logseq-ai-hub-procedure`) are auto-registered as MCP tools. Skills wrapped as callable tools. `/registry:refresh` command. ~15-22 hours.

**Agent Sessions**
SQLite-backed persistent sessions replacing in-memory conversation store. Session context (focus, relevant pages, working memory), automatic context injection, session handoff between interfaces. ~15-20 hours.

### P2 — Integration Layer

**Code Repository Integration**
Project registry pages with architecture context, ADRs, code review skills, deployment procedures with approval gates, multi-agent work coordination. ~10-15 hours.

**IoT/Infrastructure Hooks**
Generic webhook ingestion (`POST /webhook/generic/:source`), outbound HTTP step action, infrastructure monitoring pages, alert routing engine with severity-based escalation. ~12-18 hours.

## Dependency Graph

```
core-arch (done) ──▶ job-runner (done) ──▶ webhook-agent-api (done)
                          │                         │
                          │                         ▼
                          │              foundation-hardening (done)
                          │                         │
                          ▼                         ▼
                   secrets-manager (P0) ──▶ mcp-server (P0)
                          │                ╱    │    ╲
                          │               ╱     │     ╲
                          │   human-in-loop  kb-tool-  agent-
                          │      (P0)        registry  sessions
                          │                   (P1)     (P1)
                          │                ╲    │    ╱
                          │                 ╲   │   ╱
                          └──────▶ code-repo-integration (P2)
                                   iot-infra-hooks (P2)
```
