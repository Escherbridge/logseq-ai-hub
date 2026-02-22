# Specification: Code Repository Integration

## Overview

Enable the Logseq AI Hub to interact with code repositories (git operations, file reading/writing, CI/CD triggers) through a combination of MCP tool definitions in the knowledge base and existing MCP server connections. This track focuses on **orchestration** — defining the skills, tools, and procedures that connect coding agents to the system — rather than building new low-level git clients.

The key insight is that Claude Code already has full local filesystem and git access. What it needs from the Hub is:
1. **Knowledge context** — what repos exist, their architecture, past decisions
2. **Coordination** — which agent is working on what, preventing conflicts
3. **Human oversight** — approval gates for dangerous operations (force push, deploy, merge to main)
4. **Persistent memory** — storing lessons learned, architectural decisions, code review findings

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

## Non-Functional Requirements

### NFR-1: Non-Invasive

- This track does NOT modify Claude Code's behavior or capabilities.
- All features are additive — they provide information and coordination, not enforcement.
- Agents can ignore procedures, skip approvals (if configured), and work without claims.

### NFR-2: Incremental Value

- Each feature provides value independently. Users don't need to adopt all features.
- Project pages are useful even without ADRs.
- Lessons learned are useful even without work coordination.

### NFR-3: Knowledge-First

- All state is stored in the Logseq graph as pages and blocks.
- The knowledge base is the source of truth, not a database or config file.
- Users can edit, reorganize, and extend the knowledge directly in Logseq.

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
Claude Code ──MCP──▶ Hub
                     ├── project_get → reads project page
                     ├── adr_list → reads ADR pages
                     ├── lesson_search → searches memory
                     ├── ask_human → approval via WhatsApp
                     ├── work_claim → coordination
                     └── skill_summarize → runs skill via job runner
```

## Out of Scope

- Git operations within the Hub (Claude Code handles this natively).
- CI/CD pipeline management (use external MCP servers).
- Code generation or refactoring capabilities (Claude Code handles this).
- IDE integration (Claude Code provides this).
- Branch management policies (use GitHub branch protection rules).

## Open Questions

1. **Work claim granularity:** Should claims be at the file level, directory level, or arbitrary path pattern? Recommendation: arbitrary path pattern with glob support (e.g., `src/main/logseq_ai_hub/job_runner/**`).

2. **ADR numbering:** Should ADRs be auto-numbered (ADR-001, ADR-002) or user-named? Recommendation: auto-numbered with user-provided title for discoverability.

3. **Lesson categorization:** Should categories be fixed or user-defined? Recommendation: provide defaults but allow arbitrary categories via tags.
