# Implementation Plan: Open Source Release Preparation

## Overview

6 phases preparing the Logseq AI Hub repository for public open-source release. Phases are ordered to establish legal/security foundations first, then documentation, then automation. Since this is a chore track (not feature work), TDD cycles apply only where testable assertions exist (CI workflow, config changes). Documentation tasks use verification against source code instead.

**Estimated total:** 10-16 hours across 28 tasks

---

## Phase 1: License and Legal Foundation
**Goal:** Establish the legal framework for open-source release.

Tasks:
- [ ] Task 1.1: Create `LICENSE` file at repo root with MIT license text, year 2026, author "Ahmed Zaher"
- [ ] Task 1.2: Verify `package.json` (root) `license` field is "MIT" (already set -- confirm)
- [ ] Task 1.3: Verify `server/package.json` has `license: "MIT"` field (add if missing)
- [ ] Verification: Confirm LICENSE file content matches standard MIT text, both package.json files reference MIT [checkpoint marker]

---

## Phase 2: Secret Sanitization and Environment Configuration
**Goal:** Ensure no private data leaks into the public repo and provide a clean environment template.

Tasks:
- [ ] Task 2.1: Audit and fix `server/src/services/llm.ts` -- replace hardcoded `HTTP-Referer` URL with a configurable value or documented placeholder
- [ ] Task 2.2: Audit `src/main/logseq_ai_hub/core.cljs` settings descriptions -- ensure example URLs are generic (e.g., `https://your-server.example.com`)
- [ ] Task 2.3: Run full repo grep for patterns: API keys, hardcoded tokens, personal URLs, Railway-specific URLs, `Escherbridge` references in non-documentation files. Fix any findings. (Grep patterns: `/[a-zA-Z0-9]{32,}/` in source, `railway.app`, specific domain names)
- [ ] Task 2.4: Verify `.gitignore` covers all sensitive/generated paths: `.env`, `server/.env`, `server/data/`, `.shadow-cljs/`, `out/`, `main.js`, `cljs-runtime/`, `.claude/`
- [ ] Task 2.5: Create `server/.env.example` with all variables from `server/src/config.ts` -- each with descriptive comment, placeholder values, required/optional markers
- [ ] Task 2.6: (TDD) Run existing test suites to confirm sanitization changes cause no regressions: `npm test` (CLJS) and `cd server && bun test` (server)
- [ ] Verification: `grep -r` for secrets patterns returns zero non-test hits; `.env.example` lists all 14 config variables; all tests pass [checkpoint marker]

---

## Phase 3: Package Metadata and Build Configuration
**Goal:** Make package files ready for public consumption and marketplace submission.

Tasks:
- [ ] Task 3.1: Update root `package.json` -- set `private: false`, add `repository`, `description`, `keywords`, `homepage`, and update `logseq.id` from `_byud67luv` to `logseq-ai-hub`
- [ ] Task 3.2: Update `server/package.json` -- set `private: false`, add `description`, `repository` fields
- [ ] Task 3.3: Update `manifest.edn` -- ensure the plugin metadata vector has correct `id`, descriptive title, description, and author. Verify the `:module-id`, `:name`, and `:output-name` are production-ready
- [ ] Task 3.4: Review `shadow-cljs.edn` -- remove any dev-only configuration, verify source paths and build targets are clean for contributors
- [ ] Verification: `npm pack --dry-run` succeeds; `manifest.edn` parses correctly; shadow-cljs.edn has no machine-specific paths [checkpoint marker]

---

## Phase 4: Core Documentation (README and CONTRIBUTING)
**Goal:** Create the two primary documentation files that define the public face of the project.

Tasks:
- [ ] Task 4.1: Create `README.md` structure -- title, badges (CI, license), tagline, table of contents
- [ ] Task 4.2: Write README architecture section -- text diagram of Plugin (ClojureScript) <-> Agent Bridge (SSE/HTTP) <-> Server (Bun/TypeScript) <-> MCP Clients, brief explanation of each component
- [ ] Task 4.3: Write README features section -- enumerate all major systems: Job Runner (autonomous skill execution), MCP Server (80+ tools, 10 resources, 7 prompts), Agent Bridge (52 operations), AI Memory, Secrets Vault, Event Hub, Human-in-the-Loop, KB Tool Registry, Dynamic Arg Parser, Code Repo Integration, Agent Sessions
- [ ] Task 4.4: Write README prerequisites and Quick Start sections -- step-by-step for plugin dev (`yarn install`, `npx shadow-cljs watch app`, load in Logseq) and server (`cd server`, `bun install`, `cp .env.example .env`, configure, `bun run dev`)
- [ ] Task 4.5: Write README configuration section -- table of all env vars with type, default, description (sourced from config.ts); plugin settings reference
- [ ] Task 4.6: Write README MCP integration section -- how to point Claude Desktop (or other MCP client) at `POST /mcp`, auth token setup, available capabilities summary
- [ ] Task 4.7: Write README slash commands section -- table of all registered commands with description
- [ ] Task 4.8: Create `CONTRIBUTING.md` -- dev setup, TDD workflow, branch/commit conventions, how to add bridge operations, how to add MCP tools, test execution guide, Conductor track system overview
- [ ] Verification: All README sections reference actual file paths that exist in repo; Quick Start steps are internally consistent; link to CONTRIBUTING.md works [checkpoint marker]

---

## Phase 5: API and Operations Reference Documentation
**Goal:** Provide complete reference documentation for all bridge operations and MCP capabilities.

Tasks:
- [ ] Task 5.1: Create `docs/` directory and `docs/bridge-operations.md` -- enumerate all 52 operations from `agent_bridge.cljs` `operation-handlers` map with name, description, parameter spec, return shape
- [ ] Task 5.2: Create `docs/mcp-tools.md` -- enumerate all MCP tools from `server/src/services/mcp/*.ts` files grouped by category: Graph (7), Job (10), Memory (4), Messaging (3), Approval (1), Registry (4), Session (7), Character (8), Project (2), ADR (2), Lesson (2), Safeguard (5), Work (4), Task (7), Pi.dev (9), Event (7)
- [ ] Task 5.3: Add MCP resources and prompts sections to `docs/mcp-tools.md` -- 10 resources with URI templates, 7 prompts with parameter descriptions
- [ ] Task 5.4: Cross-verify documentation completeness -- run `grep "server.tool("` count against docs entries; run operation-handlers map count against bridge docs entries; flag any mismatches
- [ ] Verification: Tool count in docs matches code; all bridge operations documented; no orphaned entries [checkpoint marker]

---

## Phase 6: CI/CD Pipeline
**Goal:** Automate test execution on every push and PR.

Tasks:
- [ ] Task 6.1: Create `.github/workflows/ci.yml` -- define workflow trigger on push to main and all PRs
- [ ] Task 6.2: Add CLJS test job -- install Java (temurin 21), Node.js 20, `yarn install`, cache `.shadow-cljs` and `node_modules`, `npx shadow-cljs compile node-test`, `node out/test/node-tests.js`
- [ ] Task 6.3: Add server test job -- install Bun, `cd server && bun install`, `bun test`
- [ ] Task 6.4: Add CI status badge to README.md header
- [ ] Task 6.5: (TDD) Validate CI workflow syntax with `actionlint` or manual review of YAML structure; ensure job names, step ordering, and cache keys are correct
- [ ] Verification: Push to a branch triggers CI; both CLJS and server test jobs appear in GitHub Actions; badge renders in README [checkpoint marker]

---

## Summary

| Phase | Tasks | Focus | Est. Hours |
|-------|-------|-------|------------|
| 1 | 4 | License/Legal | 0.5-1 |
| 2 | 7 | Secrets/Env | 2-3 |
| 3 | 5 | Package/Build metadata | 1-2 |
| 4 | 9 | README + CONTRIBUTING | 4-6 |
| 5 | 5 | API reference docs | 2-3 |
| 6 | 6 | CI pipeline | 1-2 |
| **Total** | **36** | | **10-16** |
