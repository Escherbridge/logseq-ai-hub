# Implementation Plan: Plugin Dual Authentication

## Overview

4 phases, 16 tasks. Introduces a centralized `auth` namespace, adds two new settings (`authMode`, `jwtToken`), migrates all 4 HTTP/SSE modules to use the centralized token resolver, extends settings migration, and validates with integration tests.

Estimated effort: 3-5 hours.

---

## Phase 1: Auth Namespace and Settings Schema

Goal: Create the centralized auth module and extend the plugin settings schema with `authMode` and `jwtToken`.

Tasks:

- [ ] Task 1.1: Create `src/test/logseq_ai_hub/auth_test.cljs` with tests for `get-auth-mode`, `get-auth-token`, and `auth-configured?`. Test cases: (a) authMode="token" returns pluginApiToken, (b) authMode="jwt" returns jwtToken, (c) authMode=nil defaults to "token" behavior, (d) auth-configured? returns false when token is blank, (e) auth-configured? returns false when server URL is blank. (TDD: Red)

- [ ] Task 1.2: Create `src/main/logseq_ai_hub/auth.cljs` with `get-auth-mode`, `get-auth-token`, `get-server-url`, and `auth-configured?` functions. `get-auth-mode` reads `authMode` setting and defaults to `"token"`. `get-auth-token` dispatches on mode: `"token"` reads `pluginApiToken`, `"jwt"` reads `jwtToken`. `auth-configured?` checks non-blank token and server URL. No external dependencies beyond `js/logseq.settings`. (TDD: Green)

- [ ] Task 1.3: Add `authMode` setting to `settings-schema` in `core.cljs` as an enum type with options `["token", "jwt"]`, default `"token"`, positioned after `webhookServerUrl`. Add `jwtToken` setting as a string type, default `""`, positioned after `pluginApiToken`. (TDD: Implement, verify schema renders in Logseq)

- [ ] Task 1.4: Add `log-auth-warnings!` function to `auth.cljs` that logs console warnings for misconfigured auth (FR-7). Write test verifying warning messages. (TDD: Red, Green)

- [ ] Verification: Run `npm test` -- all existing tests pass, new auth tests pass. Manually load plugin in Logseq and confirm `authMode` dropdown and `jwtToken` field appear in settings. [checkpoint marker]

---

## Phase 2: Migrate HTTP Modules to Centralized Auth

Goal: Replace all direct `pluginApiToken` reads in HTTP-calling modules with `auth/get-auth-token`.

Tasks:

- [ ] Task 2.1: Update `agent_bridge.cljs` -- replace `get-api-token` private function with a require of `logseq-ai-hub.auth` and call `auth/get-auth-token` in `send-callback!`. Remove the private `get-api-token` function. Update `agent_bridge_test.cljs` to mock settings with `authMode` and verify both token and JWT modes produce correct Authorization header. (TDD: Red, Green, Refactor)

- [ ] Task 2.2: Update `event_hub/publish.cljs` -- replace `get-api-token` private function with `auth/get-auth-token`. Remove the private `get-api-token` and `get-server-url` functions, use `auth/get-server-url` and `auth/get-auth-token`. Update `event_hub/publish_test.cljs` to verify both auth modes. (TDD: Red, Green, Refactor)

- [ ] Task 2.3: Update `event_hub/init.cljs` -- replace both `get-server-url` and `get-api-token` private functions with `auth/get-server-url` and `auth/get-auth-token`. Update `fetch-recent-events` and `fetch-event-sources`. Update `event_hub/init_test.cljs` mocks. (TDD: Red, Green, Refactor)

- [ ] Task 2.4: Update `messaging.cljs` `send-message!` -- replace direct state read of `:api-token` with `auth/get-auth-token` for the Authorization header. Update `messaging_test.cljs` to verify both auth modes in send-message tests. (TDD: Red, Green, Refactor)

- [ ] Verification: Run `npm test` -- all tests pass. Grep codebase to confirm zero remaining direct reads of `pluginApiToken` outside of `auth.cljs` and `core.cljs` (settings schema). [checkpoint marker]

---

## Phase 3: Migrate SSE Connection to Centralized Auth

Goal: Update the SSE connection setup and state management to use centralized auth.

Tasks:

- [ ] Task 3.1: Update `messaging.cljs` `build-sse-url` to accept a token parameter (already does via `api-token` arg) -- no signature change needed. Update `connect!` to call `auth/get-auth-token` instead of using the passed `api-token` parameter directly from state. Store the resolved token in messaging state. (TDD: Update tests, verify SSE URL uses correct token in both modes)

- [ ] Task 3.2: Update `messaging.cljs` `init!` to use `auth/get-server-url` and `auth/get-auth-token` instead of reading settings directly. Update the `auth-configured?` guard to use `auth/auth-configured?`. (TDD: Update init tests)

- [ ] Task 3.3: Write integration test in `auth_test.cljs` that verifies `messaging/connect!` produces SSE URL with JWT token when `authMode` is `"jwt"`. Mock `js/EventSource` constructor to capture the URL. (TDD: Red, Green)

- [ ] Verification: Run `npm test` -- all tests pass. Manually test SSE connection in Logseq with token mode to confirm no regression. [checkpoint marker]

---

## Phase 4: Settings Migration and Final Validation

Goal: Extend settings migration for existing users and perform end-to-end validation.

Tasks:

- [ ] Task 4.1: Extend `migrate-settings!` in `core.cljs` to set `authMode` to `"token"` for existing users who have a non-empty `pluginApiToken` but no `authMode` value. Write test in `core_test.cljs` verifying migration idempotency: (a) existing user gets authMode="token", (b) user with authMode already set is not overwritten, (c) new user with no pluginApiToken gets no migration. (TDD: Red, Green)

- [ ] Task 4.2: Call `auth/log-auth-warnings!` from `core.cljs` `main` function after `migrate-settings!`. (TDD: Implement, verify console output)

- [ ] Task 4.3: End-to-end validation -- write a comprehensive test in `auth_test.cljs` that sets up JWT mode settings, verifies `get-auth-token` returns the JWT, and confirms the token would be sent as `Authorization: Bearer <jwt>` in a simulated HTTP call. (TDD: Red, Green)

- [ ] Verification: Run `npm run test:all` (both CLJS and server tests). Manually test in Logseq: (1) fresh install sees authMode dropdown defaulting to "token", (2) set authMode to "jwt" and paste a dummy JWT, confirm SSE reconnects with new token in URL, (3) switch back to "token" mode, confirm original token is used. [checkpoint marker]
