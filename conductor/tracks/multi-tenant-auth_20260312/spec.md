# Specification: Multi-Tenant Authentication

## Overview

Implement JWT-based authentication and tenant management for the proprietary multi-tenant server. This track adds tenant CRUD operations, JWT token lifecycle (generation and validation), password hashing, API key management per tenant, auth middleware that resolves tenant context from JWT claims, admin authentication via dual mechanism (JWT admin claim and env-var override), and in-memory sliding-window rate limiting per tenant. This work targets the proprietary server repository only and builds on the schema foundations laid by the multi-tenant-schema track.

## Background

The current server authenticates with a single `PLUGIN_API_TOKEN` bearer token (see `server/src/middleware/auth.ts`). All requests share the same identity. The `Config` interface (`server/src/config.ts`) holds a flat set of env vars with no concept of tenants, JWT, or per-tenant quotas. The `RouteContext` interface (`server/src/router.ts`) carries `config`, `db`, `agentBridge`, and other services but no tenant identity. The multi-tenant-schema track (dependency) adds `tenant_id` columns to all 8 database tables and defines a `TenantContext` type with a `resolveTenant()` utility -- but that utility extracts tenant from headers or config defaults only, not from JWT. This track replaces that stub with full JWT-based tenant resolution and adds the tenant management layer above it.

## Functional Requirements

### FR-1: Tenants Database Table
- **Description:** A `tenants` table in SQLite storing tenant records with hashed passwords and metadata.
- **Acceptance Criteria:**
  - Table schema: `id TEXT PRIMARY KEY`, `name TEXT NOT NULL`, `email TEXT UNIQUE NOT NULL`, `password_hash TEXT NOT NULL`, `is_admin INTEGER NOT NULL DEFAULT 0`, `status TEXT NOT NULL DEFAULT 'active'` (values: active, suspended, deleted), `metadata TEXT DEFAULT '{}'`, `created_at TEXT NOT NULL DEFAULT (datetime('now'))`, `updated_at TEXT NOT NULL DEFAULT (datetime('now'))`.
  - Indexes: unique on `email`, index on `status`.
  - Tenant `id` is a UUID v4 generated at creation time.
  - Table created as part of the migration system (extends multi-tenant-schema migrations).
- **Priority:** P0

### FR-2: Password Hashing with Argon2id
- **Description:** Use Bun's built-in `Bun.password.hash()` and `Bun.password.verify()` with Argon2id for all password operations.
- **Acceptance Criteria:**
  - Registration hashes passwords with `Bun.password.hash(password, "argon2id")`.
  - Login verifies passwords with `Bun.password.verify(password, hash)`.
  - No plaintext passwords are ever stored or logged.
  - Password minimum length: 8 characters. Maximum: 128 characters.
  - Validation errors return generic "invalid credentials" (no leaking whether email exists).
- **Priority:** P0

### FR-3: JWT Token Generation and Validation
- **Description:** JWT tokens using the `jose` library for tenant authentication.
- **Acceptance Criteria:**
  - `jose` library used for all JWT operations (SignJWT, jwtVerify).
  - JWT payload claims: `sub` (tenant ID), `email`, `admin` (boolean), `iat`, `exp`.
  - Access tokens expire in 1 hour (configurable via `JWT_ACCESS_TOKEN_TTL`).
  - Refresh tokens expire in 7 days (configurable via `JWT_REFRESH_TOKEN_TTL`).
  - Signing algorithm: HS256 with `JWT_SECRET` env var.
  - `JWT_SECRET` must be at least 32 characters; server refuses to start in multi-tenant mode if shorter.
  - Token issuer (`iss`) set to `JWT_ISSUER` env var (default: `"escherbridge-server"`).
  - Token audience (`aud`) set to `JWT_AUDIENCE` env var (default: `"escherbridge-api"`).
  - `jwtVerify` validates `iss`, `aud`, `exp`, and signature.
  - Invalid/expired tokens return 401 with `{ success: false, error: "Token expired" }` or `{ success: false, error: "Invalid token" }`.
- **Priority:** P0

### FR-4: Tenant CRUD Operations
- **Description:** REST API endpoints for creating, reading, updating, and deleting tenants.
- **Acceptance Criteria:**
  - `POST /api/tenants/register` -- Create a new tenant (public endpoint, no auth required). Accepts `{ name, email, password }`. Returns `{ success: true, data: { id, name, email, token, refreshToken } }`.
  - `POST /api/tenants/login` -- Authenticate and receive tokens. Accepts `{ email, password }`. Returns `{ success: true, data: { id, name, email, token, refreshToken } }`.
  - `POST /api/tenants/refresh` -- Exchange refresh token for new access token. Accepts `{ refreshToken }`. Returns `{ success: true, data: { token, refreshToken } }`.
  - `GET /api/tenants/me` -- Get current tenant profile (auth required). Returns tenant details without password hash.
  - `PUT /api/tenants/me` -- Update current tenant profile (auth required). Accepts `{ name?, email?, password? }`. Password change requires `currentPassword` field.
  - `GET /api/tenants` -- List all tenants (admin only). Returns array of tenant summaries (no password hashes).
  - `PUT /api/tenants/:id/status` -- Update tenant status (admin only). Accepts `{ status: "active" | "suspended" | "deleted" }`.
  - `DELETE /api/tenants/:id` -- Soft-delete tenant (admin only). Sets status to "deleted".
  - Duplicate email returns 409 Conflict.
  - Suspended/deleted tenants cannot log in (return 403).
- **Priority:** P0

### FR-5: API Key Management Per Tenant
- **Description:** Each tenant can create and manage API keys for programmatic access (e.g., CI/CD pipelines, plugin connections).
- **Acceptance Criteria:**
  - `api_keys` table: `id TEXT PRIMARY KEY`, `tenant_id TEXT NOT NULL`, `name TEXT NOT NULL`, `key_hash TEXT NOT NULL`, `key_prefix TEXT NOT NULL` (first 8 chars of key for identification), `last_used_at TEXT`, `expires_at TEXT`, `created_at TEXT NOT NULL DEFAULT (datetime('now'))`.
  - `POST /api/keys` -- Create API key (auth required). Returns the raw key exactly once in the response. Key format: `esh_` prefix + 32 random hex chars (e.g., `esh_a1b2c3d4...`).
  - `GET /api/keys` -- List tenant's API keys (auth required). Returns key metadata (id, name, prefix, last_used, expires_at) but never the key itself.
  - `DELETE /api/keys/:id` -- Revoke an API key (auth required). Only the owning tenant or admin can delete.
  - API keys stored as Argon2id hashes (same as passwords).
  - Auth middleware accepts API keys in `Authorization: Bearer esh_...` header as an alternative to JWT.
  - When authenticated via API key, the tenant context is resolved from the key's `tenant_id`.
  - API keys inherit the tenant's admin status (not independently configurable).
  - Maximum 10 API keys per tenant.
- **Priority:** P1

### FR-6: Auth Middleware
- **Description:** Replace the current single-token auth with a multi-strategy auth middleware.
- **Acceptance Criteria:**
  - Auth middleware runs before route handlers for all `/api/*` and `/mcp` routes.
  - Authentication strategies checked in order:
    1. `ADMIN_API_KEY` env var: If `Authorization: Bearer <value>` matches `ADMIN_API_KEY`, authenticate as admin with tenant from `X-Tenant-Id` header (or "default").
    2. JWT: If `Authorization: Bearer <jwt>`, verify JWT, extract tenant ID from `sub` claim.
    3. API Key: If `Authorization: Bearer esh_...`, look up key hash in `api_keys` table, resolve tenant.
    4. Legacy: If `MULTI_TENANT=false` and token matches `PLUGIN_API_TOKEN`, authenticate as default tenant (backward compatibility).
  - Authenticated requests populate `RouteContext` with `tenantId`, `tenantEmail`, `isAdmin`.
  - Unauthenticated requests to protected routes return 401.
  - Public routes (health, webhook endpoints, register, login, refresh) skip auth.
  - Suspended tenants receive 403 even with valid tokens.
- **Priority:** P0

### FR-7: Admin Authentication
- **Description:** Dual admin authentication mechanism for bootstrapping and operational use.
- **Acceptance Criteria:**
  - **JWT admin claim:** Tenants with `is_admin = 1` in the database receive `admin: true` in their JWT claims. Auth middleware grants admin privileges.
  - **ADMIN_API_KEY env var:** A static key set in environment. When used as bearer token, grants admin access without JWT. Used for bootstrapping (creating first admin tenant), CI/CD, and emergency access.
  - Admin-only endpoints: `GET /api/tenants`, `PUT /api/tenants/:id/status`, `DELETE /api/tenants/:id`.
  - Non-admin requests to admin-only endpoints return 403 Forbidden.
  - Either mechanism (JWT admin claim OR ADMIN_API_KEY) grants admin access -- they are not both required.
  - `ADMIN_API_KEY` is optional. If unset, admin access is only via JWT admin claim.
- **Priority:** P0

### FR-8: Rate Limiting
- **Description:** In-memory sliding-window rate limiting per tenant.
- **Acceptance Criteria:**
  - Rate limiter uses a `Map<string, number[]>` where keys are tenant IDs and values are arrays of request timestamps.
  - Default limits: 100 requests per minute per tenant (configurable via `RATE_LIMIT_PER_MINUTE` env var).
  - Sliding window: timestamps older than 1 minute are pruned on each check.
  - Rate-limited requests return 429 Too Many Requests with `Retry-After` header (seconds until next allowed request).
  - Response includes `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` headers on all authenticated responses.
  - Admin requests (via ADMIN_API_KEY) are exempt from rate limiting.
  - Rate limit state is per-process (lost on restart -- acceptable for initial version).
  - Periodic cleanup: every 5 minutes, remove entries for tenants with no requests in the last 5 minutes.
- **Priority:** P1

### FR-9: Configuration Updates
- **Description:** Extend the Config interface and loadConfig() for auth-related environment variables.
- **Acceptance Criteria:**
  - New config fields: `jwtSecret` (string, required when MULTI_TENANT=true), `jwtIssuer` (string, default "escherbridge-server"), `jwtAudience` (string, default "escherbridge-api"), `jwtAccessTokenTtl` (number, seconds, default 3600), `jwtRefreshTokenTtl` (number, seconds, default 604800), `adminApiKey` (string, optional), `rateLimitPerMinute` (number, default 100).
  - `validateConfig()` enforces: when `MULTI_TENANT=true`, `JWT_SECRET` must be set and at least 32 chars.
  - When `MULTI_TENANT=false`, all new config fields are ignored and the server behaves identically to the open-source version.
- **Priority:** P0

## Non-Functional Requirements

### NFR-1: Performance
- JWT validation must complete in under 5ms for cached keys.
- Rate limiter check must complete in under 1ms (in-memory Map lookup).
- Password hashing (Argon2id) is expected to take 50-200ms per operation (acceptable for login/register).
- API key lookup must be indexed for sub-5ms response.

### NFR-2: Security
- No plaintext passwords in database, logs, or API responses.
- JWT secrets must be at least 32 characters.
- API keys use cryptographically secure random generation (`crypto.randomBytes`).
- Timing-safe comparison for ADMIN_API_KEY check (`crypto.timingSafeEqual` or equivalent).
- Login endpoint does not reveal whether email exists (generic "invalid credentials" error).
- Rate limiting prevents brute-force login attempts.
- Refresh token rotation: issuing a new access token via refresh also rotates the refresh token.

### NFR-3: Backward Compatibility
- When `MULTI_TENANT=false` (default), the server authenticates exactly as before (single `PLUGIN_API_TOKEN` bearer).
- All existing tests pass without modification when `MULTI_TENANT=false`.
- No API contract changes visible to existing single-tenant clients.

### NFR-4: Testability
- All auth functions are unit-testable with injected dependencies.
- JWT generation/validation testable without running the full server.
- Rate limiter testable with mock timestamps.
- At least 80 new test cases across all auth features.

## User Stories

### US-1: Tenant Self-Registration
- **As** a new user of the hosted service
- **I want** to register with my email and password
- **So that** I get my own tenant space with isolated data

**Scenarios:**
- **Given** no account exists for "user@example.com"
  **When** I POST to `/api/tenants/register` with `{ name: "Alice", email: "user@example.com", password: "securepass123" }`
  **Then** I receive a 201 response with my tenant ID, JWT access token, and refresh token
  **And** my password is stored as an Argon2id hash

- **Given** an account already exists for "user@example.com"
  **When** I POST to `/api/tenants/register` with the same email
  **Then** I receive a 409 Conflict response

### US-2: Tenant Login
- **As** a registered tenant
- **I want** to log in with my credentials
- **So that** I receive a JWT for authenticated API access

**Scenarios:**
- **Given** I have a registered account
  **When** I POST to `/api/tenants/login` with correct credentials
  **Then** I receive access and refresh tokens
  **And** the access token contains my tenant ID as the `sub` claim

- **Given** my account is suspended
  **When** I POST to `/api/tenants/login` with correct credentials
  **Then** I receive a 403 response with "Account suspended"

### US-3: Authenticated API Access
- **As** an authenticated tenant
- **I want** my JWT to automatically scope all API requests to my tenant
- **So that** I only see and modify my own data

**Scenarios:**
- **Given** I have a valid JWT for tenant "t-123"
  **When** I GET `/api/characters` with the JWT in the Authorization header
  **Then** I receive only characters belonging to tenant "t-123"

- **Given** my JWT has expired
  **When** I make any authenticated API request
  **Then** I receive a 401 response with "Token expired"

### US-4: Admin Bootstrapping
- **As** a platform operator
- **I want** to use the ADMIN_API_KEY to create the first admin tenant
- **So that** I can bootstrap the platform without a chicken-and-egg problem

**Scenarios:**
- **Given** `ADMIN_API_KEY=my-secret-key` is set in the environment
  **When** I call `PUT /api/tenants/:id/status` with `Authorization: Bearer my-secret-key` and `X-Tenant-Id: default`
  **Then** the request is treated as admin
  **And** I can promote a tenant to admin

### US-5: API Key for Plugin
- **As** a tenant
- **I want** to create an API key for my Logseq plugin
- **So that** the plugin can authenticate without storing my password

**Scenarios:**
- **Given** I am authenticated
  **When** I POST to `/api/keys` with `{ name: "My Plugin" }`
  **Then** I receive a key like `esh_a1b2c3d4...` which I can use as a bearer token
  **And** the key resolves to my tenant on every request

### US-6: Rate Limiting
- **As** a platform operator
- **I want** per-tenant rate limiting
- **So that** one tenant cannot overwhelm the server

**Scenarios:**
- **Given** rate limit is 100 req/min
  **When** tenant "t-123" sends 101 requests within 1 minute
  **Then** the 101st request receives a 429 response with `Retry-After` header
  **And** the response includes `X-RateLimit-Remaining: 0`

## Technical Considerations

1. **jose library:** Lightweight, standards-compliant JWT library. Works with Bun runtime. Use `new SignJWT({...}).setProtectedHeader({ alg: 'HS256' }).setIssuedAt().setIssuer(issuer).setAudience(audience).setExpirationTime(exp).sign(secret)` for signing. Use `jwtVerify(token, secret, { issuer, audience })` for validation.

2. **Bun.password:** Built-in Argon2id support. `Bun.password.hash(pwd, "argon2id")` returns a PHC-format string. `Bun.password.verify(pwd, hash)` returns boolean. No external bcrypt/argon2 library needed.

3. **API Key format:** `esh_` prefix (4 chars) + 32 hex chars = 36 chars total. The prefix allows quick identification in logs. The key is hashed with Argon2id before storage, so lookups require scanning all keys for the tenant (or all keys globally for unknown tokens). To optimize: store `key_prefix` (first 8 chars) as plaintext for narrowing candidates before hash verification.

4. **API Key lookup optimization:** Since Argon2id is slow (~100ms per verify), we cannot hash-compare every key. Strategy: extract the prefix from the bearer token, query `SELECT * FROM api_keys WHERE key_prefix = ?`, then verify the full hash only for matching prefix rows. With the `esh_` + 8 hex prefix, collisions are extremely unlikely.

5. **Refresh token storage:** Refresh tokens are JWTs with a longer TTL and a `type: "refresh"` claim. No server-side storage needed for the initial version. Token rotation is implemented by issuing a new refresh token on each refresh call (the old one remains valid until expiry -- true revocation requires a blocklist, deferred to a future track).

6. **Auth middleware integration with router:** The middleware should run inside `createRouter()` after route matching but before handler invocation. Add an `auth` property to `RouteEntry` (e.g., `auth: "required" | "admin" | "public"`) to declaratively specify auth requirements per route.

7. **Multi-tenant-schema dependency:** That track adds `TenantContext` to `RouteContext` and `resolveTenant()`. This track replaces the stub `resolveTenant()` with JWT-based resolution and enriches the context with `tenantEmail` and `isAdmin`.

8. **Backward compatibility:** When `MULTI_TENANT=false`, the auth middleware falls through to the legacy `PLUGIN_API_TOKEN` check. All new tables (tenants, api_keys) are still created but unused.

## Out of Scope

- Tenant-level billing, usage tracking, or quotas (beyond rate limiting)
- OAuth2/OIDC provider integration (Google, GitHub login)
- Multi-factor authentication (MFA/2FA)
- Password reset via email
- Refresh token revocation/blocklist
- Per-tenant database isolation (separate SQLite files)
- Plugin-side (ClojureScript) changes for JWT auth flow
- Role-based access control beyond admin/non-admin
- Tenant invitation system or team management

## Open Questions

1. **Refresh token revocation:** Should we add a `refresh_tokens` table for server-side tracking and revocation, or is stateless JWT refresh acceptable for v1? (Recommendation: stateless for v1, add revocation table in a follow-up if needed.)
2. **API key rate limiting:** Should API keys have separate rate limits from JWT-authenticated requests, or share the same per-tenant pool? (Recommendation: share the same pool -- a tenant is a tenant regardless of auth method.)
3. **First admin creation:** Should `POST /api/tenants/register` accept an `adminSecret` field that, if matching `ADMIN_API_KEY`, creates the tenant as admin? Or should the flow be: register normal tenant, then use ADMIN_API_KEY to promote? (Recommendation: register then promote -- cleaner separation of concerns.)
