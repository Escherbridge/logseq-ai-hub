# Specification: Plugin Dual Authentication

## Overview

Update the Logseq AI Hub plugin to support two authentication modes when communicating with its backend server:

1. **Token mode** (existing) -- a simple shared bearer token for the open-source single-tenant server.
2. **JWT mode** (new) -- a long-lived JSON Web Token for the proprietary multi-tenant server.

The plugin auto-selects the mode based on a new `authMode` setting. All HTTP and SSE requests use the selected credential. Existing users experience zero disruption through silent settings migration.

## Background

The project is transitioning from a single open-source server to a dual-deployment model: an open-source single-tenant server and a proprietary multi-tenant server. The multi-tenant server uses JWT-based authentication where JWT claims carry tenant identity, eliminating the need for explicit tenant headers from the plugin side. The plugin must work seamlessly with both servers.

### Current Auth Landscape

Every outbound request from the plugin to its hub server uses the `pluginApiToken` setting value as a `Bearer` token. Auth touchpoints are scattered across 4 modules:

| Module | Function(s) | Mechanism |
|--------|-------------|-----------|
| `messaging.cljs` | `build-sse-url`, `send-message!`, `init!` | SSE query param `?token=`, HTTP `Authorization: Bearer` |
| `agent_bridge.cljs` | `get-api-token`, `send-callback!` | HTTP `Authorization: Bearer` |
| `event_hub/publish.cljs` | `get-api-token`, `publish-to-server!` | HTTP `Authorization: Bearer` |
| `event_hub/init.cljs` | `get-api-token`, `fetch-recent-events`, `fetch-event-sources` | HTTP `Authorization: Bearer` |

The MCP transport (`mcp/transport.cljs`) also uses Bearer auth, but for connections to external MCP servers (not the hub server) -- this is unaffected by this track.

## Functional Requirements

### FR-1: Auth Mode Setting
- **Description:** Add an `authMode` setting to the plugin settings schema with two allowed values: `"token"` and `"jwt"`.
- **Acceptance Criteria:**
  - Setting appears in Logseq plugin settings UI as a dropdown/enum.
  - Default value is `"token"`.
  - Changing the value takes effect on next SSE reconnection or HTTP request (no restart required for HTTP; SSE reconnects on settings change).
- **Priority:** P0

### FR-2: JWT Token Setting
- **Description:** Add a `jwtToken` setting where users paste their long-lived JWT obtained from the external admin dashboard.
- **Acceptance Criteria:**
  - Setting appears as a string input in plugin settings UI.
  - Default value is `""` (empty string).
  - Setting is only relevant when `authMode` is `"jwt"` (but always visible for simplicity -- Logseq settings schema does not support conditional visibility).
- **Priority:** P0

### FR-3: Centralized Auth Token Resolution
- **Description:** Create a single `auth` namespace that resolves the current auth token based on `authMode`. All modules must use this instead of reading `pluginApiToken` directly.
- **Acceptance Criteria:**
  - New `logseq-ai-hub.auth` namespace with `get-auth-token` function.
  - When `authMode` is `"token"`: returns the value of `pluginApiToken`.
  - When `authMode` is `"jwt"`: returns the value of `jwtToken`.
  - When `authMode` is missing/nil: falls back to `"token"` mode (backward compatibility).
  - A companion `get-auth-mode` function returns `"token"` or `"jwt"`.
  - A companion `auth-configured?` function returns true if the resolved token is non-blank and server URL is set.
- **Priority:** P0

### FR-4: HTTP Request Auth Header Migration
- **Description:** Replace all direct `pluginApiToken` reads in HTTP request construction with calls to the centralized `get-auth-token`.
- **Acceptance Criteria:**
  - `agent_bridge.cljs` `send-callback!` uses `auth/get-auth-token`.
  - `event_hub/publish.cljs` `publish-to-server!` uses `auth/get-auth-token`.
  - `event_hub/init.cljs` `fetch-recent-events` and `fetch-event-sources` use `auth/get-auth-token`.
  - `messaging.cljs` `send-message!` uses `auth/get-auth-token`.
  - All HTTP requests send `Authorization: Bearer <resolved-token>` regardless of mode.
- **Priority:** P0

### FR-5: SSE Connection Auth Migration
- **Description:** Update the SSE connection to use the centralized auth token.
- **Acceptance Criteria:**
  - `messaging.cljs` `build-sse-url` uses `auth/get-auth-token` for the query param.
  - `messaging.cljs` `init!` reads the token via `auth/get-auth-token` instead of directly from settings.
  - `messaging.cljs` `connect!` stores the resolved token (not the raw setting) in state.
  - SSE connection works identically in both modes (the server accepts JWT in the same `?token=` query param).
- **Priority:** P0

### FR-6: Silent Settings Migration
- **Description:** Extend the existing `migrate-settings!` function in `core.cljs` to handle the new `authMode` setting for existing users.
- **Acceptance Criteria:**
  - Existing users who have a non-empty `pluginApiToken` and no `authMode` setting automatically get `authMode` set to `"token"`.
  - Migration is idempotent -- running multiple times produces the same result.
  - No user-facing notification or prompt is shown.
  - Users who already have `authMode` set are not affected.
- **Priority:** P0

### FR-7: Auth Validation Feedback
- **Description:** Provide user-visible feedback when auth is misconfigured.
- **Acceptance Criteria:**
  - On plugin init, if `authMode` is `"jwt"` and `jwtToken` is blank, log a warning to the console: `"[Auth] JWT mode selected but no JWT token configured"`.
  - On plugin init, if `authMode` is `"token"` and `pluginApiToken` is blank, log a warning: `"[Auth] Token mode selected but no API token configured"`.
  - Warnings do not block initialization.
- **Priority:** P1

## Non-Functional Requirements

### NFR-1: Zero Breaking Changes
- Existing users with `authMode` unset must experience zero behavioral change.
- The `pluginApiToken` setting must remain in the settings schema (not removed).
- All existing tests must continue to pass without modification (or with minimal mock updates).

### NFR-2: Performance
- Token resolution (`get-auth-token`) is a synchronous settings read -- no async overhead, no caching needed.
- No additional HTTP requests are introduced by this feature.

### NFR-3: Security
- JWT tokens are stored in plaintext in Logseq plugin settings (same security posture as existing `pluginApiToken` and `secretsVault`).
- No JWT parsing or validation happens on the plugin side -- the plugin treats it as an opaque string.
- JWT tokens must not be logged (same log-safety posture as existing tokens).

## User Stories

### US-1: Existing User Upgrades
**As** an existing Logseq AI Hub user with a token-based server,
**I want** my plugin to continue working after updating,
**So that** I experience zero disruption.

**Given** I have `pluginApiToken` set and no `authMode` setting,
**When** the plugin loads after update,
**Then** `authMode` defaults to `"token"` and all requests use my existing `pluginApiToken`.

### US-2: New User with Multi-Tenant Server
**As** a new user connecting to the multi-tenant server,
**I want** to paste my JWT from the admin dashboard into plugin settings,
**So that** the plugin authenticates me and the server knows my tenant.

**Given** I set `authMode` to `"jwt"` and paste my JWT into `jwtToken`,
**When** the plugin connects to the server,
**Then** all HTTP and SSE requests send `Authorization: Bearer <my-jwt>`.

### US-3: Switching Auth Modes
**As** a developer testing both server types,
**I want** to switch between token and JWT mode,
**So that** I can connect to either server.

**Given** I have both `pluginApiToken` and `jwtToken` populated,
**When** I change `authMode` from `"token"` to `"jwt"`,
**Then** subsequent HTTP requests use the JWT, and SSE reconnects with the JWT.

## Technical Considerations

1. **Centralization pattern:** A new `logseq-ai-hub.auth` namespace prevents the current pattern of 4+ modules each independently reading `pluginApiToken`. This also makes future auth changes (e.g., token refresh) single-point.

2. **SSE query param:** The SSE connection currently passes the token as a URL query parameter (`?token=`). The JWT will use the same mechanism. The server must accept JWTs in this query param (server-side concern, handled by multi-tenant-auth track).

3. **Settings schema limitations:** Logseq's `useSettingsSchema` supports `"enum"` type for dropdowns. The `authMode` setting should use this type with `["token", "jwt"]` as options.

4. **No circular dependencies:** The `auth` namespace must only depend on `js/logseq.settings` (no other plugin namespaces) to avoid circular requires.

5. **Test strategy:** The `auth` namespace is pure settings reads -- tests mock `js/logseq.settings`. Integration with existing modules is verified by updating existing tests to use/mock the new auth path.

## Out of Scope

- JWT issuance, refresh, or rotation (user manually replaces expired JWT in settings).
- JWT parsing or claim extraction on the plugin side.
- Tenant ID headers (server extracts tenant from JWT claims).
- Server-side JWT validation (handled by `multi-tenant-auth` track in proprietary repo).
- Conditional settings visibility (Logseq settings schema does not support it).
- Token expiry detection or user notification.

## Open Questions

None -- all key decisions were pre-answered in the track description.
