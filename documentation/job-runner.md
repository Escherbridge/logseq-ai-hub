# Job Runner

The job runner is an autonomous skill-based automation engine. Skills are defined as Logseq pages and executed as sequential pipelines of steps. Each step performs an action and passes its output to the next.

## Key Concepts

- **Job** — a Logseq page under `Jobs/` with properties like status, skill reference, inputs, schedule, and priority
- **Skill** — a Logseq page under `Skills/` that defines a reusable pipeline of steps
- **Step** — a single action within a skill (e.g. query the graph, call an LLM, insert a block)

## How It Works

1. The **runner** maintains a priority queue and polls for jobs to execute
2. When a job is dequeued, it reads the linked skill definition from the graph
3. The **engine** executes the skill's steps sequentially, building a context of inputs, variables, and step results
4. The **executor** dispatches each step to the appropriate handler based on its action type
5. Results, logs, and status updates are written back to the job page in the graph

## Step Action Types

The executor supports 11 action types, registered at startup:

| Action | Description |
|--------|------------|
| `:graph-query` | Runs a Datalog query against the Logseq graph |
| `:llm-call` | Sends an interpolated prompt to the configured LLM model |
| `:block-insert` | Appends a block to a page |
| `:block-update` | Updates an existing block's content |
| `:page-create` | Creates a new Logseq page |
| `:mcp-tool` | Calls a tool on a connected MCP server |
| `:mcp-resource` | Reads a resource from an MCP server |
| `:transform` | Data transformations: `get-in`, `join`, `split`, `count`, `filter` |
| `:conditional` | Evaluates a condition and jumps to a target step |
| `:sub-skill` | Executes another skill as a nested call |
| `:legacy-task` | Runs a task from the legacy task system |

## Template Interpolation

Step prompts and configs support `{{variable}}` placeholders that resolve from the execution context:

| Placeholder | Resolves to |
|-------------|------------|
| `{{query}}` | Value of `"query"` in the job's `:inputs` |
| `{{step-1-result}}` | Output of step 1 |
| `{{today}}` | Today's date (ISO format) |
| `{{now}}` | Current ISO timestamp |
| `{{job-id}}` | The running job's ID |

## Cron Scheduling

Jobs with `job-type:: scheduled` and a `job-schedule` property are picked up by the scheduler. The scheduler runs a 60-second tick loop, parsing 5-field cron expressions:

```
┌─────── minute (0-59)
│ ┌───── hour (0-23)
│ │ ┌─── day of month (1-31)
│ │ │ ┌─ month (1-12)
│ │ │ │ ┌ day of week (0-6, Sun=0)
│ │ │ │ │
* * * * *
```

**Supported syntax:** `*`, specific values (`30`), ranges (`1-5`), steps (`*/15`), range+step (`1-10/2`), and comma-separated lists (`1,15,30`).

When a cron expression matches, the scheduler creates a new job instance page (e.g. `Jobs/daily-summary-20260219-0930`) and enqueues it.

## Priority Queue

Jobs are prioritized by their `job-priority` property (1=highest, 5=lowest, default=3). The queue also handles:

- **Dependency resolution** — `job-depends-on` property lists other job IDs that must complete first
- **Concurrency limits** — configurable max concurrent jobs (default: 3)
- **Deduplication** — prevents the same job from being queued twice

## MCP Client

A full Model Context Protocol client with two transport options:

- **Streamable HTTP** (default) — stateless POST-based transport with automatic Content-Type detection
- **SSE** — persistent EventSource connection with endpoint discovery and request-response correlation

The client manages multiple MCP server connections and exposes:
- `connect-server!` / `disconnect-server!` — server lifecycle
- `call-tool` — invoke a tool on a connected server
- `list-tools` / `list-resources` — discover server capabilities
- `read-resource` — fetch a resource by URI

MCP servers are configured in plugin settings as a JSON array:
```json
[{"id": "filesystem", "url": "http://localhost:8080/mcp", "auth-token": "..."}]
```

## OpenClaw Interoperability

Skills can be imported from and exported to the OpenClaw JSON format for sharing across systems.

**Import:** Paste an OpenClaw JSON string into a block and run `/job:import-skill`. The JSON is validated (required fields: `name`, `type`, `steps`), converted to a Logseq skill page, and written to the graph.

**Export:** Navigate to a skill page and run `/job:export-skill`. The skill definition is read from the graph, converted to OpenClaw format, and inserted as a JSON code block.

## Slash Commands

| Command | Description |
|---------|------------|
| `/job:run` | Enqueue the job named in the current block |
| `/job:status` | Show runner status (queued/running/completed/failed counts) |
| `/job:cancel` | Cancel a running or queued job |
| `/job:pause` | Pause a running job |
| `/job:resume` | Resume a paused job |
| `/job:create` | Create a new job page with default properties |
| `/job:import-skill` | Import an OpenClaw skill from JSON in the block |
| `/job:export-skill` | Export the current skill page to OpenClaw JSON |
| `/job:mcp-servers` | List all connected MCP servers |
| `/job:mcp-tools` | List tools from an MCP server (block text = server ID) |
| `/job:mcp-resources` | List resources from an MCP server |
| `/job:schedules` | List all active cron schedules |

## Creating a Job

### 1. Create a Skill Page

Create `Skills/summarize` with properties:
```
skill-type:: llm-chain
skill-description:: Summarizes notes from the graph
skill-inputs:: query
skill-outputs:: summary
skill-version:: 1
```

Add steps as child blocks:
```
step-order:: 1
step-action:: graph-query
step-config:: {"query": "[:find (pull ?b [*]) :where [?b :block/content ?c] [(clojure.string/includes? ?c \"{{query}}\")]]"}
```
```
step-order:: 2
step-action:: llm-call
step-prompt-template:: Summarize the following notes:\n{{step-1-result}}
step-model:: llm-model
```

### 2. Create a Job Page

Create `Jobs/daily-summary` with properties:
```
job-type:: autonomous
job-status:: draft
job-priority:: 2
job-skill:: Skills/summarize
job-input:: {"query": "today's notes"}
job-max-retries:: 3
job-created-at:: 2026-02-19T10:00:00.000Z
```

### 3. Run It

Type the job name in a block and run `/job:run`, or set `job-type:: scheduled` with `job-schedule:: 0 9 * * *` for automatic daily execution at 9 AM.

## Usage Examples

### Scheduled Daily Summary
```
job-type:: scheduled
job-status:: draft
job-skill:: Skills/daily-summary
job-schedule:: 0 9 * * 1-5
job-created-at:: 2026-02-19T10:00:00.000Z
```
Runs at 9:00 AM Monday through Friday. Creates timestamped instance pages automatically.

### MCP Tool in a Skill Step
```
step-order:: 1
step-action:: mcp-tool
step-mcp-server:: filesystem
step-mcp-tool:: read_file
step-config:: {"path": "/tmp/notes.txt"}
```
