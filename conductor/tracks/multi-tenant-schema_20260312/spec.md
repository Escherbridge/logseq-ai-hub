# Specification: Multi-Tenant Schema

## Overview

Add foundational multi-tenancy data isolation to the proprietary Bun/TypeScript server. Every database table gains a `tenant_id` column (TEXT NOT NULL), every query is scoped by tenant, and a `TenantContext` type flows from the request layer into every DB function. A migration system handles adding the column to existing databases, and a `MULTI_TENANT` feature flag preserves backward compatibility for single-tenant deployments.

## Background

The server currently stores all data in a single-tenant SQLite database (`bun:sqlite`). Tables: `contacts`, `messages`, `sse_events`, `characters`, `character_sessions`, `sessions`, `session_messages`, `events` -- 8 tables total. All query functions in `server/src/db/*.ts` accept a `Database` handle and filter parameters but never a tenant identifier. Route handlers authenticate via a single `pluginApiToken` bearer token. This track adds the schema-level and query-level foundations for tenant isolation without implementing tenant provisioning, onboarding, or billing.

## Functional Requirements

### FR-1: tenant_id Column on All Tables
- **Description:** Add `tenant_id TEXT NOT NULL` to all 8 tables: `contacts`, `messages`, `sse_events`, `characters`, `character_sessions`, `sessions`, `session_messages`, `events`.
- **Acceptance Criteria:**
  - Each table has a `tenant_id` column of type `TEXT NOT NULL`.
  - For new databases, `tenant_id` is part of the `CREATE TABLE` statement.
  - `tenant_id` has no foreign key (tenants are managed externally).
- **Priority:** P0

### FR-2: Composite Indexes
- **Description:** Create composite indexes that include `tenant_id` as the leading column for all frequently-queried access patterns.
- **Acceptance Criteria:**
  - `contacts`: `(tenant_id, platform)`, unique index on `(tenant_id, id)`
  - `messages`: `(tenant_id, contact_id)`, `(tenant_id, created_at)`, `(tenant_id, platform)`
  - `sse_events`: `(tenant_id, event_type)`
  - `characters`: `(tenant_id, name)` unique, `(tenant_id)`
  - `character_sessions`: `(tenant_id, character_id)`, `(tenant_id, updated_at)`
  - `sessions`: `(tenant_id, agent_id)`, `(tenant_id, status)`
  - `session_messages`: `(tenant_id, session_id)`
  - `events`: `(tenant_id, type)`, `(tenant_id, source)`, `(tenant_id, created_at)`
  - Old single-column indexes are dropped or replaced.
- **Priority:** P0

### FR-3: Migration System
- **Description:** A migration runner that detects the current schema version and applies pending migrations. The first migration adds `tenant_id` to all tables and backfills existing rows with a default tenant value.
- **Acceptance Criteria:**
  - A `schema_version` table tracks the current migration level.
  - Migration 001 adds `tenant_id` column to all 8 tables via `ALTER TABLE`.
  - Existing rows are backfilled with `DEFAULT_TENANT_ID` ("default").
  - The column is added as nullable first, then backfilled, then `NOT NULL` constraint enforced via table rebuild (SQLite lacks `ALTER COLUMN`).
  - New composite indexes are created; old single-column indexes are dropped.
  - Migration is idempotent -- running twice has no effect.
  - `createTestDatabase()` creates schema at the latest version (no migration needed).
- **Priority:** P0

### FR-4: TenantContext Type
- **Description:** A TypeScript type that carries tenant identity through the request lifecycle.
- **Acceptance Criteria:**
  - `TenantContext` type defined with at minimum `{ tenantId: string }`.
  - Added to `RouteContext` interface so all handlers receive it.
  - A `resolveTenant()` utility extracts tenant from the request (JWT claim, header, or config default).
  - When `MULTI_TENANT=false` (default), `resolveTenant()` returns `DEFAULT_TENANT_ID` ("default").
- **Priority:** P0

### FR-5: Tenant-Scoped DB Functions
- **Description:** Every function in `server/src/db/*.ts` accepts `tenantId: string` and includes it in all queries (INSERT, SELECT, UPDATE, DELETE).
- **Acceptance Criteria:**
  - `contacts.ts`: `upsertContact`, `getContact`, `listContacts` -- all accept and filter by `tenantId`.
  - `messages.ts`: `insertMessage`, `getMessage`, `getMessages` -- all accept and filter by `tenantId`.
  - `characters.ts`: `createCharacter`, `getCharacter`, `getCharacterByName`, `listCharacters`, `updateCharacter`, `deleteCharacter` -- all accept and filter by `tenantId`.
  - `character-sessions.ts`: `createCharacterSession`, `getCharacterSession`, `listCharacterSessions`, `saveCharacterSession`, `deleteCharacterSession` -- all accept and filter by `tenantId`.
  - `sessions.ts`: `createSession`, `getSession`, `listSessions`, `updateSession`, `addSessionMessage`, `loadSessionMessages` -- all accept and filter by `tenantId`.
  - `events.ts`: `insertEvent`, `getEventById`, `queryEvents`, `pruneEvents`, `countEvents` -- all accept and filter by `tenantId`.
  - A tenant cannot read, modify, or delete another tenant's data through any DB function.
  - `INSERT` statements include `tenant_id` in the values.
  - `SELECT`, `UPDATE`, `DELETE` statements include `WHERE tenant_id = ?`.
- **Priority:** P0

### FR-6: Route Handler Tenant Extraction
- **Description:** All route handlers extract the tenant from the request context and pass it to DB functions.
- **Acceptance Criteria:**
  - The router populates `RouteContext.tenantId` using `resolveTenant()` before dispatching.
  - Handlers that call DB functions pass `ctx.tenantId` (or equivalent).
  - Handlers that interact with services (EventBus, SessionStore) propagate tenant context.
  - In single-tenant mode (`MULTI_TENANT=false`), all handlers use "default" as tenant.
- **Priority:** P0

### FR-7: Backward Compatibility
- **Description:** When `MULTI_TENANT` environment variable is unset or `"false"`, the system behaves identically to the current single-tenant behavior.
- **Acceptance Criteria:**
  - `MULTI_TENANT` defaults to `false` in `loadConfig()`.
  - When `false`, `resolveTenant()` always returns `"default"` regardless of JWT/headers.
  - Migration still runs (column added, default backfill applied).
  - All existing tests pass without modification beyond adding the default tenant parameter.
  - No API contract changes visible to existing clients when `MULTI_TENANT=false`.
- **Priority:** P0

### FR-8: Test Coverage for Tenant Isolation
- **Description:** Comprehensive tests verifying that tenant scoping prevents cross-tenant data access.
- **Acceptance Criteria:**
  - For each of the 8 tables, a test creates data under `tenant-A` and `tenant-B`, then verifies that querying under `tenant-A` never returns `tenant-B`'s data.
  - INSERT, SELECT, UPDATE, DELETE operations are tested for cross-tenant isolation per table.
  - Migration tests verify: fresh DB gets latest schema, existing DB gets migrated, double-run is idempotent.
  - Test helper `createTestDb()` updated to include `tenant_id` in seeding functions.
  - At least 40 new test cases across isolation, migration, and query scoping.
- **Priority:** P0

## Non-Functional Requirements

### NFR-1: Performance
- Composite indexes must ensure that tenant-scoped queries do not degrade performance compared to current single-tenant queries.
- Migration of a database with up to 100,000 rows per table should complete in under 30 seconds.

### NFR-2: Data Integrity
- It must be impossible to insert a row without a `tenant_id` value.
- `NOT NULL` constraint enforced at the database level.
- Foreign key relationships (e.g., `messages.contact_id -> contacts.id`) must remain valid within the same tenant.

### NFR-3: Zero Downtime for Single-Tenant
- The migration must not corrupt or lose existing data.
- After migration, existing data is accessible under the `"default"` tenant.

## User Stories

### US-1: Multi-Tenant Query Isolation
- **As** a platform operator hosting multiple tenants
- **I want** each tenant's data completely isolated at the database level
- **So that** one tenant can never see another tenant's contacts, messages, characters, sessions, or events

**Scenarios:**
- **Given** tenant-A has 3 contacts and tenant-B has 2 contacts
  **When** I query contacts for tenant-A
  **Then** I receive exactly 3 contacts, none from tenant-B

- **Given** tenant-A creates a character named "Bot"
  **When** tenant-B creates a character also named "Bot"
  **Then** both succeed (unique constraint is per-tenant, not global)

### US-2: Seamless Single-Tenant Upgrade
- **As** an existing single-tenant user upgrading the server
- **I want** the migration to run automatically and my data to remain accessible
- **So that** I experience zero disruption

**Scenarios:**
- **Given** an existing database with 50 contacts and 200 messages
  **When** the server starts with the new code
  **Then** migration adds `tenant_id = 'default'` to all rows
  **And** all queries work identically to before

### US-3: Backward-Compatible API
- **As** a client application using the REST API
- **I want** no changes required when `MULTI_TENANT=false`
- **So that** my integration continues to work after the server update

**Scenarios:**
- **Given** `MULTI_TENANT=false` (default)
  **When** I send the same API requests as before
  **Then** I receive the same responses with the same data

## Technical Considerations

- **SQLite ALTER TABLE limitations:** SQLite cannot add `NOT NULL` columns without a default, and cannot modify column constraints after creation. The migration must: (1) add column as nullable with default, (2) backfill, (3) recreate table with `NOT NULL` constraint via the 12-step SQLite table rebuild pattern, or accept a column with `DEFAULT 'default'` that is effectively `NOT NULL` via application enforcement.
- **Pragmatic approach:** Since `bun:sqlite` wraps SQLite 3.x, the simplest correct approach is `ALTER TABLE ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default'` -- SQLite 3.x allows `ADD COLUMN` with `NOT NULL` if a `DEFAULT` is provided. This avoids table rebuilds entirely.
- **Contact ID format:** Currently `"platform:platform_user_id"`. With tenancy, this needs consideration -- if two tenants have the same WhatsApp user, the current ID scheme collides. The contact `id` should incorporate `tenant_id`, e.g., `"tenant:platform:platform_user_id"`, or the primary key should become `(tenant_id, id)` composite.
- **Unique constraint migration:** `characters.name` currently has a global unique index. It must become `(tenant_id, name)`. The external_id unique index on messages should become `(tenant_id, external_id)`.
- **Services using DB:** `EventBus`, `SessionStore`, and other services that interact with the DB layer will need `tenantId` threaded through. This track covers the DB and route layers; service-layer changes are included where they touch DB functions directly.
- **sse_events table:** This is used for SSE event delivery. Tenant scoping ensures one tenant's SSE stream does not leak another's events.
- **Test helpers:** `createTestDb()`, `seedTestContact()`, `seedTestMessage()`, `seedTestSession()` all need `tenantId` parameters with a default value of `"default"` for backward compatibility.

## Out of Scope

- Tenant provisioning, registration, or management API
- JWT-based authentication (the `resolveTenant()` stub will be ready for JWT but this track uses header/config-based extraction)
- Per-tenant configuration or rate limiting
- Tenant-aware billing or usage tracking
- Multi-database (database-per-tenant) isolation strategy
- Plugin-side (ClojureScript) changes

## Open Questions

1. **Contact ID collision:** Should the contact `id` format change from `"platform:user_id"` to `"tenant:platform:user_id"`, or should isolation rely purely on the `WHERE tenant_id = ?` clause? (Recommendation: keep current format, rely on query scoping -- simpler migration.)
2. **Tenant header name:** When `MULTI_TENANT=true`, should the tenant be extracted from `X-Tenant-Id` header, JWT `sub` claim, or both? (Recommendation: start with `X-Tenant-Id` header, add JWT later.)
3. **Cross-tenant foreign keys:** Should `messages.contact_id` foreign key be extended to include `tenant_id`? SQLite composite FK syntax requires table rebuild. (Recommendation: defer composite FKs, enforce at application layer.)
