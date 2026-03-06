# Event Hub System -- Implementation Plan

## Overview

This plan implements the Event Hub System across 9 phases, adding a universal publish/subscribe event bus to Logseq AI Hub. The system spans both the Bun/TypeScript server (EventBus, event store, webhook ingestion) and the ClojureScript plugin (event dispatcher, subscription matching, step executors, graph persistence).

Key architectural constraint: subscription matching happens exclusively on the plugin side. The server stores events, broadcasts via SSE, and exposes HTTP endpoints. The plugin receives SSE events, matches them against page-defined subscriptions, and triggers jobs.

### Key Design Decisions

1. **Webhook token verification (v1):** Server-side webhook endpoint does NOT verify per-source tokens. The server stores all valid JSON webhooks. Token verification is a v2 feature requiring plugin-synced config. This keeps Phase 2 simple.
2. **`event_subscribe` MCP tool:** Uses existing AgentBridge pattern (`page_create` operation) to create subscription pages on the plugin side. Server sends agent_request, plugin creates the page.
3. **SSE `hub_event` listener:** Registered via a separate `.addEventListener` call in `event_hub/init.cljs`, NOT added to the messaging.cljs `doseq`. This maintains separation of concerns.
4. **McpToolContext:** `eventBus` added to MCP context via `getContext()` in `index.ts` (same pattern as `approvalStore`, `workStore`).

---

## Phase 1: Server Foundation (P0)

**Goal**: Establish the server-side event infrastructure -- SQLite table, data access layer, EventBus class, config expansion, and chain depth guard.

**Dependencies**: None (first phase).

### Task 1.1: HubEvent Type and SSE Type Union

**Files to modify:**
- `server/src/types.ts`

**Work:**
- Add `"hub_event"` to the `SSEEvent.type` union.
- Add `HubEvent` interface:
  ```typescript
  interface HubEvent {
    id: string;
    type: string;
    source: string;
    timestamp: string;
    data: Record<string, unknown>;
    metadata?: {
      trace_id?: string;
      severity?: "info" | "warning" | "error" | "critical";
      tags?: string[];
      ttl?: number;
      chain_depth?: number;
    };
  }
  ```

**Acceptance criteria:**
- `"hub_event"` is a valid SSEEvent type.
- HubEvent interface is exported and usable by other modules.
- Existing 16 SSE event types remain unchanged.

---

### Task 1.2: Events Table Schema

**Files to modify:**
- `server/src/db/schema.ts` (append inside `db.exec`)

**Work:**
- Add events table and indexes to `initializeSchema` (all `IF NOT EXISTS`):
  ```sql
  CREATE TABLE IF NOT EXISTS events (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    source TEXT NOT NULL,
    data TEXT NOT NULL,
    metadata TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
  );
  CREATE INDEX IF NOT EXISTS idx_events_type ON events(type);
  CREATE INDEX IF NOT EXISTS idx_events_source ON events(source);
  CREATE INDEX IF NOT EXISTS idx_events_created ON events(created_at);
  ```

**Acceptance criteria:**
- Events table created on server startup via existing `initializeSchema` path.
- Existing tables unaffected.
- Test: `createTestDatabase()` includes events table.

---

### Task 1.3: Event Store Data Access

**Files to create:**
- `server/src/db/events.ts`

**Work:**
- `insertEvent(db, event: HubEvent): HubEvent` -- INSERT, JSON.stringify data/metadata.
- `queryEvents(db, opts: { type?, source?, since?, limit?, offset? }): { events: HubEvent[], total: number }` -- SELECT with optional WHERE, COUNT for total.
- `pruneEvents(db, retentionDays: number): number` -- DELETE old events, return count.
- `countEvents(db, opts?: { type?, source? }): number` -- COUNT with optional filters.
- `getEventById(db, id: string): HubEvent | null` -- single lookup.

**Tests to write:**
- `server/tests/event-store.test.ts` -- insert + retrieve, query by type/source/since, pagination, prune, count.

---

### Task 1.4: EventBus Class

**Files to create:**
- `server/src/services/event-bus.ts`

**Work:**
- `EventBus` class:
  - Constructor takes `sseManager` and `db: Database`.
  - `publish(event: Partial<HubEvent>): HubEvent` -- assigns id/timestamp, validates chain_depth, stores via insertEvent, broadcasts via `sseManager.broadcast({ type: "hub_event", data: { payload: hubEvent } })`.
  - Chain depth guard: if `metadata.chain_depth >= 5`, store but suppress broadcast.
  - Duplicate detection: Map<string, number> of `hash -> timestamp`. Hash = JSON.stringify of sorted-keys data. Skip if duplicate within 1s window.
  - `prune(retentionDays)` -- delegates to pruneEvents.
  - `query(opts)` -- delegates to queryEvents.

**Key design note:** EventBus wraps SSEManager. SSEManager code is NOT modified. The broadcast call nests HubEvent under `payload` key to avoid type field collision.

**Tests to write:**
- `server/tests/event-bus.test.ts` -- publish stores + broadcasts (mock SSEManager), chain depth guard at 5, duplicate detection, query/prune delegation.

---

### Task 1.5: Config Expansion

**Files to modify:**
- `server/src/config.ts`

**Work:**
- Add to Config interface: `eventRetentionDays: number`, `httpAllowlist: string[]`.
- Add to loadConfig: defaults 30 days, empty allowlist. Try/catch JSON.parse for allowlist.

---

### Task 1.6: EventBus Wiring in index.ts

**Files to modify:**
- `server/src/index.ts` -- instantiate EventBus after db/sseManager, wire into contexts.
- `server/src/router.ts` -- add `eventBus?` to RouteContext interface.
- Set up daily pruning interval.

---

## Phase 2: Webhook Ingestion (P0)

**Goal**: Accept webhook payloads from external services at a generic endpoint.

**Dependencies**: Phase 1.

### Task 2.1: Webhook Route Handler

**Files to create:**
- `server/src/routes/webhooks/event-hub.ts`

**Work:**
- `handleEventWebhook(req, ctx, params)`:
  - Validate source: `/^[a-z0-9-]+$/`. 400 if invalid.
  - Content-length <= 1MB. 413 if exceeded.
  - Rate limiting: Map per source, 100/min. 429 if exceeded.
  - Parse JSON body. 400 if invalid.
  - **No per-source token verification in v1.** The endpoint is open (protected only by rate limiting and source validation). Per-source token verification is deferred to v2 when plugin-synced webhook source configs are available on the server.
  - Construct HubEvent: `type: "webhook.received"`, `source: "webhook:{source}"`.
  - Publish via `ctx.eventBus.publish()`. Return `200 { success: true, eventId }`.
- `handleEventWebhookVerify(req, ctx, params)`:
  - Echo `hub.challenge` query param for verification flows.

**Files to modify:**
- `server/src/router.ts` -- Add `POST /webhook/event/:source` and `GET /webhook/event/:source`.

**Tests to write:**
- `server/tests/webhook-event-hub.test.ts` -- valid webhook, invalid source 400, oversized 413, rate limit 429, verification challenge, malformed JSON 400.

---

## Phase 3: Plugin Event Publishing (P0)

**Goal**: Plugin publishes events to server's EventBus via authenticated HTTP.

**Dependencies**: Phase 1.

### Task 3.1: Server Publish Endpoint

**Files to create:**
- `server/src/routes/api/events.ts`

**Work:**
- `handlePublishEvent(req, ctx)` -- authenticate, parse body, publish, return eventId.
- `handleQueryEvents(req, ctx)` -- authenticate, parse query params, query events, return paginated results.

**Files to modify:**
- `server/src/router.ts` -- Add `POST /api/events/publish` and `GET /api/events`.

**Tests to write:**
- `server/tests/api-events.test.ts` -- auth, publish, query with filters, pagination.

---

### Task 3.2: Plugin publish-to-server! Helper

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/publish.cljs`

**Work:**
- `publish-to-server! [{:keys [type source data metadata]}]` -- POST to `{server-url}/api/events/publish` with Bearer token auth. Same pattern as `agent-bridge/send-callback!`. Fire-and-forget on errors.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/publish_test.cljs` -- successful publish, server error handling, settings reading.

---

## Phase 4: Plugin Dispatcher + Subscriptions (P0)

**Goal**: Parse subscription pages, listen for hub_event SSE, match subscriptions, trigger jobs, persist to graph.

**Dependencies**: Phase 3.

### Task 4.1: Event Subscription Parser

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/parser.cljs`

**Work:**
- `parse-subscription-page [page-name block-content]` -- extracts event-pattern, event-action, event-skill, event-debounce, event-route-to, event-severity-filter, event-description.
- `parse-webhook-source-page [page-name block-content]` -- extracts webhook-source, webhook-description, webhook-verify-token, webhook-extract-*, webhook-page-prefix, webhook-route-to, webhook-auto-job.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/parser_test.cljs`

---

### Task 4.2: Glob Pattern Matching

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/pattern.cljs`

**Work:**
- `pattern-matches? [pattern event-type]` -- Simple `*` wildcard. `*` matches one dot-segment.
  - `"job.*"` matches `"job.completed"`, NOT `"job.step.completed"`.
  - Split on `.`, segments must match count, each segment matches if equal or `*`.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/pattern_test.cljs`

---

### Task 4.3: Registry Scanner Extension

**Files to modify:**
- `src/main/logseq_ai_hub/registry/scanner.cljs` -- APPEND after existing `refresh-registry!` result, NOT modify the 5-slot destructuring or Promise.all.
- `src/main/logseq_ai_hub/registry/store.cljs` -- Add `:event-subscription` and `:webhook-source` categories.

**CRITICAL**: Existing 5-slot destructuring `[tools prompts procedures skills agents]` MUST NOT be modified. Chain `.then` AFTER the existing return.

**Detailed store.cljs modifications (5 functions):**
1. **`registry` atom init (line 6-13):** Add `:event-subscriptions {}` and `:webhook-sources {}` to initial state.
2. **`init-store!` (line 15-25):** Add `:event-subscriptions {}` and `:webhook-sources {}` to reset map.
3. **`category-key` (line 27-36):** Add cases: `:event-subscription :event-subscriptions`, `:webhook-source :webhook-sources`.
4. **`list-entries` (line 59-72):** Add `(vals (:event-subscriptions @registry))` and `(vals (:webhook-sources @registry))` to the `concat` in the no-filter branch.
5. **`get-snapshot` (line 89-99):** Add `:event-subscriptions (vals (:event-subscriptions reg))` and `:webhook-sources (vals (:webhook-sources reg))` to the returned map.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/scanner_test.cljs`
- Verify existing `scanner_test.cljs` still passes.

---

### Task 4.4: Event Dispatcher

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/dispatcher.cljs`

**Work:**
- Dynamic vars: `*enqueue-job-fn*`, `*read-skill-fn*`, `*send-message-fn*`.
- `dispatch-event! [hub-event]` -- loads subscriptions, pattern match + severity filter (AND), debounce per subscription, fire matching actions.
- `handle-hub-event-sse [raw-data]` -- parse SSE, extract from `:payload`, dispatch + persist.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/dispatcher_test.cljs`

---

### Task 4.5: Graph Persistence

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/graph.cljs`

**Work:**
- `persist-to-graph! [hub-event]` -- page `Events/{Source}`, create if not exists, append child block with event metadata as properties.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/graph_test.cljs`

---

### Task 4.6: Plugin Settings Extension

**Files to modify:**
- `src/main/logseq_ai_hub/core.cljs` -- Add 4 settings: `eventHubEnabled`, `httpAllowlist`, `eventRetentionDays`, `eventGraphPersistence`.

---

### Task 4.7: Event Hub Init and SSE Listener

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/init.cljs`

**Files to modify:**
- `src/main/logseq_ai_hub/core.cljs` -- Call `event-hub-init/init!` after other inits.

**NOTE:** Do NOT modify `messaging.cljs`. Instead, register the `hub_event` SSE listener directly in `init.cljs` using the EventSource reference from messaging:
```clojure
;; In init.cljs -- register SSE listener directly
(when-let [es (messaging/get-event-source)]
  (.addEventListener es "hub_event"
    (fn [e]
      (dispatcher/handle-hub-event-sse (.-data e)))))
```
This requires exposing `get-event-source` from messaging.cljs (a simple getter for the EventSource atom). This maintains separation of concerns -- event hub logic stays in the event_hub namespace.

**Files to modify (additional):**
- `src/main/logseq_ai_hub/messaging.cljs` -- Add `get-event-source` function that returns the current EventSource instance (simple atom deref, ~3 lines).

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/init_test.cljs`

---

## Phase 5: Step Actions (P0)

**Goal**: Add `:http-request` and `:emit-event` step executors to job runner.

**Dependencies**: Phase 3, Phase 4.

### Task 5.1: HTTP Request Step Executor

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/http.cljs`

**Work:**
- Helpers: `url-allowed?`, `interpolate-map`, `interpolate-json`, `parse-response`, `abort-after`.
- `http-request-executor [step context]` -- reads config, interpolates, checks allowlist, enforces HTTPS (except localhost), fetches with AbortController timeout, returns response.

**Files to modify:**
- `src/main/logseq_ai_hub/job_runner/executor.cljs` -- Register `:http-request`.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/http_test.cljs`

---

### Task 5.2: Emit Event Step Executor

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/emit.cljs`

**Work:**
- Dynamic var: `*publish-event-fn*`.
- `emit-event-executor [step context]` -- reads config, interpolates, sets source to `"skill:{job-id}"`, increments chain_depth, publishes.

**Files to modify:**
- `src/main/logseq_ai_hub/job_runner/executor.cljs` -- Register `:emit-event`.
- `src/main/logseq_ai_hub/event_hub/init.cljs` -- Wire `*publish-event-fn*`.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/emit_test.cljs`

---

## Phase 6: Job Runner Lifecycle Events (P1)

**Goal**: Automatic event emission from job runner lifecycle transitions.

**Dependencies**: Phase 5.

### Task 6.1: Lifecycle Event Emission

**Files to modify:**
- `src/main/logseq_ai_hub/job_runner/runner.cljs` -- Add `*emit-event-fn*` dynamic var and `emit-lifecycle-event!` helper. Emit at: enqueue (job.created), execute start (job.started), success (job.completed), failure (job.failed), cancel (job.cancelled). Fire-and-forget.
- `src/main/logseq_ai_hub/event_hub/init.cljs` -- Wire `*emit-event-fn*` to `publish-to-server!`.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/lifecycle_test.cljs`

---

## Phase 7: Webhook Source Pages + MCP Tools (P1)

**Goal**: Page-based webhook source configuration and 7 MCP tools.

**Dependencies**: Phase 4, Phase 1.

### Task 7.1: Webhook Source Processing

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/webhook_source.cljs`

**Work:**
- `apply-webhook-source [hub-event source-config]` -- dot-path extraction (simple `get-in`, no JSONPath library), override page prefix, trigger auto-job, forward to route-to.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/webhook_source_test.cljs`

---

### Task 7.2: MCP Event Tools

**Files to create:**
- `server/src/services/mcp/event-tools.ts`

**Work:**
- 7 tools: `event_publish`, `event_query`, `event_subscribe`, `event_sources`, `event_recent`, `webhook_test`, `http_request`.
- `event_subscribe` creates pages via AgentBridge: calls `ctx.bridge.request("page_create", { ... })` which sends an `agent_request` SSE event to the plugin, which creates the Logseq page via `logseq.Editor.createPage`. This follows the established pattern used by other MCP tools that need plugin-side operations.
- `eventBus` must be available in the MCP tool context. Add `eventBus` to the `getContext()` return in `server/src/index.ts` (same pattern as `approvalStore`, `workStore`).

**Files to modify:**
- `server/src/services/mcp/index.ts` -- Register event tools.
- `server/src/index.ts` -- Add `eventBus` to `getContext()` return object.

**Tests to write:**
- `server/tests/event-tools.test.ts`

---

## Phase 8: Graph Change Events (P1)

**Goal**: Emit events on Logseq graph page changes.

**Dependencies**: Phase 6, Phase 3.

### Task 8.1: Graph Change Watcher

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/graph_watcher.cljs`

**Work:**
- `logseq.DB.onChanged` listener, 2s debounce, filter Events/*/Jobs/* pages, emit graph.page.created/updated/deleted and graph.block.changed.

**Files to modify:**
- `src/main/logseq_ai_hub/event_hub/init.cljs` -- Start graph watcher.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/graph_watcher_test.cljs`

---

## Phase 9: Alert Routing + Service Monitoring + Slash Commands (P2)

**Goal**: Alert routing, service monitoring convention, slash commands.

**Dependencies**: Phase 7, Phase 5.

### Task 9.1: Alert Routing Engine

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/alert.cljs`

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/alert_test.cljs`

### Task 9.2: Service Monitoring Convention

No code -- convention documented in spec.

### Task 9.3: Slash Commands

**Files to create:**
- `src/main/logseq_ai_hub/event_hub/commands.cljs`

**Work:** 4 commands: `event:list`, `event:recent`, `event:test`, `event:sources`.

**Tests to write:**
- `src/test/logseq_ai_hub/event_hub/commands_test.cljs`

---

## File Summary

### New Files (Server - TypeScript)
| File | Phase |
|------|-------|
| `server/src/db/events.ts` | 1 |
| `server/src/services/event-bus.ts` | 1 |
| `server/src/routes/webhooks/event-hub.ts` | 2 |
| `server/src/routes/api/events.ts` | 3 |
| `server/src/services/mcp/event-tools.ts` | 7 |

### New Files (Plugin - ClojureScript)
| File | Phase |
|------|-------|
| `src/main/logseq_ai_hub/event_hub/publish.cljs` | 3 |
| `src/main/logseq_ai_hub/event_hub/parser.cljs` | 4 |
| `src/main/logseq_ai_hub/event_hub/pattern.cljs` | 4 |
| `src/main/logseq_ai_hub/event_hub/dispatcher.cljs` | 4 |
| `src/main/logseq_ai_hub/event_hub/graph.cljs` | 4 |
| `src/main/logseq_ai_hub/event_hub/init.cljs` | 4 |
| `src/main/logseq_ai_hub/event_hub/http.cljs` | 5 |
| `src/main/logseq_ai_hub/event_hub/emit.cljs` | 5 |
| `src/main/logseq_ai_hub/event_hub/webhook_source.cljs` | 7 |
| `src/main/logseq_ai_hub/event_hub/graph_watcher.cljs` | 8 |
| `src/main/logseq_ai_hub/event_hub/alert.cljs` | 9 |
| `src/main/logseq_ai_hub/event_hub/commands.cljs` | 9 |

### New Test Files (Server)
| File | Phase |
|------|-------|
| `server/tests/event-store.test.ts` | 1 |
| `server/tests/event-bus.test.ts` | 1 |
| `server/tests/webhook-event-hub.test.ts` | 2 |
| `server/tests/api-events.test.ts` | 3 |
| `server/tests/event-tools.test.ts` | 7 |

### New Test Files (Plugin)
| File | Phase |
|------|-------|
| `src/test/logseq_ai_hub/event_hub/publish_test.cljs` | 3 |
| `src/test/logseq_ai_hub/event_hub/parser_test.cljs` | 4 |
| `src/test/logseq_ai_hub/event_hub/pattern_test.cljs` | 4 |
| `src/test/logseq_ai_hub/event_hub/dispatcher_test.cljs` | 4 |
| `src/test/logseq_ai_hub/event_hub/graph_test.cljs` | 4 |
| `src/test/logseq_ai_hub/event_hub/init_test.cljs` | 4 |
| `src/test/logseq_ai_hub/event_hub/http_test.cljs` | 5 |
| `src/test/logseq_ai_hub/event_hub/emit_test.cljs` | 5 |
| `src/test/logseq_ai_hub/event_hub/lifecycle_test.cljs` | 6 |
| `src/test/logseq_ai_hub/event_hub/webhook_source_test.cljs` | 7 |
| `src/test/logseq_ai_hub/event_hub/graph_watcher_test.cljs` | 8 |
| `src/test/logseq_ai_hub/event_hub/alert_test.cljs` | 9 |
| `src/test/logseq_ai_hub/event_hub/commands_test.cljs` | 9 |

### Modified Files
| File | Phase | What Changes |
|------|-------|-------------|
| `server/src/types.ts` | 1 | Add `"hub_event"` to SSEEvent union, add HubEvent interface |
| `server/src/db/schema.ts` | 1 | Add events table to initializeSchema |
| `server/src/config.ts` | 1 | Add eventRetentionDays, httpAllowlist to Config |
| `server/src/index.ts` | 1 | Instantiate EventBus, wire into contexts |
| `server/src/router.ts` | 1,2,3 | Add eventBus to RouteContext, add route entries |
| `server/src/services/mcp/index.ts` | 7 | Register event tools |
| `src/main/logseq_ai_hub/registry/store.cljs` | 4 | Add event-subscription + webhook-source categories |
| `src/main/logseq_ai_hub/registry/scanner.cljs` | 4 | Append subscription + source scans AFTER existing Promise.all |
| `src/main/logseq_ai_hub/messaging.cljs` | 4 | Add hub_event SSE listener |
| `src/main/logseq_ai_hub/core.cljs` | 4 | Add settings, init event hub |
| `src/main/logseq_ai_hub/job_runner/executor.cljs` | 5 | Register :http-request + :emit-event executors |
| `src/main/logseq_ai_hub/job_runner/runner.cljs` | 6 | Add *emit-event-fn* and lifecycle emission |

---

## Risk Mitigation

1. **Scanner 5-slot invariant**: Chain `.then` AFTER `refresh-registry!`, do NOT modify existing Promise.all. Verify existing scanner tests pass.
2. **SSE type collision**: Nest HubEvent under `payload` key in broadcast data. SSEManager spreads `event.data`, so `{ type: "hub_event", data: { payload: hubEvent } }` produces `{ type: "hub_event", payload: {...} }`.
3. **Chain depth infinite loops**: Guard at depth >= 5, implemented in EventBus.publish (server) and emit-event executor (plugin). Both enforce limit.
4. **Feedback loops from graph watcher**: Filter Events/* and Jobs/* pages. 2s debounce prevents storms. Chain depth guard limits recursion.
5. **Dynamic vars and async**: All DI uses `set!` and `def ^:dynamic` (not `with-redefs`).
