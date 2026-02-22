import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import { registerGraphTools } from "./graph-tools";
import { registerJobTools } from "./job-tools";
import { registerMemoryTools } from "./memory-tools";
import { registerMessagingTools } from "./messaging-tools";
import { registerResources } from "./resources";
import { registerPrompts } from "./prompts";

/**
 * Registers all MCP tools, resources, and prompts on the server.
 * Call this once after creating the McpServer instance.
 */
export function registerAllMcpHandlers(
  server: McpServer,
  getContext: () => McpToolContext,
): void {
  // P0: Graph operations (7 tools)
  registerGraphTools(server, getContext);

  // P0: Job runner operations (10 tools)
  registerJobTools(server, getContext);

  // P1: Memory operations (4 tools)
  registerMemoryTools(server, getContext);

  // P1: Messaging operations (3 tools, server-side only)
  registerMessagingTools(server, getContext);

  // P2: Resources (5 resources)
  registerResources(server, getContext);

  // P2: Prompt templates (4 prompts)
  registerPrompts(server);
}

export { handleMcpConfig } from "./config";
