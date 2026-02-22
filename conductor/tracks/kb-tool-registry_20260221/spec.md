# Specification: Knowledge Base as Living Tool Registry

## Overview

Transform the Logseq knowledge base into a dynamic, self-describing tool and skill registry. Pages tagged with specific conventions become discoverable tools, skills, prompts, and agent configurations that the system can enumerate, compose, and execute. The knowledge base evolves from passive storage into an active registry that MCP clients, the job runner, and the agent chat can query to find and invoke capabilities.

## Background

The system already stores skills as Logseq pages (`Skills/*`) with structured properties. However:
- Skills are only discoverable by the job runner — they're not exposed as first-class tools to MCP clients or the agent chat.
- There's no way to define new MCP tools, prompt templates, or agent configurations as Logseq pages.
- The memory system stores information but doesn't classify it as actionable knowledge (tools, procedures, contacts, etc.).
- Sub-agents are defined as pages but disconnected from the job runner and MCP layers.

This track unifies these concepts: a Logseq page with the right tags and properties becomes a **registered capability** in the system — discoverable by any agent, invocable through any interface (MCP, REST, slash command, job step).

### Architecture

```
Logseq Graph
├── Tools/send-slack-notification     ← MCP tool definition (page)
├── Skills/daily-summary              ← Executable skill (page)
├── Prompts/code-review               ← Prompt template (page)
├── Agents/code-reviewer              ← Sub-agent definition (page)
├── Procedures/deploy-to-production   ← Step-by-step procedure (page)
└── AI-Memory/api-keys-guide          ← Knowledge article (page)

         ▼ Registry Scanner ▼

Tool Registry (in-memory, refreshed on change)
├── Tools from Tools/* pages
├── Skills from Skills/* pages (auto-wrapped as tools)
├── Prompts from Prompts/* pages
├── Agent configs from agent-tagged pages
└── Procedures from Procedures/* pages
```

## Dependencies

- **mcp-server_20260221**: The MCP server needs to serve dynamically registered tools/resources/prompts from this registry.
- **job-runner_20260219**: Skills are already defined as pages; this track makes them discoverable beyond the job runner.

## Functional Requirements

### FR-1: Tool Page Convention

**Description:** Define a page convention for declaring custom MCP tools as Logseq pages.

**Page Format:**
```
tool-type:: mcp-tool
tool-name:: send-slack-notification
tool-description:: Send a notification to a Slack channel
tool-input-schema:: {"type": "object", "properties": {"channel": {"type": "string"}, "message": {"type": "string"}}, "required": ["channel", "message"]}
tool-handler:: mcp-tool
tool-mcp-server:: slack
tool-mcp-tool:: post_message
tags:: logseq-ai-hub-tool
```

**Handler Types:**
| Handler | Description |
|---|---|
| `mcp-tool` | Delegates to an MCP server tool (specified by `tool-mcp-server` + `tool-mcp-tool`) |
| `skill` | Executes a skill (specified by `tool-skill`) |
| `http` | Makes an HTTP request (specified by `tool-http-url`, `tool-http-method`) |
| `graph-query` | Runs a Datalog query (specified by `tool-query`) |

**Acceptance Criteria:**
- Pages tagged `logseq-ai-hub-tool` with valid properties are discovered by the registry scanner.
- Tool definitions are validated (required: name, description, input-schema, handler).
- Invalid tool pages are logged as warnings but don't crash the scanner.
- Tool pages under any prefix are discovered (not limited to `Tools/`).

**Priority:** P0

### FR-2: Registry Scanner

**Description:** A scanner that reads tool, skill, prompt, and agent pages from the graph and builds an in-memory registry.

**Acceptance Criteria:**
- On plugin startup, scans all pages with relevant tags:
  - `logseq-ai-hub-tool` → Tool definitions
  - `logseq-ai-hub-agent` → Sub-agent definitions (existing)
  - `logseq-ai-hub-prompt` → Prompt templates
  - `logseq-ai-hub-procedure` → Step-by-step procedures
- Skills under `Skills/*` are automatically included (no tag required, uses existing properties).
- The registry is stored in an atom as a map: `{:tools [...], :skills [...], :prompts [...], :agents [...], :procedures [...]}`.
- A `/registry:refresh` slash command triggers a manual rescan.
- The registry is refreshable via Agent Bridge operation (`refresh_registry`).
- On scan, emits a summary log: "Registry: 5 tools, 12 skills, 3 prompts, 2 agents, 4 procedures".

**Priority:** P0

### FR-3: Dynamic MCP Tool Registration

**Description:** Tools and skills from the registry are automatically exposed as MCP tools.

**Acceptance Criteria:**
- Skills are wrapped as MCP tools: skill inputs become tool parameters, skill execution becomes the tool handler.
- Custom tool pages are registered as MCP tools with their declared schemas and handlers.
- When the registry is refreshed, the MCP server's tool list is updated.
- The MCP server's `tools/list` reflects the current registry state.
- If a tool's handler is `mcp-tool`, invoking it chains through the plugin's MCP client to the specified external MCP server.
- Skill invocations via `/LLM` can reference `[[Skills/skill-name]]` to inject the skill definition as context. The `enriched/call` function auto-resolves any `[[Page]]` references in block content before dispatching to the LLM.

**Priority:** P0

### FR-4: Prompt Page Convention

**Description:** Define a page convention for declaring prompt templates as Logseq pages.

**Page Format:**
```
prompt-name:: code-review
prompt-description:: Review code for quality, security, and maintainability
prompt-arguments:: code, language, focus
tags:: logseq-ai-hub-prompt

## System
You are an expert code reviewer. Review the following {{language}} code with a focus on {{focus}}.

## User
```{{language}}
{{code}}
```

Please provide your review.
```

**Acceptance Criteria:**
- Pages tagged `logseq-ai-hub-prompt` with valid properties are discovered.
- The page body (excluding property lines) is parsed as the prompt template.
- `## System` and `## User` headings delimit system and user messages.
- Arguments use `{{variable}}` interpolation (reuses existing interpolation engine).
- Prompts are exposed via MCP `prompts/list` and `prompts/get`.
- Prompt template pages support `[[Page]]` references in their template bodies. When invoked through `/LLM` or `enriched/call`, page refs within the template are auto-resolved via the dynamic argument parser, injecting referenced page content as additional system prompt context.
- The registry scanner should tag prompt pages so they're identifiable to the arg parser for potential future auto-detection.

**Priority:** P1

### FR-5: Procedure Page Convention

**Description:** Define a page convention for step-by-step procedures that agents can follow.

**Page Format:**
```
procedure-name:: deploy-to-production
procedure-description:: Full deployment checklist for production releases
procedure-requires-approval:: true
procedure-approval-contact:: whatsapp:15551234567
tags:: logseq-ai-hub-procedure

1. Run all tests: `yarn test && cd server && bun test`
2. Build production artifacts: `yarn release`
3. Create a git tag with the version number
4. Push to main branch
5. **[APPROVAL REQUIRED]** Deploy to Railway
6. Verify health endpoint returns OK
7. Run smoke tests against production
8. Notify the team via Slack
```

**Acceptance Criteria:**
- Procedures are discoverable in the registry.
- Procedures are exposed as MCP resources (`logseq://procedures/{name}`).
- When `procedure-requires-approval` is true, the procedure includes approval gates at marked steps.
- Agents can read procedures to understand multi-step processes.
- Future: procedures can be converted to skills for automated execution.

**Priority:** P2

### FR-6: Registry Query Tools

**Description:** MCP tools for querying the registry itself.

**Tools:**

| Tool Name | Description | Parameters |
|---|---|---|
| `registry_search` | Search the registry by keyword | `{query: string, type?: "tool" | "skill" | "prompt" | "agent" | "procedure"}` |
| `registry_list` | List all registered capabilities | `{type?: string}` |
| `registry_get` | Get full details of a registered item | `{name: string, type: string}` |
| `registry_refresh` | Trigger a registry rescan | `{}` |

**Acceptance Criteria:**
- `registry_search` searches across names, descriptions, and tags.
- `registry_list` returns summaries (name, type, description) for all items or filtered by type.
- `registry_get` returns the full definition including schemas, steps, handlers.
- `registry_refresh` triggers a rescan and returns the updated counts.

**Priority:** P1

### FR-7: Auto-Discovery on Page Changes

**Description:** Automatically refresh the registry when relevant pages are created, updated, or deleted.

**Acceptance Criteria:**
- The plugin listens for Logseq page change events (`logseq.DB.onChanged`).
- When a page with a registry-relevant tag is modified, the registry is refreshed.
- Debounce: changes within a 2-second window trigger a single refresh.
- The MCP server is notified of tool list changes (via `notifications/tools/list_changed` if the client supports it).

**Priority:** P2

## Non-Functional Requirements

### NFR-1: Performance

- Registry scan should complete within 2 seconds for up to 100 registered items.
- Registry queries (search, list, get) should be sub-millisecond (in-memory atom lookup).
- Auto-refresh debouncing prevents excessive rescans on bulk edits.

### NFR-2: Extensibility

- New page types (beyond tool, skill, prompt, agent, procedure) can be added by:
  1. Defining a tag convention
  2. Adding a parser function
  3. Registering it in the scanner
- The registry schema is open-ended — new fields on pages are preserved in the registry even if not explicitly handled.

### NFR-3: Resilience

- Invalid pages are skipped with warnings, not errors.
- The registry always has a valid state (even if empty).
- If a tool page references an MCP server that isn't connected, the tool is still registered but returns an error on invocation.

## User Stories

### US-1: User defines a custom tool as a Logseq page

**As** a power user,
**I want** to create a Logseq page that defines a new MCP tool,
**So that** Claude Code can discover and use it without any code changes.

### US-2: Agent discovers available capabilities

**As** Claude Code,
**I want** to search the registry for tools related to "deployment",
**So that** I can find and compose the right tools for the user's request.

### US-3: Skills become MCP tools automatically

**As** a user with existing skill definitions,
**I want** my skills to automatically appear as MCP tools,
**So that** Claude Code can invoke them directly without me re-defining them.

### US-4: User creates a prompt template

**As** a user who frequently asks similar questions,
**I want** to define prompt templates as Logseq pages,
**So that** Claude Code can use them with different arguments each time.

## Technical Considerations

### Registry Data Structure

```clojure
(def ^:dynamic *registry*
  (atom {:tools []       ;; from logseq-ai-hub-tool pages
         :skills []      ;; from Skills/* pages
         :prompts []     ;; from logseq-ai-hub-prompt pages
         :agents []      ;; from logseq-ai-hub-agent pages
         :procedures []  ;; from logseq-ai-hub-procedure pages
         :last-scan nil  ;; ISO timestamp
         :scan-count 0}))
```

### Skill-to-Tool Wrapping

A skill `Skills/summarize` with `skill-inputs:: query` and `skill-description:: Summarizes text` becomes:

```json
{
  "name": "skill_summarize",
  "description": "Summarizes text",
  "inputSchema": {
    "type": "object",
    "properties": {"query": {"type": "string"}},
    "required": ["query"]
  }
}
```

Invoking this tool creates a job instance, runs the skill, and returns the result.

### Integration with Existing Sub-Agent System

The existing `sub_agents.cljs` already scans for `logseq-ai-hub-agent` tagged pages. The registry scanner should delegate to or wrap this existing scan rather than duplicating it. The registry becomes the single source of truth that all other systems query.

## Out of Scope

- Version control for tool/skill definitions (no diff tracking for v1).
- Tool marketplace or sharing (definitions are graph-local).
- Tool permissions (all tools are available to all authenticated clients).
- Composite tool definitions (tools that chain other tools — use skills for this).
- Schema evolution (no migration logic for changing tool page properties).

## Open Questions

1. **Page prefix requirement:** Should tool pages be required to live under `Tools/` or can they be anywhere with the right tag? Recommendation: tag-based discovery (any page) for flexibility, with `Tools/` as the conventional prefix.

2. **Schema validation depth:** Should the `tool-input-schema` JSON Schema be validated for correctness or just stored as-is? Recommendation: validate basic JSON Schema structure (type, properties exist) but don't run a full JSON Schema meta-validator.

3. **Skill wrapping opt-out:** Should there be a way to prevent a skill from being auto-exposed as an MCP tool? Recommendation: add optional `skill-mcp-expose:: false` property.
