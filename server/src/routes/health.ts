import type { Config } from "../config";
import type { AgentBridge } from "../services/agent-bridge";
import { sseManager } from "../services/sse";
import { getMcpStatus } from "../services/mcp-server";

const startTime = Date.now();

export function handleHealth(_req: Request, _config: Config, bridge?: AgentBridge, _traceId?: string): Response {
  const mcpStatus = getMcpStatus();
  return Response.json({
    status: "ok",
    uptime: Math.floor((Date.now() - startTime) / 1000),
    sseClients: sseManager.clientCount,
    agentApi: {
      enabled: true,
      pluginConnected: bridge?.isPluginConnected() ?? false,
      pendingRequests: bridge?.pendingCount ?? 0,
    },
    mcp: {
      activeSessions: mcpStatus.activeSessions,
      tools: mcpStatus.toolCount,
      resources: mcpStatus.resourceCount,
      prompts: mcpStatus.promptCount,
    },
  });
}
