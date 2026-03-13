import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import type { HubEvent } from "../../types";

export function registerEventTools(server: McpServer, getContext: () => McpToolContext): void {
  // ── event_publish ──────────────────────────────────────────────────────
  server.tool(
    "event_publish",
    "Publish an event to the Event Hub",
    {
      type: z.string().describe("Event type (e.g. 'job.completed', 'webhook.received')"),
      source: z.string().describe("Event source identifier (e.g. 'system:job-runner', 'webhook:github')"),
      data: z.record(z.unknown()).describe("Event payload data"),
      metadata: z.record(z.unknown()).optional().describe("Optional metadata (severity, tags, ttl, etc.)"),
    },
    async (params) => {
      const ctx = getContext();
      if (!ctx.eventBus) {
        return { content: [{ type: "text" as const, text: "Error: EventBus not available" }], isError: true as const };
      }
      try {
        const event = ctx.eventBus.publish({
          type: params.type,
          source: params.source,
          data: params.data,
          metadata: params.metadata as HubEvent["metadata"],
        });
        return { content: [{ type: "text" as const, text: JSON.stringify(event, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
      }
    },
  );

  // ── event_query ────────────────────────────────────────────────────────
  server.tool(
    "event_query",
    "Query events from the Event Hub with optional filters",
    {
      type: z.string().optional().describe("Filter by event type"),
      source: z.string().optional().describe("Filter by event source"),
      since: z.string().optional().describe("ISO timestamp - return events since this time"),
      limit: z.number().optional().describe("Maximum events to return (default 50)"),
      offset: z.number().optional().describe("Offset for pagination"),
    },
    async (params) => {
      const ctx = getContext();
      if (!ctx.eventBus) {
        return { content: [{ type: "text" as const, text: "Error: EventBus not available" }], isError: true as const };
      }
      try {
        const limit = Math.min(Math.max(params.limit || 50, 1), 200);
        const result = ctx.eventBus.query({
          type: params.type,
          source: params.source,
          since: params.since,
          limit,
          offset: params.offset,
        });
        return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
      }
    },
  );

  // ── event_subscribe ────────────────────────────────────────────────────
  server.tool(
    "event_subscribe",
    "Create an event subscription page in Logseq that triggers actions on matching events",
    {
      name: z.string().describe("Subscription name (will be created as EventSub/<name>)"),
      pattern: z.string().describe("Event type pattern to match (supports * wildcard, e.g. 'webhook.*')"),
      action: z.enum(["log", "route", "skill"]).describe("Action to take when event matches"),
      skill: z.string().optional().describe("Skill to trigger (required when action=skill)"),
      routeTo: z.string().optional().describe("Route destination as 'platform:recipient' (required when action=route)"),
      severityFilter: z.array(z.string()).optional().describe("Only match events with these severity levels"),
    },
    async (params) => {
      const ctx = getContext();
      if (!ctx.bridge?.isPluginConnected()) {
        return { content: [{ type: "text" as const, text: "Error: Logseq plugin not connected" }], isError: true as const };
      }
      try {
        const properties: Record<string, string> = {
          "event-pattern": params.pattern,
          "event-action": params.action,
        };
        if (params.skill) {
          properties["event-skill"] = params.skill;
        }
        if (params.routeTo) {
          properties["event-route-to"] = params.routeTo;
        }
        if (params.severityFilter && params.severityFilter.length > 0) {
          properties["event-severity-filter"] = params.severityFilter.join(", ");
        }

        const result = await ctx.bridge.sendRequest(
          "page_create",
          {
            name: `EventSub/${params.name}`,
            properties,
          },
          ctx.traceId,
        );
        return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
      }
    },
  );

  // ── event_sources ──────────────────────────────────────────────────────
  server.tool(
    "event_sources",
    "List unique event sources from the Event Hub",
    {},
    async () => {
      const ctx = getContext();
      try {
        const rows = ctx.db
          .query("SELECT DISTINCT source FROM events ORDER BY source")
          .all() as Array<{ source: string }>;
        const sources = rows.map((r) => r.source);
        return { content: [{ type: "text" as const, text: JSON.stringify({ sources, count: sources.length }, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
      }
    },
  );

  // ── event_recent ───────────────────────────────────────────────────────
  server.tool(
    "event_recent",
    "Get recent events from the Event Hub",
    {
      limit: z.number().optional().describe("Number of events to return (default 10)"),
    },
    async (params) => {
      const ctx = getContext();
      if (!ctx.eventBus) {
        return { content: [{ type: "text" as const, text: "Error: EventBus not available" }], isError: true as const };
      }
      try {
        const result = ctx.eventBus.query({ limit: params.limit ?? 10 });
        return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
      }
    },
  );

  // ── webhook_test ───────────────────────────────────────────────────────
  server.tool(
    "webhook_test",
    "Send a test webhook event to the Event Hub for testing subscriptions and pipelines",
    {
      source: z.string().describe("Webhook source name (will be prefixed with 'webhook:')"),
      data: z.record(z.unknown()).optional().describe("Test event payload data"),
    },
    async (params) => {
      const ctx = getContext();
      if (!ctx.eventBus) {
        return { content: [{ type: "text" as const, text: "Error: EventBus not available" }], isError: true as const };
      }
      try {
        const event = ctx.eventBus.publish({
          type: "webhook.test",
          source: `webhook:${params.source}`,
          data: params.data ?? {},
        });
        return { content: [{ type: "text" as const, text: JSON.stringify(event, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
      }
    },
  );

  // ── http_request ───────────────────────────────────────────────────────
  server.tool(
    "http_request",
    "Make an HTTP request (for agent use). URLs are validated against the server's HTTP allowlist. HTTPS is enforced except for localhost.",
    {
      url: z.string().describe("Request URL (must be HTTPS unless localhost)"),
      method: z.string().optional().describe("HTTP method (default GET)"),
      headers: z.record(z.string()).optional().describe("Request headers"),
      body: z.string().optional().describe("Request body"),
      timeout: z.number().optional().describe("Timeout in milliseconds (default 10000, max 60000)"),
    },
    async (params) => {
      const ctx = getContext();
      const { url, method = "GET", headers = {}, body, timeout = 10000 } = params;

      // Validate URL
      let parsed: URL;
      try {
        parsed = new URL(url);
      } catch {
        return { content: [{ type: "text" as const, text: "Error: Invalid URL" }], isError: true as const };
      }

      // HTTPS enforcement (except localhost)
      const isLocalhost = parsed.hostname === "localhost" || parsed.hostname === "127.0.0.1";
      if (parsed.protocol === "http:" && !isLocalhost) {
        return {
          content: [{ type: "text" as const, text: "Error: HTTPS is required for non-localhost URLs" }],
          isError: true as const,
        };
      }

      // Allowlist check
      const allowlist = ctx.config.httpAllowlist;
      if (!allowlist || allowlist.length === 0) {
        return {
          content: [{ type: "text" as const, text: "Error: No URLs allowed. Configure HTTP_ALLOWLIST environment variable with comma-separated domains." }],
          isError: true as const,
        };
      }
      const hostname = parsed.hostname;
      const allowed = allowlist.some((pattern) => {
        if (pattern.startsWith("*.")) {
          const suffix = pattern.slice(1); // ".example.com"
          return hostname.endsWith(suffix);
        }
        return hostname === pattern;
      });
      if (!allowed) {
        return {
          content: [{ type: "text" as const, text: `Error: URL hostname '${hostname}' is not in the HTTP allowlist` }],
          isError: true as const,
        };
      }

      // Execute request
      const effectiveTimeout = Math.min(Math.max(timeout, 1), 60000);
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), effectiveTimeout);

      try {
        const fetchOpts: RequestInit = {
          method: method.toUpperCase(),
          headers,
          signal: controller.signal,
        };
        if (body && method.toUpperCase() !== "GET") {
          fetchOpts.body = body;
        }

        const response = await fetch(url, fetchOpts);
        clearTimeout(timer);

        const contentType = response.headers.get("content-type") || "";
        let responseBody: unknown;
        if (contentType.includes("application/json")) {
          responseBody = await response.json();
        } else {
          responseBody = await response.text();
        }

        const result = {
          status: response.status,
          ok: response.ok,
          headers: Object.fromEntries(response.headers.entries()),
          body: responseBody,
        };
        return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        clearTimeout(timer);
        if (err.name === "AbortError") {
          return {
            content: [{ type: "text" as const, text: `Error: Request timed out after ${effectiveTimeout}ms` }],
            isError: true as const,
          };
        }
        return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
      }
    },
  );
}
