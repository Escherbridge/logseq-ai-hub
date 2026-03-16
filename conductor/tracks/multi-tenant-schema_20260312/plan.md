# Implementation Plan: Multi-Tenant Schema

## Overview

6 phases transforming the server's single-tenant SQLite schema into a fully tenant-scoped data layer. Each phase builds on the previous, with verification checkpoints. The approach: migration system first, then schema changes, then DB function updates table-by-table, then route integration, then isolation tests.

Estimated effort: 20-30 hours (48 tasks across 6 phases)

## Phase 1: Migration Infrastructure & Schema Changes

Goal: Build the migration system and update the schema to include `tenant_id` on all 8 tables with composite indexes.

Tasks:
- [ ] Task 1.1: Create `server/src/db/migrations.ts` with migration runner (TDD: test that `schema_version` table is created, `getCurrentVersion()` returns 0 for fresh DB, `applyMigrations()` runs pending migrations in order)
- [ ] Task 1.2: Implement Migration 001 -- add `tenant_id TEXT NOT NULL DEFAULT 'default'` to all 8 tables via `ALTER TABLE ADD COLUMN` (TDD: test that migration adds column to each table, existing rows get `'default'`, column is NOT NULL)
- [ ] Task 1.3: Implement Migration 001 index changes -- drop old single-column indexes, create composite `(tenant_id, ...)` indexes (TDD: test that composite indexes exist after migration, old indexes removed, query plans use new indexes)
- [ ] Task 1.4: Implement Migration 001 unique constraint changes -- replace `idx_characters_name` with `(tenant_id, name)`, replace `idx_messages_external` with `(tenant_id, external_id)` (TDD: test that two tenants can have same character name, same external_id)
- [ ] Task 1.5: Update `schema.ts` `initializeSchema()` to include `tenant_id` in all CREATE TABLE statements and composite indexes for fresh databases (TDD: test that fresh DB has `tenant_id` column and composite indexes on all tables)
- [ ] Task 1.6: Update `connection.ts` -- call `applyMigrations(db)` after `initializeSchema(db)` in `getDatabase()` and `createTestDatabase()` (TDD: test that `getDatabase()` on existing DB runs migration, `createTestDatabase()` produces latest schema)
- [ ] Task 1.7: Test migration idempotency (TDD: test that running migrations twice produces no errors, schema is identical, data unchanged)
- [ ] Verification: Run full existing test suite -- all tests should pass with schema changes since default tenant_id is applied. [checkpoint marker]

## Phase 2: TenantContext & Config

Goal: Define the TenantContext type, add `MULTI_TENANT` config flag, and implement tenant resolution middleware.

Tasks:
- [ ] Task 2.1: Add `multiTenant: boolean` to `Config` interface and `loadConfig()`, reading from `MULTI_TENANT` env var, default `false` (TDD: test config loads `false` by default, `true` when env set)
- [ ] Task 2.2: Create `server/src/types/tenant.ts` with `TenantContext` type `{ tenantId: string }` and `DEFAULT_TENANT_ID = "default"` constant (TDD: type-only, verified by import in test)
- [ ] Task 2.3: Add `tenantId: string` to `RouteContext` interface in `router.ts` (TDD: test that RouteContext includes tenantId field)
- [ ] Task 2.4: Create `server/src/middleware/tenant.ts` with `resolveTenant(req: Request, config: Config): string` -- returns `DEFAULT_TENANT_ID` when `multiTenant=false`, extracts `X-Tenant-Id` header when `multiTenant=true`, returns 400 if header missing in multi-tenant mode (TDD: test single-tenant returns default, multi-tenant extracts header, multi-tenant without header throws)
- [ ] Task 2.5: Integrate `resolveTenant()` into router's request dispatch -- populate `ctx.tenantId` before calling handler (TDD: test that handlers receive correct tenantId in context)
- [ ] Verification: All existing tests pass with tenantId="default" populated in RouteContext. [checkpoint marker]

## Phase 3: Tenant-Scoped DB Functions -- Contacts, Messages, SSE Events

Goal: Update the first three DB modules to accept and filter by `tenantId`.

Tasks:
- [ ] Task 3.1: Update `contacts.ts` -- `upsertContact(db, tenantId, ...)` inserts tenant_id, `getContact(db, tenantId, id)` filters by tenant, `listContacts(db, tenantId, platform?)` filters by tenant (TDD: test insert with tenant, get scoped by tenant, list scoped by tenant, cross-tenant isolation)
- [ ] Task 3.2: Update `messages.ts` -- `insertMessage(db, tenantId, ...)` inserts tenant_id, `getMessage(db, tenantId, id)` filters by tenant, `getMessages(db, tenantId, options)` filters by tenant including JOIN with contacts (TDD: test insert with tenant, get scoped, list scoped, join only returns same-tenant contacts)
- [ ] Task 3.3: Create `server/src/db/sse-events.ts` if not exists, or update inline usage -- scope `sse_events` INSERT and SELECT by tenant_id (TDD: test SSE event insert/query with tenant scoping)
- [ ] Task 3.4: Update test helpers in `server/tests/helpers.ts` -- add `tenantId` parameter (default `"default"`) to `seedTestContact()`, `seedTestMessage()` (TDD: verify helpers produce rows with correct tenant_id)
- [ ] Task 3.5: Update existing tests in `db.test.ts`, `api-messages.test.ts` to pass tenantId to updated functions (TDD: all existing tests pass with explicit default tenant)
- [ ] Verification: Run `bun test server/tests/db.test.ts server/tests/api-messages.test.ts` -- all pass. [checkpoint marker]

## Phase 4: Tenant-Scoped DB Functions -- Characters, Sessions, Events

Goal: Update the remaining DB modules for tenant scoping.

Tasks:
- [ ] Task 4.1: Update `characters.ts` -- all 6 functions accept `tenantId`, INSERT includes it, SELECT/UPDATE/DELETE filter by it (TDD: test CRUD with tenant, cross-tenant isolation, per-tenant unique name constraint)
- [ ] Task 4.2: Update `character-sessions.ts` -- all 5 functions accept `tenantId`, scope all queries (TDD: test CRUD with tenant, cross-tenant isolation, character-session relationship within tenant)
- [ ] Task 4.3: Update `sessions.ts` -- `createSession`, `getSession`, `listSessions`, `updateSession` accept `tenantId` (TDD: test session CRUD scoped by tenant, cross-tenant isolation)
- [ ] Task 4.4: Update `sessions.ts` message functions -- `addSessionMessage`, `loadSessionMessages` accept `tenantId`, scope queries (TDD: test message add/load scoped by tenant, session's last_active_at update scoped)
- [ ] Task 4.5: Update `events.ts` -- `insertEvent`, `getEventById`, `queryEvents`, `pruneEvents`, `countEvents` accept `tenantId` (TDD: test all event operations scoped by tenant, cross-tenant isolation)
- [ ] Task 4.6: Update existing tests -- `event-store.test.ts`, `session-store.test.ts`, `session-context.test.ts` and related tests pass tenantId (TDD: all existing tests pass with default tenant)
- [ ] Verification: Run full `bun test` -- all DB-layer tests pass. [checkpoint marker]

## Phase 5: Route Handler Integration

Goal: Thread `tenantId` from RouteContext through all handlers to DB calls.

Tasks:
- [ ] Task 5.1: Update `routes/api/messages.ts` -- `handleGetMessages` passes `ctx.tenantId` to `getMessages()` (TDD: test handler returns only tenant-scoped messages)
- [ ] Task 5.2: Update `routes/api/send.ts` -- `handleSendMessage` passes `ctx.tenantId` to `upsertContact()` and `insertMessage()` (TDD: test send creates contact/message under correct tenant)
- [ ] Task 5.3: Update `routes/api/characters.ts` -- all 5 handlers pass `ctx.tenantId` to character DB functions (TDD: test character CRUD via API uses tenant scoping)
- [ ] Task 5.4: Update `routes/api/character-chat.ts` -- pass `ctx.tenantId` to character and session DB functions (TDD: test character chat session scoped by tenant)
- [ ] Task 5.5: Update `routes/api/events.ts` -- `handlePublishEvent`, `handleQueryEvents` pass `ctx.tenantId` through EventBus to event DB functions (TDD: test event publish/query scoped by tenant)
- [ ] Task 5.6: Update `routes/api/agent-chat.ts` -- pass `ctx.tenantId` to SessionStore operations (TDD: test agent chat uses tenant-scoped sessions)
- [ ] Task 5.7: Update `routes/webhooks/whatsapp.ts`, `routes/webhooks/telegram.ts` -- pass tenant context to contact/message DB calls (TDD: test webhook creates data under correct tenant)
- [ ] Task 5.8: Update `routes/webhooks/event-hub.ts` -- pass tenant context to event operations (TDD: test event webhook scoped by tenant)
- [ ] Task 5.9: Update `routes/events.ts` (SSE) -- scope SSE event stream by tenant (TDD: test SSE returns only events for requesting tenant)
- [ ] Task 5.10: Update router handler signatures -- ensure all handlers in `router.ts` pass ctx with tenantId to their respective functions, update any handlers that destructure ctx manually (TDD: integration test that full request cycle uses tenant scoping)
- [ ] Verification: Run full `bun test` -- all route-level and integration tests pass. [checkpoint marker]

## Phase 6: Comprehensive Tenant Isolation Tests

Goal: Dedicated test suite proving cross-tenant isolation for every table and operation.

Tasks:
- [ ] Task 6.1: Create `server/tests/tenant-isolation.test.ts` -- contacts isolation: create contacts under tenant-A and tenant-B, verify query/update/delete scoping (TDD: 5+ test cases)
- [ ] Task 6.2: Messages isolation tests -- messages under different tenants, verify getMessages, getMessage cross-tenant blocked (TDD: 5+ test cases including JOIN behavior)
- [ ] Task 6.3: Characters isolation tests -- same-name characters under different tenants, verify CRUD isolation (TDD: 5+ test cases including unique constraint per-tenant)
- [ ] Task 6.4: Character sessions isolation tests -- sessions tied to characters within tenant boundary (TDD: 4+ test cases)
- [ ] Task 6.5: Sessions isolation tests -- agent sessions scoped by tenant, session messages scoped (TDD: 5+ test cases)
- [ ] Task 6.6: Events isolation tests -- event insert/query/prune/count scoped by tenant (TDD: 5+ test cases)
- [ ] Task 6.7: SSE events isolation tests -- SSE events scoped by tenant (TDD: 3+ test cases)
- [ ] Task 6.8: Migration tests -- fresh DB, migrated DB, idempotent migration, data preservation (TDD: 5+ test cases)
- [ ] Task 6.9: Backward compatibility tests -- with `MULTI_TENANT=false`, verify default tenant used, API unchanged, no tenant header required (TDD: 5+ test cases)
- [ ] Task 6.10: Integration test -- full request lifecycle: two tenants hit same endpoints, data never crosses (TDD: end-to-end test with router + DB)
- [ ] Verification: Run `bun test` -- all tests pass, at least 40 new test cases total, zero cross-tenant data leaks. [checkpoint marker]

## Summary

| Phase | Tasks | Focus |
|-------|-------|-------|
| 1     | 8     | Migration system, schema changes, indexes |
| 2     | 6     | TenantContext type, config, middleware |
| 3     | 6     | Contacts, messages, SSE events scoping |
| 4     | 7     | Characters, sessions, events scoping |
| 5     | 11    | Route handler integration |
| 6     | 11    | Isolation test suite |
| **Total** | **49** | |

Dependencies: Phase 1 -> Phase 2 -> Phase 3 and Phase 4 (parallel) -> Phase 5 -> Phase 6
