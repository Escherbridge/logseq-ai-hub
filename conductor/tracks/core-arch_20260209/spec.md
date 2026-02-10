# Specification: Core Plugin Architecture - Restructure and Extend

## Overview

Transform the existing `cljs-playground` Logseq plugin prototype into a production-ready modular architecture under the `logseq-ai-hub` namespace. This track establishes the foundational structure, naming, build configuration, error handling patterns, and testing infrastructure that all future tracks will build upon.

## Background

The current codebase is a working Logseq plugin with two ClojureScript files (`cljs-playground.core` and `cljs-playground.agent`) that implement a `/LLM` slash command dispatching to registered model handlers (echo, reverse, OpenAI). The architecture works but uses a prototype namespace, lacks modularity for planned features (messaging, memory, tasks), has no test infrastructure, and has no consistent error handling.

The product vision (see `conductor/product.md`) calls for messaging integration, AI memory, and task orchestration modules. This track creates the skeletal structure for those modules without implementing their logic, ensuring a clean foundation for parallel development.

## Functional Requirements

### FR-1: Namespace Migration
**Description:** Rename all ClojureScript namespaces from `cljs-playground.*` to `logseq-ai-hub.*`. Move source files from `src/main/cljs_playground/` to `src/main/logseq_ai_hub/`.

**Acceptance Criteria:**
- All source files reside under `src/main/logseq_ai_hub/`
- All `ns` declarations use `logseq-ai-hub.*` prefix
- All internal `:require` references updated to new namespaces
- The old `src/main/cljs_playground/` directory is deleted
- `shadow-cljs.edn` points to `logseq-ai-hub.core/init` as the entry point
- Plugin loads in Logseq without errors after migration

**Priority:** P0 (blocking)

### FR-2: Package Identity Update
**Description:** Update `package.json` to reflect the new project name while preserving the existing plugin ID for install compatibility.

**Acceptance Criteria:**
- `package.json` `"name"` field is `"logseq-ai-hub"`
- `package.json` `"logseq"."id"` remains `"_byud67luv"`
- `yarn watch` and `yarn release` scripts continue to function
- A `test` script is added to `package.json` for running the node-test build

**Priority:** P0 (blocking)

### FR-3: Core Module Refactor
**Description:** Refactor `logseq-ai-hub.core` as the clean plugin entry point handling initialization, settings registration, and slash command registration.

**Acceptance Criteria:**
- `logseq-ai-hub.core/init` is the single entry point called by shadow-cljs
- `logseq-ai-hub.core/main` registers settings schema and slash commands inside `logseq.ready`
- Settings schema is defined within `core.cljs` (or a dedicated settings namespace if warranted)
- The `/LLM` slash command continues to work identically to the current behavior

**Priority:** P0

### FR-4: Agent Module Refactor
**Description:** Refactor `logseq-ai-hub.agent` to cleanly separate the model registry, dispatch logic, built-in handlers, and OpenAI handler. Introduce consistent error return format.

**Acceptance Criteria:**
- Model registry (`models` atom, `register-model`, `get-model`) is preserved
- `process-input` dispatch function is preserved with identical behavior
- Built-in handlers (`echo-handler`, `reverse-handler`) are preserved
- `openai-handler` is preserved with identical API call logic
- All handlers return Promises consistently
- Error cases return a map-like structure `{:error true :message "..."}` wrapped in a Promise (or a string for backward compatibility -- see Technical Considerations)

**Priority:** P0

### FR-5: Expanded Settings Schema
**Description:** Extend the settings schema to include sections for messaging API configuration and memory configuration, preparing for future tracks.

**Acceptance Criteria:**
- Existing settings (`openAIKey`, `openAIEndpoint`, `chatModel`, `selectedModel`) are preserved with identical keys and defaults
- New settings group for messaging: `whatsappToken` (string, default ""), `telegramBotToken` (string, default ""), `webhookServerUrl` (string, default "")
- New settings group for memory: `memoryPagePrefix` (string, default "AI-Memory/"), `memoryEnabled` (boolean, default false)
- All new settings have descriptive titles and descriptions
- Settings render correctly in Logseq plugin settings panel

**Priority:** P1

### FR-6: Messaging Placeholder Module
**Description:** Create `logseq-ai-hub.messaging` with defined public API signatures and an atom-based state structure representing the intended data model for messaging integration.

**Acceptance Criteria:**
- Namespace `logseq-ai-hub.messaging` exists at `src/main/logseq_ai_hub/messaging.cljs`
- State atom `connections` defined with initial structure: `{:whatsapp {:status :disconnected :config {}} :telegram {:status :disconnected :config {}}}`
- Public API functions defined (stub implementations that return descriptive "not implemented" values):
  - `(init-connections! settings)` -- Initialize messaging connections from plugin settings
  - `(handle-incoming-message platform message)` -- Process an incoming message from a platform
  - `(send-message platform recipient content)` -- Send a message via a platform
  - `(connection-status platform)` -- Return the connection status for a platform
- Each function has a docstring describing its intended behavior
- Each stub returns a resolved Promise with `{:status :not-implemented :fn "<fn-name>"}`

**Priority:** P1

### FR-7: Memory Placeholder Module
**Description:** Create `logseq-ai-hub.memory` with defined public API signatures and an atom-based state structure for AI memory storage and retrieval.

**Acceptance Criteria:**
- Namespace `logseq-ai-hub.memory` exists at `src/main/logseq_ai_hub/memory.cljs`
- State atom `memory-index` defined with initial structure: `{:entries [] :config {:page-prefix "AI-Memory/" :enabled false}}`
- Public API functions defined (stub implementations):
  - `(init-memory! settings)` -- Initialize memory subsystem from plugin settings
  - `(store-memory! content tags)` -- Store a memory entry with tags
  - `(retrieve-memories query)` -- Retrieve memories matching a query
  - `(clear-memories!)` -- Clear all stored memories
- Each function has a docstring and returns a resolved Promise with `{:status :not-implemented :fn "<fn-name>"}`

**Priority:** P1

### FR-8: Tasks Placeholder Module
**Description:** Create `logseq-ai-hub.tasks` with defined public API signatures and an atom-based state structure for task orchestration.

**Acceptance Criteria:**
- Namespace `logseq-ai-hub.tasks` exists at `src/main/logseq_ai_hub/tasks.cljs`
- State atom `task-registry` defined with initial structure: `{:tasks {} :running #{} :config {:max-concurrent 5}}`
- Public API functions defined (stub implementations):
  - `(register-task! task-id task-def)` -- Register a named task definition
  - `(run-task! task-id params)` -- Execute a registered task with parameters
  - `(cancel-task! task-id)` -- Cancel a running task
  - `(task-status task-id)` -- Return the status of a task
  - `(list-tasks)` -- Return all registered tasks
- Each function has a docstring and returns a resolved Promise with `{:status :not-implemented :fn "<fn-name>"}`

**Priority:** P1

### FR-9: Error Handling Foundation
**Description:** Establish a consistent error handling pattern used across all modules.

**Acceptance Criteria:**
- A utility namespace `logseq-ai-hub.util.errors` exists
- Provides `(make-error code message)` that returns `{:error true :code code :message message}`
- Provides `(make-error-promise code message)` that returns a `js/Promise.resolve` wrapping the error map
- Provides `(error? result)` predicate that checks for the `:error` key
- Provides `(wrap-promise-errors promise-fn)` -- a higher-order function that wraps a promise-returning function with `.catch` handling that converts JS errors to the standard error map format
- The agent module uses `wrap-promise-errors` for the OpenAI handler
- Error maps are logged via `js/console.error` at the point of creation

**Priority:** P1

### FR-10: Testing Infrastructure
**Description:** Set up cljs.test with a shadow-cljs `:node-test` build target and write initial tests for the agent module.

**Acceptance Criteria:**
- `shadow-cljs.edn` has a `:node-test` build with `{:target :node-test :output-to "out/test/node-tests.js" :ns-regexp ".*-test$"}`
- `package.json` has a `"test"` script: `"npx shadow-cljs compile node-test && node out/test/node-tests.js"`
- Test file `src/test/logseq_ai_hub/agent_test.cljs` exists with namespace `logseq-ai-hub.agent-test`
- Tests cover:
  - `register-model` adds a handler to the registry
  - `get-model` retrieves a registered handler
  - `get-model` returns nil for unregistered model
  - `process-input` dispatches to the correct handler
  - `process-input` falls back to `default-handler` for unknown model
  - `echo-handler` returns expected format
  - `reverse-handler` returns expected format
- All tests pass via `yarn test` (or equivalent npm script)

**Priority:** P0

## Non-Functional Requirements

### NFR-1: Build Integrity
- `yarn watch` starts the dev build without errors
- `yarn release` produces a production build without errors
- The compiled `main.js` output works as a Logseq plugin
- Build time does not regress significantly (baseline: current build)

### NFR-2: Hot-Reload Compatibility
- All stateful atoms use `defonce` to survive shadow-cljs hot-reload
- The `init` / `main` split pattern is maintained for hot-reload safety

### NFR-3: Code Style Compliance
- All code follows `conductor/code_styleguides/clojurescript.md`
- Namespace sections use comment banners
- Public functions have docstrings
- JS interop is isolated at module boundaries

### NFR-4: Backward Compatibility
- The `/LLM` slash command behaves identically to the pre-migration version
- All existing settings keys are preserved with the same defaults
- The plugin ID `_byud67luv` is unchanged

## User Stories

### US-1: Developer sets up the project
**As** a developer cloning the repository,
**I want** the build to work with `yarn install && yarn watch`,
**So that** I can start developing immediately.

**Scenarios:**
- **Given** a fresh clone of the repository, **When** I run `yarn install && yarn watch`, **Then** the shadow-cljs build starts without errors and produces `main.js`.
- **Given** the dev build is running, **When** I open Logseq with the plugin loaded, **Then** "Loaded Logseq LLM Plugin" (or updated log message) appears in the console.

### US-2: User triggers the LLM command
**As** a Logseq user with the plugin installed,
**I want** the `/LLM` command to work after the architecture restructure,
**So that** my existing workflow is not broken.

**Scenarios:**
- **Given** the plugin is loaded and `selectedModel` is `"mock-model"`, **When** I type `/LLM` in a block with text "hello", **Then** a child block is inserted echoing "hello".
- **Given** `selectedModel` is `"openai-model"` and a valid API key is configured, **When** I type `/LLM`, **Then** the OpenAI API is called and the response is inserted.

### US-3: Developer runs tests
**As** a developer,
**I want** to run `yarn test` and see test results,
**So that** I can verify correctness before committing.

**Scenarios:**
- **Given** the test infrastructure is set up, **When** I run `yarn test`, **Then** the node-test build compiles and all agent tests pass with output shown in terminal.

### US-4: Developer explores new module structure
**As** a developer starting work on the messaging track,
**I want** to see a well-defined public API with docstrings in `logseq-ai-hub.messaging`,
**So that** I understand the intended interface before implementing.

**Scenarios:**
- **Given** the placeholder module exists, **When** I open `messaging.cljs`, **Then** I see function signatures with docstrings and a state atom showing the data model.
- **Given** I call any stub function, **When** I await the returned Promise, **Then** I get `{:status :not-implemented :fn "<fn-name>"}`.

## Technical Considerations

### Namespace to Directory Mapping
ClojureScript requires namespace `logseq-ai-hub.core` to map to file path `logseq_ai_hub/core.cljs` (hyphens become underscores in directories). This is already the convention in the tech-stack document.

### Error Format Compatibility
The current codebase returns plain strings from handlers (including error messages with emoji prefixes). The new error handling foundation introduces structured error maps. During this track, the `core.cljs` handler should check for both formats when deciding what to insert as a block -- if the result is a map with `:error true`, format it as a user-friendly string before inserting. This maintains backward compatibility while enabling structured error handling internally.

### Testing with JS Interop
Agent tests for pure functions (`register-model`, `get-model`, `echo-handler`, `reverse-handler`) can run directly in Node.js. The `openai-handler` depends on `js/logseq.settings` and `js/fetch`, which are not available in Node. OpenAI handler tests should be deferred to a future track or use mocking. This track focuses on testing pure logic only.

### shadow-cljs Output Directory
The current config outputs to `"./"` (project root). This is a Logseq plugin convention -- the `main.js` file must be in the root alongside `package.json`. This must be preserved. The test output goes to `out/test/` which is separate.

### defonce for Registry State
The `models` atom in the agent module must remain `defonce` so that hot-reload does not reset registered models. All new state atoms in placeholder modules should also use `defonce`.

## Out of Scope

- Implementing actual messaging integration logic (WhatsApp, Telegram)
- Implementing actual memory storage/retrieval logic
- Implementing actual task orchestration logic
- Webhook server (Node target) setup -- separate track
- CI/CD pipeline configuration
- Logseq Marketplace publishing
- Additional slash commands beyond `/LLM`
- UI components or toolbar buttons
- Core.async integration

## Open Questions

1. **Log message update**: Should the console log message change from "Loaded Logseq LLM Plugin" to "Loaded Logseq AI Hub Plugin" or similar? (Recommendation: yes, update it.)
2. **Settings grouping**: Logseq plugin settings do not support visual grouping/sections natively. Should we use key prefixes (e.g., `messaging_whatsappToken`) or keep flat keys? (Recommendation: use prefixes for logical grouping, flat keys for Logseq compatibility.)
3. **Error handling in stubs**: Should placeholder module stubs log a console warning when called, or remain silent? (Recommendation: log a `js/console.warn` so developers notice unimplemented calls during integration testing.)
