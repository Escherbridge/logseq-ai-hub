# Logseq AI Hub

Transform Logseq from a personal knowledge management tool into a central orchestration layer for AI workflows, messaging integrations, and automated task management.

## The Problem

Knowledge workers face fragmented workflows:
- **Messages are siloed** — WhatsApp, Telegram, and other platforms aren't searchable alongside your notes
- **AI interactions are manual** — copy-pasting between AI tools and notes loses context
- **No automation layer** — Logseq has no native way to trigger actions, schedule tasks, or chain operations
- **Context is lost** — AI conversations and memories aren't persisted alongside related knowledge

## What Logseq AI Hub Does

Logseq AI Hub bridges the gap between your knowledge graph and the outside world. It brings messages, AI agents, and automation directly into Logseq — so your notes become a living, active system.

### Messaging Integration
Receive WhatsApp and Telegram messages in real-time as Logseq pages. Each contact gets their own conversation page (`AI Hub/WhatsApp/John Doe`) with full message history, metadata, and searchability across your entire graph.

### AI Memory
Persistent, tag-based memory stored as Logseq pages. Store knowledge snippets, recall by category, and search across all memories — giving your AI interactions long-term context that lives alongside your notes.

### Dynamic Sub-Agents
Create AI agent personas as Logseq pages. Write a system prompt on a page, tag it, and invoke it from any block with a custom slash command. Your meeting facilitator, code reviewer, or writing coach is one `/llm-<name>` away.

### Autonomous Job Runner
Define reusable **skills** as Logseq pages with sequential steps — query the graph, call an LLM, insert blocks, invoke MCP tools, and more. Create **jobs** that execute these skills on demand, on a cron schedule, or triggered by events. Full priority queue with dependency resolution, retry logic, and concurrent execution.

**11 step action types** including graph queries, LLM calls, block/page operations, MCP tool invocations, data transforms, conditional branching, and nested skill calls.

### MCP Client
Connect to external MCP servers (filesystem, web tools, databases) and use them as step actions in your automation skills. Supports both Streamable HTTP and SSE transports.

### Task Pipelines
Chain actions together in response to triggers: AI processing, message sending, memory storage, and graph operations — all wired through a composable pipeline system.

### Agent Chat API
Natural language interface for managing the entire system. Ask "Create a job that summarizes my notes every morning at 9am" and the agent interprets, executes, and confirms — powered by OpenRouter LLM with tool calling.

### OpenClaw Interoperability
Import and export skills as portable JSON for sharing across systems and communities.

## How It Works

The system has two parts:

1. **Plugin** (ClojureScript) — runs inside Logseq, receives real-time messages via SSE, writes to your graph, and runs the autonomous job execution engine
2. **Server** (Bun/TypeScript) — standalone webhook server that receives WhatsApp and Telegram messages, persists them in SQLite, provides a REST API, and bridges external requests to the plugin

The plugin and server communicate over SSE and HTTP callbacks, using the **Agent Bridge** pattern: external requests are relayed to the plugin (which has direct graph access), executed, and results sent back. This keeps your Logseq graph as the single source of truth.

```
WhatsApp/Telegram ──▶ Bun Server (SQLite, SSE) ──▶ Logseq Plugin ──▶ Your Graph
Claude Code/MCP   ──▶ REST API / Agent Bridge   ──▶ Job Runner    ──▶ Skills & Jobs
External MCP      ◀── Plugin MCP Client         ◀── Skill Steps   ◀── Automation
OpenRouter LLM    ◀── Agent / Sub-Agents        ◀── AI Processing ◀── Your Notes
```

## Quick Start

```shell
# Install dependencies
yarn                        # Plugin
cd server && bun install    # Server

# Configure
cp server/.env.example server/.env
# Edit server/.env — set PLUGIN_API_TOKEN to any secret string

# Run
cd server && bun run dev    # Start server
yarn watch                  # Build plugin (from root)
```

Then in Logseq: **Settings > Developer Mode > Plugins > Load unpacked plugin** and point to this folder. Set the server URL and API token in plugin settings.

For full setup instructions, see the [Development Guide](documentation/development-guide.md).

## Current Capabilities

| Capability | Status | Description |
|-----------|--------|------------|
| WhatsApp integration | Implemented | Real-time message ingestion via webhooks |
| Telegram integration | Implemented | Real-time message ingestion via webhooks |
| AI Memory | Implemented | Tag-based persistent memory as Logseq pages |
| Sub-Agents | Implemented | Page-based AI personas with custom system prompts |
| Job Runner | Implemented | Autonomous skill execution with 11 action types |
| Cron Scheduling | Implemented | 5-field cron with automatic instance creation |
| Priority Queue | Implemented | Priority levels, dependency resolution, concurrency limits |
| MCP Client | Implemented | Connect to external MCP servers for tool/resource access |
| Agent Chat | Implemented | Natural language job management via LLM tool calling |
| OpenClaw | Implemented | Import/export skills as portable JSON |
| Task Pipelines | Implemented | Chainable action sequences with triggers |
| REST API | Implemented | 21+ endpoints for remote system management |

## Roadmap

| Track | Priority | Description |
|-------|----------|------------|
| ~~Foundation Hardening~~ | ~~P0~~ | ~~Done — settings rename, router perf, correlation IDs~~ |
| Secrets Manager | P0 | Secure vault with `{{secret.KEY}}` interpolation |
| MCP Server | P0 | Expose full system as MCP server for Claude Code |
| Human-in-the-Loop | P0 | Agent-to-human approval via WhatsApp/Telegram |
| KB Tool Registry | P1 | Logseq pages as discoverable, callable tools |
| Agent Sessions | P1 | Persistent SQLite-backed conversation context |
| Code Repo Integration | P2 | ADRs, code review skills, deployment procedures |
| IoT/Infra Hooks | P2 | Generic webhook ingestion, alert routing |

See the full [Roadmap](documentation/roadmap.md) for details and dependency graph.

## Documentation

| Document | Description |
|----------|------------|
| [Architecture](documentation/architecture.md) | System design, data flows, design patterns |
| [API Reference](documentation/api-reference.md) | All server REST endpoints |
| [Development Guide](documentation/development-guide.md) | Setup, building, testing, deployment |
| [Configuration](documentation/configuration.md) | All server and plugin settings |
| [Plugin Modules](documentation/plugin-modules.md) | Detailed module documentation |
| [Job Runner](documentation/job-runner.md) | Skills, jobs, scheduling, MCP client |
| [Roadmap](documentation/roadmap.md) | Planned features and dependency graph |

## Test Suite

346 tests with 885+ assertions across plugin and server.

```shell
yarn test              # Plugin tests (ClojureScript)
cd server && bun test  # Server tests (TypeScript)
```

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Plugin | ClojureScript (shadow-cljs) |
| Server | Bun / TypeScript |
| Database | SQLite |
| LLM Provider | OpenRouter (provider-agnostic) |
| Default Model | anthropic/claude-sonnet-4 |
| Deployment | Railway (Docker) |
| Protocol | MCP (Model Context Protocol) |

## License

MIT
