# Specification: IoT/Infrastructure Hooks

## Overview

Extend the Logseq AI Hub to integrate with IoT devices and infrastructure services through a generic webhook ingestion system, outbound HTTP action capabilities, and infrastructure monitoring tools. This enables the Hub to serve as a lightweight IT operations dashboard and automation layer — receiving alerts, triggering actions, and maintaining an operational knowledge base.

The approach is **protocol-agnostic and webhook-driven**: rather than building specific integrations for each IoT platform or infrastructure service, the Hub provides generic webhook endpoints that any service can POST to, combined with outbound HTTP tools for triggering actions on external services.

## Background

The system already has:
- **WhatsApp/Telegram webhooks** — specific platform integrations for receiving messages
- **Job runner** — can execute multi-step automation skills
- **MCP client** — can connect to external MCP servers for additional capabilities
- **MCP server** (from mcp-server track) — Claude Code can interact with the Hub

What's missing:
- **Generic webhook ingestion** — only WhatsApp and Telegram are supported
- **Outbound HTTP actions** — no way to call external APIs from skills/tools
- **Infrastructure monitoring pages** — no convention for tracking service health
- **Alert routing** — no way to route incoming alerts to the right person/agent

## Dependencies

- **mcp-server_20260221**: MCP server for exposing infrastructure tools.
- **human-in-loop_20260221**: Approval system for infrastructure actions.
- **kb-tool-registry_20260221**: Registry for infrastructure tool definitions.
- **secrets-manager_20260221**: Secret interpolation (`{{secret.KEY_NAME}}`) for outbound HTTP headers and bodies.

## Functional Requirements

### FR-1: Generic Webhook Ingestion

**Description:** A configurable webhook endpoint that accepts payloads from any service (monitoring tools, IoT platforms, CI/CD, etc.) and routes them into the knowledge base.

**Endpoint:** `POST /webhook/generic/:source`

**Acceptance Criteria:**
- Accepts any JSON payload at `POST /webhook/generic/{source}` where `source` is a user-defined identifier (e.g., `grafana`, `home-assistant`, `github-actions`, `uptime-robot`).
- Stores the raw payload in SQLite with source, timestamp, and content.
- Broadcasts an SSE event: `{type: "webhook_received", source, payload}`.
- Plugin creates a Logseq page: `AI Hub/Webhooks/{Source}/{timestamp}` with the payload formatted as properties and content.
- Optional: webhook sources can be configured with a transformation template (stored as a Logseq page) that extracts specific fields from the payload.
- Auth: webhooks can be configured per-source with a verify token (similar to WhatsApp verification) or left open.
- Rate limiting: max 100 webhooks per minute per source.

**Priority:** P0

### FR-2: Webhook Source Configuration Pages

**Description:** Logseq pages that configure how incoming webhooks from a source are processed.

**Page Format:**
```
webhook-source:: grafana
webhook-description:: Grafana alerting webhooks
webhook-verify-token:: my-grafana-secret
webhook-page-prefix:: AI Hub/Alerts/Grafana
webhook-extract-title:: $.alerts[0].labels.alertname
webhook-extract-severity:: $.alerts[0].labels.severity
webhook-extract-message:: $.alerts[0].annotations.summary
webhook-route-to:: whatsapp:15551234567
webhook-auto-job:: Skills/alert-handler
tags:: logseq-ai-hub-webhook-source
```

**Acceptance Criteria:**
- Pages tagged `logseq-ai-hub-webhook-source` configure webhook processing.
- `webhook-extract-*` properties use JSONPath expressions to pull specific fields from payloads.
- `webhook-route-to` optionally forwards the extracted alert as a WhatsApp/Telegram message.
- `webhook-auto-job` optionally triggers a job when a webhook arrives.
- If no source config page exists, webhooks are stored with default formatting.
- The registry scanner discovers webhook source configs.
- Alert-triggered jobs can reference runbook pages (`[[Runbooks/api-restart]]`) for automatic context injection. The `enriched/call` pipeline resolves `[[Page]]` references in job step prompts, injecting runbook content as LLM system context.

**Priority:** P1

### FR-3: Outbound HTTP Action Step

**Description:** A new job runner step action type that makes HTTP requests to external APIs.

**Step Config:**
```
step-order:: 1
step-action:: http-request
step-config:: {
  "url": "https://api.example.com/deploy",
  "method": "POST",
  "headers": {"Authorization": "Bearer {{secret.DEPLOY_TOKEN}}"},
  "body": {"environment": "production", "version": "{{step-1-result}}"},
  "timeout": 10000
}
```

**Acceptance Criteria:**
- New `:http-request` action type registered in the executor.
- Supports GET, POST, PUT, PATCH, DELETE methods.
- Headers and body support template interpolation (`{{variable}}`).
- `{{secret.KEY_NAME}}` resolves secrets from the secrets vault (from secrets-manager track). API keys and tokens are never stored in page content.
- Response is available as the step result (status code, headers, body).
- Timeout is configurable per step (default: 10 seconds, max: 60 seconds).
- TLS certificate verification is enforced (no insecure requests).
- URL allowlist can be configured in plugin settings to restrict which domains skills can call.

**Priority:** P0

### FR-4: Infrastructure Monitoring Pages

**Description:** A convention for Logseq pages that track the status of infrastructure services.

**Page Format:**
```
service-name:: Production API
service-url:: https://api.example.com
service-health-endpoint:: https://api.example.com/health
service-status:: healthy
service-last-check:: 2026-02-21T10:00:00Z
service-check-interval:: 300
service-alert-contact:: whatsapp:15551234567
tags:: logseq-ai-hub-service
```

**Acceptance Criteria:**
- Pages tagged `logseq-ai-hub-service` are discovered by the registry.
- An MCP tool `service_list` returns all monitored services with their status.
- An MCP tool `service_check` triggers an immediate health check for a service.
- Health checks hit the `service-health-endpoint` and update the page properties.
- If a service transitions from healthy to unhealthy, an alert is sent to the configured contact.
- A scheduled job can run periodic health checks (using the existing cron scheduler).
- Infrastructure monitoring pages can embed `[[Page]]` references to related service documentation. When agents query service status, the dynamic argument parser can resolve these references to provide richer operational context.

**Priority:** P1

### FR-5: MCP Tools for Infrastructure

**Description:** MCP tools for interacting with infrastructure through the Hub.

**Tools:**

| Tool Name | Description | Parameters |
|---|---|---|
| `webhook_list_sources` | List configured webhook sources | `{}` |
| `webhook_recent` | Get recent webhook events | `{source?, limit?, since?}` |
| `service_list` | List monitored infrastructure services | `{status?: string}` |
| `service_check` | Trigger a health check | `{serviceName: string}` |
| `http_request` | Make an outbound HTTP request | `{url, method, headers?, body?, timeout?}` |
| `infra_dashboard` | Get an overview of infrastructure status | `{}` |

**Acceptance Criteria:**
- `webhook_recent` queries the SQLite webhook store.
- `service_check` triggers an immediate health check and returns the result.
- `http_request` is available as both an MCP tool and a job runner step.
- `infra_dashboard` aggregates: service statuses, recent alerts, pending approvals, active jobs.
- All tools require authentication.
- `http_request` respects the URL allowlist from plugin settings.

**Priority:** P1

### FR-6: Alert Routing Engine

**Description:** Route incoming webhook alerts to the appropriate human or agent based on configurable rules.

**Acceptance Criteria:**
- Routing rules are defined in webhook source config pages (`webhook-route-to`).
- Routes can target:
  - A messaging contact: `whatsapp:15551234567` or `telegram:12345`
  - A job: `Skills/alert-handler` (triggers automated response)
  - Both: send a notification AND trigger a job
- Severity-based routing: different contacts for different severity levels.
- Escalation: if no response within N minutes, route to a secondary contact (uses approval system).
- Routing is fire-and-forget for notifications, awaitable for job triggers.

**Priority:** P2

## Non-Functional Requirements

### NFR-1: Security

- Generic webhooks can be verified via per-source tokens.
- Outbound HTTP requests are restricted to an allowlist of domains.
- API keys and tokens in HTTP headers/bodies use `{{secret.KEY_NAME}}` interpolation from the secrets-manager track. Secrets are never stored in page content, step results, SSE events, or API responses.
- Webhook payloads may contain sensitive data — stored in SQLite with standard protections.

### NFR-2: Scalability

- Webhook ingestion should handle bursts of 100 events/minute without dropping.
- SQLite webhook store should be pruned (configurable retention, default: 30 days).
- Health checks should not overwhelm monitored services (respect check intervals).

### NFR-3: Extensibility

- New webhook sources are added by creating a Logseq page (no code changes).
- New infrastructure services are added by creating a Logseq page.
- Custom alert routing rules are defined in page properties.
- The `http-request` step type is general-purpose — works with any REST API.

## User Stories

### US-1: Receive Grafana alerts in Logseq

**As** a DevOps engineer,
**I want** Grafana alerts to appear as pages in my Logseq graph,
**So that** I can track incidents alongside my operational knowledge.

### US-2: Automated incident response

**As** a developer,
**I want** to define a skill that automatically checks logs when a service goes down,
**So that** preliminary diagnosis starts before I even see the alert.

### US-3: IoT device monitoring

**As** a home automation enthusiast,
**I want** Home Assistant events to flow into my knowledge base,
**So that** I can build automation skills that react to sensor data.

### US-4: Claude Code triggers deployments

**As** a developer using Claude Code,
**I want** Claude Code to trigger a deployment via the Hub's HTTP tools,
**So that** the deployment goes through the proper approval flow.

### US-5: Infrastructure dashboard

**As** an IT operator,
**I want** to ask Claude Code "What's the status of all our services?",
**So that** I get a quick overview without logging into multiple dashboards.

## Technical Considerations

### Generic Webhook vs Platform-Specific

The generic webhook system complements (not replaces) the existing WhatsApp/Telegram webhooks. Platform-specific webhooks have dedicated parsing logic for their complex payloads. Generic webhooks store the raw payload and use JSONPath extraction for structured access.

### SQLite Schema Extension

```sql
CREATE TABLE webhooks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source TEXT NOT NULL,
  payload JSON NOT NULL,
  extracted JSON,           -- JSONPath extraction results
  processed_at TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX idx_webhooks_source ON webhooks(source);
CREATE INDEX idx_webhooks_created ON webhooks(created_at);
```

### Health Check Implementation

Health checks run as scheduled jobs in the job runner:
1. A skill `Skills/_system_health_check` with `:http-request` steps
2. The scheduler creates instances for each monitored service at its configured interval
3. Results update the service page properties via `:block-update` steps
4. State transitions (healthy→unhealthy) trigger alerts

This reuses existing infrastructure rather than building a dedicated health check loop.

### URL Allowlist

The allowlist is stored in plugin settings as a JSON array of domain patterns:
```json
["api.example.com", "*.railway.app", "hooks.slack.com"]
```

Wildcard patterns use simple glob matching. If the allowlist is empty, all domains are allowed (with a warning in the logs).

## Out of Scope

- MQTT/WebSocket protocol support (HTTP webhooks only for v1).
- Device management (provisioning, firmware updates).
- Time-series data storage (use dedicated tools like InfluxDB/Grafana).
- Dashboard UI (Logseq pages serve as the "dashboard").
- Complex event processing (CEP) or stream processing.
- Direct cloud provider integrations (AWS, GCP, Azure) — use their webhook/SNS features to POST to the generic endpoint.

## Open Questions

1. **Webhook payload size limit:** Should there be a max payload size? Recommendation: 1MB limit, reject with 413 for larger payloads.

2. **JSONPath library:** Which JSONPath implementation for the extraction logic? Recommendation: evaluate `jsonpath-plus` (npm) for the server and a ClojureScript equivalent for the plugin.

3. **Health check from server vs plugin:** Should health checks run on the server (direct HTTP) or through the plugin (via Agent Bridge)? Recommendation: server-side for simple HTTP checks (no Agent Bridge needed), plugin-side only if the check requires graph context.
