import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

export function registerMemoryTools(server: McpServer, getContext: () => McpToolContext): void {
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
    "memory_store",
    "Store a memory with a tag in the Logseq AI memory system",
    {
      tag: z.string().describe("Memory tag/category (becomes a page name under AI-Memory/)"),
      content: z.string().describe("Memory content to store"),
    },
    async (params) => bridgeTool("store_memory", params),
  );

  server.tool(
    "memory_recall",
    "Recall all memories stored under a specific tag",
    {
      tag: z.string().describe("Memory tag to recall from"),
    },
    async (params) => bridgeTool("recall_memory", params),
  );

  server.tool(
    "memory_search",
    "Full-text search across all memories",
    {
      query: z.string().describe("Search query string"),
    },
    async (params) => bridgeTool("search_memory", params),
  );

  server.tool(
    "memory_list_tags",
    "List all memory tags/categories",
    {},
    async () => bridgeTool("list_memory_tags", {}),
  );
}
