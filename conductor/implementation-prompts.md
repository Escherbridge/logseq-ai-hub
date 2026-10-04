# Implementation Prompts for Remaining Conductor Tracks

## Dependency Graph

```
open-source-prep ──┐
                   ├──> proprietary-server-bootstrap ──> multi-tenant-schema ──> multi-tenant-auth ──> multi-tenant-routing
plugin-dual-auth ──┘
```

**Parallel opportunities:**
- `open-source-prep` (finish) + `plugin-dual-auth` can run simultaneously
- `multi-tenant-schema` phases 3 & 4 can run in parallel (different DB modules)

---

## Track 1: Open Source Prep (FINISH - ~1-2h remaining)

> **Status:** Most deliverables exist. Needs verification pass and any gaps filled.

### Prompt 1A: Verify & Complete Open Source Prep

```
Complete the open-source-prep conductor track. Read the spec at conductor/tracks/open-source-prep_20260312/spec.md and plan at conductor/tracks/open-source-prep_20260312/plan.md.

Most deliverables already exist: LICENSE, README.md, CONTRIBUTING.md, server/.env.example, docs/bridge-operations.md, docs/mcp-tools.md, .github/workflows/ci.yml, updated package.json.

Perform these verification steps:
1. SECRET SANITIZATION (Phase 2): Grep the entire codebase for hardcoded API keys, personal URLs (railway.app, specific domains), tokens longer than 32 chars in source files (not test files). Fix any findings by replacing with env vars or placeholders. Verify .gitignore covers .env, server/.env, server/data/, .shadow-cljs/, .claude/
2. PACKAGE METADATA (Phase 3): Verify manifest.edn has correct :id, :title, :description, :author. Verify server/package.json has license and description fields. Verify shadow-cljs.edn has no machine-specific paths.
3. DOCUMENTATION ACCURACY (Phase 4-5): Cross-verify docs/bridge-operations.md lists all operations from src/main/logseq_ai_hub/agent_bridge.cljs operation-handlers map. Cross-verify docs/mcp-tools.md lists all tools from server/src/services/mcp/*.ts files (grep for server.tool). Report any mismatches and fix them.
4. CI VALIDATION (Phase 6): Review .github/workflows/ci.yml for correctness -- needs Java for shadow-cljs, Node.js, Bun for server, proper caching.
5. Run existing tests to confirm no regressions: npm test (CLJS) and cd server && bun test

Mark any spec acceptance criteria that are unmet and fix them. Do NOT make functional code changes -- this is documentation/config only.
```

---

## Track 2: Plugin Dual Authentication (~3-5h)

> **Status:** Pending. Independent of open-source-prep. Can start immediately.

### Prompt 2A: Auth Namespace + Settings Schema (Phase 1)

```
Implement Phase 1 of the plugin-dual-auth conductor track. Read the spec at conductor/tracks/plugin-dual-auth_20260312/spec.md and plan at conductor/tracks/plugin-dual-auth_20260312/plan.md.

This is ClojureScript in a Logseq plugin. Follow TDD (Red-Green-Refactor).

Phase 1 tasks:
1. Create src/test/logseq_ai_hub/auth_test.cljs with tests for get-auth-mode, get-auth-token, and auth-configured?. Test cases: (a) authMode="token" returns pluginApiToken, (b) authMode="jwt" returns jwtToken, (c) authMode=nil defaults to "token" behavior, (d) auth-configured? false when token blank, (e) auth-configured? false when server URL blank.
2. Create src/main/logseq_ai_hub/auth.cljs with get-auth-mode, get-auth-token, get-server-url, and auth-configured? functions. get-auth-mode reads authMode setting defaulting to "token". get-auth-token dispatches on mode. No dependencies beyond js/logseq.settings.
3. Add authMode (enum ["token","jwt"], default "token") and jwtToken (string, default "") settings to settings-schema in core.cljs.
4. Add log-auth-warnings! function to auth.cljs with tests.

At the end Run npm test to verify all existing + new tests pass fix issues that come up.

IMPORTANT ClojureScript gotchas:
- Use (aget js/logseq "settings") for settings access
- The auth namespace must NOT depend on any other plugin namespaces (no circular deps)
- Use def ^:dynamic for any test-mockable vars if needed, but prefer direct settings reads
```

### Prompt 2B: HTTP Module Migration (Phase 2)

```
Implement Phase 2 of the plugin-dual-auth conductor track. Read the plan at conductor/tracks/plugin-dual-auth_20260312/plan.md (Phase 2).

The auth namespace from Phase 1 is complete. Now migrate all HTTP modules to use centralized auth.

Phase 2 tasks (TDD for each):
1. agent_bridge.cljs: Replace get-api-token with auth/get-auth-token in send-callback!. Remove private get-api-token. Update agent_bridge_test.cljs.
2. event_hub/publish.cljs: Replace get-api-token and get-server-url with auth/get-auth-token and auth/get-server-url. Update publish_test.cljs.
3. event_hub/init.cljs: Replace get-server-url and get-api-token with auth equivalents. Update init_test.cljs.
4. messaging.cljs send-message!: Replace direct state read of :api-token with auth/get-auth-token. Update messaging_test.cljs.

After all changes, grep codebase to confirm zero remaining direct reads of pluginApiToken outside auth.cljs and core.cljs. Run npm test -- all tests must pass.
```

### Prompt 2C: SSE Migration + Settings Migration (Phases 3-4)

```
Implement Phases 3 and 4 of the plugin-dual-auth conductor track. Read the plan at conductor/tracks/plugin-dual-auth_20260312/plan.md.

Phase 3 - SSE Migration:
1. Update messaging.cljs connect! to call auth/get-auth-token instead of using passed api-token from state.
2. Update messaging.cljs init! to use auth/get-server-url and auth/get-auth-token instead of reading settings directly. Use auth/auth-configured? for the guard.
3. Write integration test verifying messaging/connect! produces SSE URL with JWT token when authMode="jwt".

Phase 4 - Settings Migration + Final Validation:
1. Extend migrate-settings! in core.cljs: set authMode to "token" for existing users with non-empty pluginApiToken but no authMode. Test idempotency.
2. Call auth/log-auth-warnings! from core.cljs main function after migrate-settings!.
3. Write end-to-end test: JWT mode settings -> get-auth-token returns JWT -> token sent as Bearer header.

Run npm test -- ALL tests must pass (existing + new). This completes the plugin-dual-auth track.
```

---

## Track 3: Proprietary Server Bootstrap (~8-14h)

> **Status:** Pending. Requires open-source-prep to be complete. Work happens in a NEW REPO.

### Prompt 3A: Create Repo + Copy Code (Phase 1)

```
Implement Phase 1 of the proprietary-server-bootstrap conductor track. Read the spec at conductor/tracks/proprietary-server-bootstrap_20260312/spec.md and plan at conductor/tracks/proprietary-server-bootstrap_20260312/plan.md.

Create a new private GitHub repository for the proprietary multi-tenant server.

Phase 1 tasks:
1. Create a private repo on GitHub named "escherbridge-server" (or ask me for the preferred name).
2. Copy all files from server/src/ to src/ at the new repo root. Copy server/tests/ to tests/. Copy server/package.json, server/tsconfig.json.
3. Update package.json: name to "escherbridge-server", verify bun-types in devDependencies.
4. Create a proprietary LICENSE file (All Rights Reserved, copyright 2026 Ahmed Zaher).
5. Create appropriate .gitignore (node_modules/, data/, .env, *.sqlite, *.sqlite-journal -- do NOT ignore bun.lockb).
6. Run bun install to generate lockfile.
7. Run bun test -- all 741 tests must pass.
8. Initial commit: "chore: bootstrap from logseq-ai-hub server/"

Verify: clone to fresh directory, bun install && bun test passes, PLUGIN_API_TOKEN=test bun run src/index.ts starts and GET /health returns 200.
```

### Prompt 3B: Docker + CI (Phases 2-3)

```
Implement Phases 2-3 of the proprietary-server-bootstrap conductor track in the escherbridge-server repo.

Phase 2 - Docker:
1. Create Dockerfile using oven/bun:1 base. Multi-stage build. Expose 3000. CMD ["bun", "run", "src/index.ts"].
2. Create .dockerignore (exclude node_modules, .git, data/, tests/, .env, *.md).
3. Create docker-compose.yml with port 3000, volume ./data:/app/data, env from .env, health check on /health.
4. Create .env.example with all vars from src/config.ts with comments.
5. Build and test: docker build succeeds, docker run responds to /health, image under 200MB.

Phase 3 - GitHub Actions CI:
1. Create .github/workflows/ci.yml: triggers on push to main + PRs.
2. Test job: checkout, setup Bun (oven-sh/setup-bun@v2), bun install, bun test.
3. Docker build verification job (parallel with test): checkout, docker build (no push).
4. Push to branch and verify both jobs pass.
```

### Prompt 3C: Multi-Tenant Config + API Contract + README (Phases 4-6)

```
Implement Phases 4-6 of the proprietary-server-bootstrap conductor track in the escherbridge-server repo.

Phase 4 - Multi-Tenant Config Scaffolding (TDD):
1. Write tests for new config fields: multiTenant (boolean, default false), jwtSecret, jwtIssuer, jwtAudience, tenantDbPathTemplate.
2. Implement in src/config.ts. Parse MULTI_TENANT, JWT_SECRET, JWT_ISSUER, JWT_AUDIENCE, TENANT_DB_PATH_TEMPLATE.
3. Write validation tests: MULTI_TENANT=true requires JWT_SECRET. MULTI_TENANT=false ignores JWT fields.
4. Implement validation. Verify server exits with clear error when misconfigured.
5. Run bun test -- all 741+ tests pass (single-tenant mode unaffected).

Phase 5 - API Contract Documentation:
1. Add apiVersion field to /health response (TDD: test /health returns apiVersion: "1.0"). Add API_VERSION constant.
2. Create docs/api-contract.md documenting ALL REST endpoints from src/router.ts (~42 routes). For each: method, path, auth, request/response schema, status codes.
3. Document SSE event types, MCP transport, and webhook endpoints.
4. Create docs/shared-types.md listing synchronized TypeScript types.

Phase 6 - README + Polish:
1. Create README.md: overview, relationship to OSS repo, prerequisites, quick start, env vars table, Docker deployment, API compatibility.
2. Create CONTRIBUTING.md: branch strategy, commit format, PR process.
3. Update .env.example with multi-tenant vars.
4. Final verification: bun test, docker build, fresh clone follows README successfully.
5. Tag v0.1.0.
```

---

## Track 4: Multi-Tenant Schema (~20-30h)

> **Status:** Pending. Depends on proprietary-server-bootstrap. Work in escherbridge-server repo.

### Prompt 4A: Migration System + Schema Changes (Phase 1)

```
Implement Phase 1 of the multi-tenant-schema conductor track in the escherbridge-server repo. Read the spec at conductor/tracks/multi-tenant-schema_20260312/spec.md and plan at conductor/tracks/multi-tenant-schema_20260312/plan.md.

Phase 1 - Migration Infrastructure + Schema Changes (TDD throughout):
1. Create src/db/migrations.ts with migration runner: schema_version table, getCurrentVersion(), applyMigrations(). Test fresh DB returns version 0.
2. Implement Migration 001: ALTER TABLE ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default' on all 8 tables (contacts, messages, sse_events, characters, character_sessions, sessions, session_messages, events). SQLite allows ADD COLUMN with NOT NULL if DEFAULT is provided.
3. Migration 001 index changes: drop old single-column indexes, create composite (tenant_id, ...) indexes per spec FR-2.
4. Migration 001 unique constraints: replace characters name unique index with (tenant_id, name), replace messages external_id with (tenant_id, external_id). Test two tenants can have same character name.
5. Update schema.ts initializeSchema() to include tenant_id in all CREATE TABLE statements and composite indexes for fresh databases.
6. Update connection.ts: call applyMigrations(db) after initializeSchema(db) in getDatabase() and createTestDatabase().
7. Test migration idempotency: running twice produces no errors.

Run full test suite -- all existing tests must pass since DEFAULT 'default' is applied.
```

### Prompt 4B: TenantContext + Config (Phase 2)

```
Implement Phase 2 of the multi-tenant-schema conductor track in the escherbridge-server repo.

Phase 2 - TenantContext & Config (TDD):
1. Verify multiTenant boolean exists in Config from bootstrap track. If not, add it.
2. Create src/types/tenant.ts with TenantContext { tenantId: string } and DEFAULT_TENANT_ID = "default".
3. Add tenantId: string to RouteContext in router.ts.
4. Create src/middleware/tenant.ts with resolveTenant(req, config): returns DEFAULT_TENANT_ID when multiTenant=false, extracts X-Tenant-Id header when true, returns 400 if header missing in multi-tenant mode.
5. Wire resolveTenant into router dispatch: populate ctx.tenantId before calling handlers.

All existing tests must pass with tenantId="default" in RouteContext.
```

### Prompt 4C: Tenant-Scoped DB - Contacts, Messages, SSE (Phase 3)

```
Implement Phase 3 of the multi-tenant-schema conductor track in the escherbridge-server repo.

Phase 3 - Tenant-Scoped DB Functions for Contacts, Messages, SSE Events (TDD):
1. contacts.ts: Update upsertContact, getContact, listContacts to accept tenantId parameter. INSERT includes tenant_id, SELECT/UPDATE/DELETE filter by tenant_id. Write cross-tenant isolation tests.
2. messages.ts: Update insertMessage, getMessage, getMessages. JOINs with contacts must also filter by tenant. Write isolation tests.
3. sse-events: Scope INSERT and SELECT by tenant_id.
4. Update test helpers in tests/helpers.ts: add tenantId parameter (default "default") to seedTestContact, seedTestMessage.
5. Update existing tests in db.test.ts, api-messages.test.ts to pass tenantId.

Run bun test -- all pass.
```

### Prompt 4D: Tenant-Scoped DB - Characters, Sessions, Events (Phase 4)

```
Implement Phase 4 of the multi-tenant-schema conductor track in the escherbridge-server repo.

Phase 4 - Tenant-Scoped DB Functions for Characters, Sessions, Events (TDD):
1. characters.ts: All 6 functions accept tenantId. Per-tenant unique name constraint. Cross-tenant isolation tests.
2. character-sessions.ts: All 5 functions accept tenantId. Scope all queries. Isolation tests.
3. sessions.ts: createSession, getSession, listSessions, updateSession accept tenantId. Session message functions (addSessionMessage, loadSessionMessages) also accept tenantId.
4. events.ts: insertEvent, getEventById, queryEvents, pruneEvents, countEvents accept tenantId. Isolation tests.
5. Update existing tests: event-store.test.ts, session-store.test.ts, session-context.test.ts pass tenantId.

Run full bun test -- all pass.

NOTE: This phase CAN run in parallel with Phase 3 if working in separate branches -- they modify different DB modules.
```

### Prompt 4E: Route Handlers + Isolation Tests (Phases 5-6)

```
Implement Phases 5-6 of the multi-tenant-schema conductor track in the escherbridge-server repo.

Phase 5 - Route Handler Tenant Extraction (TDD):
Thread ctx.tenantId through ALL route handlers to their DB calls:
- routes/api/messages.ts, send.ts, characters.ts, character-chat.ts, events.ts, agent-chat.ts
- routes/webhooks/whatsapp.ts, telegram.ts, event-hub.ts
- routes/events.ts (SSE)
Each handler extracts tenantId from RouteContext and passes to DB/service calls. Write tests verifying tenant scoping at the API level.

Phase 6 - Comprehensive Tenant Isolation Tests:
Create tests/tenant-isolation.test.ts with:
- Per-table isolation tests (contacts, messages, characters, character_sessions, sessions, events, sse_events) -- create data under tenant-A and tenant-B, verify query isolation. 5+ test cases per table.
- Migration tests: fresh DB, migrated DB, idempotent, data preservation.
- Backward compatibility: MULTI_TENANT=false uses default tenant, API unchanged.
- Integration test: two tenants hit same endpoints, data never crosses.
Target: 40+ new test cases, zero cross-tenant leaks.

Run full bun test -- all tests pass.
```

---

## Track 5: Multi-Tenant Auth (~25-35h)

> **Status:** Pending. Depends on multi-tenant-schema. Work in escherbridge-server repo.

### Prompt 5A: JWT Foundation + Config (Phase 1)

```
Implement Phase 1 of the multi-tenant-auth conductor track in the escherbridge-server repo. Read the spec at conductor/tracks/multi-tenant-auth_20260312/spec.md and plan at conductor/tracks/multi-tenant-auth_20260312/plan.md.

Phase 1 - Config + JWT Foundation (TDD):
1. bun add jose
2. Extend Config with: jwtSecret, jwtIssuer (default "escherbridge-server"), jwtAudience (default "escherbridge-api"), jwtAccessTokenTtl (default 3600), jwtRefreshTokenTtl (default 604800), adminApiKey (optional), rateLimitPerMinute (default 100).
3. Extend validateConfig: when MULTI_TENANT=true, JWT_SECRET required and >= 32 chars.
4. Create src/services/jwt.ts with generateAccessToken(payload, config) using jose SignJWT with HS256. Claims: sub, email, admin, iat, exp. Test by decoding.
5. Add generateRefreshToken with longer TTL and type:"refresh" claim.
6. Add verifyToken(token, config) using jose jwtVerify. Test: valid, expired, wrong signature, wrong issuer, wrong audience.
7. Add decodeTokenUnsafe(token) for debug/error messages.

Run bun test -- all pass.
```

### Prompt 5B: Tenants Table + Password Hashing (Phase 2)

```
Implement Phase 2 of the multi-tenant-auth conductor track in the escherbridge-server repo.

Phase 2 - Tenants Table + Passwords (TDD):
1. Create src/services/password.ts: hashPassword(password) using Bun.password.hash with argon2id, verifyPassword(password, hash) using Bun.password.verify, validatePassword(password) enforcing 8-128 char range.
2. Create migration (next number after schema track) adding tenants table: id TEXT PK, name TEXT NOT NULL, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, is_admin INTEGER DEFAULT 0, status TEXT DEFAULT 'active', metadata TEXT DEFAULT '{}', created_at, updated_at. Indexes on email (unique) and status.
3. Create src/db/tenants.ts: createTenant (generates UUID), getTenantById, getTenantByEmail, updateTenant, listTenants (excludes password_hash), deleteTenant (soft-delete to status="deleted").

Run bun test -- all pass including migration on fresh and existing DBs.
```

### Prompt 5C: Registration + Login Endpoints (Phase 3)

```
Implement Phase 3 of the multi-tenant-auth conductor track in the escherbridge-server repo.

Phase 3 - Tenant CRUD Endpoints (TDD):
Create src/routes/api/tenants.ts with:
1. handleRegister: POST /api/tenants/register (public). Accept {name, email, password}. Create tenant, return 201 with {id, name, email, token, refreshToken}. 400 on missing fields, 409 on duplicate email.
2. handleLogin: POST /api/tenants/login (public). Verify credentials, return tokens. Generic "Invalid credentials" for wrong email OR password. 403 for suspended/deleted.
3. handleRefreshToken: POST /api/tenants/refresh (public). Exchange refresh token for new access+refresh tokens. Reject access tokens used as refresh (check type claim).
4. handleGetProfile: GET /api/tenants/me (auth required). Return tenant profile without password_hash.
5. handleUpdateProfile: PUT /api/tenants/me (auth required). Update name/email/password. Password change requires currentPassword.
6. handleListTenants: GET /api/tenants (admin only). List all tenants.
7. handleUpdateTenantStatus: PUT /api/tenants/:id/status (admin only). Change status.
8. handleDeleteTenant: DELETE /api/tenants/:id (admin only). Soft-delete.
9. Register all routes in router.ts.

Test full flow: register -> login -> get profile.
```

### Prompt 5D: Auth Middleware (Phase 4)

```
Implement Phase 4 of the multi-tenant-auth conductor track in the escherbridge-server repo.

Phase 4 - Multi-Strategy Auth Middleware (TDD):
Replace single-token auth in src/middleware/auth.ts with:

1. Define AuthResult type: { tenantId, tenantEmail, isAdmin, authMethod }.
2. authenticateAdminKey(authHeader, config): Check against ADMIN_API_KEY env var using timing-safe comparison. Returns admin AuthResult or null.
3. authenticateJwt(authHeader, config): Verify JWT, extract tenant from sub claim. Returns AuthResult or null.
4. authenticateLegacyToken(authHeader, config): When MULTI_TENANT=false, check against PLUGIN_API_TOKEN. Skipped when MULTI_TENANT=true.
5. authenticateRequest(req, config, db): Try strategies in order: adminKey -> jwt -> apiKey -> legacy. Return first success or null.
6. authRequired wrapper: returns 401 if unauthenticated.
7. adminRequired wrapper: returns 401 if unauth, 403 if not admin.
8. Check tenant status after auth: suspended/deleted tenants get 403.
9. Add auth, tenantEmail, isAdmin to RouteContext.
10. Add auth property to RouteEntry ("public" | "required" | "admin"). Update router dispatch.

CRITICAL: When MULTI_TENANT=false, PLUGIN_API_TOKEN must still work for ALL existing routes. Test backward compatibility.
```

### Prompt 5E: API Key Management (Phase 5)

```
Implement Phase 5 of the multi-tenant-auth conductor track in the escherbridge-server repo.

Phase 5 - API Key Management (TDD):
1. Migration for api_keys table: id, tenant_id, name, key_hash, key_prefix (first 8 chars), last_used_at, expires_at, created_at. Index on tenant_id and key_prefix.
2. Create src/services/api-keys.ts: generateApiKey() returns "esh_" + 32 hex chars using crypto.randomBytes.
3. Create src/db/api-keys.ts: createApiKey (enforce 10/tenant limit), listApiKeys (never return key_hash), deleteApiKey (verify ownership), findApiKeyByPrefix.
4. Add authenticateApiKey strategy to auth middleware: extract prefix from "esh_..." bearer token, findApiKeyByPrefix, verify hash with Bun.password.verify, update last_used_at. This strategy runs after JWT, before legacy.
5. Create src/routes/api/keys.ts: handleCreateKey (return raw key once), handleListKeys (metadata only), handleDeleteKey (verify ownership).
6. Register /api/keys routes (auth required).

Test lifecycle: create -> authenticate with key -> list -> delete -> verify key fails.
```

### Prompt 5F: Rate Limiting + Integration Tests (Phases 6-7)

```
Implement Phases 6-7 of the multi-tenant-auth conductor track in the escherbridge-server repo.

Phase 6 - Rate Limiting (TDD):
1. Create src/middleware/rate-limiter.ts with RateLimiter class. Map<string, number[]> storage. checkLimit(tenantId) returns { allowed, remaining, resetAt }.
2. Sliding window: prune timestamps older than 1 minute. Default 100 req/min (from config).
3. Admin exemption: isAdmin=true always allowed.
4. Periodic cleanup every 5 minutes for idle tenants.
5. Response headers: X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset, Retry-After (when limited).
6. Integrate into router: authenticated requests go through limiter. 429 when exceeded. Admin bypass. Public routes skip.

Phase 7 - Integration Tests:
1. Full registration -> login -> API access with tenant scoping.
2. Admin bootstrapping: ADMIN_API_KEY -> promote tenant -> admin JWT access.
3. API key lifecycle end-to-end.
4. Token refresh with short TTL.
5. Suspended tenant blocked from all access.
6. Backward compatibility: MULTI_TENANT=false + PLUGIN_API_TOKEN works for all routes.
7. Security: malformed JWT, tampered claims, SQL injection in email, XSS in name, oversized bodies.
8. Rate limiter: 200 concurrent requests, exactly 100 succeed.
9. Cross-tenant isolation via auth at API level.

Target: 80+ new test cases total across the track.
```

---

## Track 6: Multi-Tenant Routing (~18-28h)

> **Status:** Pending. Depends on multi-tenant-auth. Work in escherbridge-server repo.

### Prompt 6A: Tenant Middleware + SSE Partitioning (Phases 1-2)

```
Implement Phases 1-2 of the multi-tenant-routing conductor track in the escherbridge-server repo. Read the spec at conductor/tracks/multi-tenant-routing_20260312/spec.md and plan at conductor/tracks/multi-tenant-routing_20260312/plan.md.

Phase 1 - TenantContext Middleware:
1. Verify/update TenantContext type and resolveTenant() to extract tenantId from JWT claims (set by auth middleware) rather than X-Tenant-Id header. When MULTI_TENANT=false, always return "default".
2. Verify RouteContext.tenantId is populated before handler dispatch.
3. Add X-Tenant-Id response header when MULTI_TENANT=true.

Phase 2 - Tenant-Scoped SSE Manager (TDD):
Refactor src/services/sse.ts:
1. Add tenantId to SSEClient interface.
2. Change storage to Map<string, Map<string, SSEClient>> (outer=tenantId, inner=clientId).
3. addClient(id, controller, tenantId) stores under tenant partition.
4. removeClient(id) removes from correct partition, cleans empty maps.
5. broadcast(event, tenantId) sends only to that tenant's clients.
6. broadcastAll(event) for system events (heartbeat).
7. clientCountForTenant(tenantId) and updated clientCount (aggregate).
8. Update heartbeat to use broadcastAll.
9. Update SSE endpoint (routes/events.ts) to pass tenantId from auth context.

Test: tenant-A broadcast doesn't reach tenant-B. Heartbeat reaches all. Single-tenant mode identical.
```

### Prompt 6B: Agent Bridge + Services (Phases 3-4)

```
Implement Phases 3-4 of the multi-tenant-routing conductor track in the escherbridge-server repo.

Phase 3 - Tenant-Scoped Agent Bridge (TDD):
1. Add tenantId to PendingRequest type.
2. sendRequest(operation, params, traceId, tenantId): broadcast SSE only to tenant's clients, store tenantId on pending request.
3. isPluginConnected(tenantId): check tenant-specific SSE clients.
4. resolveRequest: verify tenant ownership, reject cross-tenant resolution.
5. pendingCountForTenant(tenantId).
6. Update agent-callback handler to validate tenant ownership.

Phase 4 - Tenant-Scoped Services (TDD):
EventBus:
1. publish(event, tenantId) passes to insertEvent and scoped broadcast.
2. query/count/prune accept tenantId.
3. Deduplication is per-tenant (prefix dedup key with tenantId).

SessionStore:
1. create/get/list/update/addMessage/getMessages/archive/touchActivity all accept tenantId.
2. get with wrong tenant returns null.

ApprovalStore:
1. Refactor to Map<tenantId, Map<contactId, PendingApproval[]>>.
2. create/resolve/resolveById/cancel enforce tenant ownership.
3. getAll/getPending scoped by tenant.
4. Per-tenant limits (5 per contact, 100 total).
5. SSE broadcasts scoped to tenant.

All existing tests must pass with "default" tenant.
```

### Prompt 6C: Route Handlers + MCP + Integration Tests (Phases 5-6)

```
Implement Phases 5-6 of the multi-tenant-routing conductor track in the escherbridge-server repo.

Phase 5 - Route Handler Tenant Propagation (TDD):
Update EVERY route handler to extract tenantId from ctx and pass to all DB/service calls:
- send.ts, messages.ts: tenantId to DB functions
- jobs.ts, skills.ts, secrets.ts, mcp.ts: tenantId to bridge sendRequest
- agent-chat.ts: tenantId to sessionStore and bridge
- characters.ts, character-chat.ts: tenantId to character DB
- approvals.ts: tenantId to approvalStore
- events.ts: tenantId to eventBus
- webhooks (whatsapp, telegram, event-hub): tenantId from context
- routes/events.ts (SSE): tenantId to SSE manager

Phase 6 - MCP Session Binding + Integration Tests (TDD):
MCP:
1. Add tenantId to McpToolContext.
2. Create session-to-tenant map in mcp-transport.ts.
3. Extract tenant at MCP session init, inject into tool context via getContext().
4. Scope session lookup and deletion by tenant.
5. Update ALL MCP tool handlers to use ctx.tenantId.

Integration Tests:
1. Cross-tenant SSE isolation (two tenants, events don't cross).
2. Cross-tenant Agent Bridge isolation (callback rejected).
3. Cross-tenant session isolation (session not found).
4. Cross-tenant approval isolation (resolve rejected).
5. Single-tenant backward compatibility (no JWT, "default" tenant).
6. MCP session tenant binding (tools scoped to tenant).

This completes the multi-tenant-routing track and the entire conductor roadmap.
```

---

## Execution Strategy

### Optimal Order (with parallelism)

```
Session 1 (parallel):
  ├── Prompt 1A: Verify open-source-prep      (~1h)
  └── Prompt 2A: Auth namespace Phase 1        (~1.5h)

Session 2:
  └── Prompt 2B + 2C: Complete plugin-dual-auth (~2.5h)

Session 3:
  └── Prompt 3A: Bootstrap server repo          (~2h)

Session 4:
  └── Prompt 3B + 3C: Docker/CI/Config/README   (~5h)

Session 5:
  └── Prompt 4A + 4B: Migration + TenantContext  (~5h)

Session 6 (parallel phases):
  ├── Prompt 4C: DB scoping - contacts/messages  (~4h)
  └── Prompt 4D: DB scoping - chars/sessions     (~4h)

Session 7:
  └── Prompt 4E: Route handlers + isolation tests (~5h)

Session 8:
  └── Prompt 5A + 5B: JWT + tenants table        (~7h)

Session 9:
  └── Prompt 5C + 5D: Endpoints + auth middleware (~9h)

Session 10:
  └── Prompt 5E + 5F: API keys + rate limit + integration (~8h)

Session 11:
  └── Prompt 6A + 6B: SSE/Bridge/Services tenant scoping (~10h)

Session 12:
  └── Prompt 6C: Routes + MCP + final integration (~8h)
```

**Total: ~12 sessions, ~60-70h estimated**
