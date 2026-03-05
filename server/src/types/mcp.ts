import type { AgentBridge } from "../services/agent-bridge";
import type { Database } from "bun:sqlite";
import type { Config } from "../config";
import type { ApprovalStore } from "../services/approval-store";
import type { DynamicRegistry } from "../services/mcp/dynamic-registry";
import type { SessionStore } from "../services/session-store";
import type { SafeguardService } from "../services/safeguard-service";
import type { WorkClaimStore } from "../services/work-store";

/**
 * Context passed to MCP tool handlers so they can interact
 * with the Logseq plugin bridge, database, and configuration.
 */
export interface McpToolContext {
  bridge?: AgentBridge;
  db: Database;
  config: Config;
  traceId?: string;
  approvalStore?: ApprovalStore;
  dynamicRegistry?: DynamicRegistry;
  sessionStore?: SessionStore;
  safeguardService?: SafeguardService;
  workStore?: WorkClaimStore;
}

/**
 * Session tracking information exposed via the health endpoint.
 */
export interface McpSessionInfo {
  activeSessions: number;
  toolCount: number;
  resourceCount: number;
  promptCount: number;
}
