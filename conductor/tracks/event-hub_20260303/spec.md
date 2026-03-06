# Specification: Event Hub System

## Overview

A universal event hub serving as the central nervous system of Logseq AI Hub. Provides a unified publish/subscribe architecture that captures events from external webhooks, internal system operations, and user-defined sources — with page-based configuration, persistent storage, pattern-matched subscriptions, and event-driven automation triggers.

Replaces the narrower IoT/Infrastructure Hooks track with a comprehensive event system that connects all subsystems through a single event bus.

## Background

The system already has:
- **SSEManager** singleton — broadcasts 16 event types to connected clients
- **Agent Bridge** — SSE + HTTP callback pattern for server↔plugin RPC with 33+ operations
- **Job Runner** — 12 step action types, cron scheduler, priority queue, pluggable executor registry
- **Registry** — tag-based page discovery for tools, skills, prompts, procedures, agents
- **Secrets vault** — `{{secret.KEY}}` interpolation for API keys and tokens
- **MCP Server** — 29+ tools via `@modelcontextprotocol/sdk`
- **Approval/HITL** — Promise-based blocking with webhook correlation
- **Dynamic Registry** — syncs page-defined tools into MCP server
- **Enriched LLM pipeline** — `[[MCP/...]]`, `[[AI-Memory/...]]`, `[[Page]]` reference resolution

What's missing:
- **Unified event bus** — no pub/sub beyond SSE broadcast; no pattern matching, subscriptions, or event persistence
- **Generic webhook ingestion** — only WhatsApp and Telegram webhooks are supported
- **Outbound HTTP actions** — no way to call external APIs from skills/tools
- **Event-driven automation** — jobs can only be triggered manually, via cron, or from chat; no reactive event triggers
- **Internal event emission** — subsystem lifecycle events (job completion, registry changes, graph edits) are not observable
- **Custom events from skills** — skills cannot emit events that trigger other skills

## Dependencies

- **mcp-server_20260221**: MCP server for event management tools (7 new tools).
- **human-in-loop_20260221**: Approval system for escalation-based alert routing.
- **kb-tool-registry_20260221**: Registry for event subscription and webhook source page discovery.
- **secrets-manager_20260221**: Secret interpolation (`{{secret.KEY_NAME}}`) for webhook tokens, HTTP headers, and auth.

## Architecture

### Bidirectional Event Flow

```
External Sources ──► POST /webhook/event/:source ──► EventBus (Server)
                                                        │
                                                        ├── Store in SQLite
                                                        ├── Broadcast via SSE (hub_event)
                                                        └── Match subscriptions
                                                              │
                                              ┌───────────────▼──────────────┐
                                              │   Plugin Event Dispatcher     │
                                              │   - Graph page persistence   │
                                              │   - Subscription matching    │
                                              │   - Job/skill auto-trigger   │
                                              └───────────────┬──────────────┘
                                                              │
                              Plugin emitters ──► POST /api/events/publish ──► EventBus
                              (job runner, registry, graph watcher, :emit-event step)
```

1. **Server → Plugin**: EventBus broadcasts `hub_event` SSE events with HubEvent nested under a `payload` key (avoids type field collision with SSE serialization). Plugin dispatcher processes events.

2. **Plugin → Server**: Plugin POSTs events to `POST /api/events/publish` (authenticated with `pluginApiToken`). Server EventBus stores, broadcasts, and matches subscriptions.

### SSE Serialization

The existing `SSEManager.broadcast()` spreads `event.data` into the JSON payload:
```typescript
const data = JSON.stringify({ type: event.type, ...event.data });
```

To prevent HubEvent's `type` field from colliding with the SSE `type`, the EventBus nests events:
```typescript
this.sseManager.broadcast({
  type: "hub_event",
  data: { payload: hubEvent }  // nested, not spread
});
// Produces: { type: "hub_event", payload: { id: "...", type: "webhook.received", ... } }
```

### Event Envelope

Every event follows this structure:

```typescript
interface HubEvent {
  id: string;                    // UUID
  type: string;                  // e.g., "webhook.received", "job.completed"
  source: string;                // e.g., "webhook:grafana", "system:job-runner"
  timestamp: string;             // ISO 8601
  data: Record<string, unknown>; // Event-specific payload
  metadata?: {
    trace_id?: string;           // For correlation
    severity?: "info" | "warning" | "error" | "critical";
    tags?: string[];             // User-defined tags
    ttl?: number;                // Seconds before auto-cleanup
    chain_depth?: number;        // For loop prevention (max: 5)
  };
}
```

## Functional Requirements

### FR-1: EventBus Core (Server-Side)

**Description:** Server-side event bus wrapping SSEManager with persistent storage, pattern matching, and subscription support.

**Acceptance Criteria:**
- EventBus wraps SSEManager as transport layer; SSEManager code unchanged.
- `publish(event)` stores in SQLite, broadcasts via SSE as `hub_event`, matches subscriptions.
- Pattern matching: glob-style on event types (`job.*` matches `job.completed`).
- Debounce: per-subscription configurable windowing.
- `"hub_event"` added to SSEEvent type union.
- HubEvent nested under `payload` key in SSE data (no type collision).

**Priority:** P0

### FR-2: Event Store (SQLite)

**Description:** Persistent event log in SQLite with query and pruning capabilities.

**SQLite Schema:**
```sql
CREATE TABLE events (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  source TEXT NOT NULL,
  data TEXT NOT NULL,         -- JSON
  metadata TEXT,              -- JSON
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_events_type ON events(type);
CREATE INDEX idx_events_source ON events(source);
CREATE INDEX idx_events_created ON events(created_at);
```

**Acceptance Criteria:**
- Events table created on server startup.
- `insertEvent`, `queryEvents`, `pruneEvents`, `countEvents` data access functions.
- Query supports filtering by type, source, since, limit, offset.
- Auto-pruning with configurable retention (default: 30 days).

**Priority:** P0

### FR-3: Generic Webhook Ingestion

**Description:** Configurable webhook endpoint accepting payloads from any external service.

**Endpoint:** `POST /webhook/event/:source`

**Acceptance Criteria:**
- Accepts any JSON payload where `source` is a user-defined identifier (e.g., `grafana`, `home-assistant`, `github-actions`).
- Source name validated: alphanumeric + hyphens only.
- Payload size limit: 1MB (413 for larger).
- Rate limiting: 100 per minute per source (429 when exceeded).
- Constructs HubEvent with `type: "webhook.received"`, `source: "webhook:{source}"`.
- Optional per-source token verification via `Authorization` header.
- Returns `200 { eventId: string }`.
- `GET /webhook/event/:source` supports verification challenge flows.

**Priority:** P0

### FR-4: Plugin-to-Server Event Publishing

**Description:** Authenticated endpoint for the plugin to emit internal events to the server's EventBus.

**Endpoint:** `POST /api/events/publish`

**Acceptance Criteria:**
- Authenticated with Bearer token (same `pluginApiToken` as Agent Bridge).
- Accepts `{type, source, data, metadata?}`, server assigns `id` and `timestamp`.
- Publishes via EventBus (stored + broadcast + subscription matching).
- Returns `{ eventId: string }`.
- Plugin helper `publish-to-server!` uses same auth/fetch pattern as `send-callback!`.

**Priority:** P0

### FR-5: Event Subscription Pages

**Description:** Page-as-configuration for event subscriptions, following existing registry pattern.

**Page Format:**
```
event-pattern:: webhook.grafana.*
event-action:: skill
event-skill:: Skills/alert-handler
event-debounce:: 0
event-route-to:: whatsapp:15551234567
event-severity-filter:: warning,error,critical
event-description:: Handle Grafana alerts
tags:: logseq-ai-hub-event-subscription
```

**Acceptance Criteria:**
- Pages tagged `logseq-ai-hub-event-subscription` discovered by registry scanner.
- Scanner extension uses separate `scan-and-parse-type!` calls appended after existing 5-slot destructuring (no modification to existing `[tools prompts procedures skills agents]`).
- `event-pattern::` supports glob patterns (`job.*`, `webhook.grafana.*`).
- `event-action::` supports `skill` (trigger job), `route` (send message), `log` (persist only).
- `event-severity-filter::` filters by event metadata severity.
- `event-debounce::` configures debounce window in ms (0 = immediate).

**Priority:** P0

### FR-6: Plugin Event Dispatcher

**Description:** Plugin-side event handler that processes incoming hub events from SSE.

**Acceptance Criteria:**
- Listens for `hub_event` SSE events, extracts HubEvent from `payload` key.
- Persists events to Logseq graph as child blocks under `Events/{Source}/{Name}` pages.
- Matches against loaded subscriptions.
- `event-action:: skill` triggers job creation with event data as inputs.
- Dynamic vars (`*enqueue-job-fn*`, `*read-skill-fn*`) for dependency injection.

**Priority:** P0

### FR-7: `:http-request` Step Action

**Description:** Job runner step for outbound HTTP requests to external APIs.

**Step Config:**
```
step-order:: 1
step-action:: http-request
step-config:: {
  "url": "https://api.example.com/deploy",
  "method": "POST",
  "headers": {"Authorization": "Bearer {{secret.DEPLOY_TOKEN}}"},
  "body": {"environment": "production", "version": "{{step-1-result}}"},
  "timeout": 10000
}
```

**Acceptance Criteria:**
- Supports GET, POST, PUT, PATCH, DELETE methods.
- Headers and body support `{{variable}}` template interpolation.
- `{{secret.KEY_NAME}}` resolves from secrets vault via existing `*resolve-secret*`.
- Response available as step result (`{status, ok, headers, body}`).
- Timeout configurable per step (default: 10s, max: 60s) via `AbortController`.
- URL allowlist in plugin settings (`httpAllowlist` JSON array of domain patterns).
- TLS enforced (no `http://` URLs unless localhost).
- Helper functions: `interpolate-map`, `interpolate-json`, `url-allowed?`, `abort-after`, `parse-response` defined in `event_hub/http.cljs`.

**Priority:** P0

### FR-8: `:emit-event` Step Action

**Description:** Job runner step that publishes a custom event to the EventBus.

**Step Config:**
```
step-order:: 2
step-action:: emit-event
step-config:: {
  "type": "custom.deployment_complete",
  "data": {"environment": "production", "version": "{{step-1-result}}"},
  "metadata": {"severity": "info"}
}
```

**Acceptance Criteria:**
- Publishes event via `publish-to-server!` (plugin → server).
- Event type and data support template interpolation.
- Source set to `skill:{job-id}`.
- Dynamic var `*publish-event-fn*` for DI.
- Enables event chains: skill A emits event → subscription matches → skill B triggered.

**Priority:** P0

### FR-9: Job Runner Event Emission

**Description:** Automatic lifecycle event emission from the job runner.

**Events:**
- `job.created` — enqueue
- `job.started` — execution begins
- `job.completed` — success
- `job.failed` — error
- `job.cancelled` — cancellation
- `skill.executed` — skill completion
- `job.step.completed` — per-step (optional, high volume)

**Acceptance Criteria:**
- Dynamic var `*emit-event-fn*` in `runner.cljs`, wired to `publish-to-server!`.
- Events include job ID, skill ID, and trace ID in data/metadata.
- Subscriptions can react to job lifecycle (e.g., notify on failure).
- No impact on existing job runner behavior (emission is fire-and-forget).

**Priority:** P1

### FR-10: Graph Change Events

**Description:** Emit events when Logseq graph pages are created, updated, or deleted.

**Events:**
- `graph.page.created`, `graph.page.updated`, `graph.page.deleted`
- `graph.block.changed`

**Acceptance Criteria:**
- Uses `logseq.DB.onChanged` (same hook as registry watcher).
- Debounced at 2s to avoid event storms.
- Filters out `Events/*` pages (prevent feedback loops).
- Filters out `Jobs/*` pages (already covered by job events).
- Configurable page prefix filter.

**Priority:** P1

### FR-11: MCP Tools for Event Hub

**Tools:**
| Tool | Description | Parameters |
|---|---|---|
| `event_publish` | Publish a custom event | `{type, source?, data, metadata?}` |
| `event_query` | Query stored events | `{type?, source?, since?, limit?}` |
| `event_subscribe` | Create event subscription page | `{pattern, action, skill?, route_to?}` |
| `event_sources` | List configured webhook sources | `{}` |
| `event_recent` | Get recent events | `{source?, limit?}` |
| `webhook_test` | Send test webhook event | `{source, payload}` |
| `http_request` | Outbound HTTP request | `{url, method, headers?, body?, timeout?}` |

**Acceptance Criteria:**
- All 7 tools registered in MCP server.
- `event_query` reads from SQLite event store.
- `event_publish` calls EventBus directly.
- `http_request` respects URL allowlist.

**Priority:** P1

### FR-12: Webhook Source Configuration Pages

**Description:** Logseq pages that configure how incoming webhooks from a source are processed.

**Page Format:**
```
webhook-source:: grafana
webhook-description:: Grafana alerting webhooks
webhook-verify-token:: {{secret.GRAFANA_WEBHOOK_TOKEN}}
webhook-extract-title:: $.alerts[0].labels.alertname
webhook-extract-severity:: $.alerts[0].labels.severity
webhook-extract-message:: $.alerts[0].annotations.summary
webhook-page-prefix:: Events/Grafana
webhook-route-to:: whatsapp:15551234567
webhook-auto-job:: Skills/alert-handler
tags:: logseq-ai-hub-webhook-source
```

**Acceptance Criteria:**
- Pages tagged `logseq-ai-hub-webhook-source` discovered by registry scanner.
- `webhook-extract-*` uses JSONPath expressions to extract fields from payloads.
- `webhook-verify-token` supports `{{secret.KEY}}` interpolation.
- `webhook-route-to` forwards formatted alert via messaging.
- `webhook-auto-job` triggers skill with event data as inputs.
- Missing source config: webhooks stored with default formatting (no crash).
- Runbook `[[Page]]` references in job step prompts resolved via enriched/call.

**Priority:** P1

### FR-13: Alert Routing Engine

**Description:** Route webhook alerts to appropriate humans or agents.

**Acceptance Criteria:**
- Routes defined in webhook source config (`webhook-route-to`).
- Routes can target messaging contacts (`whatsapp:15551234567`) and/or skills.
- Severity-based routing: different contacts for different severity levels.
- Escalation: if no response within N minutes, route to secondary (uses approval system).
- Routing is fire-and-forget for notifications, awaitable for job triggers.

**Priority:** P2

### FR-14: Infrastructure Service Monitoring

**Description:** Convention for monitoring infrastructure services via the Event Hub.

**Page Format:**
```
service-name:: Production API
service-url:: https://api.example.com
service-health-endpoint:: https://api.example.com/health
service-status:: healthy
service-last-check:: 2026-03-03T10:00:00Z
service-check-interval:: 300
service-alert-contact:: whatsapp:15551234567
tags:: logseq-ai-hub-service
```

**Acceptance Criteria:**
- Service pages tagged `logseq-ai-hub-service` discoverable by registry.
- Health checks run as scheduled skills using `:http-request` + `:emit-event` steps.
- State transitions publish `service.health.changed` events.
- Subscriptions react to health changes (alerts, runbooks).

**Priority:** P2

### FR-15: Slash Commands

| Command | Description |
|---|---|
| `event:list` | List event sources and active subscriptions |
| `event:recent` | Show last 10 events as formatted block |
| `event:test` | Emit test event and report matched subscriptions |
| `event:sources` | List configured webhook sources |

**Priority:** P2

### FR-16: Chain Depth Guard

**Description:** Prevent infinite event loops when events trigger skills that emit events.

**Acceptance Criteria:**
- Chain depth tracked in `metadata.chain_depth`, incremented on each event-triggered job.
- Max depth: 5. Exceeded depth logs warning and skips trigger.
- Duplicate event detection within 1s window (same type + source + data hash).

**Priority:** P0

## Non-Functional Requirements

### NFR-1: Security
- Webhook tokens verified per-source via `Authorization` header.
- `{{secret.KEY_NAME}}` interpolation for tokens, HTTP headers, bodies.
- URL allowlist restricts outbound HTTP to configured domains.
- Webhook payloads stored in SQLite with standard protections.
- Event data sanitized through existing redaction utilities.
- `POST /api/events/publish` requires Bearer token authentication.

### NFR-2: Scalability
- Webhook ingestion handles 100 events/minute per source.
- SQLite event store pruned with configurable retention (default: 30 days).
- Graph event watcher debounced at 2s to prevent storms.
- Feedback loop prevention via chain depth guard and duplicate detection.

### NFR-3: Extensibility
- New webhook sources added by creating a Logseq page (no code changes).
- New event subscriptions added by creating a tagged page.
- Custom events emitted from skills via `:emit-event` step (no code changes).
- Event type taxonomy is hierarchical and extensible without code changes.
- All new step types are additive — existing job runner behavior unchanged.

### NFR-4: Compatibility
- SSEManager unchanged — EventBus wraps it as transport layer.
- Scanner 5-slot destructuring unchanged — new scans appended after existing Promise.all.
- Existing 16 SSE event types unaffected — `hub_event` added as 17th type.
- Same auth pattern as Agent Bridge (`pluginApiToken` Bearer token).

## Event Type Taxonomy

Hierarchical, dot-separated:

| Category | Events | Phase |
|---|---|---|
| Webhook | `webhook.received`, `webhook.{source}.received` | 1 |
| Job | `job.created`, `job.started`, `job.completed`, `job.failed`, `job.cancelled` | 1 |
| Job Step | `job.step.completed`, `job.step.failed` | 2 |
| Skill | `skill.executed`, `skill.failed` | 2 |
| Graph | `graph.page.created`, `graph.page.updated`, `graph.page.deleted`, `graph.block.changed` | 3 |
| Custom | `custom.*` (user-defined via `:emit-event` step) | 2 |
| Service | `service.health.checked`, `service.health.changed` | 3 |
| Approval | `approval.created`, `approval.resolved`, `approval.timeout` | 6 |
| Registry | `registry.refreshed`, `registry.entry.added`, `registry.entry.removed` | 6 |
| MCP | `mcp.tool.invoked`, `mcp.tool.completed` | 6 |
| Messaging | `message.received`, `message.sent` | 6 |

## User Stories

### US-1: Receive Grafana alerts in Logseq
**As** a DevOps engineer,
**I want** Grafana alerts to appear as blocks in my Logseq graph,
**So that** I can track incidents alongside my operational knowledge.

### US-2: Automated incident response
**As** a developer,
**I want** a skill that automatically checks logs when a service goes down,
**So that** preliminary diagnosis starts before I even see the alert.

### US-3: Event-driven skill chains
**As** a power user,
**I want** skills to emit events that trigger other skills,
**So that** I can build multi-stage automation workflows without cron scheduling.

### US-4: Claude Code triggers deployments
**As** a developer using Claude Code,
**I want** Claude Code to trigger a deployment via the Hub's HTTP tools,
**So that** the deployment goes through the proper approval flow.

### US-5: Infrastructure dashboard
**As** an IT operator,
**I want** to ask Claude Code "What's the status of all our services?",
**So that** I get a quick overview without logging into multiple dashboards.

### US-6: React to graph changes
**As** a knowledge worker,
**I want** specific automations to fire when I create or edit certain pages,
**So that** my knowledge base maintenance is partially automated.

## Plugin Settings

| Setting | Type | Default | Description |
|---|---|---|---|
| `eventHubEnabled` | boolean | `true` | Enable/disable Event Hub |
| `httpAllowlist` | string (JSON array) | `"[]"` | Domain patterns for outbound HTTP |
| `eventRetentionDays` | number | `30` | Days before auto-pruning SQLite events |
| `eventGraphPersistence` | boolean | `true` | Persist events to Logseq graph pages |

## Out of Scope

- MQTT/WebSocket protocol support (HTTP webhooks only for v1).
- Device management (provisioning, firmware updates).
- Time-series data storage (use dedicated tools like InfluxDB/Grafana).
- Dashboard UI (Logseq pages serve as the "dashboard").
- Complex event processing (CEP) or stream processing.
- Direct cloud provider integrations (AWS, GCP, Azure) — use their webhook/SNS features.
- Modifying SSEManager internals — EventBus wraps it.

## Open Questions

1. **JSONPath library:** `jsonpath-plus` (npm) for server-side extraction? Evaluate bundle size and security.
2. **Health check execution:** Server-side (direct HTTP) vs plugin-side (via skills). Recommendation: scheduled skills using `:http-request` steps — reuses existing infrastructure.
3. **Event deduplication window:** 1s default for duplicate detection. Is this sufficient for high-throughput scenarios?
