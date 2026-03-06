# Event Hub System - Comprehensive Implementation Plan

## Vision

Transform the narrow IoT/Infrastructure Hooks track into a **universal Event Hub** - the central nervous system of Logseq AI Hub. The Event Hub unifies all event flows (external webhooks, internal system events, user-defined events) into a single publish/subscribe architecture with page-based configuration, persistent storage, and event-driven automation triggers.

## Architecture Overview

```
                         ┌─────────────────────────────────────┐
                         │         External Sources            │
                         │  Grafana, GitHub, Home Assistant,   │
                         │  Stripe, Telegram, WhatsApp, etc.   │
                         └────────────┬────────────────────────┘
                                      │ POST /webhook/event/:source
                         ┌────────────▼────────────────────────┐
                         │        EventHub (Server Layer)       │
                         │  ┌──────────────────────────────┐   │
                         │  │  SSEManager (transport layer) │   │
                         │  └──────────┬───────────────────┘   │
                         │  ┌──────────▼───────────────────┐   │
                         │  │  EventBus (matching/routing)  │   │
                         │  │  - Pattern matching           │   │
                         │  │  - Subscription registry      │   │
                         │  │  - Debounce/batch support      │   │
                         │  └──────────┬───────────────────┘   │
                         │  ┌──────────▼───────────────────┐   │
                         │  │  EventStore (SQLite)          │   │
                         │  │  - Persistent event log       │   │
                         │  │  - Query/filter/replay        │   │
                         │  └──────────────────────────────┘   │
                         │                                      │
                         │  POST /api/events/publish  ◄─────────┤ Plugin can POST events
                         └────────────┬────────────────────────┘
                                      │ SSE (hub_event) + Bridge
                         ┌────────────▼────────────────────────┐
                         │     EventHub (Plugin Layer)          │
                         │  ┌──────────────────────────────┐   │
                         │  │  Event Dispatcher             │   │
                         │  │  - Graph page persistence     │   │
                         │  │  - Subscription matching      │   │
                         │  │  - Job/skill auto-trigger     │   │
                         │  └──────────────────────────────┘   │
                         │                                      │
                         │  Internal Emitters (→ POST /api/events/publish):
                         │  ├── Job Runner (lifecycle events)   │
                         │  ├── Registry (change events)        │
                         │  ├── Graph Watcher (page events)     │
                         │  └── Skill Executor (emit-event)     │
                         └──────────────────────────────────────┘
```

### Bidirectional Event Flow

Events flow in two directions:

1. **Server → Plugin** (existing pattern): Server broadcasts `hub_event` SSE events. Plugin's event dispatcher listens and processes (graph persistence, subscription matching, job triggering).

2. **Plugin → Server** (new): Plugin emits events via `POST /api/events/publish` (same endpoint used by bridge callbacks — authenticated with `pluginApiToken`). The server's EventBus receives, stores in SQLite, broadcasts via SSE, and matches subscriptions. This is how job lifecycle events, graph change events, and `:emit-event` step results reach the EventBus.

## Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| SSE evolution | Wrap with new layer | EventHub wraps SSEManager as transport. Lower risk, no changes to existing ~8 importers. |
| SSE serialization | Nest under `payload` key | Prevents HubEvent.type from colliding with SSE type field in broadcast spread. See SSE Serialization below. |
| Trigger mode | Both, immediate default | Subscriptions default to immediate job trigger. Can opt into debounce/batch via page properties. |
| Initial scope | Core subset first | Phase 1: webhooks + job lifecycle + graph changes. Phase 2+: remaining subsystems. |
| Graph storage | Single page per source | `Events/{source}` pages with events as child blocks. Avoids page explosion. |
| Event identity | Structured event envelope | `{id, type, source, timestamp, data, metadata}` - consistent across all event types. |
| Subscription config | Page-as-configuration | Tagged Logseq pages define subscriptions, following existing registry pattern. |
| Plugin-to-server events | POST /api/events/publish | Plugin POSTs events to server using existing auth pattern (same as `send-callback!`). |
| Scanner extension | Separate scan calls, not slot extension | Add new `scan-and-parse-type!` calls and append to results vector. Do NOT modify the existing 5-slot destructuring — use `concat` to merge results. |

### SSE Serialization: Avoiding the Type Collision

**Problem:** `SSEManager.broadcast()` currently serializes as:
```typescript
// sse.ts line 44
const data = JSON.stringify({ type: event.type, ...event.data });
```
If we pass `{ type: "hub_event", data: hubEvent }`, the spread produces `{ type: "hub_event", ...hubEvent }` which contains `hubEvent.type` (e.g. `"webhook.received"`) — **overwriting** the SSE `type` field.

**Solution:** Nest the HubEvent under a `payload` key instead of spreading directly:
```typescript
// EventBus.publish() calls:
this.sseManager.broadcast({
  type: "hub_event",
  data: { payload: hubEvent }  // nested, not spread
});
// Produces: { type: "hub_event", payload: { id: "...", type: "webhook.received", ... } }
```

Plugin-side listener extracts `(get event-data "payload")` to get the HubEvent. This matches the existing pattern where different SSE event types structure their `data` field differently.

## Unified Event Envelope

Every event in the system follows this structure:

```typescript
interface HubEvent {
  id: string;                    // UUID
  type: string;                  // e.g., "webhook.received", "job.completed", "graph.page_created"
  source: string;                // e.g., "webhook:grafana", "system:job-runner", "user:my-skill"
  timestamp: string;             // ISO 8601
  data: Record<string, unknown>; // Event-specific payload
  metadata?: {
    trace_id?: string;           // For correlation
    severity?: "info" | "warning" | "error" | "critical";
    tags?: string[];             // User-defined tags
    ttl?: number;                // Seconds before auto-cleanup
  };
}
```

## Event Type Taxonomy

Hierarchical, dot-separated, extensible without code changes:

### External Events (Phase 1)
- `webhook.received` - Generic webhook ingestion
- `webhook.{source}.received` - Source-specific (e.g., `webhook.grafana.received`)

### Job Runner Events (Phase 1)
- `job.created`, `job.started`, `job.completed`, `job.failed`, `job.cancelled`
- `job.step.completed`, `job.step.failed`
- `skill.executed`, `skill.failed`

### Graph Events (Phase 1)
- `graph.page.created`, `graph.page.updated`, `graph.page.deleted`
- `graph.block.changed`

### Approval Events (Phase 2)
- `approval.created`, `approval.resolved`, `approval.timeout`

### Registry Events (Phase 2)
- `registry.refreshed`, `registry.entry.added`, `registry.entry.removed`

### MCP Events (Phase 2)
- `mcp.tool.invoked`, `mcp.tool.completed`, `mcp.tool.failed`

### Messaging Events (Phase 2)
- `message.received`, `message.sent`

### User-Defined Events (Phase 1)
- `custom.*` - Emitted by skills via `:emit-event` step type

---

## Phase 1: Event Hub Core Infrastructure
**Estimated: 8-10 hours**

### Task Dependency Order (within Phase 1)

```
1.1 Event Store ──► 1.2 EventBus ──► 1.4 Webhook Endpoint
       │                  │                    │
       │                  ▼                    │
       │            1.3 Event Types            │
       │                  │                    │
       │         1.5 Publish Endpoint          │
       │                  │                    │
       ▼                  ▼                    ▼
  1.6 Plugin Dispatcher ◄─── 1.7 Subscriptions ◄── 1.8 Init
       │
       ▼
  1.9 Bridge Operations
```

**Build order:** 1.1 → 1.3 → 1.2 → 1.5 → 1.4 → 1.7 → 1.6 → 1.9 → 1.8

### 1.1 Event Store (SQLite)
**Files:** `server/src/db/schema.ts`, new `server/src/db/events.ts`

Add SQLite table for persistent event storage:
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

Data access layer (`events.ts`):
- `insertEvent(event: HubEvent): void`
- `queryEvents(opts: {type?, source?, since?, limit?, offset?}): HubEvent[]`
- `pruneEvents(olderThan: string): number` (retention cleanup)
- `countEvents(opts: {type?, source?, since?}): number`

### 1.2 EventBus Service (Server)
**Files:** new `server/src/services/event-bus.ts`
**Depends on:** 1.1 (EventStore), 1.3 (HubEvent type)

The EventBus wraps SSEManager and adds:
- **Event publication**: `publish(event: HubEvent)` - stores in SQLite, broadcasts via SSEManager, matches subscriptions
- **Subscription registry**: In-memory map of active subscriptions loaded from plugin via bridge
- **Pattern matching**: `matchPattern(pattern: string, type: string): boolean` - glob-style matching on event types (e.g., `job.*` matches `job.completed`)
- **Debounce/batch**: `debounceAndTrigger(sub, event)` - per-subscription configurable windowing using `setTimeout` with configurable delay

```typescript
class EventBus {
  private subscriptions: Map<string, EventSubscription> = new Map();
  private debounceTimers: Map<string, ReturnType<typeof setTimeout>> = new Map();

  constructor(
    private sseManager: SSEManager,
    private eventStore: EventStore
  ) {}

  async publish(event: HubEvent): Promise<void> {
    // 1. Store in SQLite
    this.eventStore.insertEvent(event);
    // 2. Broadcast via SSE — nest under `payload` key to avoid type collision
    this.sseManager.broadcast({
      type: "hub_event",
      data: { payload: event }
    });
    // 3. Match against server-side subscriptions (e.g. webhook-route-to alerts)
    await this.matchSubscriptions(event);
  }

  matchPattern(pattern: string, eventType: string): boolean {
    // Convert glob to regex: "job.*" → /^job\..*$/
    const regex = new RegExp(
      "^" + pattern.replace(/\./g, "\\.").replace(/\*/g, ".*") + "$"
    );
    return regex.test(eventType);
  }

  loadSubscriptions(subs: EventSubscription[]): void {
    this.subscriptions.clear();
    for (const sub of subs) {
      this.subscriptions.set(sub.id, sub);
    }
  }

  private async matchSubscriptions(event: HubEvent): Promise<void> {
    for (const sub of this.subscriptions.values()) {
      if (this.matchPattern(sub.pattern, event.type)) {
        if (sub.debounceMs && sub.debounceMs > 0) {
          this.debounceAndTrigger(sub, event);
        } else {
          await this.triggerAction(sub, event);
        }
      }
    }
  }

  private debounceAndTrigger(sub: EventSubscription, event: HubEvent): void {
    const existing = this.debounceTimers.get(sub.id);
    if (existing) clearTimeout(existing);
    const timer = setTimeout(() => {
      this.debounceTimers.delete(sub.id);
      this.triggerAction(sub, event);
    }, sub.debounceMs!);
    this.debounceTimers.set(sub.id, timer);
  }

  private async triggerAction(sub: EventSubscription, event: HubEvent): Promise<void> {
    // Server-side actions: route-to (send message), webhook forwarding
    // Plugin-side actions (job triggering) handled by plugin's dispatcher
    if (sub.routeTo) {
      // Forward to messaging (POST /api/send internally)
      // Implementation in Phase 4 alert routing
    }
  }
}
```

### 1.3 Event Types Extension
**Files:** `server/src/types.ts`, new `server/src/types/event.ts`

Add `"hub_event"` to the existing SSEEvent type union in `types.ts`:
```typescript
export interface SSEEvent {
  type: "connected" | "new_message" | /* ...existing 16 types... */ | "hub_event";
  data: Record<string, unknown>;
}
```

New `server/src/types/event.ts`:
```typescript
export interface HubEvent {
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
  };
}

export interface EventSubscription {
  id: string;              // Unique ID (page name or UUID)
  pattern: string;         // Glob pattern e.g. "webhook.grafana.*"
  action: "skill" | "route" | "log";
  skillId?: string;        // Skill page to trigger
  routeTo?: string;        // "whatsapp:15551234567"
  debounceMs?: number;     // Debounce window in ms (0 = immediate)
  severityFilter?: string[]; // Only match these severities
  description?: string;
}

export interface WebhookSourceConfig {
  source: string;          // Source identifier e.g. "grafana"
  description?: string;
  verifyToken?: string;    // Supports {{secret.KEY}}
  extractRules?: Record<string, string>; // field → JSONPath
  pagePrefix?: string;     // Override default Events/{source}
  routeTo?: string;        // Auto-route to contact
  autoJob?: string;        // Auto-trigger skill
}
```

### 1.4 Generic Webhook Endpoint
**Files:** new `server/src/routes/webhooks/generic.ts`, modify `server/src/router.ts`
**Depends on:** 1.2 (EventBus)

Route: `POST /webhook/event/:source`

Handler:
1. Validate source name (alphanumeric + hyphens, regex: `/^[a-zA-Z0-9-]+$/`)
2. Optional token verification (per-source from subscription registry)
3. Accept any JSON payload (max 1MB, reject with 413 for larger)
4. Rate limit: 100 per minute per source (in-memory counter with 60s sliding window)
5. Construct `HubEvent` with `type: "webhook.received"`, `source: "webhook:{source}"`, `data: { raw: payload, source }`
6. Publish via EventBus
7. Return 200 with `{ eventId: string }`

Route: `GET /webhook/event/:source` (for verification flows like WhatsApp/Slack)

### 1.5 Plugin-to-Server Event Publish Endpoint
**Files:** modify `server/src/router.ts`, new `server/src/routes/events.ts`
**Depends on:** 1.2 (EventBus)

Route: `POST /api/events/publish`
Auth: Bearer token (same `pluginApiToken` as Agent Bridge)

This is the endpoint the plugin uses to emit events from internal subsystems (job lifecycle, graph changes, custom events from skills). Request body:

```typescript
interface PublishRequest {
  type: string;       // e.g. "job.completed"
  source: string;     // e.g. "system:job-runner"
  data: Record<string, unknown>;
  metadata?: HubEvent["metadata"];
}
```

The server assigns `id` (UUID) and `timestamp`, then calls `eventBus.publish()`. Returns `{ eventId: string }`.

Plugin-side helper function (`event_hub/client.cljs`):
```clojure
(defn publish-to-server!
  "POSTs an event to the server's EventBus. Returns Promise<event-id>.
   Uses the same auth pattern as send-callback! in agent_bridge.cljs."
  [event-map]
  (let [server-url (aget js/logseq.settings "webhookServerUrl")
        token (aget js/logseq.settings "pluginApiToken")
        url (str server-url "/api/events/publish")]
    (-> (js/fetch url
          (clj->js {:method "POST"
                    :headers {"Content-Type" "application/json"
                              "Authorization" (str "Bearer " token)}
                    :body (js/JSON.stringify (clj->js event-map))}))
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.json resp)
                   (js/Promise.reject (js/Error. (str "Event publish failed: " (.-status resp)))))))
        (.then (fn [json] (.-eventId json))))))
```

### 1.6 Plugin Event Dispatcher
**Files:** new `src/main/logseq_ai_hub/event_hub/dispatcher.cljs`
**Depends on:** 1.7 (subscriptions), 1.5 (publish-to-server!)

Plugin-side event handling:
- Listens for `hub_event` SSE events
- Extracts HubEvent from `(get event-data "payload")` (nested, not spread — see SSE Serialization)
- Persists matching events to Logseq graph as child blocks under `Events/{source}` pages
- Triggers subscribed jobs/skills via job runner integration

```clojure
(ns logseq-ai-hub.event-hub.dispatcher
  (:require [logseq-ai-hub.event-hub.subscriptions :as subs]
            [logseq-ai-hub.event-hub.graph-store :as graph-store]))

(def ^:dynamic *enqueue-job-fn* nil)  ;; Wired in init to runner/enqueue-job!
(def ^:dynamic *read-skill-fn* nil)   ;; Wired in init to graph/read-skill-page

(defn- trigger-subscription!
  "Triggers the action defined by a subscription for a matched event."
  [sub event]
  (case (:action sub)
    "skill" (when (and *enqueue-job-fn* *read-skill-fn* (:skill-id sub))
              (-> (*read-skill-fn* (:skill-id sub))
                  (.then (fn [skill-def]
                           (when skill-def
                             (*enqueue-job-fn*
                               (str "Jobs/_event_" (:id event))
                               {:job-type "event-driven"
                                :job-skill (:skill-id sub)
                                :inputs {"event-type" (:type event)
                                         "event-source" (:source event)
                                         "event-data" (js/JSON.stringify (clj->js (:data event)))}}))))))
    "log" (graph-store/persist-event! event)
    nil))

(defn handle-hub-event
  "Processes an incoming hub event from SSE.
   Extracts the HubEvent from payload key, matches subscriptions, triggers actions."
  [event-data]
  (let [hub-event (js->clj (get event-data "payload") :keywordize-keys true)]
    (when hub-event
      ;; Persist to graph (Events/{source} page)
      (graph-store/persist-event! hub-event)
      ;; Match subscriptions and trigger actions
      (let [matched-subs (subs/match-event hub-event)]
        (doseq [sub matched-subs]
          (trigger-subscription! sub hub-event))))))
```

### 1.7 Event Subscription Pages
**Files:** new `src/main/logseq_ai_hub/event_hub/subscriptions.cljs`, extend `registry/scanner.cljs`
**Depends on:** 1.3 (type definitions)

Page format for subscriptions:
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

Page format for webhook source config:
```
webhook-source:: grafana
webhook-description:: Grafana alerting webhooks
webhook-verify-token:: {{secret.GRAFANA_WEBHOOK_TOKEN}}
webhook-extract-title:: $.alerts[0].labels.alertname
webhook-extract-severity:: $.alerts[0].labels.severity
webhook-extract-message:: $.alerts[0].annotations.summary
webhook-page-prefix:: Events/Grafana
tags:: logseq-ai-hub-webhook-source
```

**Subscription matching function:**
```clojure
(ns logseq-ai-hub.event-hub.subscriptions
  (:require [clojure.string :as str]))

(def ^:private subscriptions (atom []))

(defn load-subscriptions! [subs]
  (reset! subscriptions subs))

(defn- glob-matches?
  "Glob pattern matching for event types. Converts pattern to regex.
   'job.*' matches 'job.completed', 'job.failed', etc."
  [pattern event-type]
  (let [regex-str (-> pattern
                      (str/replace "." "\\.")
                      (str/replace "*" ".*"))
        regex (js/RegExp. (str "^" regex-str "$"))]
    (.test regex event-type)))

(defn match-event
  "Returns all subscriptions whose pattern matches the given event."
  [event]
  (filter (fn [sub]
            (and (glob-matches? (:pattern sub) (:type event))
                 ;; Severity filter
                 (if-let [filter-severities (:severity-filter sub)]
                   (contains? (set filter-severities)
                              (get-in event [:metadata :severity] "info"))
                   true)))
          @subscriptions))
```

**Scanner extension** — add to `registry-tags` map and extend `refresh-registry!`:

Instead of modifying the existing 5-slot destructuring at `scanner.cljs:101`:
```clojure
(let [[tools prompts procedures skills agents]
      (js->clj results :keywordize-keys true)]
```

We add new scan calls AFTER the existing `js/Promise.all` and merge results:
```clojure
;; In refresh-registry! after the existing scan, add event-hub scans:
(.then (fn [_]
         (js/Promise.all
           (clj->js
             [(scan-and-parse-type! (:event-subscription registry-tags)
                                    parser/parse-event-subscription)
              (scan-and-parse-type! (:webhook-source registry-tags)
                                    parser/parse-webhook-source)]))))
(.then (fn [event-results]
         (let [[event-subs webhook-sources] (js->clj event-results :keywordize-keys true)]
           ;; Store event subscriptions and webhook sources
           (doseq [sub event-subs]
             (store/add-entry sub))
           (doseq [src webhook-sources]
             (store/add-entry src)))))
```

And add to `registry-tags`:
```clojure
(def registry-tags
  {:tool "logseq-ai-hub-tool"
   :prompt "logseq-ai-hub-prompt"
   :procedure "logseq-ai-hub-procedure"
   :event-subscription "logseq-ai-hub-event-subscription"
   :webhook-source "logseq-ai-hub-webhook-source"})
```

### 1.8 Graph Persistence Store
**Files:** new `src/main/logseq_ai_hub/event_hub/graph_store.cljs`

Persists events as child blocks under `Events/{source}` pages:

```clojure
(ns logseq-ai-hub.event-hub.graph-store
  (:require [clojure.string :as str]))

(defn- source-to-page-name
  "Converts event source to Logseq page name.
   'webhook:grafana' → 'Events/Webhook/Grafana'"
  [source]
  (let [[category name] (str/split source #":" 2)]
    (str "Events/"
         (str/capitalize (or category "unknown"))
         "/"
         (str/capitalize (or name "unknown")))))

(defn- format-event-block
  "Formats a HubEvent as a block content string."
  [event]
  (str "**" (:type event) "** — " (:timestamp event) "\n"
       "source:: " (:source event) "\n"
       (when-let [sev (get-in event [:metadata :severity])]
         (str "severity:: " sev "\n"))
       "```json\n"
       (js/JSON.stringify (clj->js (:data event)) nil 2)
       "\n```"))

(defn persist-event!
  "Appends event as a child block under the source's Events page.
   Creates the page if it doesn't exist. Returns Promise<nil>."
  [event]
  (let [page-name (source-to-page-name (:source event))
        content (format-event-block event)]
    (-> (js/logseq.Editor.getPage page-name)
        (.then (fn [page]
                 (if page
                   (js/logseq.Editor.getPageBlocksTree page-name)
                   (-> (js/logseq.Editor.createPage
                         page-name
                         (clj->js {"event-source" (:source event)
                                   "event-type" "log"})
                         (clj->js {:createFirstBlock true}))
                       (.then (fn [_] (js/logseq.Editor.getPageBlocksTree page-name)))))))
        (.then (fn [blocks]
                 (when (and blocks (pos? (.-length blocks)))
                   (let [parent-uuid (.-uuid (aget blocks 0))]
                     (js/logseq.Editor.insertBlock parent-uuid content
                       (clj->js {:sibling false}))))))
        (.catch (fn [err]
                  (js/console.warn "Failed to persist event to graph:" err))))))
```

### 1.9 Plugin Event Hub Init
**Files:** new `src/main/logseq_ai_hub/event_hub/init.cljs`, modify `src/main/logseq_ai_hub/core.cljs`
**Depends on:** all of 1.1–1.8

Initialization wiring:
- Wire `*enqueue-job-fn*` to runner's `enqueue-job!`
- Wire `*read-skill-fn*` to graph's `read-skill-page`
- Register `hub_event` SSE listener that calls `dispatcher/handle-hub-event`
- Scan graph for subscription and source pages (via registry scanner extensions)
- Register event hub bridge operations
- Register slash commands: `event:list`, `event:recent`, `event:test`

### Phase 1 Acceptance Criteria

- [ ] SQLite `events` table created on server startup with correct schema and indexes
- [ ] `EventBus.publish()` stores event in SQLite, broadcasts via SSE as `hub_event` with nested `payload`, and matches subscriptions
- [ ] `POST /webhook/event/:source` accepts JSON, returns `{eventId}`, event appears in SQLite
- [ ] `POST /api/events/publish` accepts authenticated requests from plugin, returns `{eventId}`
- [ ] Plugin receives `hub_event` SSE, extracts from `payload` key, persists to `Events/{Source}/{Name}` page
- [ ] `logseq-ai-hub-event-subscription` tagged pages are discovered and loaded as subscriptions
- [ ] Subscription pattern matching works: `job.*` matches `job.completed` but not `webhook.received`
- [ ] Severity filtering works: subscription with `event-severity-filter:: error,critical` ignores `info` events
- [ ] Rate limiting: 101st webhook in a minute from same source returns 429
- [ ] Payload > 1MB returns 413
- [ ] `registry-tags` map includes `:event-subscription` and `:webhook-source` without breaking existing 5-slot destructuring

**Integration Test Scenarios:**
1. External service POSTs to `/webhook/event/grafana` → event stored in SQLite → SSE broadcast → plugin persists to `Events/Webhook/Grafana` page
2. Plugin emits `job.completed` via `POST /api/events/publish` → event stored → subscription with pattern `job.*` and `event-action:: skill` triggers skill execution
3. Two rapid webhooks within 500ms with subscription having `event-debounce:: 1000` → only one trigger fires

---

## Phase 2: Job Runner & Executor Integration
**Estimated: 6-8 hours**
**Depends on:** Phase 1 (EventBus, publish endpoint, dispatcher)

### 2.1 `:http-request` Step Action
**Files:** `executor.cljs`, `schemas.cljs`, `init.cljs`, new `event_hub/http.cljs`

New step executor for outbound HTTP requests. Helper functions defined in `event_hub/http.cljs`:

```clojure
(ns logseq-ai-hub.event-hub.http
  (:require [clojure.string :as str]
            [logseq-ai-hub.job-runner.interpolation :as interpolation]))

(defn interpolate-map
  "Interpolates all string values in a map using the template context.
   Non-string values are left unchanged."
  [m context]
  (when m
    (into {}
      (map (fn [[k v]]
             [k (if (string? v)
                  (interpolation/interpolate v context)
                  v)])
           m))))

(defn interpolate-json
  "Interpolates all string values in a nested JSON-like structure (map or vector).
   Recursively walks maps and vectors, interpolating string leaves."
  [structure context]
  (cond
    (string? structure) (interpolation/interpolate structure context)
    (map? structure) (into {} (map (fn [[k v]] [k (interpolate-json v context)]) structure))
    (vector? structure) (mapv #(interpolate-json % context) structure)
    :else structure))

(defn url-allowed?
  "Checks if the given URL's hostname matches the allowlist from plugin settings.
   If allowlist is empty or nil, all URLs are allowed (with warning).
   Supports simple glob patterns: '*.railway.app' matches 'my-app.railway.app'."
  [url]
  (let [allowlist-str (aget js/logseq.settings "httpAllowlist")
        allowlist (when (and allowlist-str (not (str/blank? allowlist-str)))
                    (js->clj (js/JSON.parse allowlist-str)))]
    (if (or (nil? allowlist) (empty? allowlist))
      (do (js/console.warn "HTTP allowlist is empty — all domains allowed")
          true)
      (let [parsed-url (js/URL. url)
            hostname (.-hostname parsed-url)]
        (some (fn [pattern]
                (let [regex-str (-> pattern
                                   (str/replace "." "\\.")
                                   (str/replace "*" ".*"))
                      regex (js/RegExp. (str "^" regex-str "$"))]
                  (.test regex hostname)))
              allowlist)))))

(defn abort-after
  "Creates an AbortSignal that auto-aborts after the given timeout in ms."
  [timeout-ms]
  (let [controller (js/AbortController.)]
    (js/setTimeout #(.abort controller) timeout-ms)
    (.-signal controller)))

(defn parse-response
  "Parses a fetch Response into a step result map."
  [response]
  (-> (.text response)
      (.then (fn [body]
               {:status (.-status response)
                :ok (.-ok response)
                :headers (js->clj (js/Object.fromEntries (.-headers response)))
                :body body}))))
```

Executor in `executor.cljs`:
```clojure
(defn execute-http-request
  [step context]
  (let [config (:step-config step)
        url (interpolation/interpolate (:url config) context)
        method (or (:method config) "GET")
        headers (http/interpolate-map (:headers config) context)
        body (when (:body config) (http/interpolate-json (:body config) context))
        timeout (min (or (:timeout config) 10000) 60000)]
    (if-not (http/url-allowed? url)
      (js/Promise.reject (js/Error. (str "URL not in allowlist: " url)))
      (-> (js/fetch url (clj->js {:method method
                                    :headers headers
                                    :body (when body (js/JSON.stringify (clj->js body)))
                                    :signal (http/abort-after timeout)}))
          (.then http/parse-response)))))
```

- Add `:http-request` to `schemas.cljs` step-action enum
- Register executor in `executor.cljs`
- Wire in `init.cljs`
- URL allowlist setting in plugin settings (`httpAllowlist` JSON array of domain patterns)
- `{{secret.KEY}}` interpolation in headers/body via existing interpolation engine

### 2.2 `:emit-event` Step Action
**Files:** `executor.cljs`, `schemas.cljs`

New step executor that publishes a custom event to the EventBus via `publish-to-server!`:

```clojure
(def ^:dynamic *publish-event-fn* nil) ;; Wired in init to client/publish-to-server!

(defn execute-emit-event
  [step context]
  (let [config (:step-config step)
        event-type (interpolation/interpolate (or (:type config) "custom.skill_event") context)
        event-data (http/interpolate-json (or (:data config) {}) context)
        source (str "skill:" (:job-id context))]
    (if *publish-event-fn*
      (*publish-event-fn* {:type event-type
                           :source source
                           :data event-data
                           :metadata (:metadata config)})
      (js/Promise.reject (js/Error. "Event publishing not initialized")))))
```

This enables skills to emit events that trigger other subscriptions, creating event chains.

### 2.3 Job Runner Event Emission
**Files:** `runner.cljs`, `engine.cljs`

Instrument the job runner to emit lifecycle events via `publish-to-server!`:
- `job.created` - When `enqueue-job!` adds a job to the queue
- `job.started` - When `execute-job!` begins execution
- `job.completed` - When `execute-job!` finishes successfully
- `job.failed` - When `execute-job!` catches an error
- `job.cancelled` - When a job is cancelled
- `skill.executed` - When `engine/execute-skill` completes
- `job.step.completed` - When each step finishes (optional, high volume)

Implementation: Add a dynamic var `*emit-event-fn*` in `runner.cljs`, wired in `init.cljs` to `client/publish-to-server!`. Call it at each lifecycle point. Example:

```clojure
;; In runner.cljs
(def ^:dynamic *emit-event-fn* nil)

(defn- emit-job-event! [event-type job-data]
  (when *emit-event-fn*
    (*emit-event-fn* {:type event-type
                      :source "system:job-runner"
                      :data job-data
                      :metadata {:trace_id (:trace-id job-data)}})))

;; Usage in execute-job!:
(emit-job-event! "job.started" {:job-id page-name :skill (:skill job-def)})
```

### 2.4 Event-Driven Job Triggering
**Files:** `event_hub/dispatcher.cljs`

When a subscription matches with `event-action:: skill`:
1. Read the skill page via `*read-skill-fn*` (dynamic var, avoids circular deps)
2. Create a job page (like the scheduler does for cron jobs)
3. Inject the event data as job inputs (`event-data`, `event-type`, `event-source`)
4. Enqueue via `*enqueue-job-fn*`
5. **Chain depth guard**: Track chain depth in event metadata. Max depth: 5. If exceeded, log warning and skip trigger.

This replaces the placeholder `:event-driven` job-type in schemas.cljs with a real implementation.

### Phase 2 Acceptance Criteria

- [ ] `:http-request` step makes real HTTP calls with interpolated URL, headers, body
- [ ] `{{secret.API_KEY}}` in headers resolves correctly via existing secret vault
- [ ] URL allowlist blocks requests to non-allowed domains
- [ ] Timeout aborts requests after configured duration (default 10s, max 60s)
- [ ] `:emit-event` step publishes event to server via `POST /api/events/publish`
- [ ] Job lifecycle events (`job.created`, `job.started`, `job.completed`, `job.failed`) are emitted automatically
- [ ] Event-driven job triggering: subscription with `event-action:: skill` creates and enqueues a job on matching event
- [ ] Chain depth guard prevents infinite loops (max 5 levels)
- [ ] Existing job runner behavior unchanged — all new step types are additive

**Integration Test Scenarios:**
1. Skill with `:http-request` step calling httpbin.org → response body in step result → next step can use `{{step-1-result}}`
2. Skill with `:emit-event` step → event appears in SQLite → subscription triggers another skill → verify chain works
3. Chain depth > 5 → warning logged, no further triggers

---

## Phase 3: Graph Event Watcher & MCP Tools
**Estimated: 6-8 hours**
**Depends on:** Phase 1. Can be done in parallel with Phase 2.

### 3.1 Graph Change Event Emitter
**Files:** new `src/main/logseq_ai_hub/event_hub/graph_watcher.cljs`

Use `logseq.DB.onChanged` (already used by registry watcher) to emit graph events:
- `graph.page.created` - New page detected
- `graph.page.updated` - Page content changed
- `graph.block.changed` - Block-level changes

Debounce at 2s (same as registry watcher) to avoid event storms. Can share the same listener or use a separate one.

Filter out noise:
- Ignore changes to `Events/*` pages (prevent feedback loops)
- Ignore `Jobs/*` status updates (already covered by job events)
- Configurable page prefix filter

Events published via `client/publish-to-server!`.

### 3.2 MCP Tools for Event Hub
**Files:** new `server/src/services/mcp/event-tools.ts`, modify `server/src/services/mcp/index.ts`

| Tool | Description | Parameters |
|---|---|---|
| `event_publish` | Publish a custom event | `{type, source?, data, metadata?}` |
| `event_query` | Query stored events | `{type?, source?, since?, limit?}` |
| `event_subscribe` | Create an event subscription page | `{pattern, action, skill?, route_to?}` |
| `event_sources` | List configured webhook sources | `{}` |
| `event_recent` | Get recent events by source | `{source?, limit?}` |
| `webhook_test` | Send a test webhook event | `{source, payload}` |
| `http_request` | Make outbound HTTP request | `{url, method, headers?, body?, timeout?}` |

All tools delegate via bridge operations for graph-touching actions, or directly to EventBus for server-only operations.

### 3.3 Infrastructure Service Monitoring
**Files:** new `server/src/services/health-checker.ts`

Lightweight health checking using the Event Hub:
- Service pages tagged `logseq-ai-hub-service` with `service-health-endpoint::` property
- Health checks run as scheduled skills (reusing existing cron scheduler)
- Results published as `service.health.checked` events
- State transitions (`healthy -> unhealthy`) published as `service.health.changed` events
- Subscriptions can react to health changes (send alerts, trigger runbooks)

This is NOT a dedicated health check loop - it's a scheduled skill pattern using `:http-request` steps and `:emit-event` steps, configured via Logseq pages.

### 3.4 Event Dashboard Bridge Operations
**Files:** `agent_bridge.cljs`

Additional bridge operations:
- `event_dashboard` - Aggregate status: recent events by type, active subscriptions, event rates
- `event_replay` - Re-publish historical events for testing/replay

### Phase 3 Acceptance Criteria

- [ ] Graph changes to non-excluded pages emit `graph.page.created`/`graph.page.updated` events
- [ ] Changes to `Events/*` and `Jobs/*` pages are filtered out (no feedback loops)
- [ ] Graph events are debounced at 2s
- [ ] All 7 MCP tools registered and callable via MCP protocol
- [ ] `event_query` returns events from SQLite with correct filtering
- [ ] `event_publish` creates event in EventBus (stored + broadcast)
- [ ] `event_subscribe` creates a page with correct tags and properties
- [ ] `http_request` MCP tool respects URL allowlist
- [ ] Service monitoring pages tagged `logseq-ai-hub-service` are discoverable

---

## Phase 4: Webhook Source Configuration & Extraction
**Estimated: 4-6 hours**
**Depends on:** Phase 1 webhook endpoint

### 4.1 Webhook Source Config Discovery
**Files:** extend `registry/scanner.cljs`, new `event_hub/source_config.cljs`

Scan for `logseq-ai-hub-webhook-source` tagged pages. Parse properties:
- `webhook-source::` - Source identifier
- `webhook-verify-token::` - Token for webhook verification (supports `{{secret.KEY}}`)
- `webhook-extract-*::` - JSONPath extraction rules
- `webhook-page-prefix::` - Override default `Events/{source}` path
- `webhook-route-to::` - Auto-route to messaging contact
- `webhook-auto-job::` - Auto-trigger skill on receipt

### 4.2 JSONPath Extraction Engine
**Files:** new `server/src/services/jsonpath.ts`

Lightweight JSONPath implementation (or use `jsonpath-plus` npm package):
- Support basic JSONPath expressions: `$.path.to.field`, `$.array[0].field`
- Used by webhook source configs to extract structured fields from raw payloads
- Extracted fields added to event `data` alongside raw payload

### 4.3 Webhook Token Verification
**Files:** `server/src/routes/webhooks/generic.ts`

Per-source token verification:
1. On `GET /webhook/event/:source` - Return verification challenge (configurable per-source)
2. On `POST /webhook/event/:source` - Check `Authorization` header or query param against source config token
3. Token resolution via bridge (supports `{{secret.KEY}}` in token values)

### 4.4 Alert Routing
**Files:** new `server/src/services/event-router.ts`

When a webhook event matches a source config with `webhook-route-to`:
1. Format alert message from extracted fields
2. Send via existing `/api/send` endpoint
3. If severity-based routing configured, select contact by severity
4. If escalation configured, create approval with timeout, escalate to secondary on timeout

### Phase 4 Acceptance Criteria

- [ ] `logseq-ai-hub-webhook-source` tagged pages parsed with all properties
- [ ] Webhook source config with `webhook-verify-token` rejects requests without valid token
- [ ] JSONPath extraction: `$.alerts[0].labels.alertname` extracts correct field from Grafana payload
- [ ] Extracted fields appear in event data alongside raw payload
- [ ] `webhook-route-to:: whatsapp:15551234567` auto-sends formatted alert message
- [ ] `webhook-auto-job:: Skills/alert-handler` auto-triggers skill with event data as inputs
- [ ] Missing source config falls back to default formatting (no crash)

---

## Phase 5: Slash Commands, Testing & Polish
**Estimated: 4-5 hours**
**Depends on:** Phases 1-4

### 5.1 Slash Commands
**Files:** new `src/main/logseq_ai_hub/event_hub/commands.cljs`

| Command | Description |
|---|---|
| `event:list` | List configured event sources and subscriptions |
| `event:recent` | Show recent events (last 10) as a formatted block |
| `event:test` | Emit a test event to verify subscriptions |
| `event:sources` | List configured webhook sources |

### 5.2 Event Log Pruning
**Files:** `server/src/services/event-bus.ts`

- Configurable retention period (default: 30 days)
- Pruning runs on server startup and every 24 hours
- Graph-side event log pages are not pruned (user manages manually)

### 5.3 Plugin Settings Extensions
**Files:** `core.cljs` settings schema

New settings:
- `eventHubEnabled` (boolean, default: true)
- `httpAllowlist` (string, JSON array of domain patterns, default: "[]")
- `eventRetentionDays` (number, default: 30)
- `eventGraphPersistence` (boolean, default: true)

### 5.4 Tests
**Server tests:**
- EventBus: publish, subscribe, pattern matching, debounce/batch
- EventStore: CRUD, query, pruning
- Generic webhook: ingestion, token verification, extraction
- MCP event tools: all 7 tools
- Alert routing: severity-based, escalation

**Plugin tests:**
- Event dispatcher: matching, graph persistence, job triggering
- HTTP request executor: success, error, timeout, allowlist
- Emit event executor: publish, interpolation
- Subscription parser: page property parsing, pattern validation

### Phase 5 Acceptance Criteria

- [ ] `/event:list` shows all sources and subscriptions as formatted block
- [ ] `/event:recent` shows last 10 events with timestamps
- [ ] `/event:test` emits event and reports which subscriptions matched
- [ ] Pruning removes events older than `eventRetentionDays` setting
- [ ] All server tests pass (EventBus, EventStore, webhook, MCP tools, routing)
- [ ] All plugin tests pass (dispatcher, HTTP executor, emit-event, subscriptions)
- [ ] `eventHubEnabled: false` disables all event hub functionality without errors

---

## Phase 6: Internal Event Instrumentation (Phase 2 Scope)
**Estimated: 4-6 hours**
**Independent additive work — can be done at any time after Phase 1**

Add event emission to remaining subsystems (deferred from Phase 1):

### 6.1 Approval Events
Emit: `approval.created`, `approval.resolved`, `approval.timeout`

### 6.2 Registry Events
Emit: `registry.refreshed`, `registry.entry.added`, `registry.entry.removed`

### 6.3 MCP Events
Emit: `mcp.tool.invoked`, `mcp.tool.completed`

### 6.4 Messaging Events
Emit: `message.received`, `message.sent`

These build on the EventBus infrastructure from Phase 1. Each is a small, isolated change (2-5 lines per emission point).

### Phase 6 Acceptance Criteria

- [ ] Approval lifecycle emits 3 event types
- [ ] Registry refresh emits `registry.refreshed` with entry counts
- [ ] MCP tool calls emit `mcp.tool.invoked` and `mcp.tool.completed`
- [ ] Incoming messages emit `message.received`, outgoing emit `message.sent`
- [ ] Subscriptions can match and trigger on all new event types
- [ ] No performance impact on existing operations (emission is fire-and-forget)

---

## Track Spec Update

The `tracks.md` entry should be updated from:

```
### iot-infra-hooks_20260221 -- IoT/Infrastructure Hooks
```

To:

```
### event-hub_20260303 -- Event Hub System
- **Status:** planned
- **Branch:** `track/event-hub_20260303`
- **Priority:** P1 (upgraded from P2)
- **Estimated:** 32-43 hours (6 phases)
- **Depends on:** mcp-server_20260221, human-in-loop_20260221, kb-tool-registry_20260221, secrets-manager_20260221
- **Spec:** [spec.md](tracks/event-hub_20260303/spec.md)
- **Description:** Universal event hub providing webhook ingestion, internal event emission,
  event-driven automation triggers, and outbound HTTP actions. Replaces the narrow IoT/Infrastructure
  Hooks track with a comprehensive event system that serves as the Hub's central nervous system.
```

## Files Created/Modified Summary

### New Files (17)
**Server:**
1. `server/src/db/events.ts` - Event data access layer
2. `server/src/services/event-bus.ts` - EventBus service (wraps SSEManager)
3. `server/src/services/event-router.ts` - Alert routing engine
4. `server/src/services/jsonpath.ts` - JSONPath extraction
5. `server/src/services/health-checker.ts` - Health check coordination
6. `server/src/services/mcp/event-tools.ts` - MCP event tools
7. `server/src/routes/webhooks/generic.ts` - Generic webhook endpoint
8. `server/src/routes/events.ts` - Plugin→server event publish endpoint
9. `server/src/types/event.ts` - Event type definitions

**Plugin:**
10. `src/main/logseq_ai_hub/event_hub/dispatcher.cljs` - Event dispatcher
11. `src/main/logseq_ai_hub/event_hub/subscriptions.cljs` - Subscription matching
12. `src/main/logseq_ai_hub/event_hub/graph_store.cljs` - Graph persistence
13. `src/main/logseq_ai_hub/event_hub/source_config.cljs` - Webhook source config
14. `src/main/logseq_ai_hub/event_hub/commands.cljs` - Slash commands
15. `src/main/logseq_ai_hub/event_hub/init.cljs` - Initialization wiring
16. `src/main/logseq_ai_hub/event_hub/client.cljs` - Plugin→server publish helper
17. `src/main/logseq_ai_hub/event_hub/http.cljs` - HTTP helpers (interpolate-map, url-allowed?, abort-after)

**Tests:**
18. `server/src/__tests__/event-bus.test.ts` - EventBus tests
19. `src/test/logseq_ai_hub/event_hub/` - Plugin-side tests

### Modified Files (12)
1. `server/src/types.ts` - Add `"hub_event"` to SSEEvent type union
2. `server/src/db/schema.ts` - Add events table
3. `server/src/router.ts` - Add webhook/event routes + publish endpoint
4. `server/src/index.ts` - Wire EventBus into server
5. `server/src/services/mcp/index.ts` - Register event tools
6. `src/main/logseq_ai_hub/agent_bridge.cljs` - Add event operations
7. `src/main/logseq_ai_hub/core.cljs` - Add event hub init + settings
8. `src/main/logseq_ai_hub/job_runner/executor.cljs` - Add :http-request, :emit-event
9. `src/main/logseq_ai_hub/job_runner/schemas.cljs` - Add step action types
10. `src/main/logseq_ai_hub/job_runner/init.cljs` - Wire event emission
11. `src/main/logseq_ai_hub/job_runner/runner.cljs` - Emit lifecycle events
12. `src/main/logseq_ai_hub/registry/scanner.cljs` - Discover event subscription + webhook source pages (append, don't modify existing destructuring)

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Event storms from graph watcher | 2s debounce, filter out Events/* and Jobs/* pages |
| Feedback loops (event triggers job that emits event that triggers job) | Max chain depth (5) tracked in event metadata, duplicate event detection within window |
| Webhook abuse/DoS | Per-source rate limiting (100/min), payload size limit (1MB) |
| SQLite growth from events | Auto-pruning with configurable retention (30 days default) |
| SSEManager coupling | Wrap pattern keeps SSEManager unchanged, only adds new layer |
| SSE type collision | HubEvent nested under `payload` key in SSE data, not spread directly |
| Breaking existing job runner | All new step types are additive, no existing behavior changed |
| Breaking scanner destructuring | New scans appended after existing Promise.all, no modification to 5-slot destructuring |
| Secret leakage in events | Event data sanitized through existing redaction utilities |
| Plugin-server connectivity | `publish-to-server!` uses same auth/fetch pattern as `send-callback!` — proven reliable |

## Implementation Order

1. **Phase 1** (Core) - Must be done first. Creates the foundation.
2. **Phase 2** (Job Runner) - Depends on Phase 1 EventBus. Enables automation.
3. **Phase 3** (MCP + Graph) - Depends on Phase 1. Can be done in parallel with Phase 2.
4. **Phase 4** (Webhook Config) - Depends on Phase 1 webhook endpoint.
5. **Phase 5** (Polish) - Depends on all above.
6. **Phase 6** (Full Instrumentation) - Independent additive work.

Phases 2 and 3 can be done in parallel. Phases 4 and 5 can be partially parallelized.
