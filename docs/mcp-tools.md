# MCP Tools Reference

## Overview

Logseq AI Hub exposes tools, resources, and prompt templates via the Model Context Protocol (MCP). Connect any MCP-compatible client (e.g., Claude Desktop) to access these capabilities. The server registers static tools from TypeScript files in `server/src/services/mcp/`, and can also register dynamic tools/prompts/resources at runtime from the Logseq knowledge base registry via `DynamicRegistry`.

**Source**: `server/src/services/mcp/`

---

## Tools

### Graph Tools
_Source: `graph-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `graph_query` | Run a Datalog query against the Logseq graph | `query` (string) |
| `graph_search` | Full-text search across all Logseq pages | `query` (string), `limit` (number, opt, default 50) |
| `page_read` | Read the full content of a Logseq page | `name` (string) |
| `page_create` | Create a new Logseq page with optional content | `name` (string), `content` (string, opt), `properties` (Record<string,string>, opt) |
| `page_list` | List Logseq pages matching a pattern | `pattern` (string, opt), `limit` (number, opt, default 100) |
| `block_append` | Append a block to a Logseq page | `page` (string), `content` (string), `properties` (Record<string,string>, opt) |
| `block_update` | Update an existing block's content | `uuid` (string), `content` (string) |

### Job Tools
_Source: `job-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `job_create` | Create a new job in the Logseq job runner | `name` (string), `type` (enum: one-time\|scheduled\|triggered), `skill` (string, opt), `priority` (number 1-5, opt), `schedule` (string, opt), `input` (Record, opt) |
| `job_list` | List jobs with optional filters | `status` (string, opt), `limit` (number, opt), `offset` (number, opt) |
| `job_get` | Get detailed information about a specific job | `jobId` (string) |
| `job_start` | Start or enqueue a job for execution | `jobId` (string) |
| `job_cancel` | Cancel a running or queued job | `jobId` (string) |
| `job_pause` | Pause a running job | `jobId` (string) |
| `job_resume` | Resume a paused job | `jobId` (string) |
| `skill_list` | List all available skills in the Logseq job runner | _(none)_ |
| `skill_get` | Get detailed information about a specific skill | `skillId` (string) |
| `skill_create` | Create a new skill definition in the Logseq job runner | `name` (string), `type` (string), `description` (string), `inputs` (string[], opt), `outputs` (string[], opt) |

### Memory Tools
_Source: `memory-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `memory_store` | Store a memory with a tag in the Logseq AI memory system | `tag` (string), `content` (string) |
| `memory_recall` | Recall all memories stored under a specific tag | `tag` (string) |
| `memory_search` | Full-text search across all memories | `query` (string) |
| `memory_list_tags` | List all memory tags/categories | _(none)_ |

### Messaging Tools
_Source: `messaging-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `message_send` | Send a message via WhatsApp or Telegram | `platform` (enum: whatsapp\|telegram), `recipient` (string), `content` (string) |
| `message_list` | Query message history from the database | `contact_id` (string, opt), `limit` (number, opt), `since` (string ISO, opt) |
| `contact_list` | List known contacts | `platform` (string, opt) |

### Approval Tools
_Source: `approval-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `ask_human` | Send a message to a human contact and wait for their reply (approval/rejection). Blocks until a response is received or timeout expires. | `contact` (string), `question` (string), `options` (string[], opt), `timeout_seconds` (number, opt, default 300, max 3600) |

### Registry Tools
_Source: `registry-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `registry_list` | List all registered tools, prompts, procedures, agents, and skills from the knowledge base | `type` (enum: tool\|prompt\|procedure\|agent\|skill, opt) |
| `registry_search` | Search the knowledge base registry by keyword across names and descriptions | `query` (string), `type` (enum: tool\|prompt\|procedure\|agent\|skill, opt) |
| `registry_get` | Get full details of a specific registered item from the knowledge base | `name` (string), `type` (enum: tool\|prompt\|procedure\|agent\|skill) |
| `registry_refresh` | Trigger a full registry rescan of the Logseq knowledge base | _(none)_ |

### Session Tools
_Source: `session-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `session_create` | Create a new named agent session | `name` (string, opt), `context` (object with `focus`, `relevant_pages`, `working_memory`, opt) |
| `session_get` | Retrieve a session by ID along with its recent messages | `sessionId` (string), `messageLimit` (number, opt) |
| `session_list` | List sessions for the current agent, optionally filtered by status | `status` (enum: active\|paused\|archived, opt), `limit` (number, opt) |
| `session_update_context` | Deep-merge context updates into an existing session's context | `sessionId` (string), `context` (object with `focus`, `relevant_pages`, `working_memory`, `preferences`) |
| `session_set_focus` | Set the current focus string for a session | `sessionId` (string), `focus` (string) |
| `session_add_memory` | Add or update a key-value entry in the session's working memory | `sessionId` (string), `key` (string), `value` (string) |
| `session_archive` | Archive a session, removing it from active listings | `sessionId` (string) |

### Character Tools
_Source: `character-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `character_list` | List all characters | _(none)_ |
| `character_get` | Get a character by ID or name | `id` (string) |
| `character_create` | Create a new character | `name` (string), `persona` (string, opt), `system_prompt` (string, opt), `model` (string, opt), `skills` (string[], opt), `metadata` (Record, opt) |
| `character_update` | Update a character's properties | `id` (string), `name` (string, opt), `persona` (string, nullable, opt), `system_prompt` (string, nullable, opt), `model` (string, nullable, opt), `skills` (string[], opt), `metadata` (Record, opt) |
| `character_delete` | Delete a character | `id` (string) |
| `character_memory_store` | Store a memory scoped to a specific character | `id` (string), `content` (string) |
| `character_memory_recall` | Recall all memories for a specific character | `id` (string) |
| `character_react_to_event` | Have a character process and react to a hub event. Updates their session and state. | `id` (string), `eventType` (string), `payload` (Record, opt), `source` (string, opt), `sessionId` (string, opt) |
| `character_page_sync` | Sync a character's definition to its Logseq page | `id` (string) |
| `character_relationship_set` | Set or update a directed relationship between two characters. Strength ranges from -100 (hostile) to +100 (devoted). | `fromId` (string), `toId` (string), `type` (string), `strength` (number -100..100, opt), `notes` (string, nullable, opt) |
| `character_relationship_list` | List all relationships for a character (both outgoing and incoming) | `id` (string) |
| `character_relationship_delete` | Delete a directed relationship between two characters | `fromId` (string), `toId` (string) |

### Character Session Tools
_Source: `character-session-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `character_session_list` | List sessions for a character (by character ID or name). Returns summaries with last message preview. | `characterId` (string) |
| `character_session_get` | Get a character session by ID (full messages) | `id` (string) |
| `character_session_delete` | Delete a character session by ID | `id` (string) |

### Project Tools
_Source: `project-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `project_list` | List all known code projects/repositories in the knowledge base | `status` (string, opt) |
| `project_get` | Get detailed information about a specific code project, including architecture context from the project page body | `name` (string) |

### ADR Tools
_Source: `adr-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `adr_list` | List architectural decision records (ADRs) for a project. Returns all ADRs with their status, date, and content sections. | `project` (string) |
| `adr_create` | Create a new Architectural Decision Record (ADR) for a project. Auto-numbers the ADR based on existing records. | `project` (string), `title` (string), `context` (string), `decision` (string), `consequences` (string), `status` (string, opt, default "accepted") |

### Lesson Tools
_Source: `lesson-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `lesson_store` | Store a lesson learned from a coding session into the knowledge base. Lessons are organized by project and category for future retrieval. | `project` (string), `category` (string), `title` (string), `content` (string) |
| `lesson_search` | Search past lessons learned across projects. Useful for finding relevant experience before starting new work. | `query` (string), `project` (string, opt), `category` (string, opt) |

### Safeguard Tools
_Source: `safeguard-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `safeguard_check` | Check if an operation is allowed under the project's safeguard policy. Returns allow, block, or approve-required with reason. | `project` (string), `operation` (string), `agent` (string), `details` (string) |
| `safeguard_request` | Request human approval for an operation that requires it. Sends approval request via the configured contact channel and waits for response. | `project` (string), `operation` (string), `agent` (string), `details` (string), `contact` (string, opt) |
| `safeguard_policy_get` | Get the active safeguard policy for a project. Shows the protection level, rules, and contact configuration. | `project` (string) |
| `safeguard_policy_update` | Update safeguard policy rules for a project. Changes to level 2+ policies require approval. | `project` (string), `rules` (string) |
| `safeguard_audit_log` | Retrieve the safeguard audit log for a project. Shows all recorded agent operations, approvals, and denials. | `project` (string), `since` (string, opt), `operation` (string, opt), `agent` (string, opt) |

### Work Tools
_Source: `work-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `work_claim` | Claim exclusive ownership of a file path for a session to prevent concurrent agent conflicts | `sessionId` (string), `path` (string), `description` (string) |
| `work_release` | Release a previously claimed file path, allowing other sessions to claim it | `sessionId` (string), `path` (string) |
| `work_list_claims` | List all active work claims across all sessions | _(none)_ |
| `work_log` | Log a work action for a project to the Logseq knowledge base | `project` (string), `action` (string), `details` (string) |

### Task Tools
_Source: `task-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `track_create` | Create a new work track for a project to organize related tasks | `project` (string), `trackId` (string), `description` (string), `type` (string, opt), `priority` (string, opt), `branch` (string, opt), `assignedAgent` (string, opt) |
| `track_list` | List all work tracks for a project, optionally filtered by status or type | `project` (string), `status` (string, opt), `type` (string, opt) |
| `track_update` | Update the status, priority, branch, or assigned agent for an existing track | `project` (string), `trackId` (string), `status` (string, opt), `priority` (string, opt), `branch` (string, opt), `assignedAgent` (string, opt) |
| `task_add` | Add a new task to an existing work track | `project` (string), `trackId` (string), `description` (string), `agent` (string, opt) |
| `task_update` | Update the status or assigned agent for a specific task within a track | `project` (string), `trackId` (string), `taskIndex` (number), `status` (enum: todo\|doing\|done, opt), `agent` (string, opt) |
| `task_list` | List all tasks within a specific work track, optionally filtered by status | `project` (string), `trackId` (string), `status` (string, opt) |
| `project_dashboard` | Get a high-level dashboard overview of all tracks and task progress for a project | `project` (string) |

### Pi.dev Tools
_Source: `pidev-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `pi_spawn` | Spawn a new pi.dev agent session for a project and task | `project` (string), `task` (string), `agentProfile` (string, opt), `model` (string, opt), `workingDir` (string, opt) |
| `pi_send` | Send a message to a running pi.dev agent session | `sessionId` (string), `message` (string), `steering` (string, opt) |
| `pi_status` | Get the status of a pi.dev agent session | `sessionId` (string) |
| `pi_stop` | Stop a running pi.dev agent session | `sessionId` (string) |
| `pi_list_sessions` | List all active pi.dev agent sessions | _(none)_ |
| `pi_agent_create` | Create a new pi.dev agent profile in the Logseq graph | `name` (string), `project` (string), `model` (string, opt), `description` (string, opt), `systemInstructions` (string, opt), `skills` (string, opt), `allowedTools` (string, opt), `restrictedOperations` (string, opt) |
| `pi_agent_list` | List pi.dev agent profiles, optionally filtered by project | `project` (string, opt) |
| `pi_agent_get` | Get details of a specific pi.dev agent profile | `name` (string) |
| `pi_agent_update` | Update properties of a pi.dev agent profile | `name` (string), `model` (string, opt), `description` (string, opt), `project` (string, opt) |

### Event Tools
_Source: `event-tools.ts`_

| Tool | Description | Parameters |
|------|-------------|------------|
| `hub_event_emit` | Emit a hub event (persisted and broadcast over SSE). Also triggers matching event subscriptions. | `type` (string), `payload` (Record, opt), `characterId` (string, nullable, opt), `source` (string, nullable, opt) |
| `hub_event_list` | List hub events with optional filters. Limit is clamped to server config. | `type` (string, opt), `characterId` (string, opt), `limit` (number, opt), `since` (string ISO, opt) |
| `event_subscription_list` | List event subscriptions with optional filters | `eventType` (string, opt), `characterId` (string, opt), `enabled` (boolean, opt) |
| `event_subscription_create` | Create an event subscription. When a matching hub event is emitted, a job is created. | `eventType` (string), `jobSkill` (string), `jobNamePrefix` (string), `characterId` (string, nullable, opt), `priority` (number 1-5, opt), `enabled` (boolean, opt) |
| `event_subscription_get` | Get an event subscription by ID | `id` (string) |
| `event_subscription_delete` | Delete an event subscription by ID | `id` (string) |

---

## Dynamic Tools (Knowledge Base Registry)

_Source: `dynamic-registry.ts`_

The `DynamicRegistry` class syncs tools, prompts, and resources from the Logseq knowledge base at runtime. When `registry_refresh` is called (or on server init), the registry fetches all entries of types `tool`, `skill`, `prompt`, and `procedure` from the plugin bridge.

### Dynamic Tool Registration

- **Custom tools** (type `tool`): Registered as `kb_{name}`. Parameters are derived from the entry's `input-schema` property (JSON Schema to Zod conversion). Execution is dispatched by handler type:
  - `skill` / `mcp-tool`: Delegates to `execute_skill` bridge operation
  - `http`: Makes an HTTP request to `tool-http-url`
  - `graph-query`: Runs a Datalog query with `{{variable}}` interpolation

- **Skills** (type `skill`): Registered as `skill_{name}`. Takes a generic `inputs` (Record<string,string>) parameter and delegates to `execute_skill`.

### Dynamic Prompt Registration

- **Prompts** (type `prompt`): Registered as `kb_{name}`. If the entry declares `arguments`, each becomes a named string parameter. The prompt's `system-section` and `user-section` templates are fetched and `{{variable}}` placeholders are interpolated.

### Dynamic Resource Registration

- **Procedures** (type `procedure`): Registered as resources at `logseq://procedures/{name}`. Fetches the full procedure entry via `registry_get` on access.

MCP list-changed notifications (`sendToolListChanged`, `sendPromptListChanged`, `sendResourceListChanged`) are sent to connected clients whenever new registrations are added during sync.

---

## Resources

_Source: `resources.ts`_

| URI Template | Description |
|--------------|-------------|
| `logseq://pages/{name}` | Content of a specific Logseq page |
| `logseq://jobs` | Current job statuses summary |
| `logseq://skills` | Available skill definitions |
| `logseq://memory/{tag}` | Memories stored under a specific tag |
| `logseq://projects/{name}` | Code project details including architecture context |
| `logseq://contacts` | Known contacts list (server-side, no bridge needed) |
| `logseq://characters` | All character definitions |
| `logseq://characters/{id}` | Single character by ID or name |
| `logseq://character-sessions/{characterId}` | Character conversation sessions by character ID or name |
| `logseq://projects/{name}/adrs` | Architecture Decision Records for a code project |
| `logseq://projects/{name}/lessons` | Lessons learned for a code project |
| `logseq://projects/{name}/tracks` | Tracks and tasks for a code project |
| `logseq://projects/{name}/safeguards` | Safeguard policies for a code project |

---

## Prompt Templates

_Source: `prompts.ts`_

| Name | Description | Parameters |
|------|-------------|------------|
| `summarize_page` | Summarize a Logseq page - reads the page and provides a concise summary | `page` (string) |
| `create_skill_from_description` | Generate a skill definition from a natural language description | `description` (string) |
| `analyze_knowledge_base` | Analyze the Logseq knowledge base for patterns and insights | `focus` (string, opt) |
| `draft_message` | Draft a message for a contact using knowledge base context | `contact` (string), `context` (string) |
| `code_review` | Perform a code review using project context, ADRs, and a diff | `project` (string), `diff` (string, opt), `focus` (string, opt) |
| `start_coding_session` | Initialize a coding session with full project context | `project` (string), `task` (string, opt) |
| `deployment_checklist` | Generate a deployment checklist with safeguard verification | `project` (string), `environment` (string, opt) |

---

## Totals

| Category | Count |
|----------|-------|
| **Static Tools** | |
| Graph Tools | 7 |
| Job Tools | 10 |
| Memory Tools | 4 |
| Messaging Tools | 3 |
| Approval Tools | 1 |
| Registry Tools | 4 |
| Session Tools | 7 |
| Character Tools | 12 |
| Character Session Tools | 3 |
| Project Tools | 2 |
| ADR Tools | 2 |
| Lesson Tools | 2 |
| Safeguard Tools | 5 |
| Work Tools | 4 |
| Task Tools | 7 |
| Pi.dev Tools | 9 |
| Event Tools | 6 |
| **Static Tools Total** | **88** |
| **Resources** | **13** |
| **Prompt Templates** | **7** |
| **Dynamic (runtime)** | **Varies by knowledge base content** |
