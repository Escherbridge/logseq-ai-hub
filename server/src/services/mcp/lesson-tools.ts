import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

export function registerLessonTools(server: McpServer, getContext: () => McpToolContext): void {
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
    "lesson_store",
    "Store a lesson learned from a coding session into the knowledge base. Lessons are organized by project and category for future retrieval.",
    {
      project: z.string().describe("Project name (e.g., 'logseq-ai-hub')"),
      category: z.string().describe("Lesson category: bug-fix, architecture, performance, security, deployment, testing, tooling, or any custom category"),
      title: z.string().describe("Short lesson title"),
      content: z.string().describe("Detailed lesson content"),
    },
    async (params) => bridgeTool("lesson_store", params),
  );

  server.tool(
    "lesson_search",
    "Search past lessons learned across projects. Useful for finding relevant experience before starting new work.",
    {
      query: z.string().describe("Search query to find relevant lessons"),
      project: z.string().optional().describe("Filter by project name"),
      category: z.string().optional().describe("Filter by category (bug-fix, architecture, etc.)"),
    },
    async (params) => bridgeTool("lesson_search", params),
  );
}
