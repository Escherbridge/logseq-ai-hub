# src/routes — directory notes

## §mcp-session-lifecycle

`POST/GET/DELETE /mcp` (`mcp-transport.ts`) implement the MCP Streamable
HTTP transport. Each MCP session MUST get its own `McpServer` — the SDK's
`Protocol.connect()` throws `Already connected to a transport...` if you
`.connect()` a transport onto a server that's already connected to one.
Sharing a single server singleton across sessions (the original design)
meant only the first session after boot ever worked; every later session
(client reconnect, restart) got a 500.

- **Template vs. session servers.** `services/mcp-server.ts` keeps the
  original `createMcpServer()` singleton as a registration *template* and
  the source of truth for `/health`'s tool/resource/prompt counts
  (`getMcpStatus()` reflects on it, not on any session server). It is
  never `.connect()`-ed to a transport. `createBareMcpServer()` builds a
  fresh, unregistered instance; `registerSessionServerFactory()` /
  `createSessionServer()` let `index.ts` install the actual "fully
  registered session server" recipe (95 static tools + dynamic registry
  attachment) without `mcp-server.ts` itself knowing about tool
  registration.
- **Dynamic tool replay.** `services/mcp/dynamic-registry.ts`'s
  `DynamicRegistry` now targets a *list* of attached servers instead of
  one. `attach(server)` replays every previously-synced tool/prompt/
  resource onto a newly attached session server (via cached binder
  closures keyed by name) so a session that connects after a
  `syncFromBridge()` poll still sees the full dynamic catalogue, not just
  what existed at that server's connect time. `detach(server)` removes it
  so future syncs/notifications don't leak onto dead sessions.
- **Cleanup is re-entrant and must be idempotent.** For a single
  DELETE-driven close, the SDK invokes both the transport's
  `onsessionclosed` option callback and its `onclose` assignment
  independently, and a session's own `dispose()` (which calls
  `server.close()`) closes the same transport again, re-firing `onclose`
  synchronously from inside the first cleanup call. `mcp-transport.ts`'s
  `cleanupSession()` keys everything off `sessions.has(id)` — deleting the
  map entry *before* calling `dispose()` — so every re-entrant call after
  the first sees no entry and returns immediately instead of recursing.

## §llm-relay

`llm-relay.ts` forwards `POST /api/llm/chat/completions` and `GET /api/llm/models`
to the org's OpenAI-compatible gateway (LiteLLM, Azure, Cloudflare AI Gateway,
Portkey…) so the plugin's `hub-server` LLM mode works against gateways that send
no CORS headers (the plugin is a browser iframe and can't call them directly).

- Upstream credentials (`LLM_API_KEY` / `LLM_EXTRA_HEADERS`) live only on the
  server; the client's `Authorization` is the plugin token, never a provider
  key, and is never forwarded upstream — `buildUpstreamHeaders` (`services/llm.ts`)
  builds the outgoing header set from scratch.
- Upstream status and body are passed through **verbatim**, including 4xx/5xx,
  because the plugin's parser reads the provider's own `error.message` out of it.
- Errors this route originates itself are OpenAI-shaped `{ error: { message,
  type } }` instead of the server's usual `{success:false,error}`, for the same
  reason — one parser, one shape.
- `LLM_RELAY_TIMEOUT_SECONDS` (default 110, clamped to `[10, 240]`) sets how
  long the relay waits before a clean `relay_timeout` 504 — raise it for slow
  non-streamed reasoning-model generations. `services/sse.ts`'s
  `idleTimeoutSeconds(relayTimeoutSeconds)` derives Bun's `idleTimeout` from
  it (`max(HEARTBEAT_SECONDS * 8, relayTimeoutSeconds + 10)`, capped at Bun's
  255s ceiling) — that invariant must hold, or Bun drops the socket before
  the relay answers and the plugin sees a bare network error instead.
- **A broken LLM env var disables LLM, never the server.** `loadConfig()`
  wraps every LLM-only parse (`LLM_API_KEY`/`LLM_HTTP_REFERER`/`LLM_TITLE`/
  `LLM_EXTRA_HEADERS`/`LLM_RELAY_TOKENS`/`LLM_RELAY_TIMEOUT_SECONDS`) and on
  failure records the (never-raw-value) message in `config.llmConfigErrors`
  and falls back to the safe default instead of throwing — `loadConfig()`
  runs at top level in `index.ts`, so a bare throw there crash-loops the
  *whole* server (MCP, SSE, messaging) over one LLM typo. `hasLlmCredentials`
  returns `false` whenever `llmConfigErrors` is non-empty, so a partial
  failure (e.g. relay tokens rejected, key still parsed) can't run
  half-configured. The relay returns 503 `relay_misconfigured` (checked
  before `relay_not_configured`, after auth) instead of guessing; `/health`
  reports only the **count** (`llmRelay.errors`), never the messages.
- A failed fetch never sends its `error.message` to the plugin. Bun quotes an
  invalid header **value** in that message (`Header 'api-key' has invalid
  value: 'sk-…'`), so echoing it would hand the server's key to every plugin
  user. The client gets only the origin and the error code. The detail goes to
  the server log after `redactCredentials`. `parseLlmExtraHeaders` also rejects
  CR/LF/NUL, non-Latin-1 and blank values at startup, so that path should not
  trigger.
- `hasLlmCredentials` (`config.ts`) is the single "can we call upstream?" check
  (key **or** extra headers). It is shared by the relay, agent chat, the
  character routes and the character MCP tool, so an Azure-style `api-key`-only
  setup works everywhere, not just in the relay.
- The plugin's doctor tells the hub's own replies apart from relayed ones by
  **body**, not status: `relay_not_configured` (503), `{success:false,error:
  "Unauthorized"}` (401) and `{success:false,error:"Not found"}` (404). Keep
  those bodies stable, or the doctor will blame the wrong credential.
- **Auth is `authenticateRelay`, not `authenticate`**: the plugin token OR any
  `LLM_RELAY_TOKENS` entry. Relay tokens unlock *only* these two routes — never
  `/mcp` (graph read/write/delete, secrets) or `/api/send` — because the plugin
  token is the one credential that also unlocks those and the bridge broadcast
  to every linked plugin. One relay token per org user; revoke by removing it
  from the env var. Both comparisons are constant-time (`timingSafeEqual`).
- **The client body is allowlist-filtered**, not forwarded verbatim: only
  `model`/`messages`/`tools`/`tool_choice`/`parallel_tool_calls`/`temperature`/
  `top_p`/`max_tokens`/`max_completion_tokens`/`stop`/`response_format`/`seed`/
  `presence_penalty`/`frequency_penalty`/`reasoning` pass through. Everything
  else is dropped silently — notably `api_base`/`base_url`/`api_key` (LiteLLM
  honours these in the body, letting a client redirect or override the
  server's own upstream credentials — the CVE-2024-6587 class), `models`/
  `provider` (OpenRouter body-level routing overrides), and `stream`/`n`
  (would need buffering / are uncapped fan-out, neither of which this relay
  does).
- `LLM_ALLOWED_MODELS` (comma-separated) rejects any other model with 400
  `model_not_allowed` before upstream is ever called. Empty/unset = any model.
  `/api/llm/models` is unaffected (it doesn't take a model).
- The request body is capped at 4 MiB (`LLM_RELAY_MAX_BODY_BYTES`), checked
  against `Content-Length` first and then the actual decoded byte length (a
  client can omit or lie about `Content-Length`). Over the cap -> 413
  `payload_too_large`, upstream never called.
- The upstream fetch uses `redirect: "error"`: a cross-origin redirect drops
  the `Authorization` header but can carry a custom one (e.g. `api-key`)
  to an attacker-controlled host, so the relay treats any redirect as a
  failure instead of following it.
- The upstream response **body** is redacted (`redactCredentials`, longest
  secret first) before being returned — a gateway that echoes a bad credential
  in its own error body must not hand it to plugin users. Only `Content-Type`
  is forwarded from the upstream response; no other upstream header reaches
  the client.
