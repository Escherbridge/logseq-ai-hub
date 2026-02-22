# Configuration

## Server Environment Variables

Configure in `server/.env` (copy from `server/.env.example`).

| Variable | Required | Default | Description |
|----------|----------|---------|------------|
| `PORT` | No | `3000` | Server port |
| `PLUGIN_API_TOKEN` | **Yes** | — | Shared secret between server and plugin |
| `WHATSAPP_VERIFY_TOKEN` | No | — | Token for WhatsApp webhook verification |
| `WHATSAPP_ACCESS_TOKEN` | No | — | WhatsApp Cloud API access token |
| `WHATSAPP_PHONE_NUMBER_ID` | No | — | WhatsApp business phone number ID |
| `TELEGRAM_BOT_TOKEN` | No | — | Telegram Bot API token from @BotFather |
| `DATABASE_PATH` | No | `./data/hub.sqlite` | SQLite file path |
| `LLM_API_KEY` | No | — | OpenRouter API key for agent chat |
| `LLM_ENDPOINT` | No | `https://openrouter.ai/api/v1` | LLM endpoint |
| `AGENT_MODEL` | No | `anthropic/claude-sonnet-4` | Model for agent chat |
| `AGENT_REQUEST_TIMEOUT` | No | `30000` | Agent bridge timeout in ms |

For local development, only `PLUGIN_API_TOKEN` is required. Set `LLM_API_KEY` to enable the agent chat endpoint.

## Plugin Settings

All settings are configured in the Logseq plugin settings panel.

### Connection

| Setting | Type | Default | Description |
|---------|------|---------|------------|
| `webhookServerUrl` | string | `""` | URL of the AI Hub webhook server |
| `pluginApiToken` | string | `""` | Shared secret for server authentication |

### LLM Provider

| Setting | Type | Default | Description |
|---------|------|---------|------------|
| `llmApiKey` | string | `""` | LLM provider API key (OpenRouter, etc.) |
| `llmEndpoint` | string | `https://openrouter.ai/api/v1` | LLM API endpoint URL |
| `llmModel` | string | `anthropic/claude-sonnet-4` | Model ID for LLM calls |
| `selectedModel` | enum | `llm-model` | Model for `/LLM` command |

### AI Memory

| Setting | Type | Default | Description |
|---------|------|---------|------------|
| `memoryEnabled` | boolean | `false` | Enable the AI memory system |
| `memoryPagePrefix` | string | `AI-Memory/` | Prefix for memory pages |

### Job Runner

| Setting | Type | Default | Description |
|---------|------|---------|------------|
| `jobRunnerEnabled` | boolean | `false` | Enable the autonomous job runner |
| `jobRunnerMaxConcurrent` | number | `3` | Max simultaneous jobs |
| `jobRunnerPollInterval` | number | `5000` | Queue poll interval (ms) |
| `jobRunnerDefaultTimeout` | number | `300000` | Default job timeout (ms) |
| `jobPagePrefix` | string | `Jobs/` | Prefix for job pages |
| `skillPagePrefix` | string | `Skills/` | Prefix for skill pages |

### MCP Servers

| Setting | Type | Default | Description |
|---------|------|---------|------------|
| `mcpServers` | string | `[]` | JSON array of MCP server configs |

MCP server config format:
```json
[
  {
    "id": "filesystem",
    "url": "http://localhost:8080/mcp",
    "auth-token": "optional-token"
  }
]
```

### Settings Migration

On first load after upgrading, the plugin automatically copies old settings to new names:
- `openAIKey` → `llmApiKey`
- `openAIEndpoint` → `llmEndpoint`
- `chatModel` → `llmModel`

Old settings are preserved for backward compatibility.
