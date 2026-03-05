# Plan: Code Repository Integration

## Overview

Orchestration layer for code-aware workflows. Implements project registry pages, ADRs, code review skills, deployment procedures with approval gates, work coordination, lesson-learned memory, pi.dev agent platform integration, conductor-style task management, per-track agent profiles, and a layered development safeguard pipeline.

**Estimated effort:** 20-30 hours (85 tasks across 6 phases)

---

## Phase 1: Project Foundation (FR-1)

Foundation layer — project registry pages discovered via tag scanning, exposed as MCP tools and resources. All subsequent phases depend on this.

### Task 1.1: Plugin — Project page scanner and parser [x] [c2afa28]

**Files:** `src/main/logseq_ai_hub/code_repo/project.cljs`, `src/test/logseq_ai_hub/code_repo/project_test.cljs`

- Create `code_repo/project.cljs` namespace
- Implement `scan-projects!` — Datalog query for pages tagged `logseq-ai-hub-project`
- Implement `parse-project-page` — extract `project-name`, `project-repo`, `project-local-path`, `project-branch-main`, `project-tech-stack`, `project-description`, `project-status` properties
- Store results in registry atom under `:projects` category
- Write tests: scanning finds tagged pages, parser extracts all fields, missing fields default gracefully

### Task 1.2: Plugin — Project bridge handlers [x] [c2afa28]

**Files:** `src/main/logseq_ai_hub/code_repo/bridge.cljs`, `src/test/logseq_ai_hub/code_repo/bridge_test.cljs`

- Implement `handle-project-list` — returns all projects from registry atom
- Implement `handle-project-get` — returns single project by name, including page body content as context
- Uses `page_read` bridge pattern to fetch page body (BFS child block traversal)
- Write tests for both handlers

### Task 1.3: Plugin — Register project bridge operations [x] [3c25dce]

**Files:** `src/main/logseq_ai_hub/agent_bridge.cljs` (modify), `src/main/logseq_ai_hub/code_repo/init.cljs`

- Add `"project_list"` and `"project_get"` to `operation-handlers` dispatch map in `agent_bridge.cljs`
- Create `code_repo/init.cljs` — initialization function that calls `scan-projects!` and registers DB watcher for auto-discovery
- Wire init into `core.cljs` initialization chain

### Task 1.4: Server — Project MCP tools [x] [a6feb32]

**Files:** `server/src/services/mcp/project-tools.ts`, `server/tests/project-tools.test.ts`

- Create `registerProjectTools(server, getContext)` function
- Implement `project_list` tool — bridge call to `project_list`, returns array of project summaries
- Implement `project_get` tool — bridge call to `project_get`, returns full project with page body context
- Zod schemas with descriptive parameter documentation
- Tests: tool registration, list returns projects, get returns single project with body, error handling

### Task 1.5: Server — Project MCP resource [x] [3c25dce]

**Files:** `server/src/services/mcp/resources.ts` (modify)

- Add `logseq://projects/{name}` resource template
- Resource handler calls `project_get` bridge operation
- Returns project page content as resource text

### Task 1.6: Server — Register project tools in index [x] [3c25dce]

**Files:** `server/src/services/mcp/index.ts` (modify)

- Import `registerProjectTools` from `./project-tools`
- Add call in `registerAllMcpHandlers`

---

## Phase 2: Knowledge Layer (FR-2 + FR-6)

ADR pages linked to projects and lesson-learned memory integration. Builds on Phase 1 project foundation.

### Task 2.1: Plugin — ADR page scanner and parser [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/adr.cljs`, `src/test/logseq_ai_hub/code_repo/adr_test.cljs`

- Implement `scan-adrs!` — Datalog query for pages tagged `logseq-ai-hub-adr`
- Implement `parse-adr-page` — extract `adr-project`, `adr-status`, `adr-date`, `adr-title` properties + page body sections (Context, Decision, Consequences)
- Store in registry atom under `:adrs` category
- Write tests

### Task 2.2: Plugin — ADR bridge handlers [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/bridge.cljs` (extend)

- Implement `handle-adr-list` — filter ADRs by project name
- Implement `handle-adr-create` — creates new ADR page with auto-numbered title (ADR-NNN), sets properties, writes body template
- Register `"adr_list"` and `"adr_create"` operations in `agent_bridge.cljs`
- Write tests

### Task 2.3: Server — ADR MCP tools [ ]

**Files:** `server/src/services/mcp/adr-tools.ts`, `server/tests/adr-tools.test.ts`

- Create `registerAdrTools(server, getContext)` function
- Implement `adr_list` tool — params: `{project}`, bridge call to `adr_list`
- Implement `adr_create` tool — params: `{project, title, context, decision, consequences, status?}`, bridge call to `adr_create`
- Tests

### Task 2.4: Plugin — Lesson memory storage and search [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/lessons.cljs`, `src/test/logseq_ai_hub/code_repo/lessons_test.cljs`

- Implement `handle-lesson-store` — creates page at `AI-Memory/lessons/{project}/{category}/{title}`, tagged with `logseq-ai-hub-lesson`, sets `lesson-project`, `lesson-category`, `lesson-date` properties, writes content as child blocks
- Implement `handle-lesson-search` — Datalog query for lesson pages, filter by project and/or category, full-text search in content
- Categories: `bug-fix`, `architecture`, `performance`, `security`, `deployment`, `testing`, `tooling` (allow arbitrary)
- Register `"lesson_store"` and `"lesson_search"` operations in `agent_bridge.cljs`
- Write tests

### Task 2.5: Server — Lesson MCP tools [ ]

**Files:** `server/src/services/mcp/lesson-tools.ts`, `server/tests/lesson-tools.test.ts`

- Create `registerLessonTools(server, getContext)` function
- Implement `lesson_store` tool — params: `{project, category, title, content}`
- Implement `lesson_search` tool — params: `{project?, query, category?}`
- Tests

### Task 2.6: Server — Register knowledge tools in index [ ]

**Files:** `server/src/services/mcp/index.ts` (modify)

- Import and register `registerAdrTools`, `registerLessonTools`

---

## Phase 3: Safeguard Pipeline (FR-10)

Layered human-in-the-loop safeguard system for agent operations. P0 priority — critical for safe agent execution.

### Task 3.1: Plugin — Safeguard policy page scanner and parser [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/safeguard.cljs`, `src/test/logseq_ai_hub/code_repo/safeguard_test.cljs`

- Implement `scan-safeguards!` — Datalog query for pages tagged `logseq-ai-hub-safeguard`
- Implement `parse-safeguard-page` — extract `safeguard-name`, `safeguard-project`, `safeguard-level` (0-4), `safeguard-contact`, `safeguard-escalation-contact`, `safeguard-review-interval`, `safeguard-auto-deny-after` properties + Rules section
- Parse Rules section: lines starting with `BLOCK:`, `APPROVE:`, `LOG:`, `NOTIFY:` into structured rule objects `{action, pattern, description}`
- Store in registry atom under `:safeguards` category
- Write tests

### Task 3.2: Plugin — Safeguard bridge handlers [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/bridge.cljs` (extend)

- Implement `handle-safeguard-policy-get` — returns active policy for a project (defaults to level 1 "standard" if no policy page exists)
- Implement `handle-safeguard-audit-append` — appends audit entry as block on `Projects/{project}/safeguard-log` page
- Register operations in `agent_bridge.cljs`
- Write tests

### Task 3.3: Server — SafeguardService [ ]

**Files:** `server/src/services/safeguard-service.ts`, `server/tests/safeguard-service.test.ts`

- Create `SafeguardService` class
- Constructor takes `bridge`, `approvalStore`
- `evaluatePolicy(project, operation, agent, details)` → returns `{action: "allow"|"block"|"approve", reason, rule?}`
  - Fetches policy via bridge `safeguard_policy_get`
  - Matches operation against rules (glob pattern matching for file paths)
  - Level 0: always allow. Level 4: always approve. Levels 1-3: match rules
- `requestApproval(project, operation, agent, details, contact?)` → creates approval via `approvalStore`, returns promise
  - Handles escalation: if primary contact times out, escalate to `safeguard-escalation-contact`
  - Auto-deny after `safeguard-auto-deny-after` timeout
- `logAudit(project, operation, agent, action, details)` → bridge call to `safeguard_audit_append`
- In-memory cache for policies (invalidated on bridge refresh)
- Tests: all levels, rule matching, escalation chains, timeout handling, audit logging

### Task 3.4: Server — Safeguard MCP tools [ ]

**Files:** `server/src/services/mcp/safeguard-tools.ts`, `server/tests/safeguard-tools.test.ts`

- Create `registerSafeguardTools(server, getContext)` function
- Implement `safeguard_check` tool — params: `{project, operation, agent, details}` → calls `evaluatePolicy`, logs audit, returns action + reason
- Implement `safeguard_request` tool — params: `{project, operation, agent, details, contact?}` → calls `requestApproval`, logs result
- Implement `safeguard_policy_get` tool — params: `{project}` → returns active policy details
- Implement `safeguard_policy_update` tool — params: `{project, rules}` → if current level >= 2, requires approval first; then bridge call to update page
- Implement `safeguard_audit_log` tool — params: `{project, since?, operation?, agent?}` → bridge call to read audit log page, filter entries
- Tests

### Task 3.5: Server — Integrate SafeguardService into context [ ]

**Files:** `server/src/types/mcp.ts` (modify), `server/src/index.ts` (modify), `server/src/services/mcp/index.ts` (modify)

- Add `safeguardService?: SafeguardService` to `McpToolContext`
- Instantiate in `index.ts` startup
- Register safeguard tools in `registerAllMcpHandlers`

---

## Phase 4: Work Coordination + Task Management (FR-5 + FR-8)

Work claim system for multi-agent coordination and conductor-style project/track/task management stored as Logseq pages.

### Task 4.1: Server — WorkClaimStore [ ]

**Files:** `server/src/services/work-store.ts`, `server/tests/work-store.test.ts`

- Create `WorkClaimStore` class
- In-memory store: `Map<path, {sessionId, description, claimedAt}>`
- `claim(sessionId, path, description)` — glob pattern support, checks for conflicts, returns success/conflict
- `release(sessionId, path)` — release specific claim
- `releaseAll(sessionId)` — release all claims for a session (called on session archive)
- `listClaims()` — returns all active claims
- `checkConflict(path)` — checks if path overlaps with existing claims (glob matching)
- Tests: claim/release, conflict detection, glob patterns, session cleanup

### Task 4.2: Server — Work coordination MCP tools [ ]

**Files:** `server/src/services/mcp/work-tools.ts`, `server/tests/work-tools.test.ts`

- Create `registerWorkTools(server, getContext)` function
- Implement `work_claim` tool — params: `{sessionId, path, description}`
- Implement `work_release` tool — params: `{sessionId, path}`
- Implement `work_list_claims` tool — no params, returns all claims
- Implement `work_log` tool — params: `{project, action, details}` → bridge call to append block on `Projects/{project}/log` page
- Tests

### Task 4.3: Plugin — Work log bridge handler [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/bridge.cljs` (extend)

- Implement `handle-work-log` — appends timestamped block to `Projects/{project}/log` page, creates page if not exists
- Register `"work_log"` operation in `agent_bridge.cljs`
- Write tests

### Task 4.4: Plugin — Track/task page management handlers [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/tasks.cljs`, `src/test/logseq_ai_hub/code_repo/tasks_test.cljs`

- Implement `handle-track-create` — creates page `Projects/{project}/tracks/{trackId}` with properties: `track-type`, `track-status` (planned), `track-priority`, `track-branch`, `track-assigned-agent`, optionally `track-pi-config`
- Implement `handle-track-list` — Datalog query for child pages of `Projects/{project}/tracks/`, filter by status/type
- Implement `handle-track-update` — update track page properties
- Implement `handle-task-add` — append TODO block to track page with `task-status`, `task-agent` properties
- Implement `handle-task-update` — update task block properties, transition TODO/DOING/DONE markers
- Implement `handle-task-list` — read task blocks from track page
- Implement `handle-project-dashboard` — aggregate: count tracks by status, count tasks by status, compute completion %
- Register all operations in `agent_bridge.cljs`
- Write tests

### Task 4.5: Server — Track/task MCP tools [ ]

**Files:** `server/src/services/mcp/task-tools.ts`, `server/tests/task-tools.test.ts`

- Create `registerTaskTools(server, getContext)` function
- Implement `track_create`, `track_list`, `track_update`, `task_add`, `task_update`, `task_list`, `project_dashboard` tools
- All delegate to bridge handlers
- Tests

### Task 4.6: Server — Integrate work + task services [ ]

**Files:** `server/src/types/mcp.ts` (modify), `server/src/index.ts` (modify), `server/src/services/mcp/index.ts` (modify)

- Add `workStore?: WorkClaimStore` to `McpToolContext`
- Instantiate in `index.ts`
- Register work and task tools in `registerAllMcpHandlers`
- Hook work claim release to session archive events

---

## Phase 5: Pi.dev Agent Platform (FR-7 + FR-9)

Pi.dev integration as opt-in configurable agent execution platform with per-track agent profiles.

### Task 5.1: Plugin — Pi.dev settings [ ]

**Files:** `src/main/logseq_ai_hub/core.cljs` (modify)

- Add settings: `piDevEnabled` (boolean, default false), `piDevInstallPath` (string), `piDevDefaultModel` (string, default `anthropic/claude-sonnet-4`), `piDevRpcPort` (number, default 0), `piDevMaxConcurrentSessions` (number, default 3)
- Settings migration for existing installs

### Task 5.2: Plugin — Agent profile page scanner [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/pi_agents.cljs`, `src/test/logseq_ai_hub/code_repo/pi_agents_test.cljs`

- Implement `scan-pi-agents!` — Datalog query for pages tagged `logseq-ai-hub-pi-agent`
- Implement `parse-pi-agent-page` — extract `pi-agent-name`, `pi-agent-model`, `pi-agent-project`, `pi-agent-description` properties + System Instructions, Skills, Allowed Tools, Restricted Operations sections
- Bridge handlers: `handle-pi-agent-list`, `handle-pi-agent-get`, `handle-pi-agent-create`, `handle-pi-agent-update`
- Register operations in `agent_bridge.cljs`
- Write tests

### Task 5.3: Server — PiDevManager service [ ]

**Files:** `server/src/services/pidev-manager.ts`, `server/tests/pidev-manager.test.ts`

- Create `PiDevManager` class
- Constructor: `{bridge, config}` — reads pi settings from config
- `validateInstall()` — checks binary exists at configured path, runs `pi --version`
- `spawn(project, task, options?)` — spawns pi child process with `--rpc` flag, auto-assigned port, generated AGENTS.md
  - Assembles context: project page + ADRs + lessons via bridge
  - Generates `.pi-context/AGENTS.md` and `.pi-context/SYSTEM.md` from agent profile
  - Tracks session in `Map<sessionId, PiSession>`
  - Enforces `piDevMaxConcurrentSessions` limit (queue excess)
- `send(sessionId, message, steering?)` — sends message to running session via RPC
- `status(sessionId)` — returns session status + recent output
- `stop(sessionId)` — graceful shutdown, collect results, optionally store lesson learned
- `listSessions()` — returns all active sessions
- Version detection + adapter layer for RPC protocol differences
- Tests: spawn/send/stop lifecycle, concurrent session limits, context injection, error handling when pi unavailable

### Task 5.4: Server — Pi.dev MCP tools [ ]

**Files:** `server/src/services/mcp/pidev-tools.ts`, `server/tests/pidev-tools.test.ts`

- Create `registerPiDevTools(server, getContext)` function
- Implement `pi_spawn`, `pi_send`, `pi_status`, `pi_stop`, `pi_list_sessions` tools
- Implement `pi_agent_create`, `pi_agent_list`, `pi_agent_get`, `pi_agent_update` tools
- All check `piDevEnabled` first — return informative error if disabled
- Tests

### Task 5.5: Server — Integrate PiDevManager [ ]

**Files:** `server/src/types/mcp.ts` (modify), `server/src/index.ts` (modify), `server/src/services/mcp/index.ts` (modify)

- Add `piDevManager?: PiDevManager` to `McpToolContext`
- Conditional instantiation (only if `piDevEnabled`)
- Register pi.dev tools in `registerAllMcpHandlers`

---

## Phase 6: Skills, Procedures & Integration (FR-3 + FR-4)

Template skill/procedure pages, end-to-end integration testing, final wiring.

### Task 6.1: Plugin — Code review skill template [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/templates.cljs`

- Define code review skill page content as template string
- Uses `[[Projects/{project}]]` and `[[ADR/{project}/*]]` page refs instead of explicit graph-query steps
- The dynamic arg parser resolves these via BFS traversal
- Provide `create-code-review-skill!` function that creates the page in the graph

### Task 6.2: Plugin — Deployment procedure template [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/templates.cljs` (extend)

- Define deployment procedure page content template
- Includes `[APPROVAL: ...]` markers that map to `ask_human` calls
- `procedure-requires-approval:: true`, `procedure-approval-contact::` properties
- `create-deployment-procedure!` function

### Task 6.3: Plugin — Slash commands for template creation [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/init.cljs` (extend)

- Register `/code-repo:create-project` slash command — creates a project page template
- Register `/code-repo:create-adr` slash command — creates an ADR page template for a project
- Register `/code-repo:create-review-skill` slash command — creates the code review skill template
- Register `/code-repo:create-deploy-procedure` slash command — creates the deployment procedure template

### Task 6.4: Server — MCP resources for new entity types [ ]

**Files:** `server/src/services/mcp/resources.ts` (modify)

- Add `logseq://projects/{name}/adrs` resource template
- Add `logseq://projects/{name}/lessons` resource template
- Add `logseq://projects/{name}/tracks` resource template
- Add `logseq://projects/{name}/safeguards` resource template

### Task 6.5: Server — MCP prompts for code workflows [ ]

**Files:** `server/src/services/mcp/prompts.ts` (modify)

- Add `code-review` prompt template — assembles project context + ADRs + diff for review
- Add `start-coding-session` prompt template — assembles project context + recent lessons + active claims
- Add `deployment-checklist` prompt template — assembles deployment procedure + project status

### Task 6.6: End-to-end integration tests [ ]

**Files:** `server/tests/code-repo-integration.test.ts`

- Test full workflow: project_get → adr_list → safeguard_check → work_claim → task_update → lesson_store
- Test safeguard → approval → audit log flow
- Test project_dashboard aggregation
- Verify all 20+ new tools are registered and callable

### Task 6.7: Plugin — Integration initialization and DB watcher [ ]

**Files:** `src/main/logseq_ai_hub/code_repo/init.cljs` (finalize), `src/main/logseq_ai_hub/core.cljs` (modify)

- Wire `code_repo/init.cljs` into main plugin initialization in `core.cljs`
- DB watcher: on page changes matching project/ADR/safeguard/pi-agent tags, trigger rescan
- Debounce rescans (reuse existing debounce pattern from registry)
- Final integration: all scanners run on plugin startup

---

## Summary

| Phase | FRs | New Server Files | New Plugin Files | New MCP Tools |
|-------|-----|------------------|------------------|---------------|
| 1 | FR-1 | 2 | 4 | 2 + 1 resource |
| 2 | FR-2, FR-6 | 4 | 4 | 4 |
| 3 | FR-10 | 4 | 2 | 5 |
| 4 | FR-5, FR-8 | 6 | 2 | 11 |
| 5 | FR-7, FR-9 | 4 | 2 | 9 |
| 6 | FR-3, FR-4 | 2 | 2 | 3 prompts + 4 resources |
| **Total** | **10** | **22** | **16** | **31 tools + 5 resources + 3 prompts** |
