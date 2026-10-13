import { hasLlmCredentials, type Config } from "../config";
import { relayStats } from "./api/llm-relay";
import type { AgentBridge } from "../services/agent-bridge";
import { sseManager } from "../services/sse";
import { getMcpStatus } from "../services/mcp-server";
import type { Database } from "bun:sqlite";

const startTime = Date.now();

export function handleHealth(
  _req: Request,
  config: Config,
  bridge?: AgentBridge,
  _traceId?: string,
  db?: Database,
): Response {
  const mcpStatus = getMcpStatus();
  let counts: Record<string, number> | undefined;
  if (db) {
    try {
      const getCount = (table: string) =>
        (db.query(`SELECT COUNT(*) as total FROM ${table}`).get() as { total: number }).total;
      counts = {
        characters: getCount("characters"),
        characterSessions: getCount("character_sessions"),
        characterRelationships: getCount("character_relationships"),
        hubEvents: getCount("hub_events"),
        eventSubscriptions: getCount("event_subscriptions"),
      };
    } catch {
      counts = undefined;
    }
  }
  return Response.json({
    status: "ok",
    uptime: Math.floor((Date.now() - startTime) / 1000),
    sseClients: sseManager.clientCount,
    agentApi: {
      enabled: true,
      pluginConnected: bridge?.isPluginConnected() ?? false,
      pluginBuild: sseManager.builds.at(-1) ?? null,
      pendingRequests: bridge?.pendingCount ?? 0,
    },
    mcp: {
      activeSessions: mcpStatus.activeSessions,
      tools: mcpStatus.toolCount,
      resources: mcpStatus.resourceCount,
      prompts: mcpStatus.promptCount,
    },
    llmRelay: {
      configured: hasLlmCredentials(config),
      requests: relayStats.requests,
      lastStatus: relayStats.lastStatus,
      errors: config.llmConfigErrors.length,
    },
    counts,
  });
}
