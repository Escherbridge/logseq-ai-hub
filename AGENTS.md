# AGENTS.md — install, configure and run Logseq AI Hub

Instructions for an AI coding agent asked to set this project up. Follow the phases in order and **run the stated verification after each one** — most failures in this project present as silence or an empty result, so an unverified step looks identical to a working one.

Two components:

| Component | What it is | Needed for |
|---|---|---|
| **Plugin** | ClojureScript, runs inside Logseq | everything |
| **Server** | Bun + SQLite, usually hosted | messaging, Event Hub, and exposing the graph over MCP |

The plugin works standalone for local features (`/LLM`, memory, secrets, job runner). Only add the server if the user wants messaging, webhooks, or MCP access.

---

## Phase 1 — Prerequisites

```bash
node --version    # >= 18
java -version     # >= 11 (shadow-cljs compiles on the JVM)
bun --version     # >= 1.0, only if deploying the server
```

Install anything missing before continuing. Java is required even though this is a JavaScript project.

---

## Phase 2 — Build the plugin

```bash
yarn install
npx shadow-cljs release app
```

**Verify — all three must hold:**

```bash
npm run verify:fresh                      # "OK: main.js is newer than all N files under src/main/"
grep -c '\.uuid\b' main.js                # must be > 0
ls -la main.js                            # exists, a few hundred KB
```

`.uuid` must survive minification. `externs/app.txt` reserves Logseq entity properties that Closure's advanced compilation would otherwise rename; without it the build succeeds and **every slash command silently breaks at runtime**. If you add code reading a new Logseq entity property, add its name to `externs/app.txt` and re-check with the grep above.

`main.js` is a build artifact and is gitignored. Logseq runs whatever is on disk, so **rebuild after every source change** — `npm test` runs `verify:fresh` and fails if you forget.

---

## Phase 3 — Install into Logseq

Ask the user which they want; do not assume.

- **Marketplace** — Logseq → Settings → Plugins → Marketplace → search "Logseq AI Hub".
- **Release zip** — download `logseq-ai-hub-<version>.zip` from the repo's latest release, unzip, then Logseq → Settings → Advanced → enable **Developer mode** → Plugins → **Load unpacked plugin** → select the unzipped folder.
- **From this working copy** (what you just built) — enable Developer mode, then **Load unpacked plugin** and select the repository root.

You cannot do this step from a shell; it requires the Logseq UI. Tell the user exactly which menu items to click and wait for confirmation before proceeding.

---

## Phase 4 — Configure the plugin

Settings live at **Logseq → Settings → Plugin Settings → Logseq AI Hub**. They are also stored at `~/.logseq/settings/logseq-ai-hub.json`, which you may read and edit directly — but **Logseq caches settings in memory and will overwrite your file edits on exit**, so quit Logseq first, edit, then restart.

Minimum for `/LLM`:

| Setting | Value |
|---|---|
| `llmApiKey` | an OpenRouter (or any OpenAI-compatible) key |
| `llmEndpoint` | `https://openrouter.ai/api/v1` |
| `llmModel` | e.g. `anthropic/claude-sonnet-4` |

Validate the key before blaming anything else — this costs no completion tokens:

```bash
curl -s https://openrouter.ai/api/v1/models \
  -H "Authorization: Bearer $KEY" -o /dev/null -w '%{http_code}\n'   # 200
```

Two traps worth knowing, both already defended in code but still worth checking if you are debugging an old build:

- A key pasted with **leading or trailing whitespace** produces `Bearer  sk-…` and a provider 401 reading *"Missing Authentication header"*, which sounds like a missing key rather than a malformed one.
- A **cleared** numeric or text field is stored as `""`, which is **truthy** in ClojureScript. Read settings through `logseq-ai-hub.settings` (`text` / `number` / `flag`), never `(or (aget settings k) default)`.

Prefer a non-reasoning model for `/LLM`. Reasoning models can spend their whole token budget on `reasoning` and return `content: null`.

---

## Phase 5 — Deploy the server (only if needed)

The server is a single Bun process with a SQLite database. `server/` ships a `Dockerfile` and a `railway.json`.

**Railway**

1. Create a service from the GitHub repo, set **Root Directory** to `/server`.
2. Attach a **Volume** mounted at `/app/data` — SQLite lives there and must survive redeploys.
3. Set variables, then generate a public domain.

| Variable | Required | Value |
|---|---|---|
| `PLUGIN_API_TOKEN` | yes | `openssl rand -hex 32` |
| `DATABASE_PATH` | yes | `/app/data/hub.sqlite` (inside the volume) |
| `LLM_API_KEY` | for agent endpoints | provider key |
| `WEBHOOK_SECRET` | recommended | `openssl rand -hex 24` |
| `BASE_URL` | auto on Railway | public origin for outbound links; derived from `RAILWAY_PUBLIC_DOMAIN` if unset |

Full list: `server/.env.example`.

**Docker anywhere**

```bash
cd server
docker build -t logseq-ai-hub-server .
docker run -p 3000:3000 -v "$PWD/data:/app/data" \
  -e PLUGIN_API_TOKEN=... -e DATABASE_PATH=/app/data/hub.sqlite logseq-ai-hub-server
```

**Verify:**

```bash
curl -s https://<server>/health | jq '{status, mcpTools: .mcp.tools}'
# {"status":"ok","mcpTools":104}
```

---

## Phase 6 — Link the plugin to the server

In plugin settings set **Webhook Server URL** to the server origin, **Authentication Mode** to `token`, and **Plugin API Token** to the same value as the server's `PLUGIN_API_TOKEN`. Reload the plugin.

**Verify the link — this is the field that matters:**

```bash
curl -s https://<server>/health | jq '.agentApi.pluginConnected'   # true
```

`pluginConnected` is `sseManager.clientCount > 0`. A healthy server with `pluginConnected: false` means the server is fine and **the plugin is not attached** — check the token match and that Logseq is running with the plugin enabled. If it alternates between `true` and `false` within seconds, the SSE stream is being closed by an intermediate proxy; that is a flap, not a config error.

---

## Phase 7 — Connect an MCP client

The MCP endpoint is on the **server**, not the plugin: `POST/GET/DELETE /mcp`, streamable HTTP, bearer auth.

```json
{
  "mcpServers": {
    "logseq-ai-hub": {
      "type": "url",
      "url": "https://<server>/mcp",
      "headers": { "Authorization": "Bearer <PLUGIN_API_TOKEN>" }
    }
  }
}
```

The server also serves this snippet at `GET /mcp/config` (no auth).

**Verify without a client.** A correct handshake is three calls — `initialize`, then the `notifications/initialized` notification, then your request. Skipping the notification makes `tools/list` come back empty, which looks like a broken server:

```bash
U=https://<server>/mcp
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json"
   -H "Accept: application/json, text/event-stream")

# unauthenticated must be rejected
curl -s -o /dev/null -w '%{http_code}\n' -X POST $U -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'          # 401

INIT=$(curl -si -X POST $U "${H[@]}" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}')
SID=$(echo "$INIT" | grep -i '^mcp-session-id:' | tr -d '\r' | awk '{print $2}')
curl -s -X POST $U "${H[@]}" -H "mcp-session-id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
curl -s -X POST $U "${H[@]}" -H "mcp-session-id: $SID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | sed -n 's/^data: //p' | jq '.result.tools | length'                     # 104
```

Responses are server-sent events, so the JSON body arrives on `data:` lines — pipe through `sed -n 's/^data: //p'` before `jq`.

Run `initialize` **twice** as a regression check: both must return 200 with different `mcp-session-id` values. A 500 on the second means the server is sharing one MCP instance across sessions and only the first client will ever work.

Of the 104 tools, 14 are purely server-local and work with Logseq closed — approvals (1), character sessions (3), messaging (3) and agent sessions (7). The rest live in modules that proxy at least some calls into Logseq over SSE (`graph_*`, `page_*`, `block_*`, memory, jobs, registry, projects, ADRs, lessons, safeguards, tasks, pi-agents); a few of those modules mix bridge and database calls, so check the individual tool rather than assuming per module. A bridge-backed tool called while the plugin is disconnected fails with *"Plugin not connected"* — that is `AgentBridge.sendRequest` refusing up front, not the tool being broken.

### Working with the graph

Clients receive a short usage guide in the server's `instructions` on `initialize`. The core loop is find, read, then edit by uuid:

```text
graph_search  {query: "rust ownership"}                → hits with ⟨uuid⟩ and snippets
page_read     {name: "Rust"}                           → indented outline, one ⟨uuid⟩ per block
block_insert  {uuid: "<uuid>", content: "- Borrowing\n  - &T vs &mut T"}   → new uuids
page_create   {name: "Borrow checker", content: "- ...", properties: {tags: ["rust"]}}
page_link     {from: "Rust", to: "Borrow checker"}     → related:: [[Borrow checker]], a backlink on the target
page_links    {name: "Borrow checker"}                 → shows Rust as a backlink
block_delete  {uuids: ["<uuid>", "<uuid>"]}            → all-or-nothing
```

Read tools return compact text rather than JSON to save context. On large pages pass `max_blocks` to `page_read` and drill into a branch with `block_get`. Full reference: `docs/mcp-tools.md` § Graph Tools.

---

## Phase 8 — Final verification

Have the user run **`/ai-hub:doctor`** in any Logseq block. It replaces the block with a checklist and actually exercises the configuration — it calls the provider and the server rather than just printing settings:

```
## AI Hub diagnostics
✅ **LLM** — key sk-or-…e79b accepted by https://openrouter.ai/api/v1 (820ms). Model: anthropic/claude-sonnet-4
✅ **Server** — https://… is healthy and THIS plugin is linked (104 MCP tools).
⚠️ **Job Runner** — disabled — /job:* will queue jobs that never execute.
```

Resolve every ❌ before declaring the setup done. ⚠️ lines are usually intentional (a feature the user has not enabled).

Then confirm one real flow: type a question in a block and run `/LLM`. The reply should appear as a child block.

---

## Working on the code

```bash
npm test                 # CLJS suite + build-freshness guard
npm run test:server      # cd server && bun test
cd server && bun run typecheck
npx shadow-cljs watch app   # dev build with hot reload
```

CI runs all three (`ClojureScript Tests`, `Server Tests`, `Server Typecheck`) and all must stay green.

Conventions that are load-bearing here:

- **Bun does not type-check.** `bun test` passing does not mean the code compiles. Run `bunx tsc --noEmit`; it is at zero and gated in CI. A syntax error once reached production this way.
- **Zod is v4.** `z.record()` takes two arguments — `z.record(z.string(), z.unknown())`. The one-argument form constructs fine and throws only when the field is actually supplied.
- **`logseq.DB.datascriptQuery` strips the datalog namespace.** A query pulling `[:block/name]` returns `{"name": ...}`, so after `js->clj` you read `(:name r)`, not `(:block/name r)`. The namespaced keywords remain correct **inside** the query string.
- **Never let a failure resolve to a legal-looking value.** Returning `nil` / `[]` / `""` on error is how this project shipped commands that reported success while doing nothing. Return a tagged `{:ok false :reason ...}` or reject.
- **Mocks must match the real host API.** Several test mocks once encoded the wrong `datascriptQuery` shape, so a green suite verified the code against an API that does not exist.
- `with-redefs` is **broken** with promises in CLJS — vars restore before callbacks fire. Use `set!` on the JS global and restore manually.
- Prefer directory-level `AGENTS.md` notes over long comment blocks; see `server/src/routes/AGENTS.md`.

## When something does not work

The README's **Troubleshooting** section maps symptoms to causes. Start there, and start with `/ai-hub:doctor`.
