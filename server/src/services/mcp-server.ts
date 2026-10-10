import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpSessionInfo } from "../types/mcp";

/**
 * Shared server identity/capabilities. See src/routes/AGENTS.md
 * §mcp-session-lifecycle for why both the status-template singleton and
 * every per-session server below are built from this same shape.
 */
const SERVER_INFO = { name: "logseq-ai-hub", version: "1.0.0" };

/** Sent to every client on initialize; clients surface it to the model. Keep it short — it costs context in every session. */
const SERVER_INSTRUCTIONS = `Logseq AI Hub exposes the user's Logseq graph. Graph tools need the Logseq plugin connected; "Plugin not connected" means ask the user to open Logseq.
- Find before writing: graph_search (every term must match, case-insensitive); page_list filters by namespace, tag or property and excludes journals unless journals is "include" or "only".
- page_read returns an outline where every block ends with ⟨uuid⟩. Pass those uuids to block_insert, block_update, block_move and block_delete; reference a block inside text as ((uuid)).
- Write content as a markdown outline: "- " bullets, indent to nest. [[Page]] links and #tags create backlinks; "key:: value" lines are properties.
- Connect pages with page_link (adds [[target]] to a property on the source, default "related"); inspect connections with page_links.
- Page names are case-insensitive. page_create refuses an existing page unless if_exists is "append" or "skip".
- On large pages use page_read's max_blocks, then block_get to drill into a branch.`;

const SERVER_OPTIONS = {
  capabilities: { tools: {}, resources: {}, prompts: {}, logging: {} },
  instructions: SERVER_INSTRUCTIONS,
};

/**
 * Singleton McpServer instance. Created once at startup; tools, resources,
 * and prompts are registered on it by other modules. It is never
 * `.connect()`-ed to a transport — it exists purely as the registration
 * template for `createBareMcpServer()` callers and as the status/health
 * source of truth (`getMcpStatus()`).
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

  mcpServer = new McpServer(SERVER_INFO, SERVER_OPTIONS);

  return mcpServer;
}

/**
 * Returns the existing McpServer, or null if not yet created.
 */
export function getMcpServer(): McpServer | null {
  return mcpServer;
}

/**
 * Builds a brand-new (non-singleton) McpServer with the same identity and
 * capabilities as the template. Each MCP session needs its own instance:
 * the SDK's `Protocol.connect()` throws if a transport is already attached
 * to a server, so one shared server cannot serve multiple concurrent
 * sessions. Callers are responsible for registering handlers on the
 * returned instance before connecting it to a transport.
 */
export function createBareMcpServer(): McpServer {
  return new McpServer(SERVER_INFO, SERVER_OPTIONS);
}

/**
 * A fully-registered per-session server plus its teardown hook. This
 * module only owns the *shape* of a session server — what "fully
 * registered" means (static tool handlers, dynamic registry attachment)
 * is supplied by whoever calls `registerSessionServerFactory` (index.ts).
 */
export interface SessionServer {
  server: McpServer;
  dispose: () => void;
}

let sessionServerFactory: (() => SessionServer) | null = null;

/**
 * Installs the factory used to build a new per-session McpServer. Called
 * once at startup (index.ts) after the singleton template has been fully
 * registered, so the factory can replay that same registration onto each
 * session server it builds.
 */
export function registerSessionServerFactory(
  factory: () => SessionServer,
): void {
  sessionServerFactory = factory;
}

/**
 * Builds one new session server via the installed factory, or returns
 * null if no factory has been registered yet (e.g. server not started).
 */
export function createSessionServer(): SessionServer | null {
  return sessionServerFactory ? sessionServerFactory() : null;
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
