# Specification: Open Source Release Preparation

## Overview

Prepare the Logseq AI Hub repository for public open-source release under the MIT license. The server at `server/` remains in-repo as the single-tenant open-source edition. This track covers licensing, documentation, secret sanitization, CI/CD, and marketplace readiness.

## Background

Logseq AI Hub has matured through 12 completed conductor tracks, delivering:
- A ClojureScript Logseq plugin with 62 CLJS test namespaces
- A Bun/TypeScript server with 741 tests
- 52 Agent Bridge operations connecting plugin to server via SSE + HTTP callbacks
- ~80 static MCP tools, 10 resources, 7 prompt templates
- Systems: Job Runner, MCP Server/Client, Secrets Manager, Agent Sessions, KB Tool Registry, Code Repo Integration, Event Hub, Human-in-the-Loop approvals, Dynamic Argument Parser, and more

The codebase is functionally complete for a v1 open-source release but lacks public-facing documentation, license files, CI automation, and has not been audited for accidental secret leaks or hardcoded paths.

## Functional Requirements

### FR-1: MIT License File
**Description:** Add a root `LICENSE` file with the full MIT license text.
**Acceptance Criteria:**
- [ ] `LICENSE` file exists at repository root
- [ ] Contains year 2026 and author name "Ahmed Zaher"
- [ ] Standard MIT license text, no modifications
**Priority:** P0

### FR-2: Comprehensive README.md
**Description:** Create a thorough README that serves as the primary entry point for new users and contributors.
**Acceptance Criteria:**
- [ ] Includes project name, tagline, and badge placeholders (CI status, license, version)
- [ ] Architecture overview section with a text-based diagram showing Plugin <-> Server <-> MCP flow
- [ ] Features section listing all major capabilities (Job Runner, MCP, Memory, Events, etc.)
- [ ] Prerequisites section (Node.js, Bun, Java/Clojure for shadow-cljs)
- [ ] Quick Start section with step-by-step setup for both plugin and server
- [ ] Configuration section explaining all environment variables (referencing .env.example)
- [ ] Plugin Settings section listing all Logseq plugin settings
- [ ] MCP integration section explaining how to connect Claude Desktop or other MCP clients
- [ ] Slash commands reference table
- [ ] Link to CONTRIBUTING.md for development workflow
- [ ] License section referencing MIT
**Priority:** P0

### FR-3: Source Code Secret Sanitization
**Description:** Audit all source files for hardcoded secrets, personal URLs, API keys, and private endpoints. Replace with environment variable references or remove.
**Acceptance Criteria:**
- [ ] No hardcoded API keys, tokens, or passwords in any source file
- [ ] No personal URLs (e.g., Railway deployment URLs) in source code
- [ ] Test files use only placeholder/test tokens (e.g., `"test-token"`, `"test-key"`)
- [ ] The `HTTP-Referer` header in `server/src/services/llm.ts` uses a generic repo URL placeholder or env var
- [ ] `core.cljs` settings description uses generic example URL, not a specific deployment
- [ ] `.gitignore` includes `.env`, `server/.env`, and any local config files
**Priority:** P0

### FR-4: Server .env.example
**Description:** Create a documented `.env.example` file for the server showing all required and optional environment variables.
**Acceptance Criteria:**
- [ ] File at `server/.env.example`
- [ ] Every variable from `server/src/config.ts` is listed with a descriptive comment
- [ ] Required variables clearly marked (PLUGIN_API_TOKEN)
- [ ] Optional variables show sensible defaults
- [ ] LLM variables reference OpenRouter as the default provider
- [ ] No actual secret values -- only placeholder examples
**Priority:** P0

### FR-5: Bridge Operations and MCP Tools Documentation
**Description:** Document all 52 Agent Bridge operations and MCP tools/resources/prompts so users and MCP client developers can discover available capabilities.
**Acceptance Criteria:**
- [ ] `docs/bridge-operations.md` listing all 52 bridge operations with name, description, parameters, and return shape
- [ ] `docs/mcp-tools.md` listing all MCP tools grouped by category (Graph, Job, Memory, Messaging, Approval, Registry, Session, Project, ADR, Lesson, Safeguard, Work, Task, Pi.dev, Character, Event)
- [ ] MCP resources table with URI templates and descriptions
- [ ] MCP prompt templates table with names and parameter descriptions
- [ ] Each entry has at minimum: name, one-line description, parameters
**Priority:** P1

### FR-6: CONTRIBUTING.md
**Description:** Document the development workflow for contributors.
**Acceptance Criteria:**
- [ ] Explains how to set up the development environment (shadow-cljs, Bun, nREPL)
- [ ] Documents the TDD workflow (Red-Green-Refactor)
- [ ] Explains the branch strategy (`track/<track-id>`)
- [ ] Documents the commit message format (`type(scope): description`)
- [ ] Explains how to run tests (CLJS and server) and expected pass counts
- [ ] Describes the Conductor track system for planning work
- [ ] Lists code style expectations for both ClojureScript and TypeScript
- [ ] Includes a section on the Agent Bridge pattern and how to add new operations
**Priority:** P1

### FR-7: Package Metadata Cleanup
**Description:** Update `package.json` (root and server) and `manifest.edn` with correct open-source metadata.
**Acceptance Criteria:**
- [ ] Root `package.json`: `private` field removed or set to `false`, `repository` field added with GitHub URL, `description` field added, `keywords` array added, `homepage` field added
- [ ] Server `package.json`: `private` field removed or set to `false`, `description` field added
- [ ] `manifest.edn` is valid for Logseq Marketplace submission: correct `id`, `title`, `description`, `author`, `repo` fields
- [ ] Root `package.json` `logseq.id` uses a meaningful plugin ID (not the random `_byud67luv`)
**Priority:** P1

### FR-8: Build Configuration Cleanup
**Description:** Ensure `shadow-cljs.edn` and build scripts are clean for contributors.
**Acceptance Criteria:**
- [ ] `shadow-cljs.edn` has no dev-only paths or machine-specific configuration
- [ ] `package.json` scripts section is documented (either inline comments or in README)
- [ ] Build output paths are consistent and documented
- [ ] `.gitignore` covers all build artifacts (`main.js`, `cljs-runtime/`, `out/`, `server/data/`)
**Priority:** P1

### FR-9: GitHub Actions CI Workflow
**Description:** Add CI pipeline that runs both CLJS and server tests on push and PR.
**Acceptance Criteria:**
- [ ] `.github/workflows/ci.yml` exists
- [ ] Runs on push to `main` and on pull requests
- [ ] Matrix or sequential jobs for: CLJS tests (shadow-cljs compile + node run) and Server tests (bun test)
- [ ] Uses appropriate runtimes: Node.js (for shadow-cljs), Java (for ClojureScript compilation), Bun (for server)
- [ ] Caches `node_modules` and `.shadow-cljs` for speed
- [ ] Reports test results clearly in CI output
- [ ] Badge in README links to this workflow
**Priority:** P1

## Non-Functional Requirements

### NFR-1: No Broken Existing Tests
All existing tests must continue to pass after changes. No regressions from documentation or config changes.

### NFR-2: Minimal Source Code Changes
This track should avoid functional changes to the codebase. Only sanitization fixes (removing hardcoded values) and metadata updates are permitted in source files. All other deliverables are new documentation or configuration files.

### NFR-3: Documentation Accuracy
All documented operations, tools, and configuration must be verified against actual source code. No invented or outdated entries.

## User Stories

### US-1: New User Setup
**As** a developer discovering the project on GitHub,
**I want** to follow the README to get the plugin and server running locally,
**So that** I can evaluate the project's capabilities.

**Acceptance:**
- Given: A fresh clone of the repo
- When: Following README Quick Start instructions
- Then: Plugin builds successfully and server starts without errors

### US-2: MCP Client Integration
**As** a developer wanting to connect an MCP client (e.g., Claude Desktop),
**I want** clear documentation of available MCP tools, resources, and prompts,
**So that** I can configure my client and use the tools effectively.

**Acceptance:**
- Given: A running server instance
- When: Reading the MCP tools documentation
- Then: Every tool listed in docs matches what the server actually exposes

### US-3: Contributor Onboarding
**As** a potential contributor,
**I want** to understand the architecture, testing approach, and contribution workflow,
**So that** I can submit a meaningful pull request.

**Acceptance:**
- Given: Reading CONTRIBUTING.md
- When: Setting up dev environment and running tests
- Then: All instructions work and tests pass as described

### US-4: CI Confidence
**As** a maintainer,
**I want** CI to automatically run all tests on every PR,
**So that** regressions are caught before merge.

**Acceptance:**
- Given: A PR is opened
- When: CI workflow triggers
- Then: Both CLJS and server tests run and report results

## Technical Considerations

- The `shadow-cljs.edn` browser target outputs `main.js` to the repo root, which is how Logseq plugins work. This is intentional and must not change.
- The server uses Bun runtime, not Node.js. CI must install Bun separately.
- ClojureScript compilation requires Java. CI must install a JDK.
- The `manifest.edn` format is specific to Logseq Marketplace and is a ClojureScript EDN file, not JSON. The relevant fields are `:id`, `:title`, `:description`, and metadata in the vector structure.
- Bridge operations are defined in `src/main/logseq_ai_hub/agent_bridge.cljs` in the `operation-handlers` map. MCP tools are registered across `server/src/services/mcp/*.ts` files.
- The GitHub repo URL should use the `escherbridge` organization (matching the existing `HTTP-Referer` in `llm.ts`).

## Out of Scope

- Multi-tenant server architecture (staying single-tenant for open source)
- Docker/container deployment configuration
- Automated release publishing to npm or Logseq Marketplace
- API documentation (OpenAPI/Swagger) for the REST endpoints
- Changelog generation or version bumping automation
- Logo or branding assets
- Internationalization of documentation

## Open Questions

1. **GitHub repository URL**: Is `https://github.com/escherbridge/logseq-ai-hub` the intended public repo URL?
2. **Plugin ID**: What should replace `_byud67luv` in `package.json`? Suggestion: `logseq-ai-hub`
3. **Author attribution**: Should the README credit both human author and AI assistant, or just the human author?
4. **Screenshot availability**: Are there screenshots of the plugin UI to include in the README, or should we use text descriptions only?
