import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

export function registerAdrTools(server: McpServer, getContext: () => McpToolContext): void {
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
    "adr_list",
    "List architectural decision records (ADRs) for a project. Returns all ADRs with their status, date, and content sections (Context, Decision, Consequences).",
    {
      project: z.string().describe("Project name to list ADRs for (e.g., 'logseq-ai-hub')"),
    },
    async (params) => bridgeTool("adr_list", params),
  );

  server.tool(
    "adr_create",
    "Create a new Architectural Decision Record (ADR) for a project. Auto-numbers the ADR based on existing records.",
    {
      project: z.string().describe("Project name"),
      title: z.string().describe("ADR title (e.g., 'Use SSE bridge for server-plugin communication')"),
      context: z.string().describe("Context section: why is this decision needed?"),
      decision: z.string().describe("Decision section: what was decided?"),
      consequences: z.string().describe("Consequences section: what are the trade-offs?"),
      status: z.string().optional().describe("ADR status: proposed, accepted, deprecated, superseded (default: accepted)"),
    },
    async (params) => bridgeTool("adr_create", params),
  );
}
