import type { Config } from "../../config";

/**
 * GET /mcp/config - Discovery endpoint (no auth required).
 * Returns the MCP server configuration snippet that clients
 * can paste into their MCP settings (e.g., Claude Code's mcp.json).
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
