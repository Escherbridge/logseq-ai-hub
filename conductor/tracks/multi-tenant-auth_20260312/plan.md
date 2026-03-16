# Implementation Plan: Multi-Tenant Authentication

## Overview

7 phases implementing JWT-based authentication, tenant management, API key management, auth middleware, admin authentication, and rate limiting for the proprietary multi-tenant server. Each phase builds incrementally with TDD throughout. All work targets the proprietary server repository (assumes proprietary-server-bootstrap and multi-tenant-schema tracks are complete).

**Total estimated effort:** 25-35 hours (65 tasks across 7 phases)

**Dependencies:**
- proprietary-server-bootstrap_20260312 (server repo exists with independent package.json)
- multi-tenant-schema_20260312 (tenant_id columns, TenantContext type, resolveTenant stub, migration system)

---

## Phase 1: Configuration and JWT Foundation
**Goal:** Extend config with auth env vars, add jose dependency, implement JWT sign/verify utilities.

Tasks:
- [ ] Task 1.1: Add `jose` dependency to package.json (`bun add jose`). Verify import resolves.
- [ ] Task 1.2: Extend Config interface with auth fields (TDD: Write test that loadConfig reads JWT_SECRET, JWT_ISSUER, JWT_AUDIENCE, JWT_ACCESS_TOKEN_TTL, JWT_REFRESH_TOKEN_TTL, ADMIN_API_KEY, RATE_LIMIT_PER_MINUTE from env vars with correct defaults. Implement in config.ts.)
- [ ] Task 1.3: Extend validateConfig for multi-tenant auth requirements (TDD: Write test that when MULTI_TENANT=true, missing JWT_SECRET fails validation; JWT_SECRET under 32 chars fails; valid JWT_SECRET passes. Implement validation logic.)
- [ ] Task 1.4: Create `src/services/jwt.ts` with `generateAccessToken(payload, config)` (TDD: Write test that generates a JWT with sub, email, admin, iat, exp claims using HS256. Verify claims are correct by decoding. Implement with jose SignJWT.)
- [ ] Task 1.5: Add `generateRefreshToken(payload, config)` to jwt.ts (TDD: Write test that refresh token has longer TTL, includes `type: "refresh"` claim. Implement.)
- [ ] Task 1.6: Add `verifyToken(token, config)` to jwt.ts (TDD: Write tests for valid token, expired token, invalid signature, wrong issuer, wrong audience. Implement with jose jwtVerify.)
- [ ] Task 1.7: Add `decodeTokenUnsafe(token)` for extracting claims without verification (TDD: Write test that decodes a JWT without verifying signature -- used for error messages and debugging. Implement with jose decodeJwt.)
- [ ] Verification: Run all JWT tests, confirm jose integration works with Bun runtime. [checkpoint marker]

---

## Phase 2: Tenants Table and Password Hashing
**Goal:** Create tenants table via migration, implement tenant DB functions, password hashing utilities.

Tasks:
- [ ] Task 2.1: Create `src/services/password.ts` with `hashPassword(password)` (TDD: Write test that hashes a password using Bun.password.hash with argon2id. Verify output is a PHC-format string. Implement.)
- [ ] Task 2.2: Add `verifyPassword(password, hash)` to password.ts (TDD: Write test that correct password verifies true, incorrect verifies false. Implement with Bun.password.verify.)
- [ ] Task 2.3: Add `validatePassword(password)` to password.ts (TDD: Write tests for minimum 8 chars, maximum 128 chars, empty string. Returns validation result object.)
- [ ] Task 2.4: Create migration 002 (or next number) in the migration system to add `tenants` table (TDD: Write test that migration creates table with correct schema -- id, name, email, password_hash, is_admin, status, metadata, created_at, updated_at. Verify indexes on email (unique) and status. Implement migration.)
- [ ] Task 2.5: Create `src/db/tenants.ts` with `createTenant(db, { name, email, passwordHash })` (TDD: Write test that inserts a tenant with UUID id, returns the created tenant. Test duplicate email returns error. Implement.)
- [ ] Task 2.6: Add `getTenantById(db, id)` and `getTenantByEmail(db, email)` to tenants.ts (TDD: Write tests for found and not-found cases. Implement.)
- [ ] Task 2.7: Add `updateTenant(db, id, updates)` to tenants.ts (TDD: Write test that updates name, email, password_hash, status. Verify updated_at changes. Implement.)
- [ ] Task 2.8: Add `listTenants(db)` to tenants.ts (TDD: Write test that returns all tenants without password_hash field. Implement with explicit column selection.)
- [ ] Task 2.9: Add `deleteTenant(db, id)` as soft-delete to tenants.ts (TDD: Write test that sets status to "deleted" and updated_at. Verify tenant still exists in DB. Implement.)
- [ ] Verification: Run migration on a fresh DB and an existing DB. Verify tenants table created. Run all tenant DB tests. [checkpoint marker]

---

## Phase 3: Tenant Registration and Login Endpoints
**Goal:** Implement registration, login, and token refresh endpoints.

Tasks:
- [ ] Task 3.1: Create `src/routes/api/tenants.ts` with `handleRegister(req, ctx)` (TDD: Write test that POST with valid {name, email, password} creates tenant, returns 201 with id, name, email, token, refreshToken. Test missing fields return 400. Test duplicate email returns 409. Implement.)
- [ ] Task 3.2: Add `handleLogin(req, ctx)` to tenants.ts (TDD: Write test that correct credentials return tokens. Wrong password returns 401 with generic "Invalid credentials". Nonexistent email returns 401 with same generic message. Suspended tenant returns 403. Deleted tenant returns 403. Implement.)
- [ ] Task 3.3: Add `handleRefreshToken(req, ctx)` to tenants.ts (TDD: Write test that valid refresh token returns new access token and new refresh token. Expired refresh token returns 401. Access token used as refresh returns 401 -- check for type claim. Implement.)
- [ ] Task 3.4: Add `handleGetProfile(req, ctx)` to tenants.ts (TDD: Write test that returns current tenant profile from ctx.tenantId. No password_hash in response. Implement.)
- [ ] Task 3.5: Add `handleUpdateProfile(req, ctx)` to tenants.ts (TDD: Write test that updates name/email. Test password change requires currentPassword field and verifies it. Test email change checks for duplicates. Implement.)
- [ ] Task 3.6: Add `handleListTenants(req, ctx)` admin endpoint (TDD: Write test that returns all tenants. This handler assumes admin check is done by middleware -- test it returns tenant list. Implement.)
- [ ] Task 3.7: Add `handleUpdateTenantStatus(req, ctx, params)` admin endpoint (TDD: Write test that changes tenant status. Valid statuses: active, suspended, deleted. Invalid status returns 400. Implement.)
- [ ] Task 3.8: Add `handleDeleteTenant(req, ctx, params)` admin endpoint (TDD: Write test that soft-deletes tenant. Returns 200 on success, 404 if not found. Implement.)
- [ ] Task 3.9: Register all tenant routes in the router (TDD: Write integration test that verifies route matching for /api/tenants/register, /api/tenants/login, /api/tenants/refresh, /api/tenants/me, /api/tenants, /api/tenants/:id/status, /api/tenants/:id. Implement route entries.)
- [ ] Verification: Test full registration -> login -> profile flow manually or via integration test. Verify tokens are valid JWTs with correct claims. [checkpoint marker]

---

## Phase 4: Auth Middleware
**Goal:** Replace the single-token auth with multi-strategy middleware that resolves JWT, API key, admin key, or legacy token.

Tasks:
- [ ] Task 4.1: Define `AuthResult` type in `src/middleware/auth.ts` (TDD: Write type tests -- AuthResult has tenantId, tenantEmail, isAdmin, authMethod. Implement type definition.)
- [ ] Task 4.2: Implement `authenticateAdminKey(authHeader, config)` strategy (TDD: Write test that matching ADMIN_API_KEY returns admin AuthResult. Non-matching returns null. Empty ADMIN_API_KEY config means strategy is skipped. Uses timing-safe comparison. Implement.)
- [ ] Task 4.3: Implement `authenticateJwt(authHeader, config)` strategy (TDD: Write test that valid JWT returns AuthResult with tenantId from sub claim. Expired JWT returns specific error. Invalid JWT returns null. Implement using jwt.ts verifyToken.)
- [ ] Task 4.4: Implement `authenticateLegacyToken(authHeader, config)` strategy (TDD: Write test that when MULTI_TENANT=false, matching PLUGIN_API_TOKEN returns AuthResult with tenantId="default". When MULTI_TENANT=true, this strategy is skipped. Implement.)
- [ ] Task 4.5: Implement `authenticateRequest(req, config, db)` orchestrator (TDD: Write test that tries strategies in order: adminKey -> jwt -> apiKey -> legacy. Returns first successful AuthResult or null. Implement.)
- [ ] Task 4.6: Add `authRequired` middleware wrapper function (TDD: Write test that wraps a handler, calls authenticateRequest, returns 401 if null, calls handler with enriched context if authenticated. Implement.)
- [ ] Task 4.7: Add `adminRequired` middleware wrapper function (TDD: Write test that wraps a handler, calls authenticateRequest, returns 401 if not authenticated, returns 403 if not admin, calls handler if admin. Implement.)
- [ ] Task 4.8: Add tenant status check to auth flow (TDD: Write test that after JWT auth resolves a tenantId, the tenant's status is checked in DB. Suspended/deleted tenants get 403. Active tenants proceed. Implement.)
- [ ] Task 4.9: Extend RouteContext with auth fields (TDD: Write test verifying RouteContext now includes optional tenantId, tenantEmail, isAdmin, authMethod fields. Update the interface.)
- [ ] Task 4.10: Update RouteEntry to include `auth` property (TDD: Write test that route entries can specify `auth: "public" | "required" | "admin"`. Update the type and router dispatch logic to call appropriate middleware before handler.)
- [ ] Task 4.11: Integrate auth middleware into router dispatch loop (TDD: Write integration test -- public route returns 200 without auth, required route returns 401 without auth and 200 with valid JWT, admin route returns 403 with non-admin JWT. Implement in createRouter.)
- [ ] Verification: Test all auth strategies end-to-end. Verify backward compatibility: set MULTI_TENANT=false, confirm PLUGIN_API_TOKEN still works for all existing routes. [checkpoint marker]

---

## Phase 5: API Key Management
**Goal:** Implement API key generation, storage, lookup, and authentication strategy.

Tasks:
- [ ] Task 5.1: Create migration for `api_keys` table (TDD: Write test that migration creates table with id, tenant_id, name, key_hash, key_prefix, last_used_at, expires_at, created_at. Index on tenant_id and key_prefix. Implement migration.)
- [ ] Task 5.2: Create `src/services/api-keys.ts` with `generateApiKey()` (TDD: Write test that returns a key starting with "esh_" followed by 32 hex chars -- 36 total. Uses crypto.randomBytes for randomness. Implement.)
- [ ] Task 5.3: Create `src/db/api-keys.ts` with `createApiKey(db, { tenantId, name, keyHash, keyPrefix })` (TDD: Write test that inserts key and returns metadata. Test 10-key limit per tenant enforced. Implement.)
- [ ] Task 5.4: Add `listApiKeys(db, tenantId)` to api-keys.ts (TDD: Write test that returns keys for tenant only, never returns key_hash. Implement.)
- [ ] Task 5.5: Add `deleteApiKey(db, keyId, tenantId)` to api-keys.ts (TDD: Write test that deletes key only if owned by tenant. Returns false if not found or wrong tenant. Implement.)
- [ ] Task 5.6: Add `findApiKeyByPrefix(db, prefix)` to api-keys.ts (TDD: Write test that returns candidate keys matching prefix. Used by auth strategy for hash verification. Implement.)
- [ ] Task 5.7: Implement `authenticateApiKey(authHeader, config, db)` strategy in auth.ts (TDD: Write test that extracts prefix from bearer token, finds candidates by prefix, verifies hash with Bun.password.verify, returns AuthResult on match. Updates last_used_at. Returns null on no match. Implement.)
- [ ] Task 5.8: Integrate API key strategy into `authenticateRequest` orchestrator (TDD: Write test that API key auth works in the chain -- after JWT, before legacy. Implement.)
- [ ] Task 5.9: Create `src/routes/api/keys.ts` with `handleCreateKey`, `handleListKeys`, `handleDeleteKey` (TDD: Write tests for each endpoint. Create returns raw key once. List returns metadata only. Delete verifies ownership. Implement.)
- [ ] Task 5.10: Register API key routes in router (TDD: Write test for route matching on /api/keys and /api/keys/:id. All routes require auth. Implement.)
- [ ] Verification: Test full flow: create key -> use key to authenticate -> list keys -> delete key -> verify key no longer works. [checkpoint marker]

---

## Phase 6: Rate Limiting
**Goal:** Implement in-memory sliding-window rate limiter per tenant with response headers.

Tasks:
- [ ] Task 6.1: Create `src/middleware/rate-limiter.ts` with `RateLimiter` class (TDD: Write test that constructor accepts limit and windowMs. Class has `checkLimit(tenantId): { allowed: boolean, remaining: number, resetAt: number }` method. Implement with Map<string, number[]>.)
- [ ] Task 6.2: Implement sliding window logic (TDD: Write test that first N requests pass, N+1 is rejected. Requests after window slides are allowed again. Use deterministic timestamps for testing. Implement pruning of old timestamps.)
- [ ] Task 6.3: Implement admin exemption (TDD: Write test that `checkLimit` with `isAdmin=true` always returns allowed. Implement.)
- [ ] Task 6.4: Implement periodic cleanup (TDD: Write test that `cleanup()` removes entries with no requests in the window. Implement with setInterval in production, manual call in tests.)
- [ ] Task 6.5: Add rate limit response headers utility (TDD: Write test that `rateLimitHeaders(limit, remaining, resetAt)` returns correct X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset, and Retry-After (only when remaining=0) headers. Implement.)
- [ ] Task 6.6: Integrate rate limiter into router (TDD: Write test that authenticated requests go through rate limiter. 429 response when exceeded. Headers present on all authenticated responses. Admin requests bypass limiter. Public routes skip limiter. Implement.)
- [ ] Task 6.7: Add RateLimiter to server initialization (TDD: Write test that RateLimiter is created with config.rateLimitPerMinute and cleanup interval is started. Implement in index.ts.)
- [ ] Verification: Test rate limiting with rapid requests. Verify headers on normal responses. Verify 429 when limit exceeded. Verify admin bypass. [checkpoint marker]

---

## Phase 7: Integration Testing and Edge Cases
**Goal:** End-to-end integration tests, backward compatibility verification, security edge cases.

Tasks:
- [ ] Task 7.1: Full registration -> login -> API access integration test (TDD: Register tenant, login, use JWT to access /api/characters, verify tenant scoping works end-to-end.)
- [ ] Task 7.2: Admin bootstrapping integration test (TDD: Use ADMIN_API_KEY to access admin endpoints, promote a tenant to admin, verify promoted tenant can access admin endpoints via JWT.)
- [ ] Task 7.3: API key lifecycle integration test (TDD: Create API key, use it to access API, list keys, delete key, verify deleted key fails auth.)
- [ ] Task 7.4: Token refresh lifecycle test (TDD: Login, wait for access token to expire (use short TTL in test), refresh, verify new tokens work.)
- [ ] Task 7.5: Suspended tenant test (TDD: Register, login, admin suspends tenant, verify all subsequent requests return 403, verify login returns 403.)
- [ ] Task 7.6: Backward compatibility test (TDD: Set MULTI_TENANT=false, verify PLUGIN_API_TOKEN auth works for all existing route patterns. No tenant routes needed.)
- [ ] Task 7.7: Security edge cases (TDD: Test malformed JWT, JWT with tampered claims, expired token, token signed with wrong secret, SQL injection in email field, XSS in tenant name field, oversized request bodies.)
- [ ] Task 7.8: Rate limiter under concurrent load (TDD: Fire 200 concurrent requests for the same tenant with limit=100. Verify exactly 100 succeed and 100 get 429. Use Promise.all.)
- [ ] Task 7.9: Cross-tenant isolation via auth (TDD: Create two tenants, each creates a character. Tenant A cannot see Tenant B's characters. Verify at the API level with JWT auth.)
- [ ] Verification: All tests pass. Run full test suite to verify no regressions. Document test count. [checkpoint marker]

---

## Summary

| Phase | Focus | Tasks | Est. Hours |
|-------|-------|-------|------------|
| 1 | Config + JWT Foundation | 8 | 3-4 |
| 2 | Tenants Table + Passwords | 10 | 4-5 |
| 3 | Registration/Login Endpoints | 10 | 4-5 |
| 4 | Auth Middleware | 12 | 5-7 |
| 5 | API Key Management | 11 | 4-5 |
| 6 | Rate Limiting | 8 | 3-4 |
| 7 | Integration + Edge Cases | 10 | 3-5 |
| **Total** | | **69** | **25-35** |
