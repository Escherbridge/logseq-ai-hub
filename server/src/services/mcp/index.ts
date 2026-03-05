import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import { registerGraphTools } from "./graph-tools";
import { registerJobTools } from "./job-tools";
import { registerMemoryTools } from "./memory-tools";
import { registerMessagingTools } from "./messaging-tools";
import { registerCharacterTools } from "./character-tools";
import { registerApprovalTools } from "./approval-tools";
import { registerRegistryTools } from "./registry-tools";
import { registerSessionTools } from "./session-tools";
import { registerProjectTools } from "./project-tools";
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

  // P1: Approval operations (1 tool)
  registerApprovalTools(server, getContext);

  // P1: Registry operations (4 tools)
  registerRegistryTools(server, getContext);

  // P4: Session management (7 tools)
  registerSessionTools(server, getContext);

  // P5: Code repository integration (2 tools)
  registerProjectTools(server, getContext);

  // P2: Resources (5 resources)
  registerResources(server, getContext);
  registerPrompts(server);
}

export { handleMcpConfig } from "./config";
