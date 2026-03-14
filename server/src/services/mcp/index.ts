import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import { registerGraphTools } from "./graph-tools";
import { registerJobTools } from "./job-tools";
import { registerMemoryTools } from "./memory-tools";
import { registerMessagingTools } from "./messaging-tools";
import { registerCharacterTools } from "./character-tools";
import { registerEventTools } from "./event-tools";
import { registerCharacterSessionTools } from "./character-session-tools";
import { registerApprovalTools } from "./approval-tools";
import { registerRegistryTools } from "./registry-tools";
import { registerSessionTools } from "./session-tools";
import { registerProjectTools } from "./project-tools";
import { registerAdrTools } from "./adr-tools";
import { registerLessonTools } from "./lesson-tools";
import { registerSafeguardTools } from "./safeguard-tools";
import { registerWorkTools } from "./work-tools";
import { registerTaskTools } from "./task-tools";
import { registerPiDevTools } from "./pidev-tools";
import { registerEventTools } from "./event-tools";
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
  registerEventTools(server, getContext);
  registerCharacterSessionTools(server, getContext);

  // P1: Approval operations (1 tool)
  registerApprovalTools(server, getContext);

  // P1: Registry operations (4 tools)
  registerRegistryTools(server, getContext);

  // P4: Session management (7 tools)
  registerSessionTools(server, getContext);

  // P5: Code repository integration (2 project + 2 ADR + 2 lesson = 6 tools)
  registerProjectTools(server, getContext);
  registerAdrTools(server, getContext);
  registerLessonTools(server, getContext);

  // P6: Safeguard pipeline (5 tools)
  registerSafeguardTools(server, getContext);

  // P7: Work coordination (4 tools) + Task management (7 tools)
  registerWorkTools(server, getContext);
  registerTaskTools(server, getContext);

  // P8: Pi.dev agent platform (9 tools)
  registerPiDevTools(server, getContext);

  // P9: Event Hub (7 tools)
  registerEventTools(server, getContext);

  // P2: Resources (6 resources)
  registerResources(server, getContext);
  registerPrompts(server);
}

export { handleMcpConfig } from "./config";
