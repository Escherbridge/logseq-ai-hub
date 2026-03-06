# Specification: Code Repository Integration

## Overview

Enable the Logseq AI Hub to interact with code repositories (git operations, file reading/writing, CI/CD triggers) through a combination of MCP tool definitions in the knowledge base and existing MCP server connections. This track focuses on **orchestration** — defining the skills, tools, and procedures that connect coding agents to the system — rather than building new low-level git clients.

Additionally, this track integrates [pi.dev](https://pi.dev/) as an extensible agent platform alongside Claude Code. Pi provides a lightweight, multi-provider terminal coding agent with SDK/RPC integration, session tree management, and a skills system — making it a complementary execution engine that can be configured per-project and per-track. A conductor-style project module enables users to create, track, and manage development tasks across projects with customized pi.dev agent setups for each track.

The key insight is that coding agents (Claude Code, pi.dev, or others) already have full local filesystem and git access. What they need from the Hub is:
1. **Knowledge context** — what repos exist, their architecture, past decisions
2. **Coordination** — which agent is working on what, preventing conflicts
3. **Human oversight** — approval gates for dangerous operations (force push, deploy, merge to main), with strong, modifiable safeguard pipelines that link back to the Hub
4. **Persistent memory** — storing lessons learned, architectural decisions, code review findings
5. **Agent orchestration** — configuring and dispatching pi.dev agents with project-specific context, models, and skills
6. **Task management** — conductor-like track/task lifecycle management within each project

## Background

Claude Code is already a powerful coding agent. This track doesn't duplicate its capabilities — it augments them by connecting Claude Code to the Hub's knowledge base, approval system, and orchestration capabilities.

The Hub already has:
- **MCP server** (from mcp-server track) — Claude Code connects here
- **Knowledge base / registry** (from kb-tool-registry track) — stores tool/skill/procedure definitions
- **Human-in-the-loop** (from human-in-loop track) — approval gates via WhatsApp
- **Memory system** — persistent storage of architectural decisions and lessons learned
- **Job runner** — can execute multi-step skills

External MCP servers like `filesystem` and `git` provide the low-level operations. The Hub provides the intelligence layer on top.

## Dependencies

- **mcp-server_20260221**: MCP server for Claude Code to connect.
- **kb-tool-registry_20260221**: Registry for tool/skill definitions stored as pages.
- **human-in-loop_20260221**: Approval system for dangerous operations.
- **pi.dev** (external): Terminal coding agent with SDK/RPC interface. User provides local install path via plugin settings. Available at [pi.dev](https://pi.dev/), installed via npm (`npm i -g @anthropic-ai/pi` or similar). The Hub invokes pi via its RPC/SDK interface — it does not bundle or ship pi itself.

## Functional Requirements

### FR-1: Project Registry Pages

**Description:** Logseq pages that describe code projects/repositories known to the system.

**Page Format:**
```
project-name:: logseq-ai-hub
project-repo:: https://github.com/user/logseq-ai-hub
project-local-path:: /Users/atooz/Documents/Escherbridge/LogseqPlugin
project-branch-main:: main
project-tech-stack:: ClojureScript, TypeScript, Bun
project-description:: Logseq plugin with AI workflow orchestration
project-status:: active
tags:: logseq-ai-hub-project
```

**Acceptance Criteria:**
- Pages tagged `logseq-ai-hub-project` are discovered by the registry scanner.
- Projects are exposed as MCP resources (`logseq://projects/{name}`).
- The project page body can contain free-form notes about architecture, conventions, and decisions.
- An MCP tool `project_list` returns all known projects.
- An MCP tool `project_get` returns project details including the page body as context.

**Priority:** P0

### FR-2: Architectural Decision Records (ADRs)

**Description:** Logseq pages that capture architectural decisions, linked to projects.

**Page Format:**
```
adr-project:: logseq-ai-hub
adr-status:: accepted
adr-date:: 2026-02-21
adr-title:: Use SSE bridge for server-plugin communication
tags:: logseq-ai-hub-adr

## Context
The Logseq graph lives in the plugin (browser context) and cannot be accessed from the server directly.

## Decision
Implement a request-response bridge using SSE events from server to plugin and HTTP callbacks from plugin to server.

## Consequences
- All graph operations have 30-700ms latency
- Server cannot function for graph ops when plugin is disconnected
- Clean separation of concerns between server and plugin
```

**Acceptance Criteria:**
- ADR pages are discoverable in the registry.
- ADRs are linked to projects via the `adr-project` property.
- An MCP tool `adr_list` returns ADRs for a project.
- An MCP tool `adr_create` creates a new ADR page.
- Agents can read ADRs to understand past decisions before making new ones.

**Priority:** P1

### FR-3: Code Review Skill

**Description:** A skill definition (Logseq page) that orchestrates code review by combining graph knowledge with LLM analysis.

**Skill:**
```
skill-type:: llm-chain
skill-description:: Review code changes against project architecture and past decisions
skill-inputs:: project, diff
skill-outputs:: review
skill-version:: 1
tags:: logseq-ai-hub-tool

Step 1: Fetch project context
  step-order:: 1
  step-action:: graph-query
  step-config:: {"query": "[:find (pull ?b [*]) :where [?b :block/page ?p] [?p :block/name \"{{project}}\"]]"}

Step 2: Fetch relevant ADRs
  step-order:: 2
  step-action:: graph-query
  step-config:: {"query": "[:find (pull ?b [*]) :where [?b :block/properties ?props] [(get ?props :adr-project) ?proj] [(= ?proj \"{{project}}\")]]"}

Step 3: LLM review
  step-order:: 3
  step-action:: llm-call
  step-prompt-template:: |
    Review this code diff for the {{project}} project.

    Project context: {{step-1-result}}
    Relevant architectural decisions: {{step-2-result}}

    Diff:
    {{diff}}

    Provide a thorough code review considering the project's architecture and past decisions.
```

**Acceptance Criteria:**
- The skill is provided as a template that users can customize.
- It's automatically exposed as an MCP tool via the registry (from kb-tool-registry track).
- Claude Code can invoke it to get context-aware code reviews.
- Code review skills can reference project architecture pages via `[[Projects/my-app]]` instead of explicit Datalog graph-query steps. The arg parser's `[[Page]]` reference pattern resolves these automatically via BFS traversal, simplifying skill definitions.
- ADR pages referenced as `[[ADR/001-SSE-Bridge]]` inject architectural context for LLM decisions without requiring separate graph-query steps.

**Priority:** P1

### FR-4: Deployment Procedure with Approval Gates

**Description:** A procedure definition that codifies deployment steps with human approval checkpoints.

**Procedure:**
```
procedure-name:: deploy-to-production
procedure-description:: Full production deployment with safety checks
procedure-requires-approval:: true
procedure-approval-contact:: whatsapp:15551234567
procedure-project:: logseq-ai-hub
tags:: logseq-ai-hub-procedure

1. Run test suite: `yarn test && cd server && bun test`
2. Check for uncommitted changes
3. Build production artifacts: `yarn release`
4. **[APPROVAL: Deploy to production?]** Push to main and trigger Railway deploy
5. Verify health endpoint: `curl https://app.railway.app/health`
6. Run smoke tests
7. **[APPROVAL: Notify team?]** Send deployment notification via Slack/WhatsApp
8. Update deployment log in knowledge base
```

**Acceptance Criteria:**
- Procedures with approval gates integrate with the human-in-the-loop system.
- Claude Code can read the procedure, follow the steps, and invoke `ask_human` at approval gates.
- The procedure is exposed as an MCP resource.
- Future: procedures can be converted to executable skills.

**Priority:** P2

### FR-5: Work Coordination Tools

**Description:** MCP tools for coordinating work between multiple agents or sessions.

**Tools:**

| Tool Name | Description | Parameters |
|---|---|---|
| `work_claim` | Claim a file/area to work on (prevents conflicts) | `{sessionId, path, description}` |
| `work_release` | Release a claimed file/area | `{sessionId, path}` |
| `work_list_claims` | List all active work claims | `{}` |
| `work_log` | Log a significant action to the project log | `{project, action, details}` |

**Acceptance Criteria:**
- Work claims are stored in-memory (with session association).
- If an agent tries to claim a file that's already claimed by another session, it gets a warning.
- Claims are automatically released when a session is archived.
- `work_log` entries are stored as blocks on a project log page (e.g., `Projects/logseq-ai-hub/log`).

**Priority:** P2

### FR-6: Lesson Learned Memory Integration

**Description:** Automated storage of lessons learned from coding sessions into the knowledge base.

**MCP Tools:**

| Tool Name | Description | Parameters |
|---|---|---|
| `lesson_store` | Store a lesson learned from a coding session | `{project, category, title, content}` |
| `lesson_search` | Search past lessons | `{project?, query, category?}` |

**Categories:** `bug-fix`, `architecture`, `performance`, `security`, `deployment`, `testing`, `tooling`

**Acceptance Criteria:**
- Lessons are stored as memory pages under `AI-Memory/lessons/{project}/{category}/{title}`.
- Each lesson is tagged and timestamped for retrieval.
- When starting work on a project, the agent can search for relevant past lessons.
- Lessons are exposed as MCP resources for browsing.
- Lesson-learned memories can reference related pages via `[[Page]]` refs for richer context. When recalling lessons through `/LLM` or `enriched/call`, the dynamic argument parser resolves all embedded page references.

**Priority:** P1

### FR-7: Pi.dev Agent Platform Integration

**Description:** Integration with [pi.dev](https://pi.dev/) as a configurable agent execution platform. Users enable the feature in plugin settings and provide the path to their local pi installation. The Hub then communicates with pi via its RPC/SDK interface to dispatch tasks, inject knowledge-base context, and retrieve session results.

**Plugin Settings:**

| Setting Key | Type | Default | Description |
|---|---|---|---|
| `piDevEnabled` | boolean | `false` | Enable pi.dev agent integration |
| `piDevInstallPath` | string | `""` | Absolute path to the pi binary (e.g., `/usr/local/bin/pi` or `C:\Users\...\pi.exe`) |
| `piDevDefaultModel` | string | `"anthropic/claude-sonnet-4"` | Default model for pi sessions |
| `piDevRpcPort` | number | `0` | RPC port override (0 = auto-assign) |
| `piDevMaxConcurrentSessions` | number | `3` | Max concurrent pi agent sessions |

**MCP Tools:**

| Tool Name | Description | Parameters |
|---|---|---|
| `pi_spawn` | Spawn a new pi.dev agent session with injected context | `{project, task, model?, agentsFile?, workingDir?, timeout?}` |
| `pi_send` | Send a message or follow-up to a running pi session | `{sessionId, message, steering?}` |
| `pi_status` | Get status and recent output from a pi session | `{sessionId}` |
| `pi_stop` | Gracefully stop a pi session and collect results | `{sessionId}` |
| `pi_list_sessions` | List all active pi sessions | `{}` |

**Context Injection Flow:**
1. When `pi_spawn` is called, the Hub assembles context from the project's registry page, relevant ADRs, applicable lessons, and the track's AGENTS.md file (see FR-9).
2. This context is written to a temporary AGENTS.md / SYSTEM.md file in the project's working directory (or a `.pi-context/` subdirectory).
3. Pi reads these instruction files natively on startup — no special protocol needed.
4. On session completion, the Hub extracts results and optionally stores lessons learned (FR-6).

**Acceptance Criteria:**
- Pi.dev integration is entirely opt-in — disabled by default.
- The Hub validates the install path on enable (checks binary exists and is executable).
- `pi_spawn` creates an isolated pi session with full knowledge-base context injected.
- Sessions are tracked in-memory with association to projects and tracks.
- Session output is streamable via SSE for real-time monitoring in the Hub UI.
- If pi is not installed or the path is invalid, tools return clear error messages (not silent failures).
- Pi sessions respect the `piDevMaxConcurrentSessions` limit; excess requests are queued.

**Priority:** P1

### FR-8: Project Task Management Module

**Description:** A conductor-style project and task management system within the knowledge base. Each project can have multiple tracks (features, bugs, chores), each with a spec, plan, and task list — all stored as Logseq pages. This mirrors the structure of [claude-conductor](https://github.com/lackeyjb/claude-conductor) but lives inside the graph so it's browsable, editable, and searchable alongside all other knowledge.

**Page Structure:**
```
Projects/{project-name}/tracks
├── Projects/{project-name}/tracks/{track-id}
│   ├── track-type:: feature | bug | chore | spike
│   ├── track-status:: planned | in-progress | completed | blocked
│   ├── track-priority:: P0 | P1 | P2 | P3
│   ├── track-branch:: track/{track-id}
│   ├── track-assigned-agent:: claude-code | pi | manual
│   ├── track-pi-config:: [[Projects/{project-name}/pi-agents/{agent-profile}]]
│   └── Body: spec and plan as child blocks
└── ...
```

**Task Block Format (within track pages):**
```
- TODO Task description
  task-status:: pending | in-progress | completed | blocked
  task-agent:: claude-code | pi:{sessionId} | manual
  task-started:: 2026-03-03T10:00:00Z
  task-completed::
  task-notes:: Any relevant notes or blockers
```

**MCP Tools:**

| Tool Name | Description | Parameters |
|---|---|---|
| `track_create` | Create a new track for a project | `{project, title, type, priority, spec?, piAgentConfig?}` |
| `track_list` | List all tracks for a project with status | `{project, status?, type?}` |
| `track_update` | Update track status, priority, or assignment | `{project, trackId, status?, priority?, assignedAgent?}` |
| `task_add` | Add a task to a track | `{project, trackId, description, agent?}` |
| `task_update` | Update task status and notes | `{project, trackId, taskIndex, status?, notes?}` |
| `task_list` | List tasks in a track | `{project, trackId, status?}` |
| `project_dashboard` | Aggregate view: all tracks, task counts, completion % | `{project}` |

**Acceptance Criteria:**
- Tracks and tasks are stored as Logseq pages and blocks — users can view and edit them directly in Logseq.
- `track_create` generates a new page under `Projects/{project}/tracks/` with the correct properties.
- Task status updates are reflected immediately in the graph.
- `project_dashboard` returns a summary similar to conductor's `/conductor:status` output.
- Agents (Claude Code or pi.dev) can self-assign tasks via `task_update` when starting work.
- Track pages link to their pi agent configuration (FR-9) when applicable.
- All task transitions (pending→in-progress→completed) are logged to the project log (FR-5 `work_log`).

**Priority:** P1

### FR-9: Per-Track Pi Agent Configurations

**Description:** Each project track can have a customized pi.dev agent profile that specifies the model, system instructions, skills, and tool access for that track's work. Profiles are stored as Logseq pages so users can inspect and modify them.

**Agent Profile Page Format:**
```
pi-agent-name:: frontend-specialist
pi-agent-model:: anthropic/claude-sonnet-4
pi-agent-project:: logseq-ai-hub
pi-agent-description:: Handles React component development with accessibility focus
tags:: logseq-ai-hub-pi-agent

## System Instructions
You are a frontend specialist working on the Logseq AI Hub.
Follow the project's component conventions documented in [[Projects/logseq-ai-hub]].
Consult [[ADR/003-Component-Patterns]] before creating new components.

## Skills
- code-review
- accessibility-audit
- component-scaffold

## Allowed Tools
- file read/write in src/main/logseq_ai_hub/ui/**
- git operations on feature branches only
- npm/bun commands (non-destructive)

## Restricted Operations
- No force push
- No direct commits to main
- No package.json dependency additions without approval
- No deletion of test files

## Safeguard Level
safeguard-level:: standard
```

**MCP Tools:**

| Tool Name | Description | Parameters |
|---|---|---|
| `pi_agent_create` | Create a new pi agent profile for a project | `{project, name, model, description, systemInstructions, skills?, allowedTools?, restrictions?}` |
| `pi_agent_list` | List agent profiles for a project | `{project}` |
| `pi_agent_get` | Get full agent profile details | `{project, name}` |
| `pi_agent_update` | Modify an agent profile | `{project, name, ...fields}` |

**Acceptance Criteria:**
- Agent profiles are stored as pages under `Projects/{project}/pi-agents/`.
- When `pi_spawn` is called with a track that has a `track-pi-config` reference, the linked profile's system instructions, skills, and tool restrictions are injected into the pi session.
- Restrictions defined in the profile are enforced via the safeguard pipeline (FR-10) — pi sessions that attempt restricted operations trigger approval requests.
- Users can create multiple agent profiles per project (e.g., `frontend-specialist`, `backend-api`, `test-writer`) and assign them to different tracks.
- Profiles support `[[Page]]` references in system instructions — the arg parser resolves these to inject live knowledge-base context at session start.
- Default profile is used when no track-specific profile is assigned.

**Priority:** P2

### FR-10: Development Safeguard Pipeline

**Description:** A layered, modifiable human-in-the-loop safeguard system that governs all agent operations — from pi.dev sessions to Claude Code MCP calls. Safeguards are defined as Logseq pages, composable into pipelines, and link back to the Hub's approval system (from human-in-loop track) at every level.

**Safeguard Levels:**

| Level | Name | Description |
|---|---|---|
| 0 | `unrestricted` | No approval gates. Agent operates freely. Not recommended for production repos. |
| 1 | `standard` | Approval required for destructive operations (force push, deploy, delete, merge to main). Default level. |
| 2 | `guarded` | Approval for destructive ops + file writes outside agent's claimed area + dependency changes. |
| 3 | `supervised` | All operations logged and summarized; human reviews batched summaries at defined intervals. |
| 4 | `locked` | Every operation requires explicit approval. For critical production paths. |

**Safeguard Policy Page Format:**
```
safeguard-name:: production-deploy-policy
safeguard-project:: logseq-ai-hub
safeguard-level:: 2
safeguard-contact:: whatsapp:15551234567
safeguard-escalation-contact:: whatsapp:15559876543
safeguard-review-interval:: 30m
safeguard-auto-deny-after:: 1h
tags:: logseq-ai-hub-safeguard

## Rules
- BLOCK: force push to any branch
- BLOCK: deletion of files matching src/main/**
- APPROVE: merge to main (requires 2 approvals)
- APPROVE: dependency additions (package.json, deps.edn)
- LOG: all git commits (no approval, but recorded)
- NOTIFY: test failures (send summary to contact)

## Escalation
If no response within review-interval, escalate to escalation-contact.
If no response within auto-deny-after, deny the operation and notify both contacts.

## Override
override-token:: (stored in secretsVault, not in page)
Providing the override token bypasses approval for a single operation. All overrides are logged.
```

**MCP Tools:**

| Tool Name | Description | Parameters |
|---|---|---|
| `safeguard_check` | Check if an operation is allowed under current policy | `{project, operation, agent, details}` |
| `safeguard_request` | Request approval for a blocked operation | `{project, operation, agent, details, contact?}` |
| `safeguard_policy_get` | Get the active safeguard policy for a project | `{project}` |
| `safeguard_policy_update` | Update safeguard rules (requires approval if level >= 2) | `{project, rules}` |
| `safeguard_audit_log` | Retrieve the audit log for a project | `{project, since?, operation?, agent?}` |

**Pipeline Integration:**
```
Agent Action → safeguard_check → Policy Evaluation
                                    ├── ALLOW → execute + LOG
                                    ├── BLOCK → deny + NOTIFY
                                    ├── APPROVE → ask_human (FR from HITL track)
                                    │               ├── approved → execute + LOG
                                    │               ├── denied → deny + LOG
                                    │               └── timeout → escalate or auto-deny
                                    └── LOG → execute + record to audit log
```

**Linking Back to the Hub:**
- Every safeguard event (check, approval, denial, override) is recorded as a block on `Projects/{project}/safeguard-log`.
- The audit log is browsable in Logseq — users can query, filter, and review all agent actions.
- SSE events broadcast safeguard status in real time to the Hub UI.
- Safeguard policies are editable in Logseq — changes take effect immediately but are themselves subject to safeguard rules (modifying a level-2+ policy requires approval).
- The project dashboard (FR-8) includes a safeguard summary: recent approvals, denials, and pending requests.

**Acceptance Criteria:**
- Safeguard policies are stored as Logseq pages under `Projects/{project}/safeguards/`.
- Every pi.dev session and Claude Code MCP operation is evaluated against the active policy before execution.
- The `safeguard_check` tool returns `allow`, `block`, or `approve-required` with a human-readable reason.
- Approval requests integrate with the existing `ask_human` tool from the HITL track.
- Escalation chains work: primary contact → escalation contact → auto-deny, with configurable timeouts.
- Override tokens are stored in the secrets vault (not in page content) and every use is logged.
- Users can modify safeguard policies directly in Logseq; changes to level 2+ policies require approval through the same system.
- Audit logs are append-only and queryable via MCP tools and Logseq's built-in search.
- Default safeguard level is `standard` (level 1) for new projects.

**Priority:** P0

## Non-Functional Requirements

### NFR-1: Non-Invasive

- This track does NOT modify Claude Code's or pi.dev's behavior or capabilities.
- All features are additive — they provide information and coordination.
- Pi.dev integration is entirely opt-in; the Hub is fully functional without it.
- Agents can ignore procedures and work without claims (unless safeguard level restricts this).

### NFR-2: Incremental Value

- Each feature provides value independently. Users don't need to adopt all features.
- Project pages are useful even without ADRs.
- Lessons learned are useful even without work coordination.
- Pi.dev integration is useful even without the task management module.
- Safeguards are useful even without pi.dev — they apply to all agent operations.

### NFR-3: Knowledge-First

- All state is stored in the Logseq graph as pages and blocks.
- The knowledge base is the source of truth, not a database or config file.
- Users can edit, reorganize, and extend the knowledge directly in Logseq.
- Safeguard policies, agent profiles, tracks, and tasks are all Logseq pages.

### NFR-4: Defense in Depth

- Safeguards operate at multiple levels: Hub MCP tools (enforced), pi.dev SYSTEM.md (advisory), and git-level protections (external).
- No single point of failure — even if one layer is bypassed, others provide protection.
- All agent actions are logged to append-only audit pages in the graph.
- Safeguard policy changes are themselves subject to safeguard rules.

### NFR-5: Graceful Degradation

- If pi.dev is unavailable, all non-pi features continue to work normally.
- If the HITL system (WhatsApp/Telegram) is unreachable, safeguard requests queue and timeout according to policy.
- If the Logseq graph is temporarily inaccessible, in-flight operations continue but new context injection is paused.

## User Stories

### US-1: Context-aware coding assistance

**As** a developer using Claude Code,
**I want** Claude Code to read my project's architecture and past decisions from the Hub,
**So that** it writes code that's consistent with established patterns.

### US-2: Safe deployments

**As** a developer,
**I want** to define deployment procedures with approval gates,
**So that** automated agents can't push to production without my explicit approval.

### US-3: Knowledge accumulation

**As** a team lead,
**I want** coding agents to store lessons learned after each session,
**So that** future sessions benefit from past experience.

### US-4: Multi-agent coordination

**As** a developer running multiple coding agents,
**I want** them to coordinate via work claims,
**So that** they don't create conflicting changes to the same files.

### US-5: Pi.dev agent for focused tasks

**As** a developer,
**I want** to spawn a pi.dev agent from the Hub with my project's architecture context pre-loaded,
**So that** I can delegate focused tasks (component scaffolding, test writing, refactoring) to a lightweight agent without manually setting up context each time.

### US-6: Conductor-style project management

**As** a team lead,
**I want** to create tracks (features, bugs, chores) for each project with specs and task lists stored in my Logseq graph,
**So that** I can track progress, assign work to agents, and see a dashboard of completion status — all within the knowledge base I already use.

### US-7: Customized agent profiles per track

**As** a developer working on multiple tracks,
**I want** each track to use a pi.dev agent configured for that specific domain (frontend, backend, testing),
**So that** each agent has the right model, system instructions, and tool restrictions for its work without manual reconfiguration.

### US-8: Modifiable safeguards with audit trail

**As** a developer responsible for production systems,
**I want** to define safeguard policies per project that require human approval for dangerous operations, with escalation chains and full audit logging,
**So that** no agent can force-push, deploy, or delete critical files without my explicit consent — and I can review every action taken.

### US-9: Safeguard policy editing under safeguards

**As** a security-conscious team lead,
**I want** changes to safeguard policies to themselves require approval when the policy is level 2 or higher,
**So that** a compromised or misbehaving agent cannot weaken the safety guardrails protecting my codebase.

## Technical Considerations

### External MCP Servers for File/Git Operations

The Hub doesn't need its own git client. Claude Code already has native file access. For server-side operations (CI/CD triggers, webhook-driven builds), external MCP servers can provide:
- `@modelcontextprotocol/server-filesystem` — file read/write
- `@modelcontextprotocol/server-github` — GitHub API access
- Custom MCP servers for CI/CD (Railway, Vercel, etc.)

The Hub's role is to provide the knowledge layer and coordination on top.

### Template Skills vs Hardcoded Skills

All code review, deployment, and other skills are defined as Logseq pages (templates), not hardcoded in the plugin. Users can:
- Modify skills to fit their workflow
- Create new skills for their specific needs
- Share skills via OpenClaw export/import

### Integration Points

```
Claude Code ──MCP──▶ Hub ◀──RPC/SDK── pi.dev
                     │
                     ├── project_get → reads project page
                     ├── adr_list → reads ADR pages
                     ├── lesson_search → searches memory
                     ├── ask_human → approval via WhatsApp
                     ├── work_claim → coordination
                     ├── skill_summarize → runs skill via job runner
                     │
                     ├── pi_spawn → launch pi session with KB context
                     ├── pi_send / pi_status → interact with pi sessions
                     │
                     ├── track_create / task_update → conductor-style task management
                     ├── project_dashboard → aggregate project view
                     │
                     ├── pi_agent_create → per-track agent profiles
                     │
                     └── safeguard_check → policy evaluation pipeline
                         ├── ALLOW → execute + log
                         ├── BLOCK → deny + notify
                         └── APPROVE → ask_human → escalate/timeout
```

### Pi.dev Communication Architecture

```
Hub Server
  │
  ├── Spawns pi via child_process (path from settings)
  │     └── pi --rpc --port {auto} --agents-file {generated}
  │
  ├── Connects via RPC (JSON protocol over stdio/TCP)
  │     ├── Send task messages
  │     ├── Receive streaming output
  │     └── Collect session results
  │
  └── Context injection
        ├── Generates AGENTS.md from project page + ADRs + lessons
        ├── Generates SYSTEM.md from agent profile (FR-9)
        └── Places in project workdir/.pi-context/
```

## Out of Scope

- Git operations within the Hub (Claude Code and pi.dev handle this natively).
- CI/CD pipeline management (use external MCP servers).
- Code generation or refactoring capabilities (Claude Code and pi.dev handle this).
- IDE integration (Claude Code provides this).
- Branch management policies (use GitHub branch protection rules).
- Shipping or bundling pi.dev — the user installs it independently.
- Building a custom agent runtime — pi.dev and Claude Code are the execution engines; the Hub provides orchestration and knowledge.

## Open Questions

1. **Work claim granularity:** Should claims be at the file level, directory level, or arbitrary path pattern? Recommendation: arbitrary path pattern with glob support (e.g., `src/main/logseq_ai_hub/job_runner/**`).

2. **ADR numbering:** Should ADRs be auto-numbered (ADR-001, ADR-002) or user-named? Recommendation: auto-numbered with user-provided title for discoverability.

3. **Lesson categorization:** Should categories be fixed or user-defined? Recommendation: provide defaults but allow arbitrary categories via tags.

4. **Pi.dev RPC stability:** Pi.dev's RPC interface may evolve across versions. Should the Hub pin to a specific pi version or implement an adapter layer? Recommendation: adapter layer with version detection — the Hub checks `pi --version` on startup and loads the appropriate RPC client.

5. **Pi.dev session isolation:** Should pi sessions run in the project's working directory directly, or in a temporary worktree (like conductor agents)? Recommendation: support both — default to project workdir for read-heavy tasks, worktree for write-heavy tasks to prevent conflicts.

6. **Safeguard enforcement depth:** Should safeguards apply only to Hub-mediated operations, or also attempt to enforce within pi.dev sessions (e.g., via SYSTEM.md restrictions)? Recommendation: both — Hub enforces at the MCP tool level, and the generated SYSTEM.md instructs pi to self-restrict. The SYSTEM.md approach is advisory (pi could ignore it), so critical restrictions must be enforced at the Hub level.

7. **Task format compatibility:** Should the task management module (FR-8) use Logseq's native TODO/DOING/DONE markers, or custom `task-status` properties? Recommendation: use Logseq's native TODO system as the primary status indicator (for compatibility with Logseq's built-in task views) with `task-status` properties for extended metadata (agent assignment, timestamps).

8. **Multi-user safeguard approval:** For teams, should safeguard approvals require consensus (N-of-M approvals) or is single approval sufficient? Recommendation: start with single approval, add N-of-M as an optional policy rule in a future iteration.

9. **Pi.dev availability:** What happens when pi.dev is enabled in settings but the binary is not found or crashes? Recommendation: graceful degradation — Hub logs a warning, pi-related MCP tools return informative errors, and all non-pi features continue to work normally. The project dashboard shows pi status as "unavailable".
