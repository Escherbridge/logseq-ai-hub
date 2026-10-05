import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import type { Config } from "../config";
import type { RouteContext } from "../router";
import { authenticate, unauthorizedResponse } from "../middleware/auth";
import {
  getMcpServer,
  createSessionServer,
  onSessionInitialized,
  onSessionClosed,
} from "../services/mcp-server";

/**
 * Per-session transport + teardown hook, keyed by session ID. Each MCP
 * session gets both its own transport AND its own McpServer (see
 * src/routes/AGENTS.md §mcp-session-lifecycle) — the SDK's
 * `Protocol.connect()` throws if a transport is already attached to a
 * server, so the old shared-singleton-server design bricked every
 * session after the first.
 */
interface SessionEntry {
  transport: WebStandardStreamableHTTPServerTransport;
  dispose: () => void;
}

const sessions = new Map<string, SessionEntry>();

/**
 * Tears down one session exactly once. Both the transport's
 * `onsessionclosed` option and its `onclose` assignment fire
 * independently for a single DELETE-driven close (the SDK invokes each),
 * and a session server's own `dispose()` may itself close the transport
 * and re-enter this function synchronously. Keying cleanup off map
 * presence — deleted up front, before `dispose()` runs — makes every
 * call site idempotent and breaks that re-entrant cycle.
 */
function cleanupSession(sessionId: string | undefined): void {
  if (!sessionId) return;
  const entry = sessions.get(sessionId);
  if (!entry) return;
  sessions.delete(sessionId);
  onSessionClosed(sessionId);
  entry.dispose();
}

/**
 * Handles POST /mcp and GET /mcp requests.
 * Uses web-standard Request/Response for Bun compatibility.
 */
export async function handleMcpRequest(
  req: Request,
  ctx: RouteContext,
): Promise<Response> {
  if (!authenticate(req, ctx.config)) {
    return unauthorizedResponse();
  }

  if (!getMcpServer()) {
    return Response.json(
      { success: false, error: "MCP server not initialized" },
      { status: 503 },
    );
  }

  const sessionId = req.headers.get("mcp-session-id");

  // Existing session — forward to its transport
  if (sessionId && sessions.has(sessionId)) {
    return sessions.get(sessionId)!.transport.handleRequest(req);
  }

  // New session (initialization). Each session gets a brand-new McpServer
  // from the registered factory — never the shared singleton — because
  // the SDK forbids attaching a second transport to an already-connected
  // Protocol instance.
  const sessionServer = createSessionServer();
  if (!sessionServer) {
    return Response.json(
      { success: false, error: "MCP session server not available" },
      { status: 503 },
    );
  }

  const transport = new WebStandardStreamableHTTPServerTransport({
    sessionIdGenerator: () => crypto.randomUUID(),
    onsessioninitialized: (id: string) => {
      sessions.set(id, { transport, dispose: sessionServer.dispose });
      onSessionInitialized(id);
    },
    onsessionclosed: (id: string) => {
      cleanupSession(id);
    },
  });

  transport.onclose = () => {
    cleanupSession(transport.sessionId);
  };

  await sessionServer.server.connect(transport);
  const response = await transport.handleRequest(req);

  // Defensive: if initialization never completed (malformed request,
  // parse error) `onsessioninitialized` never fired, so nothing is in
  // `sessions` to dispose this server later — release it now instead of
  // leaking a dynamic-registry attachment.
  if (!transport.sessionId || !sessions.has(transport.sessionId)) {
    sessionServer.dispose();
  }

  return response;
}

/**
 * Handles DELETE /mcp for session termination.
 */
export async function handleMcpDelete(
  req: Request,
  ctx: RouteContext,
): Promise<Response> {
  if (!authenticate(req, ctx.config)) {
    return unauthorizedResponse();
  }

  const sessionId = req.headers.get("mcp-session-id");
  if (!sessionId || !sessions.has(sessionId)) {
    return Response.json(
      { success: false, error: "Session not found" },
      { status: 404 },
    );
  }

  const transport = sessions.get(sessionId)!.transport;
  return transport.handleRequest(req);
}

/**
 * GET /mcp/config - Discovery endpoint (no auth required).
 * Returns the MCP server configuration snippet for Claude Code.
 */
export function handleMcpConfig(_req: Request, config: Config): Response {
  const serverUrl = config.baseUrl || `http://localhost:${config.port}`;
  return Response.json({
    mcpServers: {
      "logseq-ai-hub": {
        type: "url",
        url: `${serverUrl}/mcp`,
        headers: {
          Authorization: "Bearer <YOUR_PLUGIN_API_TOKEN>",
        },
      },
    },
  });
}
