# Implementation Plan: Multi-Tenant Routing

## Overview

This plan adds tenant-aware request routing to the proprietary server across 6 phases. Each phase is self-contained with its own verification step. The work progresses bottom-up: types and middleware first, then SSE and Agent Bridge partitioning, then service-layer threading, then route handler updates, then MCP session binding, and finally integration tests.

**Total estimated time:** 18-28 hours (52 tasks across 6 phases)

**Dependencies:** multi-tenant-schema_20260312 (DB functions accept tenantId), multi-tenant-auth_20260312 (JWT validated, claims available)

---

## Phase 1: TenantContext Types and Middleware

Goal: Define the TenantContext type, implement the resolveTenant() middleware function, and wire it into the router so all route handlers receive tenantId.

Tasks:
- [ ] Task 1.1: Define TenantContext type (TDD: Write test asserting TenantContext has tenantId string field; create `src/types/tenant.ts` with `TenantContext` interface and `DEFAULT_TENANT_ID` constant "default")
- [ ] Task 1.2: Implement resolveTenant() function (TDD: Write tests -- when MULTI_TENANT=false returns default tenant, when MULTI_TENANT=true extracts tenantId from JWT claims, when MULTI_TENANT=true and no tenantId returns null; create `src/middleware/tenant.ts` with `resolveTenant(req: Request, config: Config): TenantContext | null`)
- [ ] Task 1.3: Add tenantId to RouteContext interface (TDD: Write test that RouteContext includes tenantId field; update `src/router.ts` RouteContext to include `tenantId: string`)
- [ ] Task 1.4: Wire resolveTenant into router request handler (TDD: Write integration test -- router calls resolveTenant and populates ctx.tenantId before dispatching; update the router's fetch handler to call resolveTenant and set ctx.tenantId)
- [ ] Task 1.5: Add MULTI_TENANT and JWT config fields to Config (TDD: Write test for loadConfig reading MULTI_TENANT env var; update `src/config.ts` with `multiTenant: boolean`, leveraging fields added by proprietary-server-bootstrap)
- [ ] Task 1.6: Add X-Tenant-Id response header (TDD: Write test that responses include X-Tenant-Id header when MULTI_TENANT=true; update router to set header on all responses)
- [ ] Verification: Run full test suite, confirm all existing tests pass with default tenant, verify resolveTenant returns "default" when MULTI_TENANT=false [checkpoint marker]

---

## Phase 2: Tenant-Scoped SSE Manager

Goal: Refactor SSEManager to partition clients by tenant. Broadcasts become tenant-scoped. Heartbeat remains global.

Tasks:
- [ ] Task 2.1: Add tenantId to SSEClient interface (TDD: Write test that SSEClient has tenantId field; update `src/services/sse.ts` SSEClient interface)
- [ ] Task 2.2: Refactor SSEManager internal storage to nested Map (TDD: Write test -- addClient with tenantId stores under correct partition; change `clients` from `Map<string, SSEClient>` to `Map<string, Map<string, SSEClient>>` where outer key is tenantId)
- [ ] Task 2.3: Implement tenant-scoped addClient (TDD: Write test -- addClient("c1", controller, "tenant-a") places client under tenant-a partition; update addClient signature to accept tenantId)
- [ ] Task 2.4: Implement tenant-scoped removeClient (TDD: Write test -- removeClient("c1") removes from correct tenant partition, cleans up empty tenant maps; update removeClient to find and remove across partitions)
- [ ] Task 2.5: Implement tenant-scoped broadcast (TDD: Write test -- broadcast(event, "tenant-a") sends only to tenant-a clients, not tenant-b clients; update broadcast signature to accept optional tenantId)
- [ ] Task 2.6: Implement broadcastAll for system events (TDD: Write test -- broadcastAll sends to all clients regardless of tenant; add broadcastAll method that iterates all partitions)
- [ ] Task 2.7: Implement clientCountForTenant (TDD: Write test -- returns correct count per tenant; add clientCountForTenant method)
- [ ] Task 2.8: Update clientCount to aggregate all tenants (TDD: Write test -- clientCount returns sum of all tenants' clients)
- [ ] Task 2.9: Update heartbeat to use broadcastAll (TDD: Write test -- heartbeat sends to all clients; update start() to use broadcastAll for heartbeat interval)
- [ ] Task 2.10: Update SSE endpoint to pass tenantId (TDD: Write test -- handleSSE associates connection with tenant from auth; update `src/routes/events.ts` to extract tenantId from context and pass to addClient)
- [ ] Verification: Run full test suite, verify tenant-A broadcast does not reach tenant-B, verify heartbeat reaches all clients, verify single-tenant mode works identically [checkpoint marker]

---

## Phase 3: Tenant-Scoped Agent Bridge

Goal: Refactor AgentBridge so that pending requests are partitioned by tenant and SSE broadcasts are tenant-scoped.

Tasks:
- [ ] Task 3.1: Add tenantId to PendingRequest type (TDD: Write test that PendingRequest has tenantId field; update `src/types/agent.ts`)
- [ ] Task 3.2: Update sendRequest to accept and use tenantId (TDD: Write test -- sendRequest("op", {}, undefined, "tenant-a") broadcasts SSE only to tenant-a clients and stores tenantId on pending request; update AgentBridge.sendRequest)
- [ ] Task 3.3: Update isPluginConnected to accept tenantId (TDD: Write test -- isPluginConnected("tenant-a") returns true only if tenant-a has SSE clients; update to use sseManager.clientCountForTenant)
- [ ] Task 3.4: Enforce tenant ownership on resolveRequest (TDD: Write test -- resolveRequest fails if requestId belongs to a different tenant; add tenant verification to resolveRequest)
- [ ] Task 3.5: Implement pendingCountForTenant (TDD: Write test -- returns correct count per tenant; add pendingCountForTenant method)
- [ ] Task 3.6: Update agent-callback handler for tenant context (TDD: Write test -- callback handler extracts tenantId from context and validates ownership; update `src/routes/api/agent-callback.ts`)
- [ ] Task 3.7: Update bridge helper for tenant propagation (TDD: Write test -- bridge helper passes tenantId through to sendRequest; update `src/helpers/bridge.ts` if it wraps AgentBridge calls)
- [ ] Verification: Run full test suite, verify tenant-A cannot resolve tenant-B's pending requests, verify single-tenant mode works identically [checkpoint marker]

---

## Phase 4: Tenant-Scoped Services (EventBus, SessionStore, ApprovalStore)

Goal: Thread tenantId through all service-layer methods that interact with the database or SSE.

Tasks:
- [ ] Task 4.1: Update EventBus.publish to accept tenantId (TDD: Write test -- publish(event, "tenant-a") passes tenantId to insertEvent and broadcasts to tenant-a only; update `src/services/event-bus.ts`)
- [ ] Task 4.2: Update EventBus.query and count to accept tenantId (TDD: Write test -- query with tenantId passes it to queryEvents; update query/count methods)
- [ ] Task 4.3: Update EventBus.prune to accept tenantId (TDD: Write test -- prune with tenantId passes it to pruneEvents; update prune method)
- [ ] Task 4.4: Make EventBus deduplication per-tenant (TDD: Write test -- same event from two tenants is not deduplicated; prefix dedup key with tenantId)
- [ ] Task 4.5: Update SessionStore.create to accept tenantId (TDD: Write test -- create passes tenantId to createSession DB function; update `src/services/session-store.ts`)
- [ ] Task 4.6: Update SessionStore.get to accept tenantId (TDD: Write test -- get with wrong tenantId returns null even if session exists; update get method)
- [ ] Task 4.7: Update SessionStore.list to accept tenantId (TDD: Write test -- list with tenantId only returns that tenant's sessions)
- [ ] Task 4.8: Update remaining SessionStore methods for tenantId (TDD: Write tests for update, addMessage, getMessages, archive, touchActivity, updateContext, setFocus, addMemory, getMessage -- all accept and pass tenantId)
- [ ] Task 4.9: Update ApprovalStore to partition by tenant (TDD: Write test -- create approval for tenant-a is not visible to tenant-b getAll; refactor internal storage from `Map<contactId, PendingApproval[]>` to `Map<tenantId, Map<contactId, PendingApproval[]>>`)
- [ ] Task 4.10: Update ApprovalStore.create for tenantId (TDD: Write test -- create passes tenantId, broadcasts SSE to tenant only, enforces per-tenant limits)
- [ ] Task 4.11: Update ApprovalStore.resolve and resolveById for tenantId (TDD: Write test -- resolve with wrong tenant returns false; enforce tenant ownership)
- [ ] Task 4.12: Update ApprovalStore.cancel for tenantId (TDD: Write test -- cancel with wrong tenant returns false)
- [ ] Task 4.13: Update ApprovalStore.getAll and getPending for tenantId (TDD: Write test -- returns only the specified tenant's approvals)
- [ ] Verification: Run full test suite, verify cross-tenant isolation for EventBus/SessionStore/ApprovalStore, verify single-tenant default works [checkpoint marker]

---

## Phase 5: Route Handler Tenant Propagation

Goal: Update every route handler file to extract tenantId from RouteContext and pass it to all DB and service calls.

Tasks:
- [ ] Task 5.1: Update send and messages handlers (TDD: Write test -- handleSendMessage and handleGetMessages pass tenantId to DB functions; update `src/routes/api/send.ts` and `src/routes/api/messages.ts`)
- [ ] Task 5.2: Update job handlers (TDD: Write test -- all job handlers pass tenantId to bridge sendRequest; update `src/routes/api/jobs.ts`)
- [ ] Task 5.3: Update skill handlers (TDD: Write test -- all skill handlers pass tenantId to bridge sendRequest; update `src/routes/api/skills.ts`)
- [ ] Task 5.4: Update secret handlers (TDD: Write test -- all secret handlers pass tenantId to bridge sendRequest; update `src/routes/api/secrets.ts`)
- [ ] Task 5.5: Update agent-chat handler (TDD: Write test -- handleAgentChat passes tenantId to sessionStore and bridge; update `src/routes/api/agent-chat.ts`)
- [ ] Task 5.6: Update character handlers (TDD: Write test -- all character CRUD handlers pass tenantId to DB; update `src/routes/api/characters.ts`)
- [ ] Task 5.7: Update character-chat handler (TDD: Write test -- handleCharacterChat passes tenantId to session DB and bridge; update `src/routes/api/character-chat.ts`)
- [ ] Task 5.8: Update approval handlers (TDD: Write test -- all approval handlers pass tenantId to approvalStore; update `src/routes/api/approvals.ts`)
- [ ] Task 5.9: Update event handlers (TDD: Write test -- handlePublishEvent and handleQueryEvents pass tenantId to eventBus; update `src/routes/api/events.ts`)
- [ ] Task 5.10: Update MCP API handlers (TDD: Write test -- MCP listing handlers pass tenantId to bridge; update `src/routes/api/mcp.ts`)
- [ ] Task 5.11: Update webhook handlers for tenant context (TDD: Write test -- WhatsApp and Telegram webhook handlers use tenant from context; update `src/routes/webhooks/whatsapp.ts`, `src/routes/webhooks/telegram.ts`, `src/routes/webhooks/event-hub.ts`)
- [ ] Verification: Run full test suite, verify all route handlers propagate tenantId, manually test a request flow end-to-end with default tenant [checkpoint marker]

---

## Phase 6: MCP Session Tenant Binding and Integration Tests

Goal: Bind MCP sessions to tenants, update MCP tool context, and write comprehensive cross-tenant integration tests.

Tasks:
- [ ] Task 6.1: Add tenantId to McpToolContext (TDD: Write test that McpToolContext has tenantId field; update `src/types/mcp.ts`)
- [ ] Task 6.2: Create session-to-tenant mapping in MCP transport (TDD: Write test -- new MCP session stores tenant association; add `sessionTenantMap: Map<string, string>` to `src/routes/mcp-transport.ts`)
- [ ] Task 6.3: Extract tenant at MCP session initialization (TDD: Write test -- handleMcpRequest extracts tenantId from auth and stores in sessionTenantMap; update handleMcpRequest)
- [ ] Task 6.4: Inject tenantId into MCP tool context (TDD: Write test -- getContext() returns tenantId matching the current MCP session; create a session-aware getContext wrapper)
- [ ] Task 6.5: Scope MCP session lookup by tenant (TDD: Write test -- handleMcpRequest with existing session rejects if tenant does not match; add tenant verification to session lookup)
- [ ] Task 6.6: Scope MCP session deletion by tenant (TDD: Write test -- handleMcpDelete rejects if session belongs to different tenant; update handleMcpDelete)
- [ ] Task 6.7: Update all MCP tool handlers to use tenantId from context (TDD: Write test -- graph_search MCP tool passes tenantId to bridge; verify all tool modules use ctx.tenantId. This is a bulk update across all tool files in `src/services/mcp/`)
- [ ] Task 6.8: Integration test -- cross-tenant SSE isolation (TDD: Write test -- tenant-A and tenant-B both connect SSE, event for tenant-A does not appear on tenant-B stream)
- [ ] Task 6.9: Integration test -- cross-tenant Agent Bridge isolation (TDD: Write test -- tenant-A bridge request cannot be resolved by tenant-B callback)
- [ ] Task 6.10: Integration test -- cross-tenant session isolation (TDD: Write test -- tenant-A session is not accessible by tenant-B)
- [ ] Task 6.11: Integration test -- cross-tenant approval isolation (TDD: Write test -- tenant-A approval cannot be resolved by tenant-B)
- [ ] Task 6.12: Integration test -- single-tenant backward compatibility (TDD: Write test -- with MULTI_TENANT=false, full request lifecycle works with "default" tenant, no JWT required)
- [ ] Task 6.13: Integration test -- MCP session tenant binding (TDD: Write test -- MCP session bound to tenant-A, tool calls scoped to tenant-A)
- [ ] Verification: Run full test suite, verify zero cross-tenant data leakage, verify all existing tests pass, manually test full request flow with two different tenant JWTs [checkpoint marker]
