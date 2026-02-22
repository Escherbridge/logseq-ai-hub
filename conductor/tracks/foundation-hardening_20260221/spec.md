# Specification: Foundation Hardening — Cross-Cutting Infrastructure Fixes

## Overview

Address cross-cutting infrastructure issues identified during the track completeness analysis before building new features. This track fixes plugin settings naming, router performance, SSE connection resilience, request traceability, and settings write safety — all of which affect every downstream track.

This is a short, high-priority hardening sprint. No new features — only fixes and improvements to the existing foundation that subsequent tracks depend on.

## Background

The deep analysis of the 7 planned tracks revealed several infrastructure weaknesses:

1. **Plugin settings names are misleading.** `openAIKey`, `openAIEndpoint`, and `chatModel` reference OpenAI but the system uses OpenRouter. Defaults point to `api.openai.com` and `gpt-3.5-turbo` despite the server correctly defaulting to OpenRouter.

2. **Router rebuilds the route table on every request.** `buildRouteTable()` in `router.ts` is called inside the request handler closure (line 186), reconstructing ~21 route entries per request. This is unnecessary — the route table is static.

3. **No request correlation.** When an MCP tool call flows through JSON-RPC → Agent Bridge SSE → Plugin dispatch → Callback POST → response, there is no shared ID to trace the request across these 4 hops. Debugging production issues requires log timestamp correlation, which is fragile.

4. **SSE connection has no reconnection logic.** The `messaging.cljs` SSE error handler (line 148-153) detects `CLOSED` state but does not attempt to reconnect. A network blip permanently disconnects the plugin from the server, silently breaking all Agent Bridge operations.

5. **Settings writes can race.** The upcoming secrets manager will write to `logseq.settings` via `set-secret!` and `remove-secret!`. If a slash command and an Agent Bridge operation trigger concurrent writes, the JSON can be corrupted. The existing `graph.cljs` solves this for graph operations with a write queue — settings need the same pattern.

## Dependencies

- None. This track has no dependencies — it is the first to implement.

## Functional Requirements

### FR-1: Plugin Settings Cleanup

**Description:** Rename misleading OpenAI-specific setting keys to provider-agnostic names and update defaults to match the actual OpenRouter configuration.

**Current State:**
```clojure
{:key "openAIKey"      :default ""}
{:key "openAIEndpoint" :default "https://api.openai.com/v1"}
{:key "chatModel"      :default "gpt-3.5-turbo"}
```

**Target State:**
```clojure
{:key "llmApiKey"   :default ""}
{:key "llmEndpoint" :default "https://openrouter.ai/api/v1"}
{:key "llmModel"    :default "anthropic/claude-sonnet-4"}
```

**Acceptance Criteria:**
- Settings schema in `core.cljs` uses the new key names and defaults.
- A migration function in `core.cljs` (called during `main`) copies values from old keys to new keys if: (a) old key has a non-empty value, and (b) new key is empty/default. This ensures existing users don't lose their configuration.
- All consumers of these settings (`agent.cljs`, any LLM-calling code) are updated to read the new key names.
- The `selectedModel` enum choices are updated: `"openai-model"` → `"llm-model"` (or better: remove the enum entirely if it's not used beyond the prototype `/LLM` command).
- Old keys are removed from the schema after migration.
- Settings descriptions are updated to reference "LLM API" instead of "OpenAI".

**Priority:** P0

### FR-2: Router Performance Fix

**Description:** Build the route table once at router initialization instead of on every request.

**Current State (router.ts:176-186):**
```typescript
return async (req: Request): Promise<Response> => {
    // ...
    const routes = buildRouteTable(); // Called EVERY request
    // ...
};
```

**Target State:**
```typescript
const routes = buildRouteTable(); // Called ONCE at init
return async (req: Request): Promise<Response> => {
    // ...uses `routes` from closure...
};
```

**Acceptance Criteria:**
- `buildRouteTable()` is called once inside `createRouter()`, before the returned handler closure.
- The handler closure captures the pre-built `routes` array via closure.
- All existing route tests continue to pass.
- No behavioral changes to any API endpoint.

**Priority:** P0

### FR-3: Correlation IDs (Request Tracing)

**Description:** Add a unique trace ID to every request that flows through the system, enabling end-to-end debugging across server → Agent Bridge → plugin → callback.

**Acceptance Criteria:**

**Server side:**
- Every inbound HTTP request gets a `traceId` (either from an `X-Trace-Id` request header or auto-generated via `crypto.randomUUID()`).
- The `traceId` is passed through `AgentBridge.sendRequest()` as part of the request payload: `{requestId, operation, params, traceId}`.
- The `traceId` is included in all log lines related to that request.
- The `traceId` is included in the HTTP response as an `X-Trace-Id` header.
- SSE events include `traceId` when they are related to a specific request (e.g., `agent_request`).

**Plugin side:**
- `dispatch-agent-request` reads `traceId` from the event data.
- `send-callback!` includes `traceId` in the callback payload.
- Console logs for agent operations include `traceId`.

**Format:** Standard UUID v4 (e.g., `550e8400-e29b-41d4-a716-446655440000`).

**Priority:** P1

### FR-4: SSE Auto-Reconnection with Exponential Backoff

**Description:** Add automatic reconnection to the SSE connection in `messaging.cljs` so that network disruptions don't permanently disconnect the plugin.

**Current State (messaging.cljs:148-153):**
```clojure
(set! (.-onerror es)
      (fn [_e]
        (js/console.error "SSE connection error")
        (when (= (.-readyState es) 2) ;; CLOSED
          (swap! state assoc :connected? false))))
```

**Acceptance Criteria:**
- When the SSE connection enters CLOSED state (readyState = 2) and the disconnection was NOT intentional, the plugin automatically attempts to reconnect.
- Reconnection uses exponential backoff: 1s → 2s → 4s → 8s → 16s → 30s (cap at 30 seconds).
- Backoff resets to 1s on successful connection (on receiving the `connected` SSE event).
- An `:intentional-disconnect?` flag in the state atom distinguishes intentional `disconnect!` calls from network failures. When true, no reconnection is attempted.
- `disconnect!` sets `:intentional-disconnect? true`. `connect!` resets it to `false`.
- Each reconnection attempt logs: `"SSE reconnecting (attempt N, backoff Xs)"`.
- Maximum reconnection attempts: unlimited (the connection should always try to come back for unintentional disconnects).
- The reconnection timer is stored in the state atom and cleared on `disconnect!` to prevent leaked timers.
- Agent Bridge handler is re-registered on each reconnection (via `register-agent-handler!`).

**Priority:** P0

### FR-5: Settings Write Serialization

**Description:** A write queue module that serializes all writes to `logseq.settings` to prevent concurrent write races.

**File:** `src/main/logseq_ai_hub/settings_writer.cljs`

**Acceptance Criteria:**
- A new module `settings-writer` provides `queue-settings-write!` function.
- `queue-settings-write!` accepts a function `f` that performs the settings write. It returns a Promise that resolves when `f` completes.
- Writes are serialized: the next write starts only after the previous write's Promise resolves.
- Implementation pattern: a Promise chain stored in an atom (same pattern as the graph write queue in `graph.cljs`).
- The secrets module (from the secrets-manager track) will use this for `set-secret!` and `remove-secret!`.
- The settings migration (FR-1) uses this to safely copy old values to new keys.
- Errors in write functions are caught and logged but don't break the queue (next write still proceeds).
- The module is initialized in `core.cljs` `main` function.

**Priority:** P0

## Non-Functional Requirements

### NFR-1: Backward Compatibility

- The settings migration must preserve existing user configurations. Users who have set `openAIKey` with an OpenRouter key must find it automatically available under `llmApiKey` after the update.
- All existing REST API endpoints continue to work identically.
- The SSE reconnection is purely additive — it doesn't change the connection behavior for functioning connections.

### NFR-2: Performance

- Router fix eliminates ~21 object allocations per request.
- Correlation ID generation adds < 1ms per request (`crypto.randomUUID()` is fast).
- Settings write queue adds negligible overhead (Promise chaining).
- SSE reconnection backoff prevents server flooding.

### NFR-3: Testability

- Settings migration has unit tests: old keys migrate, new keys preserved, empty old keys don't overwrite new values.
- Router fix verified by existing route tests (no new tests needed).
- Correlation ID propagation has an integration test: request → bridge → callback → verify traceId consistency.
- SSE reconnection has unit tests: backoff timing, intentional vs unintentional disconnect, timer cleanup.
- Settings write queue has unit tests: serialization order, error recovery, concurrent write safety.
- Test coverage target: 80%.

### NFR-4: Observability

- All console.log/error calls in modified code include structured context (operation name, traceId where available).
- Reconnection events are logged with attempt count and backoff duration.
- Settings migration logs which keys were migrated.

## User Stories

### US-1: Seamless reconnection

**As** a user running the plugin with a remote server,
**I want** the SSE connection to automatically recover from network interruptions,
**So that** I don't have to restart the plugin or Logseq to re-establish the connection.

### US-2: Correct defaults for new users

**As** a new user setting up the plugin,
**I want** the settings to show OpenRouter as the default LLM endpoint,
**So that** I don't have to figure out which URL to enter.

### US-3: Debugging agent operations

**As** a developer debugging a failed Agent Bridge operation,
**I want** a trace ID that links the server log, SSE event, plugin log, and callback together,
**So that** I can reconstruct the full request lifecycle.

## Technical Considerations

### Settings Migration Strategy

The migration runs once during `main` initialization:

```clojure
(defn- migrate-settings! []
  (let [old-key (aget js/logseq.settings "openAIKey")
        new-key (aget js/logseq.settings "llmApiKey")]
    (when (and (not (str/blank? old-key))
               (or (nil? new-key) (str/blank? new-key)))
      (js/logseq.updateSettings (clj->js {:llmApiKey old-key}))
      (js/console.log "Settings migration: openAIKey → llmApiKey")))
  ;; Repeat for openAIEndpoint → llmEndpoint, chatModel → llmModel
  )
```

### SSE Reconnection Pattern

```clojure
(defn- schedule-reconnect! []
  (when-not (:intentional-disconnect? @state)
    (let [attempt (inc (or (:reconnect-attempt @state) 0))
          backoff (min 30000 (* 1000 (js/Math.pow 2 (dec attempt))))]
      (js/console.log (str "SSE reconnecting (attempt " attempt ", backoff " (/ backoff 1000) "s)"))
      (swap! state assoc :reconnect-attempt attempt)
      (let [timer (js/setTimeout
                    (fn []
                      (connect! (:server-url @state) (:api-token @state)))
                    backoff)]
        (swap! state assoc :reconnect-timer timer)))))
```

### Write Queue Pattern (from graph.cljs)

```clojure
(defonce write-queue (atom (js/Promise.resolve nil)))

(defn queue-settings-write! [f]
  (let [p (js/Promise.
            (fn [resolve _reject]
              (swap! write-queue
                (fn [prev]
                  (-> prev
                      (.then (fn [_] (f)))
                      (.then resolve)
                      (.catch (fn [err]
                                (js/console.error "Settings write error:" err)
                                (resolve nil))))))))]
    p))
```

## Out of Scope

- Encryption of settings at rest (handled by OS-level file permissions).
- Circuit breaker for Agent Bridge (future enhancement).
- Request priority/backpressure on the SSE connection (future enhancement).
- Structured logging library integration (console.log with context is sufficient for v1).
- Plugin settings UI improvements (Logseq controls the settings panel).

## Open Questions

1. **Old settings keys removal timing:** Should old keys (`openAIKey`, etc.) be removed from the schema immediately, or kept for one release cycle as deprecated? Recommendation: remove immediately — the migration copies values forward, and keeping both creates confusion.

2. **Trace ID propagation to MCP tools:** Should the MCP server (from the mcp-server track) propagate its JSON-RPC request ID as the trace ID, or generate a separate one? Recommendation: generate a separate traceId and include the JSON-RPC ID in the trace context for correlation.

3. **SSE reconnection notification:** Should the plugin show a Logseq notification when it reconnects? Recommendation: yes — show a brief "Reconnected to server" notification on successful reconnection after a failure.
