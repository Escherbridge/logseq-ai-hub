# Tech Stack: Logseq AI Hub

## Language
- **ClojureScript** — Primary language for all plugin and server code
- Compiles to JavaScript via shadow-cljs

## Build Tooling
- **shadow-cljs** `2.16.2` — ClojureScript compiler and build tool
  - Dev server on port 8080
  - nREPL on port 8702 for interactive development
  - Browser target for plugin, Node target for webhook server
- **yarn/npm** — Package management

## Plugin Framework
- **Logseq Plugin API** — `js/logseq.*` interop
  - `logseq.Editor` — Block/page manipulation
  - `logseq.App` — Notifications, navigation
  - `logseq.DB` — Graph queries
  - `logseq.settings` — Plugin configuration
  - `logseq.ready` — Initialization lifecycle

## External APIs
- **OpenAI API** (or compatible endpoints) — LLM inference
- **WhatsApp Cloud API** — Message webhooks and sending
- **Telegram Bot API** — Message webhooks and sending

## Server (Webhook Receiver)
- **ClojureScript on Node.js** — Webhook server for Railway deployment
- **shadow-cljs `:node-script`** target for server build
- HTTP server library TBD (likely `http` built-in or lightweight CLJS wrapper)

## Hosting
- **Railway** — Webhook server deployment
- **Logseq Marketplace** — Plugin distribution

## Testing
- **cljs.test** — Unit testing framework
- **shadow-cljs test runner** — Test execution
- Manual testing with Logseq desktop/web app

## Project Structure
```
logseq-ai-hub/
├── src/
│   ├── main/
│   │   └── ai_hub/
│   │       ├── core.cljs          # Plugin entry point
│   │       ├── agent.cljs         # Model registry & dispatch
│   │       ├── messaging.cljs     # WhatsApp/Telegram integration
│   │       ├── memory.cljs        # AI memory storage/retrieval
│   │       ├── tasks.cljs         # Task orchestration
│   │       └── server.cljs        # Webhook server (Node target)
│   ├── test/
│   │   └── ai_hub/
│   │       ├── agent_test.cljs
│   │       ├── messaging_test.cljs
│   │       ├── memory_test.cljs
│   │       └── tasks_test.cljs
│   └── dev/
│       └── shadow/
│           └── user.cljs
├── conductor/                     # Conductor context
├── shadow-cljs.edn               # Build config
├── package.json
└── README.md
```

## Key Dependencies
- `shadow-cljs` — Build tooling
- Logseq Plugin SDK (loaded at runtime, not bundled)
- No additional CLJS dependencies currently; will add as needed
