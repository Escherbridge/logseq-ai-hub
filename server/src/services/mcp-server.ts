import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpSessionInfo } from "../types/mcp";

/**
 * Singleton McpServer instance. Created once at startup;
 * tools, resources, and prompts are registered on it by other modules.
 */
let mcpServer: McpServer | null = null;

/**
 * Tracks active MCP session count (incremented/decremented via
 * onsessioninitialized / onsessionclosed transport callbacks).
 */
let activeSessions = 0;

/**
 * Creates and returns the singleton McpServer. Idempotent: calling
 * a second time returns the existing instance.
 */
export function createMcpServer(): McpServer {
  if (mcpServer) return mcpServer;

  mcpServer = new McpServer(
    {
      name: "logseq-ai-hub",
      version: "1.0.0",
    },
    {
      capabilities: {
        tools: {},
        resources: {},
        prompts: {},
        logging: {},
      },
    },
  );

  return mcpServer;
}

/**
 * Returns the existing McpServer, or null if not yet created.
 */
export function getMcpServer(): McpServer | null {
  return mcpServer;
}

/**
 * Increment active session count. Intended as the
 * `onsessioninitialized` callback for transport options.
 */
export function onSessionInitialized(_sessionId: string): void {
  activeSessions++;
}

/**
 * Decrement active session count. Intended as the
 * `onsessionclosed` callback for transport options.
 */
export function onSessionClosed(_sessionId: string): void {
  activeSessions = Math.max(0, activeSessions - 1);
}

/**
 * Snapshot of MCP server status for the health endpoint.
 */
export function getMcpStatus(): McpSessionInfo {
  const srv = mcpServer as unknown as {
    _registeredTools?: Record<string, unknown>;
    _registeredResources?: Record<string, unknown>;
    _registeredResourceTemplates?: Record<string, unknown>;
    _registeredPrompts?: Record<string, unknown>;
  } | null;

  return {
    activeSessions,
    toolCount: srv?._registeredTools
      ? Object.keys(srv._registeredTools).length
      : 0,
    resourceCount:
      (srv?._registeredResources
        ? Object.keys(srv._registeredResources).length
        : 0) +
      (srv?._registeredResourceTemplates
        ? Object.keys(srv._registeredResourceTemplates).length
        : 0),
    promptCount: srv?._registeredPrompts
      ? Object.keys(srv._registeredPrompts).length
      : 0,
  };
}
