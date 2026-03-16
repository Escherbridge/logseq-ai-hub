# Specification: Multi-Tenant Routing

## Overview

Add tenant-aware request routing to the proprietary multi-tenant server. Every inbound request resolves a tenant context from the JWT auth token (validated by the multi-tenant-auth track), and that tenant context flows through all middleware, route handlers, service calls, SSE connections, Agent Bridge operations, and MCP sessions. When `MULTI_TENANT=false`, all code paths use a `"default"` tenant, preserving full backward compatibility with the single-tenant open-source server.

## Background

After the multi-tenant-schema track, the database layer accepts `tenantId` on all query functions, and the multi-tenant-auth track validates JWTs and exposes `tenantId` in the decoded claims. However, the application layer still lacks the plumbing to:

1. Extract `tenantId` from the validated JWT and create a `TenantContext`.
2. Propagate that context through all route handlers, services, and MCP tool calls.
3. Partition SSE connections by tenant so broadcasts are scoped.
4. Partition Agent Bridge pending requests by tenant so one tenant's plugin cannot resolve another tenant's callbacks.
5. Bind MCP sessions to a tenant at initialization time.
6. Pass `tenantId` into EventBus, SessionStore, ApprovalStore, and all other services that interact with the database or SSE layer.

This track implements the "context threading" layer -- the connective tissue between authentication (JWT claims) and data isolation (tenant-scoped DB queries).

## Functional Requirements

### FR-1: TenantContext Middleware
- **Description:** A middleware function `resolveTenant(req, config)` that extracts the tenant identity from the request and returns a `TenantContext` object.
- **Acceptance Criteria:**
  - When `MULTI_TENANT=true`, extracts `tenantId` from the JWT claims attached to the request by the auth middleware (e.g., `req.tenantId` or a decoded JWT payload field).
  - When `MULTI_TENANT=false`, returns `TenantContext { tenantId: "default" }` regardless of any JWT or header.
  - Returns a well-typed `TenantContext` object: `{ tenantId: string }`.
  - If `MULTI_TENANT=true` and no `tenantId` can be resolved, returns a 401 response.
  - `TenantContext` is added to `RouteContext` interface so all handlers receive it.
- **Priority:** P0

### FR-2: RouteContext Tenant Propagation
- **Description:** The router populates `RouteContext.tenantId` before dispatching to any route handler. All handlers receive tenant context automatically.
- **Acceptance Criteria:**
  - `RouteContext` interface gains a `tenantId: string` field.
  - The router's request handler calls `resolveTenant()` after auth and before routing.
  - Every route handler receives `ctx.tenantId` without modification to handler signatures.
  - Route handlers that call DB functions pass `ctx.tenantId` to every DB call.
  - Route handlers that call services (EventBus, SessionStore, etc.) pass `ctx.tenantId`.
- **Priority:** P0

### FR-3: Tenant-Scoped SSE Manager
- **Description:** SSEManager is partitioned by tenantId so that broadcasts are scoped to the tenant that owns the connection.
- **Acceptance Criteria:**
  - `SSEManager.addClient(id, controller, tenantId)` associates the client with a tenant.
  - `SSEManager.broadcast(event, tenantId)` sends only to clients belonging to that tenant.
  - `SSEManager.broadcastAll(event)` sends to all clients regardless of tenant (for system-level events like heartbeat).
  - `SSEManager.clientCount` returns total across all tenants.
  - `SSEManager.clientCountForTenant(tenantId)` returns count for a specific tenant.
  - `SSEManager.removeClient(id)` removes the client from its tenant partition.
  - Heartbeat continues to broadcast to all clients.
  - The SSE `/events` endpoint associates the connection with the tenant from auth.
  - When `MULTI_TENANT=false`, all clients are under the `"default"` tenant -- behavior is identical to current.
- **Priority:** P0

### FR-4: Tenant-Scoped Agent Bridge
- **Description:** AgentBridge pending requests are partitioned by tenantId so that one tenant's plugin connections cannot interact with another tenant's requests.
- **Acceptance Criteria:**
  - `AgentBridge.sendRequest(operation, params, traceId, tenantId)` broadcasts the SSE event only to clients of that tenant.
  - `AgentBridge.isPluginConnected(tenantId)` checks if the specific tenant has any SSE clients.
  - `AgentBridge.resolveRequest(requestId, result)` can only resolve requests belonging to the tenant that owns them (enforcement via internal tracking).
  - `AgentBridge.pendingCount` returns total across all tenants.
  - `AgentBridge.pendingCountForTenant(tenantId)` returns count for a specific tenant.
  - When `MULTI_TENANT=false`, all operations use `"default"` tenant -- behavior is identical to current.
- **Priority:** P0

### FR-5: Tenant-Scoped MCP Sessions
- **Description:** MCP transport sessions are bound to a tenant at initialization time. MCP tool calls execute in the context of that tenant.
- **Acceptance Criteria:**
  - When a new MCP session is created (`POST /mcp`), the tenant is extracted from the JWT and associated with the session.
  - The `McpToolContext` interface gains a `tenantId: string` field.
  - All MCP tool handlers receive `tenantId` via the context and pass it to DB/service calls.
  - Session-to-tenant mapping is tracked alongside the transport map.
  - `DELETE /mcp` only allows deleting sessions belonging to the authenticated tenant.
  - `GET /mcp` (SSE for server-to-client notifications) is scoped to the session's tenant.
  - When `MULTI_TENANT=false`, all sessions use `"default"` tenant.
- **Priority:** P0

### FR-6: Tenant-Scoped EventBus
- **Description:** EventBus operations are scoped by tenant. Events published by one tenant are stored and broadcast only to that tenant.
- **Acceptance Criteria:**
  - `EventBus.publish(event, tenantId)` passes `tenantId` to `insertEvent()` and broadcasts via `sseManager.broadcast(event, tenantId)`.
  - `EventBus.query(opts, tenantId)` passes `tenantId` to `queryEvents()`.
  - `EventBus.count(opts, tenantId)` passes `tenantId` to `countEvents()`.
  - `EventBus.prune(retentionDays, tenantId)` passes `tenantId` to `pruneEvents()`.
  - Deduplication is per-tenant (dedup keys include tenantId).
  - When `MULTI_TENANT=false`, all operations use `"default"` tenant.
- **Priority:** P0

### FR-7: Tenant-Scoped SessionStore
- **Description:** SessionStore operations are scoped by tenant.
- **Acceptance Criteria:**
  - `SessionStore.create(params, tenantId)` passes `tenantId` to `createSession()`.
  - `SessionStore.get(id, tenantId)` passes `tenantId` to `getSession()`.
  - `SessionStore.list(agentId, opts, tenantId)` passes `tenantId` to `listSessions()`.
  - All other SessionStore methods that access the DB accept and pass `tenantId`.
  - A session created by tenant-A is not accessible by tenant-B (even if the UUID is known).
  - When `MULTI_TENANT=false`, all operations use `"default"` tenant.
- **Priority:** P0

### FR-8: Tenant-Scoped ApprovalStore
- **Description:** ApprovalStore operations are scoped by tenant. One tenant's approvals cannot be resolved by another tenant.
- **Acceptance Criteria:**
  - `ApprovalStore.create(params, tenantId)` stores the approval under the tenant.
  - `ApprovalStore.resolve(contactId, response, resolvedBy, tenantId)` only resolves approvals belonging to that tenant.
  - `ApprovalStore.resolveById(approvalId, response, tenantId)` enforces tenant ownership.
  - `ApprovalStore.cancel(approvalId, tenantId)` enforces tenant ownership.
  - `ApprovalStore.getAll(tenantId)` returns only the tenant's approvals.
  - `ApprovalStore.getPending(contactId, tenantId)` returns only the tenant's pending approvals for that contact.
  - SSE broadcasts from approval lifecycle events are scoped to the tenant.
  - Per-contact limit (5) is per-tenant-per-contact. Total limit (100) is per-tenant.
  - When `MULTI_TENANT=false`, all operations use `"default"` tenant.
- **Priority:** P0

### FR-9: Tenant Context in All Route Handlers
- **Description:** Every route handler file is updated to extract and propagate tenantId from the route context.
- **Acceptance Criteria:**
  - `routes/api/send.ts` -- passes tenantId to message/contact DB calls.
  - `routes/api/messages.ts` -- passes tenantId to message query.
  - `routes/api/jobs.ts` -- passes tenantId to bridge requests.
  - `routes/api/skills.ts` -- passes tenantId to bridge requests.
  - `routes/api/secrets.ts` -- passes tenantId to bridge requests.
  - `routes/api/agent-chat.ts` -- passes tenantId to session store and bridge.
  - `routes/api/agent-callback.ts` -- passes tenantId to bridge resolve.
  - `routes/api/characters.ts` -- passes tenantId to character DB calls.
  - `routes/api/character-chat.ts` -- passes tenantId to character session and bridge calls.
  - `routes/api/approvals.ts` -- passes tenantId to approval store.
  - `routes/api/events.ts` -- passes tenantId to event bus.
  - `routes/api/mcp.ts` -- passes tenantId to bridge requests.
  - `routes/webhooks/whatsapp.ts` -- passes tenantId to DB and approval store.
  - `routes/webhooks/telegram.ts` -- passes tenantId to DB and approval store.
  - `routes/webhooks/event-hub.ts` -- passes tenantId to event bus.
  - `routes/events.ts` (SSE) -- passes tenantId to SSE manager.
  - `routes/mcp-transport.ts` -- associates tenant with MCP session.
  - `routes/health.ts` -- no tenant needed (health is global).
- **Priority:** P0

### FR-10: Backward Compatibility
- **Description:** When `MULTI_TENANT=false` (default), the system behaves identically to the current single-tenant server.
- **Acceptance Criteria:**
  - All existing tests pass without modification beyond adding the default `"default"` tenant parameter where required.
  - No API contract changes visible to existing clients when `MULTI_TENANT=false`.
  - The open-source plugin works with both the proprietary server (MULTI_TENANT=false) and the community server.
  - Single-tenant mode does not require JWT -- bearer token auth continues to work.
  - Default tenant propagation is transparent to handlers.
- **Priority:** P0

## Non-Functional Requirements

### NFR-1: Performance
- Tenant context resolution adds less than 1ms overhead per request.
- Tenant-scoped SSE broadcast does not iterate over all clients globally -- O(n) where n is the tenant's clients, not all clients.
- No additional database queries needed for tenant resolution (it comes from the JWT, already in memory).

### NFR-2: Security
- Cross-tenant data access is impossible at the routing/service layer. Even if a tenant guesses another tenant's resource IDs, the tenant filter prevents access.
- SSE connections cannot receive events from other tenants.
- MCP sessions cannot execute tools in another tenant's context.
- Agent Bridge requests are partitioned -- a malicious callback cannot resolve another tenant's pending request.

### NFR-3: Observability
- `X-Tenant-Id` response header is set on all responses when `MULTI_TENANT=true` (for debugging).
- Tenant ID is included in error logs and trace context.

### NFR-4: Testability
- All tenant-scoped services accept `tenantId` as a parameter, making unit tests straightforward.
- Integration tests can create two tenants and verify complete isolation.

## User Stories

### US-1: Tenant-Isolated API Access
- **As** a tenant using the multi-tenant server
- **I want** my API requests to only access my data
- **So that** no other tenant can see my contacts, messages, sessions, or events

**Scenarios:**
- **Given** tenant-A and tenant-B both have contacts in the database
  **When** tenant-A calls `GET /api/messages`
  **Then** only tenant-A's messages are returned

- **Given** tenant-A creates a session via `POST /api/agent/chat`
  **When** tenant-B tries to continue that session (same session ID)
  **Then** tenant-B gets a "session not found" error

### US-2: Tenant-Isolated SSE Streams
- **As** a tenant running the Logseq plugin
- **I want** my SSE event stream to only contain my events
- **So that** I never see another tenant's messages, jobs, or approvals

**Scenarios:**
- **Given** tenant-A and tenant-B both have SSE connections
  **When** tenant-A receives a new WhatsApp message
  **Then** only tenant-A's SSE stream receives the `new_message` event
  **And** tenant-B's stream does not

### US-3: Tenant-Isolated Agent Bridge
- **As** a tenant using the Agent Bridge
- **I want** my plugin's bridge requests to be isolated
- **So that** only my plugin can respond to my server's requests

**Scenarios:**
- **Given** tenant-A's plugin is connected and tenant-A makes a bridge request
  **When** tenant-B tries to send a callback for that request ID
  **Then** the callback is rejected

### US-4: Single-Tenant Backward Compatibility
- **As** a user running the server in single-tenant mode
- **I want** everything to work exactly as before
- **So that** the multi-tenant routing code does not affect my deployment

**Scenarios:**
- **Given** `MULTI_TENANT=false` (default)
  **When** I use the server normally with bearer token auth
  **Then** all features work identically to the open-source version
  **And** no JWT is required

### US-5: MCP Session Tenant Binding
- **As** a tenant using MCP tools
- **I want** my MCP session to be bound to my tenant
- **So that** all tool calls execute in my tenant's context

**Scenarios:**
- **Given** tenant-A initializes an MCP session
  **When** tenant-A calls `graph_search` tool
  **Then** the search is scoped to tenant-A's graph data

## Technical Considerations

1. **Tenant extraction strategy:** The multi-tenant-auth track validates the JWT and makes the decoded payload available. This track reads `tenantId` from that payload. The exact mechanism (e.g., `req.tenantClaims`, a header set by auth middleware, or a function `getAuthenticatedTenant(req)`) depends on the auth track's implementation. The spec assumes a function-based approach: `resolveTenant(req, config): TenantContext`.

2. **SSEManager refactor:** The current SSEManager uses a flat `Map<string, SSEClient>`. For tenant partitioning, this becomes `Map<string, Map<string, SSEClient>>` where the outer key is tenantId and the inner key is clientId. Alternatively, each SSEClient gains a `tenantId` field and broadcast filters by it. The nested-map approach is preferred for O(1) tenant lookup.

3. **AgentBridge refactor:** PendingRequests need a `tenantId` field. The `resolveRequest` method must verify tenant ownership. The `isPluginConnected` method must check tenant-specific SSE clients.

4. **MCP transport session-tenant map:** A parallel `Map<string, string>` mapping session IDs to tenant IDs. When `getContext()` is called for MCP tool handlers, the tenantId is injected from this map.

5. **ApprovalStore refactor:** The current structure is `Map<contactId, PendingApproval[]>`. For multi-tenant, this becomes `Map<tenantId, Map<contactId, PendingApproval[]>>` or each PendingApproval gains a `tenantId` field with filtering. The nested-map approach ensures O(1) tenant isolation.

6. **Webhook tenant resolution:** Webhooks from WhatsApp/Telegram are inbound from external platforms and do not carry JWTs. In multi-tenant mode, webhook tenant resolution requires a different strategy (e.g., tenant-specific webhook URLs `/webhook/:tenantId/whatsapp`, or a webhook-to-tenant mapping table). This is a known edge case. For this track, webhook handlers will accept tenantId from the route context, and the webhook routing strategy will be defined in a follow-up or handled by extracting tenant from the webhook URL path.

7. **Service constructor changes:** Services like `SessionStore`, `ApprovalStore`, and `EventBus` currently do not take tenantId at construction time. Tenant context flows through method calls rather than being baked into the instance. This keeps services stateless with respect to tenant identity.

8. **Gradual rollout:** All changes are additive. The `tenantId` parameter is added as the last parameter with a default value of `"default"` where possible, ensuring backward compatibility.

## Out of Scope

- Tenant provisioning, registration, or management API
- JWT validation logic (handled by multi-tenant-auth track)
- Per-tenant configuration or rate limiting
- Per-tenant billing or usage tracking
- Webhook-to-tenant mapping table (follow-up track)
- Plugin-side (ClojureScript) changes for multi-tenant auth
- Database-per-tenant isolation (schema track handles row-level isolation)
- Tenant admin dashboard

## Open Questions

1. **Webhook tenant resolution:** How should inbound webhooks (WhatsApp, Telegram, Event Hub) resolve their tenant? Options: (a) tenant-specific webhook URLs `/webhook/:tenantId/whatsapp`, (b) a webhook registration table mapping webhook tokens to tenants, (c) defer to a follow-up track. (Recommendation: (a) tenant-specific URLs for this track, with (b) as a future enhancement.)

2. **MCP tool context injection:** Should tenantId be added to the `McpToolContext` and injected at the `getContext()` level, or should each tool handler extract it from the MCP session metadata? (Recommendation: inject via `getContext()` with a tenant-aware wrapper.)

3. **SSE heartbeat scope:** Should heartbeats be global (all clients) or per-tenant? (Recommendation: global -- heartbeats carry no tenant data and are purely for connection keepalive.)
