import type { AgentBridge } from "../services/agent-bridge";
import type { Database } from "bun:sqlite";
import type { Config } from "../config";

/**
 * Context passed to MCP tool handlers so they can interact
 * with the Logseq plugin bridge, database, and configuration.
 */
export interface McpToolContext {
  bridge?: AgentBridge;
  db: Database;
  config: Config;
  traceId?: string;
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
