# Logseq AI Hub

A Logseq plugin that connects WhatsApp and Telegram messages to your knowledge graph. Incoming messages arrive via webhooks, stream to the plugin over SSE, and get automatically written into Logseq as pages and blocks. Includes an AI memory system and a task orchestration engine.

The project has two parts:

1. **Plugin** (ClojureScript) — runs inside Logseq, receives real-time messages via SSE, writes them into your graph
2. **Server** (Bun/TypeScript) — standalone webhook server that receives WhatsApp and Telegram messages, persists them in SQLite, and streams events to the plugin

## Architecture

```
WhatsApp Cloud API ──┐
                     ├──▶ Bun Server (webhook ingestion, SQLite, SSE) ──▶ Logseq Plugin
Telegram Bot API ────┘         │                                              │
                               │  POST /webhook/whatsapp                      │ EventSource
                               │  POST /webhook/telegram                      │ /events?token=...
                               │  GET  /api/messages                          │
                               │  POST /api/send                              ▼
                               │  GET  /events (SSE)                  Logseq Graph
                               │  GET  /health                        └── AI Hub/WhatsApp/John Doe
                               ▼                                      └── AI Hub/Telegram/Alice
                          SQLite DB                                   └── AI-Memory/meetings
                          (contacts, messages)                        └── AI-Memory/ideas
```

## Prerequisites

- [Node.js](https://nodejs.org/) (v18+) — for shadow-cljs
- [Bun](https://bun.sh/) (v1+) — for the webhook server
- [Yarn](https://yarnpkg.com/) (v1) — for plugin dependencies
- [Logseq](https://logseq.com/) desktop app — with developer mode enabled

## Quick Start

### 1. Install dependencies

```shell
# Plugin dependencies (root directory)
yarn

# Server dependencies
cd server
bun install
cd ..
```

### 2. Configure the server

```shell
cp server/.env.example server/.env
```

Edit `server/.env` with your values:

| Variable | Required | Description |
|---|---|---|
| `PORT` | No | Server port (default: `3000`) |
| `PLUGIN_API_TOKEN` | **Yes** | Shared secret between server and plugin |
| `WHATSAPP_VERIFY_TOKEN` | No | Token for WhatsApp webhook verification |
| `WHATSAPP_ACCESS_TOKEN` | No | WhatsApp Cloud API access token |
| `WHATSAPP_PHONE_NUMBER_ID` | No | WhatsApp business phone number ID |
| `TELEGRAM_BOT_TOKEN` | No | Telegram Bot API token from @BotFather |
| `DATABASE_PATH` | No | SQLite file path (default: `./data/hub.sqlite`) |

For local development, only `PLUGIN_API_TOKEN` is required. Set it to any string (e.g. `my-secret-token`).

### 3. Start the server

```shell
cd server
bun run dev
```

The server starts with file watching on `http://localhost:3000`. The `data/` directory and SQLite database are created automatically on first run.

### 4. Build and load the plugin

```shell
# In the root directory
yarn watch
```

In Logseq:
1. Go to **Settings > Advanced > Developer Mode** and enable it
2. Click **Plugins > Load unpacked plugin** and select this project folder
3. Open plugin settings and configure:
   - **Webhook Server URL**: `http://localhost:3000` (or whatever port your server is on)
   - **Plugin API Token**: the same value you set in `server/.env`

The plugin will connect to the server via SSE and start receiving messages in real time.

![Reload button](resources/screenshot1.png)

## Project Structure

```
.
├── src/
│   ├── main/logseq_ai_hub/       # Plugin source (ClojureScript)
│   │   ├── core.cljs              # Entry point, settings schema, init
│   │   ├── agent.cljs             # LLM model registry and dispatch
│   │   ├── messaging.cljs         # SSE client, message ingestion
│   │   ├── memory.cljs            # AI memory store/retrieve system
│   │   └── tasks.cljs             # Task orchestration engine
│   ├── test/logseq_ai_hub/        # Plugin tests
│   │   ├── agent_test.cljs
│   │   ├── messaging_test.cljs
│   │   ├── memory_test.cljs
│   │   └── tasks_test.cljs
│   └── dev/shadow/user.cljs       # REPL dev helpers
├── server/                         # Webhook server (Bun/TypeScript)
│   ├── src/
│   │   ├── index.ts               # Bun.serve() entry point
│   │   ├── router.ts              # URL routing + CORS middleware
│   │   ├── config.ts              # Environment variable loading
│   │   ├── types.ts               # Shared TypeScript interfaces
│   │   ├── db/
│   │   │   ├── connection.ts      # SQLite singleton (WAL mode)
│   │   │   ├── schema.ts          # Table definitions
│   │   │   ├── contacts.ts        # Contact CRUD
│   │   │   └── messages.ts        # Message CRUD with deduplication
│   │   ├── routes/
│   │   │   ├── health.ts          # GET /health
│   │   │   ├── events.ts          # GET /events (SSE stream)
│   │   │   ├── webhooks/
│   │   │   │   ├── whatsapp.ts    # WhatsApp verification + ingestion
│   │   │   │   └── telegram.ts    # Telegram update handling
│   │   │   └── api/
│   │   │       ├── send.ts        # POST /api/send (outgoing messages)
│   │   │       └── messages.ts    # GET /api/messages (history query)
│   │   └── services/
│   │       ├── sse.ts             # SSE connection manager + heartbeat
│   │       ├── whatsapp.ts        # WhatsApp Cloud API client
│   │       └── telegram.ts        # Telegram Bot API client
│   ├── tests/
│   │   ├── helpers.ts             # Test DB + seed utilities
│   │   ├── db.test.ts             # Contact and message CRUD
│   │   ├── health.test.ts         # Health endpoint
│   │   ├── webhook-whatsapp.test.ts
│   │   ├── webhook-telegram.test.ts
│   │   ├── sse.test.ts            # SSE manager unit tests
│   │   ├── api-send.test.ts       # Send API tests
│   │   ├── api-messages.test.ts   # Messages API tests
│   │   └── integration.test.ts    # Full webhook-to-API flow
│   ├── Dockerfile                 # Production container (oven/bun:1)
│   ├── railway.json               # Railway deployment config
│   ├── .env.example               # Environment variable template
│   └── package.json
├── shadow-cljs.edn                # ClojureScript build config
├── package.json                    # Plugin manifest + scripts
└── index.html                     # Logseq plugin entry HTML
```

## Plugin Modules

### core.cljs — Entry Point

Initializes the plugin when Logseq loads. Registers the settings schema (server URL, API token, memory config, AI model selection) and the `/LLM` slash command. Calls `init!` on each module in order: messaging, memory, tasks.

### agent.cljs — LLM Model Registry

A pluggable model system. Models are registered by ID with a handler function that takes input text and returns a Promise of the response.

**Built-in models:**
- `mock-model` — echoes input back (for testing)
- `reverse-model` — reverses the input string
- `openai-model` — calls OpenAI-compatible API (configurable endpoint, model, and key)

**Slash command:** `/LLM` — processes the current block's text through the selected model and inserts the response as a child block.

### messaging.cljs — SSE Client + Message Ingestion

Connects to the webhook server via `EventSource` and streams incoming messages into Logseq pages.

**How it works:**
1. On `init!`, reads `webhookServerUrl` and `pluginApiToken` from settings
2. Opens an SSE connection to `<server>/events?token=<token>`
3. Listens for `new_message` and `message_sent` events
4. For each message, calls all registered handlers (default: `ingest-message!`)

**`ingest-message!`** creates a page named `AI Hub/<Platform>/<Contact Name>` and appends a block:
```
**John Doe** (WhatsApp) - 2026-02-11 14:30
Hello from WhatsApp!
platform:: whatsapp
sender:: whatsapp:15551234567
message-id:: 42
direction:: incoming
```

**`send-message!`** sends an outgoing message by POSTing to `/api/send` with Bearer auth.

### memory.cljs — AI Memory System

Stores and retrieves memories as Logseq pages under a configurable prefix (default: `AI-Memory/`).

**Settings:**
- **Enable AI Memory** — toggle the system on/off
- **Memory Page Prefix** — page name prefix (default: `AI-Memory/`)

**Slash commands (available when memory is enabled):**
- `/ai-memory:store` — stores the current block as a memory. Format: `tag: content`. Creates page `AI-Memory/<tag>` and appends the content with a timestamp.
- `/ai-memory:recall` — searches all memory pages for blocks matching the current block's text using Datalog queries. Inserts results as a child block.

**API:**
- `store-memory!` [tag content] — programmatic memory storage
- `retrieve-by-tag` [tag] — get all blocks from a specific tag page
- `retrieve-memories` [query-str] — full-text search across all memory pages
- `clear-memories!` — delete all memory pages

### tasks.cljs — Task Orchestration Engine

A pipeline system that chains actions together in response to triggers. Tasks are defined as a sequence of steps, where each step's output feeds into the next step's input.

**Step actions:**
| Action | Module | Input |
|---|---|---|
| `:ai-process` | `agent/process-input` | content string |
| `:send-message` | `messaging/send-message!` | `{:platform :recipient :content}` |
| `:store-memory` | `memory/store-memory!` | `{:tag :content}` |
| `:logseq-ingest` | `messaging/ingest-message!` | message map |
| `:logseq-insert` | `logseq.Editor.insertBlock` | `{:block-uuid :content}` |

**Built-in task:**
- `message-to-logseq` — automatically ingests every incoming message into Logseq (trigger: `:on-new-message`)

**Slash commands:**
- `/task:list` — lists all registered tasks with their enabled/disabled status
- `/task:run <task-id>` — manually runs a task by ID

**Registering custom tasks:**
```clojure
(tasks/register-task!
  {:id :auto-summarize
   :name "Auto Summarize Messages"
   :steps [{:action :ai-process :input-from :trigger}
           {:action :store-memory}]
   :trigger :on-new-message
   :enabled true})
```

## Server API

All API routes (except webhooks and health) require authentication.

### Authentication

- **REST API**: `Authorization: Bearer <PLUGIN_API_TOKEN>` header
- **SSE**: `?token=<PLUGIN_API_TOKEN>` query parameter (EventSource doesn't support custom headers)

### Endpoints

#### `GET /health`
Returns server status. No auth required.
```json
{"status": "ok", "uptime": 123, "sseClients": 1}
```

#### `GET /events`
SSE stream. Requires `?token=` query param. Emits:
- `connected` — on initial connection
- `new_message` — when a message arrives via webhook
- `message_sent` — when an outgoing message is sent via API
- `heartbeat` — every 30 seconds

#### `GET /webhook/whatsapp`
WhatsApp webhook verification (challenge-response). No auth.

#### `POST /webhook/whatsapp`
WhatsApp message ingestion. Parses the nested Cloud API payload, upserts contact, stores message (deduplicates by `external_id`), broadcasts SSE event.

#### `POST /webhook/telegram`
Telegram update handling. Parses the Update object, extracts sender and message text, stores and broadcasts.

#### `POST /api/send`
Send an outgoing message. Bearer auth required.
```json
{"platform": "whatsapp", "recipient": "15551234567", "content": "Hello!"}
```

#### `GET /api/messages`
Query message history. Bearer auth required. Query params:
- `contact_id` — filter by contact (e.g. `whatsapp:15551234567`)
- `limit` — max results (default: 50)
- `offset` — pagination offset (default: 0)
- `since` — ISO timestamp filter

### Database Schema

**contacts** — `id` (PK, format: `platform:user_id`), `platform`, `platform_user_id`, `display_name`, `metadata`, timestamps

**messages** — `id` (auto PK), `external_id` (unique, for dedup), `contact_id` (FK), `platform`, `direction`, `content`, `media_type`, `media_url`, `status`, `raw_payload`, `created_at`

## Development

### Plugin (ClojureScript)

```shell
# Watch mode (auto-recompile on save)
yarn watch

# Run tests (54 tests, 149 assertions)
yarn test

# Production build
yarn release
```

The plugin compiles to `main.js` in the project root. Logseq loads it from `index.html`.

**REPL:** shadow-cljs exposes an nREPL on port 8702. Connect from your editor using the shadow-cljs nREPL middleware. See the [shadow-cljs REPL docs](https://shadow-cljs.github.io/docs/UsersGuide.html#cider) for setup with CIDER, Calva, or Cursive.

### Server (Bun/TypeScript)

```shell
cd server

# Dev mode with file watching
bun run dev

# Run all tests (36 tests: 26 unit + 10 integration)
bun test

# Production start (no file watching)
bun run start
```

### Testing a webhook locally

You can simulate a WhatsApp webhook by POSTing a payload to the server:

```shell
curl -X POST http://localhost:3000/webhook/whatsapp \
  -H "Content-Type: application/json" \
  -d '{
    "object": "whatsapp_business_account",
    "entry": [{
      "id": "BIZ",
      "changes": [{
        "value": {
          "messaging_product": "whatsapp",
          "metadata": {"display_phone_number": "15551234567", "phone_number_id": "123456"},
          "contacts": [{"profile": {"name": "Test User"}, "wa_id": "15559999999"}],
          "messages": [{
            "from": "15559999999",
            "id": "wamid_test_1",
            "timestamp": "1739100000",
            "text": {"body": "Hello from local test!"},
            "type": "text"
          }]
        },
        "field": "messages"
      }]
    }]
  }'
```

Then check it was stored:
```shell
curl http://localhost:3000/api/messages -H "Authorization: Bearer <your-token>"
```

### Setting up WhatsApp webhooks

1. Create a [Meta Developer App](https://developers.facebook.com/) with the WhatsApp product
2. In the WhatsApp product settings, configure the webhook:
   - **Callback URL**: `https://<your-server>/webhook/whatsapp`
   - **Verify token**: the value of `WHATSAPP_VERIFY_TOKEN` in your `.env`
   - Subscribe to the `messages` field
3. Set `WHATSAPP_ACCESS_TOKEN` and `WHATSAPP_PHONE_NUMBER_ID` from the API setup page

### Setting up Telegram webhooks

1. Create a bot via [@BotFather](https://t.me/BotFather) and get the bot token
2. Set the webhook URL:
   ```shell
   curl "https://api.telegram.org/bot<TOKEN>/setWebhook?url=https://<your-server>/webhook/telegram"
   ```
3. Set `TELEGRAM_BOT_TOKEN` in your `.env`

## Deployment (Railway)

The server includes a `Dockerfile` and `railway.json` for one-click Railway deployment.

1. Push to a Git repo
2. Create a new Railway project from the repo
3. Set the root directory to `server/`
4. Add all environment variables from `.env.example` in the Railway dashboard
5. Railway will build and deploy using the Dockerfile
6. Update your plugin settings with the Railway URL (e.g. `https://your-app.railway.app`)
7. Update your WhatsApp/Telegram webhook URLs to point to the Railway domain

## Test Summary

| Component | Tests | Assertions |
|---|---|---|
| Plugin: agent | 6 | 10 |
| Plugin: messaging | 13 | 35+ |
| Plugin: memory | 16 | 40+ |
| Plugin: tasks | 19 | 60+ |
| Server: unit | 26 | 60+ |
| Server: integration | 10 | 20+ |
| **Total** | **90** | **225+** |

## License

MIT
