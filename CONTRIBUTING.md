# Contributing to Logseq AI Hub

Thank you for your interest in contributing! This guide covers everything you need to get started.

## Table of Contents

1. [Getting Started](#getting-started)
2. [Development Environment](#development-environment)
3. [Architecture Overview](#architecture-overview)
4. [Testing (TDD Workflow)](#testing-tdd-workflow)
5. [Branch Strategy](#branch-strategy)
6. [Commit Message Format](#commit-message-format)
7. [Adding New Bridge Operations](#adding-new-bridge-operations)
8. [Adding New MCP Tools](#adding-new-mcp-tools)
9. [Code Style](#code-style)
10. [Pull Requests](#pull-requests)

---

## Getting Started

### Clone the Repository

```bash
git clone https://github.com/Escherbridge/logseq-ai-hub.git
cd logseq-ai-hub
```

### Install Dependencies

Root (plugin/ClojureScript):

```bash
yarn install
```

Server:

```bash
cd server && bun install
```

### Set Up Environment

Copy the example environment file and fill in your values:

```bash
cp server/.env.example server/.env
```

Key environment variables:

| Variable | Description |
|---|---|
| `LLM_API_KEY` / `OPENROUTER_API_KEY` | API key for your LLM provider |
| `LLM_ENDPOINT` | LLM API base URL (default: `https://openrouter.ai/api/v1`) |
| `AGENT_MODEL` | Model identifier (default: `anthropic/claude-sonnet-4`) |

---

## Development Environment

### Plugin Development (ClojureScript)

Start the shadow-cljs watcher:

```bash
npx shadow-cljs watch app
```

Then load the built plugin as an unpacked plugin in Logseq:
1. Open Logseq settings
2. Navigate to Plugins
3. Enable developer mode
4. Load the `dist/` directory as an unpacked plugin

### Server Development

```bash
cd server && bun run dev
```

The server runs with hot-reload enabled.

### nREPL

An nREPL server is available for interactive ClojureScript development, configured in `shadow-cljs.edn`:

- **Port**: 8702
- Connect with your editor's Clojure plugin (e.g., Calva for VS Code, CIDER for Emacs)

---

## Architecture Overview

The plugin follows a two-process architecture: a ClojureScript plugin running inside Logseq and a Bun/TypeScript server handling agent sessions and external integrations.

### Plugin (ClojureScript)

**Location**: `src/main/logseq_ai_hub/`

The Logseq plugin logic written in ClojureScript. Key subsystems:

- `agent_bridge.cljs` — 52-operation bridge between plugin and server (SSE + HTTP callback pattern)
- `job_runner/` — Autonomous skill-based automation scheduler
- `mcp/` — Model Context Protocol client (protocol, transport, client, page reader, on-demand)
- `llm/` — Tool-use loop, argument parser, memory context injection
- `event_hub/` — Internal event bus for plugin-side coordination

### Server (Bun/TypeScript)

**Location**: `server/src/`

HTTP server managing agent sessions, MCP servers, and the bridge API. Key areas:

- `server/src/services/mcp/` — MCP tool category registrations
- `server/src/db/` — Persistence (character relationships, hub events, etc.)
- `server/src/routes/` — HTTP route handlers for bridge operations

### Agent Bridge

The bridge connects the Logseq plugin to the server using an SSE (Server-Sent Events) stream for server-to-plugin messages and HTTP callbacks for plugin-to-server responses. It currently exposes 52 named operations.

### Tests

- **CLJS tests**: `src/test/` — ClojureScript unit tests using cljs.test
- **Server tests**: `server/src/**/*.test.ts` — Bun test runner

---

## Testing (TDD Workflow)

This project follows a Red-Green-Refactor cycle:

1. **Red**: Write a failing test that specifies the desired behavior
2. **Green**: Write the minimum code to make the test pass
3. **Refactor**: Clean up the implementation while keeping tests green

### Running CLJS Tests

Compile and run in one step:

```bash
npm test
```

Or in two steps (useful during development to separate compile errors from test failures):

```bash
npm run test:compile
npm run test:run
```

### Running Server Tests

```bash
cd server && bun test
```

### Running All Tests

```bash
npm run test:all
```

### Test Count Expectations

| Suite | Expected Count |
|---|---|
| CLJS test namespaces | 62 |
| Server tests | 741+ |

If your changes reduce these counts without a deliberate removal, investigate before submitting.

---

## Branch Strategy

- **Main branch**: `main` — stable, always passing CI
- **Feature branches**: `track/<track-id>` — following the Conductor methodology
- Always branch from `main`:

```bash
git checkout main
git pull
git checkout -b track/<your-track-id>
```

---

## Commit Message Format

```
type(scope): description

Body (optional, wrap at 72 chars)
```

### Types

| Type | When to use |
|---|---|
| `feat` | New feature or capability |
| `fix` | Bug fix |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `test` | Adding or updating tests |
| `docs` | Documentation changes |
| `chore` | Build process, dependency updates, CI config |

### Scopes

Common scopes: `plugin`, `server`, `mcp`, `bridge`, `jobs`, `memory`, `events`, `llm`, `ci`

### Examples

```
feat(bridge): add fetch-block-content operation

Exposes block content retrieval via the agent bridge so server-side
agents can read arbitrary blocks by UUID.
```

```
fix(mcp): handle transport reconnect on SSE disconnect
```

```
test(jobs): add scheduler interval edge cases
```

---

## Adding New Bridge Operations

Bridge operations are defined in `src/main/logseq_ai_hub/agent_bridge.cljs` in the `operation-handlers` map.

### Plugin Side

Add an entry to `operation-handlers`:

```clojure
:your-operation-name
{:handler (fn [params callback]
            ;; params is a JS object from the server
            ;; call (callback result) when done
            (let [result (do-something params)]
              (callback result)))
 :description "Human-readable description of what this operation does"}
```

### Server Side

Register a corresponding route handler in `server/src/routes/` that sends the operation request over SSE and awaits the plugin callback.

### Guidelines

- Keep handler functions pure where possible; side effects should be minimal and obvious
- Add a test in `src/test/logseq_ai_hub/` covering the new operation
- Update the operation count comment if one exists in the file

---

## Adding New MCP Tools

MCP tools are registered in `server/src/services/mcp/` grouped by category.

### Registration Pattern

In a `*-tools.ts` file:

```typescript
server.tool(
  "tool-name",
  {
    description: "What this tool does",
    schema: z.object({
      param: z.string().describe("Parameter description"),
    }),
  },
  async (args) => {
    // implementation
    return { result: "..." };
  }
);
```

### Guidelines

- Group related tools in a single `*-tools.ts` category file
- Always provide a clear `description` — this is what the LLM sees when deciding which tool to call
- Use Zod schemas for input validation
- Add a corresponding test in `server/src/**/*.test.ts`

---

## Code Style

### ClojureScript

- **Naming**: kebab-case for vars and functions (`my-function`, `process-block`)
- **Dynamic vars**: declare with `^:dynamic` metadata; use `set!` for mutation in async contexts (not `with-redefs`, which has lexical scope and breaks Promises)
- **Error handling**: use `make-error` from `util.errors` — requires 2-3 args: `(make-error :type "message")` or `(make-error :type "message" {:data ...})`
- **Conditionals**: prefer `when`/`when-not` over `if` when there is no else branch; be careful with `if-not` as the else branch nests one level deeper

### TypeScript

- **Naming**: camelCase for variables and functions, PascalCase for types and classes
- **Types**: strict TypeScript — avoid `any`, use explicit return types on exported functions
- **Testing**: use the Bun test runner (`import { test, expect } from "bun:test"`)
- **Imports**: prefer named imports over namespace imports

---

## Pull Requests

Before opening a PR:

1. Ensure all tests pass locally (`npm run test:all`)
2. Confirm your branch is up to date with `main`
3. Check that the CLJS test count has not unexpectedly decreased
4. Run `npx shadow-cljs compile app` to catch any compilation errors in the plugin build

In your PR description:

- **Reference the Conductor track** if this work is part of a tracked feature (`track/<track-id>`)
- **Describe what changed and why** — not just what the code does
- **Include test coverage** for any new features or bug fixes
- **List any environment variable additions** so reviewers know to update `.env.example`

PRs are merged by squash-merge to keep the main branch history clean.
