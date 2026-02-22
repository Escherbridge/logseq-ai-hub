import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import type { Config } from "../config";
import type { RouteContext } from "../router";
import { authenticate, unauthorizedResponse } from "../middleware/auth";
import {
  getMcpServer,
  onSessionInitialized,
  onSessionClosed,
} from "../services/mcp-server";

/**
 * Per-session transports keyed by session ID.
 * Each MCP session gets its own transport that persists
 * across requests so the SDK can track initialization state.
 */
const transports = new Map<string, WebStandardStreamableHTTPServerTransport>();

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

  const server = getMcpServer();
  if (!server) {
    return Response.json(
      { success: false, error: "MCP server not initialized" },
      { status: 503 },
    );
  }

  const sessionId = req.headers.get("mcp-session-id");

  // Existing session — forward to its transport
  if (sessionId && transports.has(sessionId)) {
    const transport = transports.get(sessionId)!;
    return transport.handleRequest(req);
  }

  // New session (initialization)
  const transport = new WebStandardStreamableHTTPServerTransport({
    sessionIdGenerator: () => crypto.randomUUID(),
    onsessioninitialized: (id: string) => {
      transports.set(id, transport);
      onSessionInitialized(id);
    },
    onsessionclosed: (id: string) => {
      transports.delete(id);
      onSessionClosed(id);
    },
  });

  transport.onclose = () => {
    if (transport.sessionId) {
      transports.delete(transport.sessionId);
      onSessionClosed(transport.sessionId);
    }
  };

  await server.connect(transport);
  return transport.handleRequest(req);
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
  if (!sessionId || !transports.has(sessionId)) {
    return Response.json(
      { success: false, error: "Session not found" },
      { status: 404 },
    );
  }

  const transport = transports.get(sessionId)!;
  return transport.handleRequest(req);
}

/**
 * GET /mcp/config - Discovery endpoint (no auth required).
 * Returns the MCP server configuration snippet for Claude Code.
 */
export function handleMcpConfig(_req: Request, config: Config): Response {
  const serverUrl = `http://localhost:${config.port}`;
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
