# Tracks: Logseq AI Hub

## Completed Tracks

### core-arch_20260209 -- Core Plugin Architecture - Restructure and Extend
- **Status:** completed
- **Branch:** `track/core-arch_20260209`
- **Priority:** P0
- **Estimated:** 8-12 hours (30 tasks across 6 phases)
- **Spec:** [spec.md](tracks/core-arch_20260209/spec.md)
- **Plan:** [plan.md](tracks/core-arch_20260209/plan.md)
- **Description:** Transform the cljs-playground prototype into a production-ready logseq-ai-hub modular architecture. Namespace migration, error handling foundation, placeholder modules (messaging, memory, tasks), expanded settings schema, and testing infrastructure with cljs.test + node-test runner.

### job-runner_20260219 -- Job Runner System: Autonomous Task Execution Engine
- **Status:** completed
- **Branch:** `track/job-runner_20260219`
- **Priority:** P1
- **Estimated:** 35-50 hours (62 tasks across 9 phases)
- **Depends on:** core-arch_20260209
- **Spec:** [spec.md](tracks/job-runner_20260219/spec.md)
- **Plan:** [plan.md](tracks/job-runner_20260219/plan.md)
- **Description:** Autonomous task execution engine with job pages, skill pages, MCP server connectivity, OpenClaw skill import/export, priority queue with dependency resolution, cron scheduling, and full skill execution engine with 10 step action types.

### webhook-agent-api_20260219 -- Webhook Server Agent Interaction Layer
- **Status:** completed
- **Branch:** `track/webhook-agent-api_20260219`
- **Priority:** P1
- **Estimated:** 26-33 hours (52 tasks across 7 phases)
- **Depends on:** core-arch_20260209, job-runner_20260219
- **Spec:** [spec.md](tracks/webhook-agent-api_20260219/spec.md)
- **Plan:** [plan.md](tracks/webhook-agent-api_20260219/plan.md)
- **Description:** Agent interaction layer on the TypeScript/Bun webhook server exposing REST API for remote job management, skills discovery, MCP connector listing, and a conversational agent interface. Uses an SSE request-response bridge to proxy operations to the Logseq plugin for graph execution.

### foundation-hardening_20260221 -- Foundation Hardening: Cross-Cutting Infrastructure Fixes
- **Status:** completed
- **Branch:** `track/foundation-hardening_20260221`
- **Priority:** P0 (prerequisite for all downstream tracks)
- **Estimated:** 4-6 hours
- **Depends on:** (none — builds on completed tracks)
- **Spec:** [spec.md](tracks/foundation-hardening_20260221/spec.md)
- **Description:** Cross-cutting infrastructure fixes: rename misleading OpenAI settings to provider-agnostic LLM names with OpenRouter defaults, fix router.ts per-request route table rebuild, add correlation IDs (traceId) for end-to-end request tracing through Agent Bridge, SSE auto-reconnection with exponential backoff, and settings write serialization to prevent concurrent write races.

### secrets-manager_20260221 -- Secrets Manager: Secure Key-Value Vault
- **Status:** completed
- **Branch:** `track/secrets-manager_20260221`
- **Priority:** P0 (prerequisite for secure MCP connections)
- **Estimated:** 8-12 hours
- **Depends on:** foundation-hardening_20260221 (settings write serialization), job-runner_20260219 (interpolation engine, MCP client)
- **Spec:** [spec.md](tracks/secrets-manager_20260221/spec.md)
- **Description:** Secure key-value vault stored in Logseq plugin settings for API keys, tokens, and credentials. Adds `{{secret.KEY_NAME}}` template interpolation, secret references in MCP server auth configs, slash commands for management (`/secrets:list`, `/secrets:set`, `/secrets:remove`), Agent Bridge operations, MCP tools for remote management, and value redaction in all outputs. Eliminates plaintext credentials in page content and config strings.

### mcp-server_20260221 -- MCP Server Transport: Expose Plugin as MCP Server
- **Status:** completed
- **Branch:** `track/mcp-server_20260221`
- **Priority:** P0 (critical path)
- **Estimated:** 20-30 hours
- **Depends on:** webhook-agent-api_20260219 (Agent Bridge, REST API, plugin handlers), secrets-manager_20260221 (secure auth tokens), foundation-hardening_20260221 (router fix, correlation IDs)
- **Spec:** [spec.md](tracks/mcp-server_20260221/spec.md)
- **Description:** MCP server on the Bun webhook server using `@modelcontextprotocol/sdk` with `WebStandardStreamableHTTPServerTransport` at `POST /mcp`. Exposes 24 MCP tools (7 graph, 10 job/skill, 4 memory, 3 messaging), 5 resources (`logseq://pages/{name}`, `logseq://jobs`, `logseq://skills`, `logseq://memory/{tag}`, `logseq://contacts`), 4 prompt templates, session management, and `GET /mcp/config` discovery endpoint. 59 tests covering tools, resources, prompts, transport auth, and server lifecycle.

### agent-tool-expansion_20260222 -- Agent Chat Tool Expansion: Graph + Memory + MCP Tool Use
- **Status:** completed
- **Branch:** `main`
- **Priority:** P1
- **Estimated:** 1-2 hours
- **Depends on:** mcp-server_20260221 (plugin-side bridge handlers)
- **Description:** Expanded the conversational agent chat (`POST /api/agent/chat`) from 13 job/skill/MCP-listing operations to 24 operations by adding 7 graph tools (`graph_query`, `graph_search`, `page_read`, `page_create`, `page_list`, `block_append`, `block_update`) and 4 memory tools (`store_memory`, `recall_memory`, `search_memory`, `list_memory_tags`). The LLM can now chain graph reads, memory recalls, and page writes in a single conversation turn. Updated system prompt with graph/memory usage guidance. No new server code needed — all bridge handlers were already implemented in the MCP server track.

### dynamic-arg-parser_20260222 -- Dynamic Argument Parser: Ad-Hoc MCP, Memory, and Page Context for /LLM
- **Status:** completed
- **Branch:** `main`
- **Priority:** P1
- **Estimated:** 6-10 hours
- **Depends on:** job-runner_20260219 (MCP client, parser, interpolation), secrets-manager_20260221 (`{{secret.KEY}}` interpolation)
- **Description:** Dynamic argument parser preprocesses `/LLM` block content, detecting `[[MCP/...]]`, `[[AI-Memory/...]]`, and `[[Page Name]]` references. MCP refs connect on-demand servers with multi-turn tool calling. Memory refs inject curated context. Page refs fetch block trees with BFS link traversal (configurable depth via `depth:N`) and token budgets (configurable via `max-tokens:N`). All three chainable in a single block. Moved MCP from `job_runner/mcp/` to top-level `mcp/` namespace. Universal `enriched/call` entry point reusable by all plugin-side commands including sub-agents. New plugin settings for `pageRefDepth` and `pageRefMaxTokens`.

### human-in-loop_20260221 -- Human-in-the-Loop Approval Flow
- **Status:** completed
- **Branch:** `main`
- **Priority:** P0 (critical path)
- **Estimated:** 12-18 hours
- **Depends on:** mcp-server_20260221 (MCP tool exposure), webhook-agent-api_20260219 (messaging, webhooks)
- **Spec:** [spec.md](tracks/human-in-loop_20260221/spec.md)
- **Plan:** [plan.md](tracks/human-in-loop_20260221/plan.md)
- **Description:** Bidirectional human approval system: agents send questions/approval requests to humans via WhatsApp/Telegram, wait for the reply, and resume execution with the response. In-memory approval store with Promise-based blocking, FIFO resolution, options validation, per-contact (5) and total (100) limits, timeout auto-resolution. `ask_human` MCP tool with contact resolution and message formatting. Webhook-to-approval correlation for WhatsApp and Telegram. REST API for approval management (`GET /api/approvals`, `POST /api/approvals/ask`, `POST /api/approvals/:id/resolve`, `DELETE /api/approvals/:id`). SSE events for approval lifecycle. Job runner `:ask-human` step action type. 98 new tests, 224 total server tests passing.

### kb-tool-registry_20260221 -- Knowledge Base as Living Tool Registry
- **Status:** completed
- **Branch:** `main`
- **Priority:** P1
- **Estimated:** 15-22 hours
- **Depends on:** mcp-server_20260221 (MCP tool exposure), job-runner_20260219 (skill page format), dynamic-arg-parser_20260222 (`[[Page]]` ref pattern for prompt templates)
- **Spec:** [spec.md](tracks/kb-tool-registry_20260221/spec.md)
- **Plan:** [plan.md](tracks/kb-tool-registry_20260221/plan.md)
- **Description:** Dynamic capability registry transforming Logseq pages into discoverable MCP tools, prompts, and resources. Plugin-side registry with atom-based store, tag-based scanner, parsers for tool/prompt/procedure pages, bridge operations for CRUD+search+refresh. Server-side registry query MCP tools (4 tools: `registry_list`, `registry_search`, `registry_get`, `registry_refresh`), `DynamicRegistry` class for syncing page-defined tools/prompts/resources into MCP. Skills auto-wrapped as MCP tools with `skill_` prefix. Debounced DB watcher for auto-discovery. 29 MCP tools total. New files: 8 plugin source + 8 plugin tests + 2 server source. 224 server tests, 32 pre-existing ClojureScript failures unchanged.

### agent-sessions_20260221 -- Claude Code Agent Session Management
- **Status:** completed
- **Branch:** `track/agent-sessions_20260221`
- **Priority:** P1
- **Estimated:** 15-20 hours
- **Depends on:** mcp-server_20260221 (MCP tools), webhook-agent-api_20260219 (agent chat API)
- **Spec:** [spec.md](tracks/agent-sessions_20260221/spec.md)
- **Plan:** [plan.md](tracks/agent-sessions_20260221/plan.md)
- **Description:** Persistent, context-rich agent sessions backed by SQLite. Replaces the in-memory 20-message conversation store with durable sessions that survive restarts, include working context (focus, relevant pages, working memory), automatic context injection (recent activity, relevant memories), session lifecycle tools, and session handoff between interfaces (agent chat ↔ MCP).

### code-repo-integration_20260221 -- Code Repository Integration
- **Status:** completed
- **Branch:** `track/code-repo-integration_20260221`
- **Priority:** P1
- **Estimated:** 20-30 hours
- **Depends on:** mcp-server_20260221, kb-tool-registry_20260221, human-in-loop_20260221, dynamic-arg-parser_20260222 (`[[Page]]` refs replace explicit graph-query steps)
- **Spec:** [spec.md](tracks/code-repo-integration_20260221/spec.md)
- **Description:** Orchestration layer for code-aware workflows with pi.dev agent integration. Includes: project registry pages with architecture context, ADRs, code review skills enriched with KB context, deployment procedures with approval gates, work coordination tools, lesson-learned memory, pi.dev agent platform integration (opt-in, user provides install path), conductor-style project/track/task management within the graph, per-track customizable pi.dev agent profiles, and a layered development safeguard pipeline (5 levels from unrestricted to locked) with escalation chains, audit logging, and override controls — all linking back to the Hub's HITL approval system.

### event-hub_20260303 -- Event Hub System
- **Status:** completed
- **Branch:** `main`
- **Priority:** P1 (upgraded from P2)
- **Estimated:** 32-43 hours (6 phases)
- **Depends on:** mcp-server_20260221, human-in-loop_20260221, kb-tool-registry_20260221, secrets-manager_20260221
- **Spec:** [spec.md](tracks/event-hub_20260303/spec.md)
- **Plan:** [plan.md](tracks/event-hub_20260303/plan.md)
- **Description:** Universal event hub — the central nervous system of Logseq AI Hub. Provides generic webhook ingestion (`POST /webhook/event/:source`), bidirectional event flow (server→plugin via SSE, plugin→server via `POST /api/events/publish`), internal event emission from all subsystems (job runner, registry, graph, MCP, approvals, messaging), event-driven automation triggers via page-defined subscriptions (`logseq-ai-hub-event-subscription`), outbound HTTP action step type (`:http-request`) with URL allowlist and `{{secret.KEY}}` interpolation, custom event emission from skills (`:emit-event` step), 7 MCP tools for event management, infrastructure service monitoring, JSONPath extraction for webhook payloads, alert routing, and slash commands. Replaces the narrow IoT/Infrastructure Hooks track.

## Active Tracks

### open-source-prep_20260312 -- Open Source Release Preparation
- **Status:** in-progress
- **Type:** chore
- **Branch:** `track/open-source-prep_20260312`
- **Priority:** P0
- **Estimated:** 10-16 hours (36 tasks across 6 phases)
- **Depends on:** (none -- all feature tracks completed)
- **Spec:** [spec.md](tracks/open-source-prep_20260312/spec.md)
- **Plan:** [plan.md](tracks/open-source-prep_20260312/plan.md)
- **Description:** Prepare repository for public open-source release. MIT LICENSE file, comprehensive README.md with architecture overview and setup guide, source code secret sanitization, server `.env.example`, full documentation of 52 bridge operations and 80+ MCP tools/10 resources/7 prompts, CONTRIBUTING.md with dev workflow, package.json and manifest.edn metadata cleanup, and GitHub Actions CI workflow for both CLJS and server tests.

### plugin-dual-auth_20260312 -- Plugin Dual Authentication: Token and JWT Modes
- **Status:** pending
- **Type:** feature
- **Branch:** `track/plugin-dual-auth_20260312`
- **Priority:** P0
- **Estimated:** 3-5 hours (16 tasks across 4 phases)
- **Depends on:** event-hub_20260303 (latest plugin state with all auth touchpoints)
- **Spec:** [spec.md](tracks/plugin-dual-auth_20260312/spec.md)
- **Plan:** [plan.md](tracks/plugin-dual-auth_20260312/plan.md)
- **Description:** Add dual authentication to the Logseq plugin: existing simple bearer token for the open-source single-tenant server and JWT-based authentication for the proprietary multi-tenant server. New `authMode` enum setting ("token" | "jwt"), new `jwtToken` setting, centralized `logseq-ai-hub.auth` namespace replacing scattered `get-api-token` calls across 4 modules (messaging, agent-bridge, event-hub/publish, event-hub/init). Silent settings migration for existing users. Plugin sends JWT as `Authorization: Bearer <jwt>` -- server extracts tenant from claims. No JWT issuance/refresh/validation on plugin side.

### proprietary-server-bootstrap_20260312 -- Proprietary Server Bootstrap: Private Multi-Tenant Repository
- **Status:** pending
- **Branch:** N/A (work happens in a separate repository)
- **Priority:** P0
- **Estimated:** 8-14 hours (35 tasks across 6 phases)
- **Depends on:** event-hub_20260303 (latest server state with 741 tests), open-source-prep_20260312 (repo must be clean before forking)
- **Spec:** [spec.md](tracks/proprietary-server-bootstrap_20260312/spec.md)
- **Plan:** [plan.md](tracks/proprietary-server-bootstrap_20260312/plan.md)
- **Description:** Create a private GitHub repository for the proprietary multi-tenant server. Copies the current `server/` directory as the foundation, sets up independent package.json/tsconfig/bun workspace, adds Dockerfile and docker-compose for deployment, GitHub Actions CI, multi-tenant environment config scaffolding (MULTI_TENANT, JWT_SECRET, etc.), API contract documentation, and shared types documentation. The open-source plugin must work with both servers.

### multi-tenant-schema_20260312 -- Multi-Tenant Schema: Foundational Data Isolation Layer
- **Status:** pending
- **Branch:** `track/multi-tenant-schema_20260312`
- **Priority:** P0
- **Estimated:** 20-30 hours (49 tasks across 6 phases)
- **Depends on:** proprietary-server-bootstrap_20260312 (server repo established), event-hub_20260303 (all tables finalized)
- **Spec:** [spec.md](tracks/multi-tenant-schema_20260312/spec.md)
- **Plan:** [plan.md](tracks/multi-tenant-schema_20260312/plan.md)
- **Description:** Add `tenant_id` column (TEXT NOT NULL) to all 8 database tables (contacts, messages, sse_events, characters, character_sessions, sessions, session_messages, events). Create composite indexes, migration system with backfill for existing data, TenantContext type flowing through request handlers, tenant-scoped DB query functions, `MULTI_TENANT` feature flag for backward compatibility, and comprehensive cross-tenant isolation tests. This work targets the proprietary server repo only.

### multi-tenant-auth_20260312 -- Multi-Tenant Authentication: JWT Auth, Tenant Management, and Rate Limiting
- **Status:** pending
- **Type:** feature
- **Branch:** `track/multi-tenant-auth_20260312`
- **Priority:** P0
- **Estimated:** 25-35 hours (69 tasks across 7 phases)
- **Depends on:** proprietary-server-bootstrap_20260312 (server repo exists), multi-tenant-schema_20260312 (tenant_id columns, TenantContext, migration system)
- **Spec:** [spec.md](tracks/multi-tenant-auth_20260312/spec.md)
- **Plan:** [plan.md](tracks/multi-tenant-auth_20260312/plan.md)
- **Description:** JWT-based authentication and tenant management for the proprietary multi-tenant server. Includes: JWT token generation/validation via `jose` library, password hashing with Bun's built-in Argon2id, tenant CRUD operations (register, login, refresh, profile, admin management), API key management per tenant (`esh_` prefixed keys), multi-strategy auth middleware (admin key, JWT, API key, legacy token), admin authentication via dual mechanism (JWT admin claim and ADMIN_API_KEY env var), in-memory sliding-window rate limiting per tenant, and full backward compatibility when MULTI_TENANT=false. Targets proprietary server repo only.

### multi-tenant-routing_20260312 -- Multi-Tenant Routing: Tenant-Aware Request Routing
- **Status:** pending
- **Type:** feature
- **Branch:** `track/multi-tenant-routing_20260312`
- **Priority:** P0
- **Estimated:** 18-28 hours (52 tasks across 6 phases)
- **Depends on:** multi-tenant-schema_20260312 (DB functions accept tenantId), multi-tenant-auth_20260312 (JWT validated, claims available)
- **Spec:** [spec.md](tracks/multi-tenant-routing_20260312/spec.md)
- **Plan:** [plan.md](tracks/multi-tenant-routing_20260312/plan.md)
- **Description:** Tenant-aware request routing for the proprietary multi-tenant server. Tenant context extraction middleware (reads tenantId from JWT claims), tenant context propagation through all route handlers, tenant-scoped SSE connections (SSEManager partitioned by tenant), tenant-scoped Agent Bridge (pendingRequests partitioned by tenant), tenant-scoped MCP sessions, tenant-scoped EventBus/SessionStore/ApprovalStore, backward compatibility (MULTI_TENANT=false uses "default" tenant). This work targets the proprietary server repo only.

## Dependency Graph

```
core-arch (done) ──> job-runner (done) ──> webhook-agent-api (done)
                          |                         |
                          |                         v
                          |              foundation-hardening (done)
                          |                         |
                          v                         v
                   secrets-manager (done) ──> mcp-server (done)
                          |                    |         \
                          |                    v          \
                          |          dynamic-arg-parser    agent-sessions
                          |              (done)              (done)
                          |             /     |
                          |            /      |
                          |   human-in-loop  kb-tool-registry
                          |      (done)          (done)
                          |         \          /
                          |          \        /
                          └──────> code-repo-integration (done)
                                   event-hub (done)
                                        |
                                        v
                              open-source-prep (active)
                              plugin-dual-auth (pending)
                                        |
                                        v
                              proprietary-server-bootstrap (pending)
                                   [separate repo]
                                        |
                                        v
                              multi-tenant-schema (pending)
                                   [proprietary repo]
                                        |
                                        v
                              multi-tenant-auth (pending)
                                   [proprietary repo]
                                        |
                                        v
                              multi-tenant-routing (pending)
                                   [proprietary repo]
```

## Implementation Order

1. ~~**foundation-hardening**~~ — Done. Settings renamed, router fixed, correlation IDs, SSE reconnection, write serialization all implemented.
2. ~~**secrets-manager**~~ — Done. Vault in plugin settings, `{{secret.KEY}}` interpolation, slash commands, Agent Bridge ops, server API with rate limiting, redaction utilities.
3. ~~**mcp-server**~~ — Done. 24 MCP tools, 5 resources, 4 prompts, WebStandardStreamableHTTPServerTransport, session management, config discovery. 59 tests passing.
4. ~~**dynamic-arg-parser**~~ — Done. `[[MCP/...]]`, `[[AI-Memory/...]]`, `[[Page]]` refs in `/LLM` blocks. On-demand MCP, multi-turn tool calling, BFS page traversal, token budgets, universal `enriched/call`. Sub-agents support all ref types.
5. ~~**human-in-loop**~~ — Done. Approval store, `ask_human` MCP tool, webhook correlation, REST API, SSE events, `:ask-human` executor. 98 new tests.
6. ~~**kb-tool-registry**~~ — Done. Dynamic registry with plugin-side store, scanner, parsers, bridge ops. Server-side registry query tools (4) + DynamicRegistry class for syncing tools/prompts/resources. Skills auto-wrapped as MCP tools. Debounced DB watcher. 29 total MCP tools.
7. ~~**agent-sessions**~~ — Done. Persistent, context-rich sessions. Uses `graph-context/resolve-page-refs` for session context enrichment.
8. ~~**code-repo-integration**~~ — Done. Orchestration layer for coding workflows. `[[Page]]` refs replace explicit graph-query steps in skills. Builds on #5, #6.
9. ~~**event-hub**~~ — Done. Universal event hub: webhook ingestion, internal event emission, event-driven automation, outbound HTTP, graph watcher, alert routing. 12 plugin source files, 13 plugin tests, 5 server source files, 5 server tests. 741 server tests passing.
10. **open-source-prep** — In progress. License, README, sanitization, .env.example, API docs, CONTRIBUTING, CI.
11. **plugin-dual-auth** — Pending. Centralized auth namespace, authMode/jwtToken settings, module migration, silent settings migration.
12. **proprietary-server-bootstrap** — Pending. Private repo creation, Docker deployment, CI, multi-tenant config scaffolding, API contract docs.
13. **multi-tenant-schema** — Pending. Migration system, tenant_id on all 8 tables, composite indexes, TenantContext, scoped queries, backward compat, isolation tests. Targets proprietary repo.
14. **multi-tenant-auth** — Pending. JWT auth via jose, Argon2id passwords, tenant CRUD, API keys, multi-strategy auth middleware, admin dual-auth, sliding-window rate limiting. Targets proprietary repo.
15. **multi-tenant-routing** — Pending. Tenant context middleware, tenant-scoped SSE/Bridge/MCP/EventBus/SessionStore/ApprovalStore, route handler propagation, cross-tenant integration tests. Targets proprietary repo.
