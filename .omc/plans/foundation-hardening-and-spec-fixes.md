# Work Plan: Foundation Hardening Track + Spec Fixes

**Created:** 2026-02-21
**Estimated:** 1-2 hours (spec writing + edits, no code)
**Scope:** Create new track spec, apply fixes to 4 existing specs, update tracks.md

---

## Phase 1: Create Foundation Hardening Track Spec

**Task 1.1:** Write `conductor/tracks/foundation-hardening_20260221/spec.md`

The spec covers 5 functional requirements:

### FR-1: Plugin Settings Cleanup (Breaking Change)
- Rename `openAIKey` → `llmApiKey` in settings-schema
- Rename `openAIEndpoint` → `llmEndpoint`, default to `https://openrouter.ai/api/v1`
- Rename `chatModel` → `llmModel`, default to `anthropic/claude-sonnet-4`
- Update all references in `core.cljs`, `agent.cljs`, and any other consumers
- Add a migration helper: on init, if old keys have values and new keys don't, copy them over
- Priority: P0

### FR-2: Router Performance Fix
- Move `buildRouteTable()` call from inside the request handler (router.ts:186) to `createRouter()` init
- The route table is static — build it once and close over it
- Simple refactor: `const routes = buildRouteTable();` at the top of `createRouter`, remove from the closure
- Priority: P0

### FR-3: Correlation IDs
- Server: generate `crypto.randomUUID()` for each inbound HTTP request
- Pass `traceId` through Agent Bridge `sendRequest()` as part of the request payload
- Plugin: receive `traceId` in `dispatch-agent-request`, include in callback
- Server: log `traceId` in all response logs and error handlers
- Add `traceId` to SSE events for debugging
- Priority: P1

### FR-4: SSE Auto-Reconnection
- Current state: `messaging.cljs:148-153` detects CLOSED but does NOT reconnect
- Add exponential backoff reconnection: 1s → 2s → 4s → 8s → 16s → 30s (cap)
- Reset backoff on successful connection
- Max reconnection attempts: unlimited (the connection should always try to come back)
- Log reconnection attempts with attempt count
- Debounce: don't reconnect if `disconnect!` was called explicitly (add `:intentional-disconnect?` flag to state atom)
- Priority: P0

### FR-5: Settings Write Serialization
- Problem: concurrent `set-secret!`, `remove-secret!`, and settings changes can race when writing to `logseq.settings`
- Solution: a write queue (Promise chain) in a new `settings-writer.cljs` module
- Same pattern as the existing write queue in `graph.cljs` (serialize Logseq API calls)
- API: `(queue-settings-write! f)` where `f` is a zero-arg function that calls `logseq.settings` APIs
- The secrets module (`secrets.cljs`) will use this for all vault writes
- Priority: P0

### Non-Functional Requirements
- NFR-1: Settings migration must be backward-compatible (old keys still read if present)
- NFR-2: Router change must not alter any API behavior
- NFR-3: Correlation IDs must not add >1ms latency
- NFR-4: Reconnection must not flood the server with connection attempts
- NFR-5: 80% test coverage for new modules

### Dependencies
- None (this track has no dependencies — it's the first to implement)

### Depends-on-by
- secrets-manager_20260221 (needs settings write serialization)
- mcp-server_20260221 (needs router fix, correlation IDs)
- All downstream tracks benefit from SSE resilience

---

## Phase 2: Fix Existing Specs

### Task 2.1: Fix IoT/Infrastructure Hooks spec
**File:** `conductor/tracks/iot-infra-hooks_20260221/spec.md`

Changes:
1. **Remove FR-7 entirely** (Environment Variable Vault) — duplicates secrets-manager
2. **Update FR-3** (`http-request` step): change `{{env.DEPLOY_TOKEN}}` → `{{secret.DEPLOY_TOKEN}}` and `{{env.VAR_NAME}}` → `{{secret.VAR_NAME}}`
3. **Add dependency:** `secrets-manager_20260221` (for `{{secret.}}` interpolation in HTTP headers/bodies)
4. **Renumber if needed** — with FR-7 removed, no renumbering needed (FR-1 through FR-6 remain)
5. **Update NFR-1 Security section** — remove references to "environment vault" encryption, reference secrets-manager instead

### Task 2.2: Fix Agent Sessions spec
**File:** `conductor/tracks/agent-sessions_20260221/spec.md`

Changes:
1. **Remove `kb-tool-registry_20260221` from Dependencies** — the spec doesn't use registry features
2. Update the dependency list to only: `mcp-server_20260221`, `webhook-agent-api_20260219`

### Task 2.3: Fix MCP Server spec
**File:** `conductor/tracks/mcp-server_20260221/spec.md`

Changes:
1. **Add to FR-1:** "Use the official `@modelcontextprotocol/sdk` package with `StreamableHTTPServerTransport` for protocol compliance. Do NOT implement JSON-RPC 2.0 parsing manually."
2. **Add to Technical Considerations:** A new section "MCP SDK Usage" explaining:
   - Package: `@modelcontextprotocol/sdk`
   - Transport: `StreamableHTTPServerTransport`
   - Benefits: protocol compliance, session management, capability negotiation handled by SDK
   - Integration: mount the SDK's transport handler at `POST /mcp` alongside existing Bun routes
3. **Add dependency:** `foundation-hardening_20260221` (needs router fix for clean route integration, correlation IDs)

### Task 2.4: Update MCP Server spec for foundation-hardening dependency
- Add `foundation-hardening_20260221` to the Depends-on list

---

## Phase 3: Update tracks.md

### Task 3.1: Update `conductor/tracks.md`

Changes:
1. **Add foundation-hardening track** to Active Tracks section (P0, 4-6 hours, no dependencies)
2. **Update secrets-manager** dependency: add `foundation-hardening_20260221`
3. **Update mcp-server** dependency: add `foundation-hardening_20260221`
4. **Update agent-sessions** dependency: remove `kb-tool-registry_20260221`
5. **Update iot-infra-hooks** dependency: add `secrets-manager_20260221`
6. **Update dependency graph** ASCII art to show foundation-hardening as the new root
7. **Update implementation order** to:
   1. foundation-hardening (P0, 4-6h)
   2. secrets-manager (P0, 8-12h)
   3. mcp-server (P0, 20-30h)
   4. human-in-loop (P0, 12-18h) — parallel with #5
   5. kb-tool-registry (P1, 15-22h) — parallel with #4
   6. agent-sessions (P1, 15-20h)
   7. code-repo-integration (P2, 10-15h)
   8. iot-infra-hooks (P2, 12-18h)

---

## Execution Order

1. Phase 1: Write foundation-hardening spec (largest piece of new content)
2. Phase 2: Apply all 4 spec fixes (smaller targeted edits)
3. Phase 3: Update tracks.md (final integration)

All phases are spec/doc work — no code changes in this plan.
