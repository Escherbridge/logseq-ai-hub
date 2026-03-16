# Specification: Proprietary Server Bootstrap

## Overview

Create a private repository for the proprietary multi-tenant version of the Logseq AI Hub server. This is a **copy** of the current `server/` directory from the LogseqPlugin repo that will diverge with multi-tenant features (JWT auth, per-tenant isolation, usage tracking). The open-source plugin must continue to work with **both** the single-tenant community server and the proprietary multi-tenant server.

## Background

The current server (`server/` in LogseqPlugin) is a Bun/TypeScript application with:
- 75+ source files across routes, services, middleware, DB, and MCP layers
- 741 tests across 46 test files
- Single bearer token auth (`PLUGIN_API_TOKEN`)
- SQLite database via `bun:sqlite`
- MCP server with 29+ tools, resources, and prompts
- SSE-based Agent Bridge for plugin communication
- Webhook ingestion (WhatsApp, Telegram, Event Hub)
- Agent chat, sessions, approvals, dynamic registry, safeguards, event bus

The proprietary version will add multi-tenancy on top of this foundation. Both servers must expose the same API surface so the Logseq plugin can connect to either one without code changes (beyond switching the server URL and auth credentials).

## Functional Requirements

### FR-1: Private Repository Creation
**Description:** Create a new private GitHub repository under the Escherbridge organization (or user account) to host the proprietary server code.
**Acceptance Criteria:**
- Private repo exists on GitHub (e.g., `escherbridge-server` or `logseq-ai-hub-cloud`)
- Repository has appropriate `.gitignore` (node_modules, data/, .env, *.sqlite)
- Repository has a LICENSE file (proprietary/all-rights-reserved)
- Initial commit contains the full server codebase
**Priority:** P0

### FR-2: Server Code Copy
**Description:** Copy the entire `server/` directory contents as the foundation of the new repository's root.
**Acceptance Criteria:**
- All source files from `server/src/` are present and unchanged
- All test files from `server/tests/` are present and unchanged
- `package.json` and `tsconfig.json` are present
- `bun test` passes with 741 tests in the new repo (zero regressions)
- The server starts successfully with `bun run src/index.ts`
**Priority:** P0

### FR-3: Independent Package Configuration
**Description:** Set up the new repo as a standalone project with its own package.json, tsconfig, and lockfile.
**Acceptance Criteria:**
- `package.json` has updated `name` field (e.g., `escherbridge-server`)
- `package.json` has all necessary dependencies listed (including `bun-types`)
- `bun install` succeeds from a clean checkout
- `tsconfig.json` compiles without errors
- No references or imports from the parent LogseqPlugin repo
**Priority:** P0

### FR-4: Docker Deployment Configuration
**Description:** Add Dockerfile and docker-compose.yml for containerized deployment.
**Acceptance Criteria:**
- `Dockerfile` builds a production image using official `oven/bun` base
- Container runs the server on configurable port (default 3000)
- SQLite data directory is mounted as a volume for persistence
- `docker-compose.yml` defines the service with volume mounts, port mapping, and environment variables
- `docker build` succeeds and `docker run` starts the server
- Health check endpoint (`GET /health`) responds 200 inside the container
**Priority:** P0

### FR-5: GitHub Actions CI
**Description:** Set up continuous integration for the private repository.
**Acceptance Criteria:**
- `.github/workflows/ci.yml` triggers on push to `main` and on pull requests
- CI installs Bun, runs `bun install`, runs `bun test`
- CI builds the Docker image (without pushing) to verify Dockerfile validity
- Test results are reported in the workflow summary
- CI completes in under 5 minutes for the current test suite
**Priority:** P1

### FR-6: Multi-Tenant Environment Configuration
**Description:** Add environment variables and configuration scaffolding for multi-tenant mode, without implementing the actual multi-tenant logic yet.
**Acceptance Criteria:**
- New config fields: `MULTI_TENANT` (boolean, default false), `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, `TENANT_DB_PATH_TEMPLATE` (e.g., `./data/tenants/{tenant_id}.sqlite`)
- When `MULTI_TENANT=false`, server behaves identically to the open-source version (single bearer token auth)
- When `MULTI_TENANT=true` and JWT_SECRET is not set, server logs an error and exits
- Config validation updated to enforce multi-tenant required fields
- Existing tests continue to pass (they run in single-tenant mode)
**Priority:** P1

### FR-7: Bridge API Contract Documentation
**Description:** Document the API contract between the open-source plugin and the server so both repos can evolve without breaking compatibility.
**Acceptance Criteria:**
- `docs/api-contract.md` documents every REST endpoint (method, path, request/response shape, auth requirement)
- `docs/api-contract.md` documents the SSE event types and payloads
- `docs/api-contract.md` documents the MCP transport endpoint and auth
- `docs/api-contract.md` documents the webhook endpoints
- Version field added to `/health` response (e.g., `apiVersion: "1.0"`)
- Both servers (OSS and proprietary) will use the same API version
**Priority:** P1

### FR-8: Shared Types Package (Optional/Future-Proofing)
**Description:** Extract shared TypeScript types into a structure that could become a shared package, or at minimum document the types that must stay synchronized.
**Acceptance Criteria:**
- `src/types/shared/` directory contains types used by both servers and potentially the plugin
- Types include: API request/response shapes, SSE event types, MCP tool schemas, bridge operation types
- `docs/shared-types.md` lists every shared type and its purpose
- Types are exported from a barrel file (`src/types/shared/index.ts`)
**Priority:** P2

### FR-9: Repository README
**Description:** Add a README documenting the relationship between this repo and the open-source LogseqPlugin repo.
**Acceptance Criteria:**
- README explains the purpose (proprietary multi-tenant server for Logseq AI Hub)
- README documents the relationship to the open-source repo (forked from `server/`)
- README includes setup instructions (prerequisites, install, run, test)
- README includes Docker deployment instructions
- README includes environment variable reference table
- README documents the API compatibility contract
**Priority:** P1

## Non-Functional Requirements

### NFR-1: Zero Regression
All 741 existing tests must pass in the new repository without modification (beyond import path adjustments if any).

### NFR-2: Build Reproducibility
`bun install` from a clean clone must produce a working build. Lock file (`bun.lockb`) must be committed.

### NFR-3: Container Size
Docker image should be under 200MB (Bun base + application code + SQLite).

### NFR-4: CI Speed
GitHub Actions CI pipeline should complete in under 5 minutes.

### NFR-5: Configuration Isolation
Multi-tenant config must not affect single-tenant behavior. Default config must be identical to the open-source server.

## User Stories

### US-1: Developer sets up the proprietary server locally
**As** a developer on the Escherbridge team,
**I want** to clone the private repo and run the server locally,
**So that** I can develop multi-tenant features on top of the existing codebase.

**Given** a fresh clone of the private repo
**When** I run `bun install && bun test`
**Then** all 741 tests pass
**And** I can start the server with `bun run dev`

### US-2: DevOps deploys the server via Docker
**As** a DevOps engineer,
**I want** to build and deploy the server using Docker,
**So that** the proprietary server runs in a containerized production environment.

**Given** the Dockerfile in the repo root
**When** I run `docker build -t escherbridge-server .`
**Then** the image builds successfully under 200MB
**And** `docker run -p 3000:3000 -e PLUGIN_API_TOKEN=xxx escherbridge-server` starts the server
**And** `GET /health` returns 200

### US-3: Plugin connects to either server
**As** a Logseq AI Hub user,
**I want** my plugin to work with both the community server and the proprietary server,
**So that** I can choose my deployment without changing plugin code.

**Given** the plugin is configured with a server URL
**When** the server URL points to the proprietary server
**Then** all existing plugin features work identically
**And** the only difference is the auth mechanism (bearer token vs JWT in future)

### US-4: CI catches regressions
**As** a developer,
**I want** CI to run all tests on every push,
**So that** multi-tenant changes never break the existing API contract.

**Given** a push to any branch
**When** GitHub Actions runs
**Then** all tests execute
**And** Docker build is verified
**And** failures block merging

## Technical Considerations

1. **Bun Runtime**: The server uses `bun:sqlite` built-in. Docker image must use `oven/bun` base image.
2. **SQLite**: File-based database. Docker deployment needs volume mount for persistence. Multi-tenant mode will eventually need per-tenant databases or schema-based isolation.
3. **MCP SDK**: `@modelcontextprotocol/sdk` is the only npm dependency currently. More will be added for JWT (e.g., `jose`).
4. **Auth Divergence Point**: Current auth is `Bearer ${PLUGIN_API_TOKEN}`. Multi-tenant will add JWT validation. The auth middleware (`src/middleware/auth.ts`) is the primary divergence point.
5. **SSE Bridge**: The Agent Bridge uses SSE for server-to-plugin communication. In multi-tenant mode, SSE connections will need tenant affinity.
6. **API Versioning**: Adding `apiVersion` to `/health` response enables future contract negotiation.

## Out of Scope

- **Actual multi-tenant implementation** (JWT validation, tenant isolation, per-tenant databases) -- that is a separate track
- **Payment/billing integration** -- future track
- **User management UI** -- future track
- **Database migration from SQLite to PostgreSQL** -- future track (may be needed for production multi-tenant)
- **Kubernetes manifests** -- Docker only for now
- **Private npm registry for shared types** -- document types inline; extract to package later if needed
- **Automated sync mechanism between OSS and proprietary repos** -- manual cherry-pick for now

## Open Questions

1. **Repository name**: `escherbridge-server` vs `logseq-ai-hub-cloud` vs something else?
2. **GitHub organization**: Personal account (`atooz`) or create an `escherbridge` org?
3. **Shared types strategy**: Start with documented types (copy-sync), or immediately set up a shared npm package / git submodule?
4. **SQLite in production**: Is SQLite sufficient for initial multi-tenant deployment, or should PostgreSQL be on the roadmap from day one?
5. **Domain/hosting**: What domain and hosting provider for the proprietary server? (Railway, Fly.io, AWS, etc.)
