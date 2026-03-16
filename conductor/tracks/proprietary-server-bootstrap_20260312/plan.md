# Implementation Plan: Proprietary Server Bootstrap

## Overview

6 phases taking the current `server/` directory from the LogseqPlugin repo and establishing it as a standalone private repository with Docker deployment, CI, multi-tenant config scaffolding, and API contract documentation. Estimated 8-14 hours total.

**Approach:** This track is primarily infrastructure/DevOps work rather than feature code. TDD applies where new code is written (config validation, API versioning). Phases are ordered so each builds on the previous -- repository first, then configuration, then deployment, then CI, then documentation.

---

## Phase 1: Repository Creation and Code Copy
**Goal:** Create the private GitHub repo and copy the server codebase as a standalone project.
**Estimated:** 1-2 hours

Tasks:
- [ ] Task 1.1: Create private GitHub repository (e.g., `escherbridge-server`) with proprietary LICENSE and appropriate `.gitignore` (node_modules/, data/, .env, *.sqlite, *.sqlite-journal, bun.lockb should NOT be ignored)
- [ ] Task 1.2: Copy all `server/src/` files to `src/` in the new repo root (preserve directory structure exactly)
- [ ] Task 1.3: Copy all `server/tests/` files to `tests/` in the new repo root (preserve directory structure exactly)
- [ ] Task 1.4: Copy `server/package.json` and `server/tsconfig.json` to repo root. Update `package.json` name to `escherbridge-server` (or chosen name), verify `bun-types` is in devDependencies (add if missing from types reference)
- [ ] Task 1.5: Run `bun install` to generate lockfile, then run `bun test` to verify all 741 tests pass
- [ ] Task 1.6: Initial commit with message `chore: bootstrap from logseq-ai-hub server/`
- [ ] Verification: Clone the repo to a fresh directory, run `bun install && bun test`. All 741 tests must pass. Start server with `PLUGIN_API_TOKEN=test bun run src/index.ts` and confirm `GET /health` returns 200. [checkpoint marker]

---

## Phase 2: Docker Deployment Configuration
**Goal:** Add Dockerfile and docker-compose.yml for containerized deployment.
**Estimated:** 1-2 hours

Tasks:
- [ ] Task 2.1: Create `Dockerfile` using `oven/bun:1` as base image. Multi-stage build: install dependencies in build stage, copy to slim runtime stage. Expose port 3000. CMD `["bun", "run", "src/index.ts"]`
- [ ] Task 2.2: Create `.dockerignore` to exclude node_modules, .git, data/, tests/, .env, *.md (keep src/ and package.json/tsconfig.json/bun.lockb)
- [ ] Task 2.3: Create `docker-compose.yml` with service definition: build context, port mapping (3000:3000), volume mount for `./data:/app/data`, environment variables from `.env` file, health check using `curl` or `wget` against `/health`, restart policy
- [ ] Task 2.4: Create `.env.example` documenting all environment variables with comments and safe defaults
- [ ] Task 2.5: Build and test: `docker build -t escherbridge-server .` must succeed. `docker run` with `PLUGIN_API_TOKEN=test` must respond to `/health`
- [ ] Verification: `docker-compose up --build` starts the server. `curl http://localhost:3000/health` returns 200 with JSON body. `docker images escherbridge-server --format "{{.Size}}"` is under 200MB. [checkpoint marker]

---

## Phase 3: GitHub Actions CI
**Goal:** Set up continuous integration that runs tests and validates the Docker build on every push and PR.
**Estimated:** 1-2 hours

Tasks:
- [ ] Task 3.1: Create `.github/workflows/ci.yml` with triggers on push to `main` and all pull requests
- [ ] Task 3.2: Add test job: checkout, install Bun (`oven-sh/setup-bun@v2`), `bun install`, `bun test`. Use `ubuntu-latest` runner
- [ ] Task 3.3: Add Docker build verification job: checkout, `docker build -t escherbridge-server .` (build only, no push). This job can run in parallel with the test job
- [ ] Task 3.4: Add branch protection rule documentation (require CI to pass before merge) in README or contributing guide
- [ ] Verification: Push to a branch, confirm both CI jobs (test + docker build) pass in GitHub Actions. Verify total CI time is under 5 minutes. [checkpoint marker]

---

## Phase 4: Multi-Tenant Configuration Scaffolding
**Goal:** Add environment variables and config validation for multi-tenant mode without implementing actual multi-tenant logic. The server must behave identically to the OSS version when `MULTI_TENANT` is not set.
**Estimated:** 2-3 hours

Tasks:
- [ ] Task 4.1: (TDD) Write tests for new config fields: `multiTenant` (boolean, default false), `jwtSecret`, `jwtIssuer`, `jwtAudience`, `tenantDbPathTemplate`. Test that `loadConfig()` parses them from env vars. Test defaults are correct
- [ ] Task 4.2: Implement new config fields in `src/config.ts`. Add `MULTI_TENANT`, `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, `TENANT_DB_PATH_TEMPLATE` parsing to `loadConfig()`
- [ ] Task 4.3: (TDD) Write tests for multi-tenant config validation: when `MULTI_TENANT=true`, `JWT_SECRET` is required (validation error if missing). When `MULTI_TENANT=false`, JWT fields are ignored. Existing `pluginApiToken` validation unchanged
- [ ] Task 4.4: Implement multi-tenant validation in `validateConfig()`. Add conditional checks that only fire when `multiTenant` is true
- [ ] Task 4.5: (TDD) Write test for `src/index.ts` behavior: when multi-tenant config validation fails, process should log errors (verify config validation returns errors). Do NOT test process.exit directly -- test the validation function
- [ ] Task 4.6: Update `src/index.ts` to call multi-tenant validation and exit on errors (same pattern as existing `validateConfig`)
- [ ] Task 4.7: Run full test suite -- all existing 741 tests must still pass (they run without `MULTI_TENANT` env var, so single-tenant mode)
- [ ] Verification: Set `MULTI_TENANT=true` without `JWT_SECRET` and confirm server exits with clear error message. Set `MULTI_TENANT=false` and confirm server starts normally. Run `bun test` and verify all tests pass. [checkpoint marker]

---

## Phase 5: API Contract Documentation and Versioning
**Goal:** Document the API contract between plugin and server, and add API versioning to the health endpoint.
**Estimated:** 2-3 hours

Tasks:
- [ ] Task 5.1: (TDD) Write test for `/health` endpoint returning `apiVersion: "1.0"` in its response body
- [ ] Task 5.2: Implement `apiVersion` field in the health endpoint response. Add `API_VERSION` constant (e.g., in `src/config.ts` or a new `src/version.ts`)
- [ ] Task 5.3: Create `docs/api-contract.md` -- document all REST endpoints. For each: method, path, auth requirement, request body schema, response body schema, status codes. Cover: `/health`, `/events` (SSE), `/api/send`, `/api/messages`, `/api/agent/*`, `/api/jobs/*`, `/api/skills/*`, `/api/mcp/*`, `/api/secrets/*`, `/api/approvals/*`, `/api/events/*`, `/api/characters/*`
- [ ] Task 5.4: Document SSE event types and payloads in `docs/api-contract.md` -- connection protocol, event names, JSON payload shapes
- [ ] Task 5.5: Document MCP transport in `docs/api-contract.md` -- `POST /mcp`, `GET /mcp`, `DELETE /mcp`, `GET /mcp/config`, auth requirements, session management
- [ ] Task 5.6: Document webhook endpoints in `docs/api-contract.md` -- `/webhook/whatsapp`, `/webhook/telegram`, `/webhook/event/:source`, verification flows, payload formats
- [ ] Task 5.7: Create `docs/shared-types.md` listing all TypeScript types/interfaces that must stay synchronized between OSS and proprietary servers. Reference the source files where each is defined
- [ ] Verification: Review `docs/api-contract.md` covers every route in `src/router.ts` (42 route entries). Run `bun test` to confirm apiVersion tests pass. [checkpoint marker]

---

## Phase 6: Repository README and Final Polish
**Goal:** Add comprehensive README, finalize all configuration, and ensure the repository is ready for development.
**Estimated:** 1-2 hours

Tasks:
- [ ] Task 6.1: Create `README.md` with sections: Overview, Relationship to Open-Source Repo, Prerequisites (Bun, Docker), Quick Start (local dev), Environment Variables (full table), Testing, Docker Deployment, API Compatibility, Contributing
- [ ] Task 6.2: Add `CONTRIBUTING.md` with branch strategy, commit message format (from workflow.md), PR process, CI requirements
- [ ] Task 6.3: Update `.env.example` to include all new multi-tenant env vars with documentation comments
- [ ] Task 6.4: Final test run: `bun test` (all pass), `docker build` (succeeds), review all docs for accuracy
- [ ] Task 6.5: Tag initial release: `git tag v0.1.0` with message "Initial bootstrap from logseq-ai-hub server/"
- [ ] Verification: Fresh clone to new directory. Follow README instructions exactly: `bun install`, `bun test` (all pass), `docker-compose up --build` (server starts), `curl /health` (200 with apiVersion). README is accurate and complete. [checkpoint marker]

---

## Summary

| Phase | Tasks | Estimated | Key Deliverable |
|-------|-------|-----------|-----------------|
| 1. Repo + Code Copy | 7 | 1-2h | Working standalone repo with passing tests |
| 2. Docker | 5 | 1-2h | Dockerfile + docker-compose |
| 3. CI | 4 | 1-2h | GitHub Actions pipeline |
| 4. Multi-Tenant Config | 7 | 2-3h | Config scaffolding, validation |
| 5. API Contract | 7 | 2-3h | docs/api-contract.md, apiVersion |
| 6. README + Polish | 5 | 1-2h | Complete documentation, v0.1.0 tag |
| **Total** | **35** | **8-14h** | **Production-ready private repo** |

## Dependencies

- GitHub account/org with permissions to create private repos
- Docker installed locally for Phase 2 verification
- Bun installed locally (already present for current development)
- No dependencies on other Conductor tracks -- this track is independent

## Risks

1. **Test environment differences**: Some tests may rely on relative paths or assumptions about the parent repo structure. Mitigation: Phase 1 verification catches these immediately.
2. **Bun version drift**: The OSS and proprietary repos may drift to different Bun versions over time. Mitigation: Pin Bun version in CI and document in README.
3. **API divergence**: As multi-tenant features are added, the API may diverge from OSS. Mitigation: API contract document (Phase 5) serves as the compatibility baseline. `apiVersion` field enables future negotiation.
4. **SQLite in Docker**: SQLite with volume mounts can have locking issues with certain file systems. Mitigation: Document recommended volume configurations, test with both bind mounts and named volumes.
