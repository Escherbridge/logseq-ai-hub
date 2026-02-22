# Plugin Modules

Detailed documentation for each plugin module. For architecture overview, see [Architecture](architecture.md).

## core.cljs — Entry Point

Initializes the plugin when Logseq loads. Registers the settings schema (server URL, API token, memory config, AI model selection) and the `/LLM` slash command. Runs settings migration (old `openAIKey`/`openAIEndpoint`/`chatModel` keys are automatically copied to the new `llmApiKey`/`llmEndpoint`/`llmModel` keys on first load). Calls `init!` on each module in order: messaging, memory, tasks, sub-agents, job-runner, agent-bridge.

## agent.cljs — LLM Model Registry

A pluggable model system. Models are registered by ID with a handler function that takes input text and returns a Promise of the response.

**Built-in models:**
- `mock-model` — echoes input back (for testing)
- `reverse-model` — reverses the input string
- `llm-model` — calls any OpenAI-compatible API (default: OpenRouter with `anthropic/claude-sonnet-4`)

**System prompt support:** `make-llm-handler` is a factory function that creates handlers with an optional system prompt. `process-with-system-prompt` dispatches input with a system prompt prepended as a `{:role "system"}` message. This is used by the sub-agent system.

**Slash command:** `/LLM` — processes the current block's text through the selected model and inserts the response as a child block.

## messaging.cljs — SSE Client + Message Ingestion

Connects to the webhook server via `EventSource` and streams incoming messages into Logseq pages. Includes automatic reconnection with exponential backoff.

**How it works:**
1. On `init!`, reads `webhookServerUrl` and `pluginApiToken` from settings
2. Opens an SSE connection to `<server>/events?token=<token>`
3. Listens for `new_message`, `message_sent`, and `agent_request` events
4. For each message, calls all registered handlers (default: `ingest-message!`)
5. On connection loss, automatically reconnects with exponential backoff (1s -> 2s -> 4s -> 8s -> 16s -> 30s cap)
6. Intentional disconnects (via `disconnect!`) suppress reconnection

**Message ingestion** creates a page named `AI Hub/<Platform>/<Contact Name>` and appends a block:
```
**John Doe** (WhatsApp) - 2026-02-11 14:30
Hello from WhatsApp!
platform:: whatsapp
sender:: whatsapp:15551234567
message-id:: 42
direction:: incoming
```

**Outgoing messages** are sent by POSTing to `/api/send` with Bearer auth.

## memory.cljs — AI Memory System

Stores and retrieves memories as Logseq pages under a configurable prefix (default: `AI-Memory/`).

**Slash commands (available when memory is enabled):**
- `/ai-memory:store` — stores the current block as a memory. First line = page name/tag, remaining lines = content. Single-line blocks default to the `inbox` tag. Creates page `AI-Memory/<tag>` and appends the content with a timestamp.
- `/ai-memory:recall` — recalls memories from a specific page. Block text = page name to recall from. Inserts all memories from that page as child blocks.
- `/ai-memory:search` — full-text search across all memory pages using Datalog queries. Inserts matching results as child blocks.
- `/ai-memory:list` — lists all memory pages as `[[page]]` links inserted as child blocks.

**Programmatic API:**
- `store-memory!` [tag content] — programmatic memory storage
- `retrieve-by-tag` [tag] — get all blocks from a specific tag page
- `retrieve-memories` [query-str] — full-text search across all memory pages
- `clear-memories!` — delete all memory pages

## tasks.cljs — Task Orchestration Engine

A pipeline system that chains actions together in response to triggers. Tasks are defined as a sequence of steps, where each step's output feeds into the next step's input.

**Step actions:**
| Action | Module | Input |
|--------|--------|-------|
| `:ai-process` | `agent/process-input` | content string |
| `:send-message` | `messaging/send-message!` | `{:platform :recipient :content}` |
| `:store-memory` | `memory/store-memory!` | `{:tag :content}` |
| `:logseq-ingest` | `messaging/ingest-message!` | message map |
| `:logseq-insert` | `logseq.Editor.insertBlock` | `{:block-uuid :content}` |

**Built-in task:** `message-to-logseq` — automatically ingests every incoming message into Logseq (trigger: `:on-new-message`)

**Slash commands:**
- `/task:list` — lists all registered tasks with their enabled/disabled status
- `/task:run <task-id>` — manually runs a task by ID

## sub_agents.cljs — Dynamic Sub-Agent System

Create AI agent characters as Logseq pages. Each agent page's content becomes the system prompt for an LLM model, and the agent gets its own slash command.

**Workflow:**
1. Write an agent name in a block and type `/new-agent`
2. A page is created with a default system prompt and `tags:: logseq-ai-hub-agent`
3. Edit the page to customize the system prompt
4. Invoke the agent with `/llm-<agent-name>` from any block

**Slash commands:**
- `/new-agent` — creates a new agent page
- `/refresh-agents` — rescans for agent pages and registers new commands
- `/llm-<agent-name>` — invokes a specific agent

**On plugin restart**, `init!` calls `refresh-agents!` to scan for existing agent pages and re-register their commands.

**Limitation:** Logseq's plugin API does not support unregistering slash commands. If an agent page is deleted, its `/llm-<name>` command persists for the session but shows an error when invoked.

## agent_bridge.cljs — Agent Request Dispatcher

Handles `agent_request` SSE events from the server, dispatching to the appropriate handler and sending results back via HTTP callback.

**Correlation IDs:** Each request carries a `traceId` (UUID). The bridge propagates this through all operations and includes it in the callback payload, enabling end-to-end tracing.

**Supported operations:** `create_job`, `list_jobs`, `get_job`, `start_job`, `cancel_job`, `pause_job`, `resume_job`, `list_skills`, `get_skill`, `create_skill`, `list_mcp_servers`, `list_mcp_tools`, `list_mcp_resources`

## settings_writer.cljs — Settings Write Queue

A Promise-chain queue that serializes all writes to `logseq.settings`, preventing concurrent write races.

**API:**
- `queue-settings-write!` [write-fn] — enqueues a write function, returns a Promise
- `reset-queue!` [] — resets the queue (for tests)
