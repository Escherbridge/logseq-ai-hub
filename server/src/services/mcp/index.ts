import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import { registerGraphTools } from "./graph-tools";
import { registerJobTools } from "./job-tools";
import { registerMemoryTools } from "./memory-tools";
import { registerMessagingTools } from "./messaging-tools";
import { registerCharacterTools } from "./character-tools";
import { registerResources } from "./resources";
import { registerPrompts } from "./prompts";

export function registerAllMcpHandlers(
  server: McpServer,
  getContext: () => McpToolContext,
): void {
  registerGraphTools(server, getContext);
  registerJobTools(server, getContext);
  registerMemoryTools(server, getContext);
  registerMessagingTools(server, getContext);
  registerCharacterTools(server, getContext);
  registerResources(server, getContext);
  registerPrompts(server);
}

export { handleMcpConfig } from "./config";
