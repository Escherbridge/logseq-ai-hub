# Development Guide

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

Edit `server/.env` with your values. See [Configuration](configuration.md) for all available settings.

For local development, only `PLUGIN_API_TOKEN` is required. Set it to any string (e.g. `my-secret-token`). Set `LLM_API_KEY` to enable the agent chat endpoint.

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
   - **Webhook Server URL**: `http://localhost:3000`
   - **Plugin API Token**: the same value you set in `server/.env`

The plugin will connect to the server via SSE and start receiving messages in real time.

## Building

### Plugin (ClojureScript)

```shell
# Watch mode (auto-recompile on save)
yarn watch

# Production build
yarn release
```

The plugin compiles to `main.js` in the project root. Logseq loads it from `index.html`.

### Server (Bun/TypeScript)

```shell
cd server

# Dev mode with file watching
bun run dev

# Production start (no file watching)
bun run start
```

## Testing

### Plugin Tests

```shell
# Compile and run all plugin tests (346 tests, 885+ assertions)
yarn test

# Or manually:
npx shadow-cljs compile node-test && node out/test/node-tests.js
```

The plugin test suite uses `cljs.test` with async test support for Promise-based code. Tests run in Node.js via the `node-test` shadow-cljs build target.

### Server Tests

```shell
cd server && bun test
```

### Test Coverage Summary

| Component | Tests | Assertions |
|-----------|-------|-----------|
| Plugin: agent | 9 | 16+ |
| Plugin: messaging | 13 | 35+ |
| Plugin: memory | 24 | 60+ |
| Plugin: tasks | 19 | 60+ |
| Plugin: sub-agents | 15 | 40+ |
| Plugin: core (settings) | 8 | 20+ |
| Plugin: agent-bridge | 5 | 15+ |
| Plugin: settings-writer | 5 | 10+ |
| Plugin: job-runner (16 files) | 181 | 480+ |
| Server: all (7 files) | 67 | 118+ |
| **Total** | **346** | **885+** |

## REPL Development

shadow-cljs exposes an nREPL on port 8702. Connect from your editor using the shadow-cljs nREPL middleware. See the [shadow-cljs REPL docs](https://shadow-cljs.github.io/docs/UsersGuide.html#cider) for setup with CIDER, Calva, or Cursive.

## Testing Webhooks Locally

Simulate a WhatsApp webhook by POSTing a payload to the server:

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

Verify it was stored:
```shell
curl http://localhost:3000/api/messages -H "Authorization: Bearer <your-token>"
```

## Setting Up WhatsApp Webhooks

1. Create a [Meta Developer App](https://developers.facebook.com/) with the WhatsApp product
2. In the WhatsApp product settings, configure the webhook:
   - **Callback URL**: `https://<your-server>/webhook/whatsapp`
   - **Verify token**: the value of `WHATSAPP_VERIFY_TOKEN` in your `.env`
   - Subscribe to the `messages` field
3. Set `WHATSAPP_ACCESS_TOKEN` and `WHATSAPP_PHONE_NUMBER_ID` from the API setup page

## Setting Up Telegram Webhooks

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

## Project Structure

```
.
├── src/
│   ├── main/logseq_ai_hub/       # Plugin source (ClojureScript)
│   ├── test/logseq_ai_hub/       # Plugin tests
│   └── dev/shadow/user.cljs      # REPL dev helpers
├── server/                        # Webhook server (Bun/TypeScript)
│   ├── src/                       # Server source
│   ├── tests/                     # Server tests
│   └── package.json
├── shadow-cljs.edn               # ClojureScript build config
├── package.json                   # Plugin manifest + scripts
└── index.html                     # Logseq plugin entry HTML
```

See [Architecture](architecture.md) for detailed module breakdowns.
