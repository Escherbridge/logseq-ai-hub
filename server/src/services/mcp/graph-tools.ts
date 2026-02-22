import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

export function registerGraphTools(server: McpServer, getContext: () => McpToolContext): void {
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
    "graph_query",
    "Run a Datalog query against the Logseq graph",
    { query: z.string().describe("Datalog query string") },
    async ({ query }) => bridgeTool("graph_query", { query }),
  );

  server.tool(
    "graph_search",
    "Full-text search across all Logseq pages",
    {
      query: z.string().describe("Search query string"),
      limit: z.number().optional().describe("Maximum results to return (default 50)"),
    },
    async (params) => bridgeTool("graph_search", params),
  );

  server.tool(
    "page_read",
    "Read the full content of a Logseq page",
    { name: z.string().describe("Page name") },
    async (params) => bridgeTool("page_read", params),
  );

  server.tool(
    "page_create",
    "Create a new Logseq page with optional content",
    {
      name: z.string().describe("Page name"),
      content: z.string().optional().describe("Initial page content"),
      properties: z.record(z.string()).optional().describe("Page properties as key-value pairs"),
    },
    async (params) => bridgeTool("page_create", params),
  );

  server.tool(
    "page_list",
    "List Logseq pages matching a pattern",
    {
      pattern: z.string().optional().describe("Filter pattern (case-insensitive substring match)"),
      limit: z.number().optional().describe("Maximum pages to return (default 100)"),
    },
    async (params) => bridgeTool("page_list", params),
  );

  server.tool(
    "block_append",
    "Append a block to a Logseq page",
    {
      page: z.string().describe("Page name to append to"),
      content: z.string().describe("Block content (markdown)"),
      properties: z.record(z.string()).optional().describe("Block properties as key-value pairs"),
    },
    async (params) => bridgeTool("block_append", params),
  );

  server.tool(
    "block_update",
    "Update an existing block's content",
    {
      uuid: z.string().describe("Block UUID"),
      content: z.string().describe("New block content"),
    },
    async (params) => bridgeTool("block_update", params),
  );
}
