# Specification: Secrets Manager — Secure Key-Value Vault in Plugin Settings

## Overview

Add a secrets manager to the Logseq AI Hub that stores API keys, tokens, and credentials as an encrypted/obfuscated JSON key-value store within the Logseq plugin settings. Secrets are resolvable at runtime via `{{secret.KEY_NAME}}` template interpolation in job runner steps, MCP server configs, outbound HTTP headers, and any other configurable field. This eliminates the need to hardcode sensitive values in Logseq page content or MCP server config strings.

## Background

Currently, sensitive values are handled in several fragile ways:
- **MCP server auth tokens** are stored as plaintext in the `mcpServers` JSON setting: `{"id": "fs", "url": "...", "auth-token": "my-secret-token"}`.
- **OpenAI API key** is stored as plaintext in the `openAIKey` setting.
- **Job runner steps** that call external APIs (future `:http-request` action) need API keys, but there's no secure way to reference them from skill page content.
- **Logseq page content** is stored in the graph as plain text markdown. Putting API keys in page content (e.g., in a skill step's config) means they're visible, searchable, and potentially synced to cloud services.

### Problems

1. **Secrets in page content**: If a skill step needs an API key, it currently has to be written into the page. This is visible to anyone with graph access and gets synced.
2. **No indirection**: MCP server configs contain `auth-token` directly. Rotating a key means editing the JSON in settings.
3. **No reuse**: The same API key might be needed by multiple skills and MCP configs. Currently each must store its own copy.
4. **No separation of concerns**: Configuration (what to do) and credentials (how to authenticate) are mixed together.

### Solution

A dedicated secrets vault in plugin settings that:
- Stores secrets as a JSON object of key-value pairs
- Is only accessible through the plugin settings UI (not in page content)
- Is resolvable via `{{secret.KEY_NAME}}` in the template interpolation engine
- Can be referenced in MCP server configs via `{{secret.KEY_NAME}}`
- Is never exposed in API responses, SSE events, MCP tool results, or Logseq page content

## Dependencies

- **job-runner_20260219**: Template interpolation engine (`interpolation.cljs`), executor, MCP client.
- **mcp-server_20260221**: MCP tools should be able to reference secrets for outbound calls.

## Functional Requirements

### FR-1: Secrets Setting in Plugin Settings Schema

**Description:** Add a `secretsVault` setting to the plugin settings schema that stores a JSON object of key-value pairs.

**Acceptance Criteria:**
- New setting in `settings-schema` in `core.cljs`:
  ```clojure
  {:key "secretsVault"
   :type "string"
   :title "Secrets Vault"
   :description "JSON object of secret key-value pairs. Reference in configs with {{secret.KEY_NAME}}. Example: {\"OPENROUTER_KEY\": \"sk-...\", \"SLACK_TOKEN\": \"xoxb-...\"}"
   :default "{}"}
  ```
- The setting accepts a JSON string (Logseq settings don't support object types natively).
- The vault is parsed on plugin init and cached in memory.
- Invalid JSON is handled gracefully with a warning (empty vault, not a crash).
- The vault is re-parsed when settings change (Logseq settings change listener).

**Priority:** P0

### FR-2: Secret Resolution Module

**Description:** A dedicated ClojureScript module (`secrets.cljs`) that manages the vault state and provides secret resolution.

**File:** `src/main/logseq_ai_hub/secrets.cljs`

**Acceptance Criteria:**
- `init!` — Reads `secretsVault` from settings, parses JSON, stores in an atom.
- `get-secret [key]` — Returns the value for a key, or `nil` if not found.
- `has-secret? [key]` — Returns `true` if the key exists.
- `list-keys []` — Returns a vector of key names (NOT values) for discovery.
- `set-secret! [key value]` — Updates a secret in the vault and persists to Logseq settings.
- `remove-secret! [key]` — Removes a secret from the vault and persists.
- `reload!` — Re-reads the vault from settings (for settings change events).
- The in-memory atom is the canonical state; `set-secret!` and `remove-secret!` write through to settings.
- Secret values are NEVER logged, printed to console, or included in error messages. Only key names can be logged.

**Priority:** P0

### FR-3: Template Interpolation Extension

**Description:** Extend the `interpolation.cljs` module to resolve `{{secret.KEY_NAME}}` references from the secrets vault.

**Acceptance Criteria:**
- The `lookup-variable` function recognizes the `secret.` prefix.
- `{{secret.OPENROUTER_KEY}}` resolves to the value of the `OPENROUTER_KEY` secret.
- Secret resolution is checked AFTER inputs and step results but BEFORE returning the empty-string fallback.
- If the secret key doesn't exist, it resolves to an empty string (same as other missing variables) with a console warning.
- The interpolation engine's dynamic var for the secrets resolver is set during `init.cljs` wiring (same pattern as other dependency injection).
- Secret values interpolated into strings are NOT stored in step results or job logs. The interpolation happens at execution time only.

**Priority:** P0

### FR-4: MCP Server Config Secret References

**Description:** Allow MCP server configurations to reference secrets instead of containing plaintext tokens.

**Current format:**
```json
[{"id": "filesystem", "url": "http://localhost:8080/mcp", "auth-token": "plaintext-token-bad"}]
```

**New format (both work):**
```json
[{"id": "filesystem", "url": "http://localhost:8080/mcp", "auth-token": "{{secret.FS_MCP_TOKEN}}"}]
```

**Acceptance Criteria:**
- When parsing MCP server configs in `init.cljs`, `auth-token` values starting with `{{secret.` are resolved from the vault.
- Secrets are resolved at connection time, not at parse time (so rotating a key and reconnecting works).
- If the secret doesn't exist, the connection proceeds without auth (with a warning).
- The MCP client's `connect-server!` function receives the resolved token, never the template string.
- Existing plaintext tokens continue to work (backward compatible).

**Priority:** P0

### FR-5: Slash Commands for Secret Management

**Description:** Slash commands for managing secrets from within Logseq.

**Commands:**

| Command | Description |
|---|---|
| `/secrets:list` | Lists all secret key names (NOT values) as child blocks |
| `/secrets:set` | Sets a secret. Block text format: `KEY_NAME value-here` |
| `/secrets:remove` | Removes a secret. Block text = key name to remove |
| `/secrets:test` | Tests secret resolution. Block text = `{{secret.KEY_NAME}}`, inserts `✓ KEY_NAME exists` or `✗ KEY_NAME not found` |

**Acceptance Criteria:**
- `/secrets:list` inserts key names only. Values are NEVER displayed.
- `/secrets:set` parses the first word as the key, the rest as the value. Confirms with "Secret KEY_NAME saved".
- `/secrets:remove` confirms with "Secret KEY_NAME removed".
- `/secrets:test` checks if a secret exists without revealing its value.
- All commands use Logseq notification for feedback.

**Priority:** P1

### FR-6: Agent Bridge Secrets Operations

**Description:** Expose secret key listing (not values) via the Agent Bridge for the MCP server to report available secrets.

**Operations:**

| Operation | Description | Returns |
|---|---|---|
| `list_secret_keys` | List available secret key names | `{keys: ["OPENROUTER_KEY", "SLACK_TOKEN", ...]}` |
| `set_secret` | Set a secret key-value pair | `{success: true, key: "..."}` |
| `remove_secret` | Remove a secret | `{success: true, key: "..."}` |

**Acceptance Criteria:**
- `list_secret_keys` returns key names only, NEVER values.
- `set_secret` accepts `{key: string, value: string}` and stores in the vault.
- `remove_secret` accepts `{key: string}` and removes from the vault.
- These operations allow the MCP server (and by extension Claude Code) to manage secrets remotely.
- Values are never returned in any response — only keys and success/failure status.

**Priority:** P1

### FR-7: MCP Tools for Secrets

**Description:** MCP tools for managing secrets from Claude Code or other MCP clients.

**Tools:**

| Tool Name | Description | Parameters |
|---|---|---|
| `secret_list_keys` | List all secret key names (not values) | `{}` |
| `secret_set` | Store a secret key-value pair | `{key: string, value: string}` |
| `secret_remove` | Remove a secret | `{key: string}` |
| `secret_check` | Check if a secret key exists | `{key: string}` |

**Acceptance Criteria:**
- `secret_list_keys` returns only key names.
- `secret_set` stores the secret and confirms. The value is NEVER echoed back.
- `secret_remove` removes and confirms.
- `secret_check` returns `{exists: true/false}` without revealing the value.
- These delegate to the Agent Bridge operations from FR-6.

**Priority:** P1

### FR-8: Secret Redaction in Outputs

**Description:** Ensure secret values never leak into outputs — API responses, SSE events, logs, step results, or page content.

**Acceptance Criteria:**
- Job step results that contain interpolated secrets are stored with the secret redacted (replaced with `[REDACTED:KEY_NAME]`).
- API responses from the server never include raw secret values.
- SSE events never include secret values.
- Console logs never include secret values (only key names).
- If a secret value accidentally appears in an MCP tool result, it is redacted before returning to the client.
- Redaction is applied by the interpolation engine: after interpolation, the engine returns a `{:result "interpolated string" :contains-secrets true}` marker that downstream consumers can use for redaction decisions.

**Priority:** P0

## Non-Functional Requirements

### NFR-1: Security

- Secret values are stored in Logseq plugin settings, which are stored on the local filesystem (not in the graph). This means they are NOT synced to Logseq Sync or other graph-sharing mechanisms.
- Secret values in memory are stored as plain strings (JavaScript has no secure memory). Clearing secrets removes them from the atom.
- No encryption at rest for v1 (plugin settings are stored as JSON files by Logseq). The separation from graph content is the primary security boundary.
- Rate limiting on `secret_set` MCP tool: max 10 calls per minute to prevent brute-force enumeration via timing attacks.

### NFR-2: Backward Compatibility

- All existing configs continue to work. Plaintext values in MCP server configs, API keys in settings, etc. are not affected.
- The `{{secret.}}` prefix is new and doesn't conflict with existing `{{variable}}` patterns.
- The interpolation extension is additive — all existing variable resolution continues to work.

### NFR-3: Performance

- Vault parsing happens once at init (< 1ms for typical vault sizes).
- Secret lookup is O(1) (atom containing a map).
- Re-parsing on settings change is debounced (100ms).

### NFR-4: Testability

- `secrets.cljs` has unit tests for all CRUD operations.
- Interpolation extension has unit tests for `{{secret.}}` resolution.
- MCP config secret resolution has integration tests.
- Redaction logic has unit tests.
- Test coverage target: 90% (security-critical module).

## User Stories

### US-1: Store API keys securely

**As** a user with multiple API keys (OpenRouter, Slack, GitHub),
**I want** to store them in a single vault in plugin settings,
**So that** they're never written into my Logseq graph pages.

**Scenarios:**
- **Given** I open plugin settings, **When** I enter `{"OPENROUTER_KEY": "sk-or-...", "SLACK_TOKEN": "xoxb-..."}` in the Secrets Vault field, **Then** I can reference these as `{{secret.OPENROUTER_KEY}}` in any config.

### US-2: Secure MCP server connections

**As** a user configuring MCP server connections,
**I want** to reference secrets for auth tokens instead of putting plaintext tokens in the JSON config,
**So that** rotating a key only requires updating one place.

**Scenarios:**
- **Given** I have `GITHUB_MCP_TOKEN` in my vault, **When** I configure an MCP server with `"auth-token": "{{secret.GITHUB_MCP_TOKEN}}"`, **Then** the MCP client resolves the token at connection time.

### US-3: Use secrets in job skills

**As** a user building automation skills,
**I want** to reference API keys in step configs without embedding them in page content,
**So that** my skill definitions are shareable without leaking credentials.

**Scenarios:**
- **Given** a skill step with `step-config:: {"url": "https://api.slack.com/post", "headers": {"Authorization": "Bearer {{secret.SLACK_TOKEN}}"}}`, **When** the step executes, **Then** the secret is resolved at runtime and never stored in the step result.

### US-4: Claude Code manages secrets

**As** a developer using Claude Code,
**I want** to set up API keys via MCP tools,
**So that** I can configure the system without opening Logseq settings.

**Scenarios:**
- **Given** Claude Code is connected via MCP, **When** I say "store my new Slack token", **Then** Claude Code calls `secret_set` with the key and value. The value is never echoed in the response.

### US-5: List secrets without exposing values

**As** a developer debugging a configuration issue,
**I want** to see which secrets exist without seeing their values,
**So that** I can verify the right keys are configured.

**Scenarios:**
- **Given** I have 3 secrets in the vault, **When** I type `/secrets:list`, **Then** I see the 3 key names as child blocks without any values.

## Technical Considerations

### Logseq Plugin Settings Storage

Logseq stores plugin settings in `~/.logseq/settings/<plugin-id>.json`. This is a local file, separate from the graph database. This means:
- Secrets are NOT synced via Logseq Sync.
- Secrets are NOT searchable via graph queries.
- Secrets ARE readable by any process with filesystem access (no OS-level encryption).

For v1, this is acceptable. Future enhancement: use OS keychain (macOS Keychain, Windows Credential Manager) via a native module.

### Interpolation Engine Extension

The current `lookup-variable` function in `interpolation.cljs` checks step results, inputs, then variables. Adding secret resolution:

```clojure
(defn- lookup-variable [var-name context]
  (cond
    ;; ... existing checks ...

    ;; Secret reference
    (str/starts-with? var-name "secret.")
    (let [secret-key (subs var-name 7)] ;; strip "secret." prefix
      (or (*resolve-secret* secret-key) ""))

    ;; Not found
    :else ""))
```

Where `*resolve-secret*` is a dynamic var set during init to `secrets/get-secret`. This avoids a circular dependency between the interpolation module and the secrets module.

### MCP Config Resolution Timing

Secrets in MCP server configs should be resolved at **connection time**, not at **parse time**. This means:
1. Parse the JSON config string → get `{:auth-token "{{secret.TOKEN}}"}`
2. Store the raw config
3. When `connect-server!` is called, resolve the template string
4. Pass the resolved value to the transport layer

This allows:
- Changing a secret and reconnecting without re-parsing the config
- Detecting missing secrets at connection time with a clear error message

### Redaction Strategy

Two approaches:
1. **Post-interpolation redaction**: After interpolating, scan the result for any substring that matches a vault value and replace with `[REDACTED]`. Problem: expensive for large strings, false positives if a secret happens to appear in content.
2. **Template-aware redaction**: Track which `{{secret.}}` references were interpolated, and record that the result contains secrets. Downstream consumers check this flag and redact before logging/storing.

**Recommendation:** Option 2 (template-aware). The interpolation function returns metadata about which secrets were used, and callers decide how to handle it.

### Settings Change Listener

Logseq provides `logseq.onSettingsChanged` to react to settings changes. The secrets module registers a listener that re-parses the vault when the `secretsVault` setting changes:

```clojure
(js/logseq.onSettingsChanged
  (fn [new-settings old-settings]
    (when (not= (aget new-settings "secretsVault")
                (aget old-settings "secretsVault"))
      (reload!))))
```

## Out of Scope

- OS keychain integration (macOS Keychain, Windows Credential Manager).
- Encryption at rest (beyond what the OS provides for the settings file).
- Secret rotation scheduling (automatic key rotation).
- Secret sharing between multiple plugin instances.
- Access control (all secrets are accessible to all modules/tools).
- Audit log of secret access (which module read which secret when).
- Integration with external secret managers (HashiCorp Vault, AWS Secrets Manager).
- Secret value masking in the Logseq settings UI (Logseq shows the raw JSON string).

## Open Questions

1. **Settings UI masking:** Logseq's settings UI will show the JSON string including plaintext values. Should we use a custom UI panel instead? Recommendation: for v1, accept the limitation. Logseq's settings UI is only visible when the user opens it intentionally. Add a note in the description: "Values are visible in this settings field. Do not share screenshots of plugin settings."

2. **Maximum vault size:** Logseq settings are stored as JSON files. Is there a practical size limit? Recommendation: cap at 100 keys to keep the JSON manageable. Most users will have < 20 secrets.

3. **Secret name validation:** Should key names be restricted to alphanumeric + underscores? Recommendation: yes — `[A-Z0-9_]+` (uppercase with underscores, like environment variables) for consistency and to avoid interpolation edge cases.
