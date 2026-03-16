# Agent Bridge Operations Reference

## Overview

The Agent Bridge connects the Logseq plugin to the AI Hub server via SSE (Server-Sent Events) streaming and HTTP callbacks. The plugin registers operation handlers that the server can invoke remotely. Each operation is dispatched by the `dispatch-agent-request` function in `src/main/logseq_ai_hub/agent_bridge.cljs`, which looks up the handler in the `operation-handlers` map, invokes it with the provided params, and sends the result back to the server via a POST to `/api/agent/callback`.

**Source**: `src/main/logseq_ai_hub/agent_bridge.cljs`

---

## Operations

### Job Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `create_job` | Create a new job page and enqueue it for execution | `name` (string), `type` (string), `priority` (number, default 3), `schedule` (string, opt), `skill` (string, opt), `input` (object, opt) | `{jobId, name, status: "queued"}` |
| `list_jobs` | List job pages, optionally filtered by status | `status` (string, opt), `limit` (number, default 50), `offset` (number, default 0) | `{jobs: [{jobId, name, status, type, priority, createdAt}], total}` |
| `get_job` | Get detailed information about a specific job | `jobId` (string) | `{jobId, name, status, type, priority, skill, input, schedule, createdAt, startedAt, completedAt, result, error}` |
| `start_job` | Enqueue a job for execution | `jobId` (string) | `{jobId, status: "queued"}` |
| `cancel_job` | Cancel a running or queued job | `jobId` (string) | `{jobId, status: "cancelled"}` |
| `pause_job` | Pause a running job | `jobId` (string) | `{jobId, status: "paused"}` |
| `resume_job` | Resume a paused job | `jobId` (string) | `{jobId, status: "queued"}` |

### Skill Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `list_skills` | List all skill pages under the Skills/ prefix | _(none)_ | `{skills: [{skillId, name, type, description, inputs, outputs, tags}]}` |
| `get_skill` | Get detailed information about a specific skill | `skillId` (string) | `{skillId, name, type, description, inputs, outputs, tags, steps, version}` |
| `create_skill` | Create a new skill definition page | `name` (string), `type` (string), `description` (string), `inputs` (string[], opt), `outputs` (string[], opt) | `{skillId, name}` |

### MCP Client Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `list_mcp_servers` | List configured MCP server connections | _(none)_ | `{servers: [{id, url, status}]}` |
| `list_mcp_tools` | List tools available on a specific MCP server | `serverId` (string) | `{tools: [{name, description, inputSchema}]}` |
| `list_mcp_resources` | List MCP resources (stub, not fully implemented) | _(none)_ | `{resources: []}` |

### Secrets Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `list_secret_keys` | List all stored secret key names | _(none)_ | `{keys: [string]}` |
| `set_secret` | Store a secret key-value pair | `key` (string), `value` (string) | `{success: true, key}` |
| `remove_secret` | Remove a stored secret | `key` (string) | `{success: true, key}` |

### Graph Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `graph_query` | Run a Datalog query against the Logseq graph | `query` (string) | `{results: [...]}` |
| `graph_search` | Full-text search across Logseq pages | `query` (string), `limit` (number, default 50) | `{results: [...], total}` |
| `page_read` | Read the full block tree of a Logseq page | `name` (string) | `{page, blocks: [...]}` |
| `page_create` | Create a new Logseq page with optional content and properties | `name` (string), `content` (string, opt), `properties` (object, opt) | `{page, created: true}` |
| `page_list` | List pages matching a substring pattern | `pattern` (string, opt), `limit` (number, default 100) | `{pages: [{name, originalName}], total}` |
| `block_append` | Append a block to a Logseq page | `page` (string), `content` (string), `properties` (object, opt) | `{page, blockUuid}` |
| `block_update` | Update an existing block's content by UUID | `uuid` (string), `content` (string) | `{uuid, updated: true}` |

### Memory Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `store_memory` | Store a memory block under an AI-Memory tag page | `tag` (string), `content` (string) | `{tag, stored: true, blockUuid}` |
| `recall_memory` | Recall all memory blocks stored under a specific tag | `tag` (string) | `{tag, memories: [{content, uuid}], count}` |
| `search_memory` | Full-text search across all memory pages | `query` (string) | `{query, results: [{content, page}], count}` |
| `list_memory_tags` | List all AI-Memory tag pages | _(none)_ | `{tags: [{tag, page}], count}` |

### Registry Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `registry_list` | List all registry entries, optionally filtered by type | `type` (string: "tool"\|"prompt"\|"procedure"\|"agent"\|"skill", opt) | `{entries: [...], count, version}` |
| `registry_get` | Get a single registry entry by name and type | `name` (string), `type` (string) | Full entry details (minus :type key) |
| `registry_search` | Search registry entries by keyword | `query` (string), `type` (string, opt) | `{entries: [{id, type, name, description}], count}` |
| `registry_refresh` | Trigger a full registry rescan of all graph pages | _(none)_ | Counts of discovered items by type |
| `execute_skill` | Execute a skill by ID with given inputs | `skillId` (string), `inputs` (object, opt) | Skill execution result |

### Project Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `project_list` | List all code project entries, optionally filtered by status | `status` (string: "active"\|"archived", opt) | `{projects: [{name, repo, localPath, branchMain, techStack, description, status}], count}` |
| `project_get` | Get a single project by name, including page body context | `name` (string) | `{name, repo, localPath, branchMain, techStack, description, status, body}` |

### ADR Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `adr_list` | List ADRs for a project, with status, date, and sections | `project` (string) | `{adrs: [{title, project, status, date, sections: {context, decision, consequences}}], count}` |
| `adr_create` | Create a new auto-numbered ADR page with Context/Decision/Consequences sections | `project` (string), `title` (string), `context` (string), `decision` (string), `consequences` (string), `status` (string, default "proposed") | `{page, adrNumber, title}` |

### Lesson Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `lesson_store` | Store a lesson learned as a page under AI-Memory/lessons | `project` (string), `category` (string), `title` (string), `content` (string) | `{page, project, category, title, stored: true}` |
| `lesson_search` | Search stored lessons by query with optional project/category filter | `query` (string), `project` (string, opt), `category` (string, opt) | `{results: [{page, project, category, title, content, date}], count, query}` |

### Safeguard Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `safeguard_policy_get` | Get the active safeguard policy for a project (or default) | `project` (string) | `{name, project, level, levelName, rules: [{action, description, pattern}], ...}` |
| `safeguard_audit_append` | Append a timestamped audit entry to a project's safeguard log page | `project` (string), `operation` (string), `agent` (string), `action` (string), `details` (string) | `{logged: true, page}` |

### Work Log Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `work_log` | Append a timestamped work log entry to a project's log page | `project` (string), `action` (string), `details` (string, opt) | `{project, action, logged: true, page}` |

### Track/Task Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `track_create` | Create a track page at Projects/{project}/tracks/{trackId} | `project` (string), `trackId` (string), `description` (string, opt), `type` (string, opt), `priority` (string, opt), `branch` (string, opt), `assignedAgent` (string, opt) | `{project, trackId, page, status: "planned", created: true}` |
| `track_list` | List all tracks for a project with optional status/type filters | `project` (string), `status` (string, opt), `type` (string, opt) | `{tracks: [{page, trackId, status, type, priority, branch, assignedAgent, description}], count, project}` |
| `track_update` | Update properties (status, priority, branch, agent) on a track page | `project` (string), `trackId` (string), `status` (string, opt), `priority` (string, opt), `branch` (string, opt), `assignedAgent` (string, opt) | `{project, trackId, updated: true}` |
| `task_add` | Append a TODO task block to a track page | `project` (string), `trackId` (string), `description` (string), `agent` (string, opt) | `{project, trackId, description, added: true}` |
| `task_update` | Update a task block's status or assigned agent by index | `project` (string), `trackId` (string), `taskIndex` (number, default 0), `status` (string, opt), `agent` (string, opt) | `{project, trackId, taskIndex, updated: true}` |
| `task_list` | List tasks on a track page, optionally filtered by status | `project` (string), `trackId` (string), `status` (string, opt) | `{tasks: [{index, status, description, content}], count}` |
| `project_dashboard` | Get a summary dashboard of all tracks and task progress for a project | `project` (string) | `{project, tracks: {total, by-status}, tasks: {total, by-status}, completion-pct}` |

### Pi.dev Agent Profile Operations

| Operation | Description | Parameters | Returns |
|-----------|-------------|------------|---------|
| `pi_agent_list` | List all pi.dev agent profiles, optionally filtered by project | `project` (string, opt) | `{agents: [{page, name, model, project, description, system-instructions, skills, allowed-tools, restricted-operations}], count}` |
| `pi_agent_get` | Get a single pi.dev agent profile by name | `name` (string) | Full agent profile map with sections |
| `pi_agent_create` | Create a new pi.dev agent profile page with system instructions and sections | `name` (string), `project` (string, opt), `model` (string, opt), `description` (string, opt), `systemInstructions` (string, opt), `skills` (string, opt), `allowedTools` (string, opt), `restrictedOperations` (string, opt) | `{page, created: true}` |
| `pi_agent_update` | Update properties on an existing pi.dev agent profile page | `name` (string), `model` (string, opt), `description` (string, opt), `project` (string, opt) | `{name, updated: true}` |

---

## Totals

| Category | Count |
|----------|-------|
| Job Operations | 7 |
| Skill Operations | 3 |
| MCP Client Operations | 3 |
| Secrets Operations | 3 |
| Graph Operations | 7 |
| Memory Operations | 4 |
| Registry Operations | 5 |
| Project Operations | 2 |
| ADR Operations | 2 |
| Lesson Operations | 2 |
| Safeguard Operations | 2 |
| Work Log Operations | 1 |
| Track/Task Operations | 7 |
| Pi.dev Agent Profile Operations | 4 |
| **Total** | **52** |
