import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

export function registerProjectTools(server: McpServer, getContext: () => McpToolContext): void {
  const bridgeTool = async (operation: string, params: Record<string, unknown>) => {
    const ctx = getContext();
    if (!ctx.bridge?.isPluginConnected()) {
      return { content: [{ type: "text" as const, text: "Error: Logseq plugin not connected" }], isError: true as const };
    }
    try {
      const result = await ctx.bridge.sendRequest(operation, params, ctx.traceId);
      return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
    } catch (err: any) {
      return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
    }
  };

  server.tool(
    "project_list",
    "List all known code projects/repositories in the knowledge base",
    {
      status: z.string().optional().describe("Filter by project status (e.g., 'active', 'archived')"),
    },
    async (params) => bridgeTool("project_list", params),
  );

  server.tool(
    "project_get",
    "Get detailed information about a specific code project, including architecture context from the project page body",
    {
      name: z.string().describe("Project name (e.g., 'logseq-ai-hub')"),
    },
    async (params) => bridgeTool("project_get", params),
  );
}
