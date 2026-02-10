# Implementation Plan: Core Plugin Architecture - Restructure and Extend

## Overview

This plan restructures the Logseq plugin from the `cljs-playground` prototype namespace into a production-ready `logseq-ai-hub` modular architecture across 6 phases. Each phase ends with a verification checkpoint. All tasks follow the Red-Green-Refactor TDD cycle where applicable.

**Estimated total effort:** 8-12 hours across 25 tasks
**Branch:** `track/core-arch_20260209`

---

## Phase 1: Testing Infrastructure & Build Configuration

**Goal:** Establish the test runner and updated build config so that all subsequent phases can be developed test-first. This phase is done before the namespace rename to validate that the test infrastructure works with the current code.

### Tasks:

- [ ] **Task 1.1: Add node-test build target to shadow-cljs.edn**
  - Add `:node-test` build: `{:target :node-test :output-to "out/test/node-tests.js" :ns-regexp ".*-test$"}`
  - Add `"out"` to `.gitignore` if not present
  - **TDD:** N/A (config change)
  - **Commit:** `chore(build): add node-test build target to shadow-cljs.edn`

- [ ] **Task 1.2: Add test script to package.json**
  - Add `"test": "npx shadow-cljs compile node-test && node out/test/node-tests.js"` to scripts
  - **TDD:** N/A (config change)
  - **Commit:** `chore(build): add test script to package.json`

- [ ] **Task 1.3: Create first test file and verify test runner**
  - Create `src/test/cljs_playground/agent_test.cljs` (temporary namespace, will rename in Phase 2)
  - Write one trivial test: `(deftest sanity-check (is (= 1 1)))`
  - Run `yarn test` and confirm it compiles and passes
  - **TDD:** Red (write test) -> Green (it should pass immediately since it is a sanity check)
  - **Commit:** `test(agent): add sanity check test and verify node-test runner`

- [ ] **Task 1.4: Write agent registry tests (pre-rename)**
  - In `agent_test.cljs`, write tests against the current `cljs-playground.agent` namespace:
    - `register-model` adds handler to `@models` atom
    - `get-model` returns registered handler
    - `get-model` returns `nil` for unregistered model-id
    - `process-input` dispatches to correct handler (using echo-handler)
    - `process-input` falls back to `default-handler` for unknown model-id
  - All tests must pass via `yarn test`
  - **TDD:** Red (write tests requiring agent) -> Green (tests pass against existing code)
  - **Commit:** `test(agent): add registry and dispatch tests`

- [ ] **Task 1.5: Write handler tests (pre-rename)**
  - Add tests for `echo-handler` and `reverse-handler`:
    - `echo-handler` returns a Promise resolving to string containing the input
    - `reverse-handler` returns a Promise resolving to string containing the reversed input
  - Note: Use `.then` to unwrap Promises in test assertions with `cljs.test/async`
  - **TDD:** Red -> Green
  - **Commit:** `test(agent): add echo and reverse handler tests`

- [ ] **Verification 1.6:** Run `yarn test` -- all tests pass. Run `yarn watch` -- dev build still works. Load plugin in Logseq -- `/LLM` still functions. [checkpoint marker]

---

## Phase 2: Namespace Migration

**Goal:** Rename all namespaces from `cljs-playground.*` to `logseq-ai-hub.*`, move files to the new directory structure, update build config, and delete the old directory.

### Tasks:

- [ ] **Task 2.1: Create new directory structure**
  - Create `src/main/logseq_ai_hub/` directory
  - Create `src/test/logseq_ai_hub/` directory
  - **TDD:** N/A (file system change)
  - **Commit:** `chore(structure): create logseq-ai-hub directory structure`

- [ ] **Task 2.2: Migrate agent.cljs to new namespace**
  - Copy `src/main/cljs_playground/agent.cljs` to `src/main/logseq_ai_hub/agent.cljs`
  - Update `ns` declaration from `cljs-playground.agent` to `logseq-ai-hub.agent`
  - No other code changes
  - **TDD:** Existing tests will break (Red) -- fix in next task
  - **Commit:** `refactor(agent): migrate to logseq-ai-hub namespace`

- [ ] **Task 2.3: Migrate core.cljs to new namespace**
  - Copy `src/main/cljs_playground/core.cljs` to `src/main/logseq_ai_hub/core.cljs`
  - Update `ns` declaration from `cljs-playground.core` to `logseq-ai-hub.core`
  - Update `:require` from `[cljs-playground.agent :as agent]` to `[logseq-ai-hub.agent :as agent]`
  - Update console log to `"Loaded Logseq AI Hub Plugin"`
  - **TDD:** N/A (core has no unit tests yet; verified by build)
  - **Commit:** `refactor(core): migrate to logseq-ai-hub namespace`

- [ ] **Task 2.4: Update shadow-cljs.edn entry point**
  - Change `:init-fn` from `cljs-playground.core/init` to `logseq-ai-hub.core/init`
  - Update `:node-test` build `:ns-regexp` if needed (should be fine as-is with `".*-test$"`)
  - **TDD:** N/A (config change)
  - **Commit:** `chore(build): update shadow-cljs entry point to logseq-ai-hub`

- [ ] **Task 2.5: Migrate test file to new namespace**
  - Move `src/test/cljs_playground/agent_test.cljs` to `src/test/logseq_ai_hub/agent_test.cljs`
  - Update `ns` to `logseq-ai-hub.agent-test`
  - Update `:require` to `[logseq-ai-hub.agent :as agent]`
  - Run `yarn test` -- all tests must pass (Green)
  - **TDD:** Green (tests pass against migrated code)
  - **Commit:** `test(agent): migrate tests to logseq-ai-hub namespace`

- [ ] **Task 2.6: Update package.json name**
  - Change `"name"` from `"logseq-cljs-playground"` to `"logseq-ai-hub"`
  - Keep `"logseq"."id"` as `"_byud67luv"`
  - **TDD:** N/A (config change)
  - **Commit:** `chore(package): rename to logseq-ai-hub`

- [ ] **Task 2.7: Delete old cljs-playground directory**
  - Delete `src/main/cljs_playground/` entirely
  - Delete `src/test/cljs_playground/` if it exists
  - Run `yarn test` and `yarn watch` to confirm nothing references old paths
  - **TDD:** N/A (cleanup)
  - **Commit:** `chore(cleanup): remove old cljs-playground directory`

- [ ] **Verification 2.8:** Run `yarn test` -- all tests pass. Run `yarn watch` -- build succeeds. Load plugin in Logseq -- `/LLM` works with all three models. No references to `cljs-playground` remain in any source file. [checkpoint marker]

---

## Phase 3: Error Handling Foundation

**Goal:** Create the `logseq-ai-hub.util.errors` utility namespace with consistent error construction, detection, and promise-wrapping functions.

### Tasks:

- [ ] **Task 3.1: Write error utility tests**
  - Create `src/test/logseq_ai_hub/util/errors_test.cljs` with namespace `logseq-ai-hub.util.errors-test`
  - Write tests:
    - `make-error` returns a map with `:error true`, `:code`, and `:message`
    - `error?` returns true for error maps, false for other values
    - `error?` returns false for nil, strings, and maps without `:error`
    - `make-error-promise` returns a Promise resolving to an error map
  - Run `yarn test` -- tests fail (Red)
  - **TDD:** Red
  - **Commit:** `test(errors): add error utility tests`

- [ ] **Task 3.2: Implement error utilities**
  - Create `src/main/logseq_ai_hub/util/errors.cljs` with namespace `logseq-ai-hub.util.errors`
  - Implement `make-error`, `error?`, `make-error-promise`
  - Implement `wrap-promise-errors` -- takes a promise-returning function, returns a new function that catches JS errors and converts them to error maps via `.catch`
  - Run `yarn test` -- all tests pass (Green)
  - **TDD:** Green
  - **Commit:** `feat(errors): implement error utility functions`

- [ ] **Task 3.3: Write wrap-promise-errors tests**
  - Add tests for `wrap-promise-errors`:
    - Wrapping a function that resolves normally passes through the value
    - Wrapping a function that rejects converts the error to an error map
  - Run `yarn test` -- tests pass (Green, already implemented)
  - **TDD:** Red -> Green (if any edge cases found, fix)
  - **Commit:** `test(errors): add wrap-promise-errors tests`

- [ ] **Task 3.4: Integrate error utilities into agent module**
  - Add `[logseq-ai-hub.util.errors :as errors]` to agent requires
  - Wrap `openai-handler` with `wrap-promise-errors` pattern (the `.catch` block should use `errors/make-error` instead of returning a raw string)
  - Update `core.cljs` to check for `errors/error?` on the result and format it as a user-friendly string if so, before inserting as a block
  - Ensure existing tests still pass; add one test verifying error format from agent dispatch
  - **TDD:** Refactor (existing tests green, add new test, keep green)
  - **Commit:** `refactor(agent): integrate structured error handling`

- [ ] **Verification 3.5:** Run `yarn test` -- all tests pass. Run `yarn watch` -- build succeeds. Test `/LLM` with missing API key -- error message still displays correctly in Logseq. [checkpoint marker]

---

## Phase 4: Placeholder Modules

**Goal:** Create the three placeholder modules (`messaging`, `memory`, `tasks`) with defined APIs, docstrings, and atom-based state structures.

### Tasks:

- [ ] **Task 4.1: Create messaging module with tests**
  - **Red:** Create `src/test/logseq_ai_hub/messaging_test.cljs`. Write tests:
    - `connections` atom has expected initial structure
    - `init-connections!` returns not-implemented status
    - `handle-incoming-message` returns not-implemented status
    - `send-message` returns not-implemented status
    - `connection-status` returns not-implemented status
  - **Green:** Create `src/main/logseq_ai_hub/messaging.cljs` with:
    - `(defonce connections (atom {:whatsapp {:status :disconnected :config {}} :telegram {:status :disconnected :config {}}}))`
    - Stub functions: `init-connections!`, `handle-incoming-message`, `send-message`, `connection-status`
    - Each returns `(js/Promise.resolve {:status :not-implemented :fn "<fn-name>"})`
    - Each logs `(js/console.warn "Not implemented:" "<fn-name>")`
  - **Refactor:** Ensure docstrings are clear and complete
  - **Commit:** `feat(messaging): add placeholder module with API signatures and state`

- [ ] **Task 4.2: Create memory module with tests**
  - **Red:** Create `src/test/logseq_ai_hub/memory_test.cljs`. Write tests:
    - `memory-index` atom has expected initial structure
    - `init-memory!` returns not-implemented status
    - `store-memory!` returns not-implemented status
    - `retrieve-memories` returns not-implemented status
    - `clear-memories!` returns not-implemented status
  - **Green:** Create `src/main/logseq_ai_hub/memory.cljs` with:
    - `(defonce memory-index (atom {:entries [] :config {:page-prefix "AI-Memory/" :enabled false}}))`
    - Stub functions: `init-memory!`, `store-memory!`, `retrieve-memories`, `clear-memories!`
    - Each returns `(js/Promise.resolve {:status :not-implemented :fn "<fn-name>"})`
    - Each logs `(js/console.warn "Not implemented:" "<fn-name>")`
  - **Refactor:** Ensure docstrings are clear and complete
  - **Commit:** `feat(memory): add placeholder module with API signatures and state`

- [ ] **Task 4.3: Create tasks module with tests**
  - **Red:** Create `src/test/logseq_ai_hub/tasks_test.cljs`. Write tests:
    - `task-registry` atom has expected initial structure
    - `register-task!` returns not-implemented status
    - `run-task!` returns not-implemented status
    - `cancel-task!` returns not-implemented status
    - `task-status` returns not-implemented status
    - `list-tasks` returns not-implemented status
  - **Green:** Create `src/main/logseq_ai_hub/tasks.cljs` with:
    - `(defonce task-registry (atom {:tasks {} :running #{} :config {:max-concurrent 5}}))`
    - Stub functions: `register-task!`, `run-task!`, `cancel-task!`, `task-status`, `list-tasks`
    - Each returns `(js/Promise.resolve {:status :not-implemented :fn "<fn-name>"})`
    - Each logs `(js/console.warn "Not implemented:" "<fn-name>")`
  - **Refactor:** Ensure docstrings are clear and complete
  - **Commit:** `feat(tasks): add placeholder module with API signatures and state`

- [ ] **Verification 4.4:** Run `yarn test` -- all tests pass (agent, errors, messaging, memory, tasks). All placeholder modules load without errors. [checkpoint marker]

---

## Phase 5: Settings Schema Expansion

**Goal:** Extend the settings schema in `core.cljs` to include configuration for messaging APIs and memory, preparing for future module implementation.

### Tasks:

- [ ] **Task 5.1: Add messaging settings to schema**
  - Add to `settings-schema` in `core.cljs`:
    - `{:key "whatsappToken" :type "string" :title "WhatsApp Cloud API Token" :description "Your WhatsApp Cloud API access token." :default ""}`
    - `{:key "telegramBotToken" :type "string" :title "Telegram Bot Token" :description "Your Telegram Bot API token from @BotFather." :default ""}`
    - `{:key "webhookServerUrl" :type "string" :title "Webhook Server URL" :description "URL of the external webhook server (e.g., Railway deployment)." :default ""}`
  - Verify existing settings are unchanged
  - **TDD:** N/A (settings schema is declarative data, verified manually in Logseq)
  - **Commit:** `feat(core): add messaging settings to schema`

- [ ] **Task 5.2: Add memory settings to schema**
  - Add to `settings-schema` in `core.cljs`:
    - `{:key "memoryPagePrefix" :type "string" :title "Memory Page Prefix" :description "Page prefix for AI memory storage (e.g., AI-Memory/)." :default "AI-Memory/"}`
    - `{:key "memoryEnabled" :type "boolean" :title "Enable AI Memory" :description "Enable or disable the AI memory subsystem." :default false}`
  - **TDD:** N/A (declarative data)
  - **Commit:** `feat(core): add memory settings to schema`

- [ ] **Verification 5.3:** Run `yarn watch`. Load plugin in Logseq. Open plugin settings. Confirm all new settings fields appear with correct labels, descriptions, and defaults. Confirm existing settings are unchanged. [checkpoint marker]

---

## Phase 6: Final Integration & Cleanup

**Goal:** Ensure all modules load together, the build is clean, and the codebase is ready for future track development.

### Tasks:

- [ ] **Task 6.1: Wire placeholder modules into core initialization**
  - In `core.cljs`, add requires for `logseq-ai-hub.messaging`, `logseq-ai-hub.memory`, `logseq-ai-hub.tasks`
  - In the `main` function, add initialization comments (no actual init calls yet, just ensure namespaces load):
    ```clojure
    ;; Future: (messaging/init-connections! (js->clj js/logseq.settings))
    ;; Future: (memory/init-memory! (js->clj js/logseq.settings))
    ```
  - This forces shadow-cljs to include the modules in the build, catching any compile errors
  - **TDD:** Run `yarn test` and `yarn watch` -- both succeed
  - **Commit:** `feat(core): require placeholder modules in initialization`

- [ ] **Task 6.2: Code style audit and docstring review**
  - Review all files against `conductor/code_styleguides/clojurescript.md`:
    - Comment banners for sections
    - Docstrings on all public functions
    - `defonce` for all state atoms
    - JS interop at boundaries only
    - Consistent formatting
  - Fix any violations found
  - **TDD:** N/A (style review)
  - **Commit:** `refactor(all): code style audit and docstring cleanup`

- [ ] **Task 6.3: Update manifest.edn**
  - Update `manifest.edn` to reflect new module entry points if needed
  - Verify it is consistent with `shadow-cljs.edn` output
  - **TDD:** N/A (config)
  - **Commit:** `chore(build): update manifest.edn for new architecture`

- [ ] **Task 6.4: Full integration verification**
  - Run `yarn test` -- all tests pass
  - Run `yarn release` -- production build succeeds without warnings
  - Load production build in Logseq:
    - Plugin settings panel shows all settings (AI, messaging, memory)
    - `/LLM` command works with mock-model
    - `/LLM` command works with reverse-model
    - `/LLM` command works with openai-model (if API key configured)
    - Console shows "Loaded Logseq AI Hub Plugin"
    - No console errors on plugin load
  - **TDD:** N/A (manual integration test)
  - **Commit:** N/A (verification only)
  - [checkpoint marker]

---

## Summary

| Phase | Tasks | Focus |
|-------|-------|-------|
| 1. Testing Infrastructure | 6 | Test runner, initial agent tests |
| 2. Namespace Migration | 8 | Rename, move files, update config |
| 3. Error Handling | 5 | Error utility module, integration |
| 4. Placeholder Modules | 4 | Messaging, memory, tasks stubs |
| 5. Settings Expansion | 3 | New settings for messaging, memory |
| 6. Final Integration | 4 | Wiring, cleanup, full verification |
| **Total** | **30** | |

## Dependencies

- Phase 1 must complete before Phase 2 (tests validate migration)
- Phase 2 must complete before Phases 3-5 (namespace must be finalized)
- Phase 3 can run in parallel with Phase 4 (error utils and placeholders are independent, but Phase 3 before 4 is preferred so placeholders can use error utils if desired)
- Phase 5 can run in parallel with Phases 3-4
- Phase 6 depends on all prior phases
