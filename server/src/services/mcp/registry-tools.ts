import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

export function registerRegistryTools(
  server: McpServer,
  getContext: () => McpToolContext,
): void {
  const bridgeTool = async (
    operation: string,
    params: Record<string, unknown>,
  ) => {
    const ctx = getContext();
    if (!ctx.bridge?.isPluginConnected()) {
      return {
        content: [
          { type: "text" as const, text: "Error: Logseq plugin not connected" },
        ],
        isError: true as const,
      };
    }
    try {
      const result = await ctx.bridge.sendRequest(
        operation,
        params,
        ctx.traceId,
      );
      return {
        content: [
          { type: "text" as const, text: JSON.stringify(result, null, 2) },
        ],
      };
    } catch (err: any) {
      return {
        content: [{ type: "text" as const, text: `Error: ${err.message}` }],
        isError: true as const,
      };
    }
  };

  server.tool(
    "registry_list",
    "List all registered tools, prompts, procedures, agents, and skills from the knowledge base",
    {
      type: z
        .enum(["tool", "prompt", "procedure", "agent", "skill"])
        .optional()
        .describe("Filter by entry type"),
    },
    async (params) => bridgeTool("registry_list", params),
  );

  server.tool(
    "registry_search",
    "Search the knowledge base registry by keyword across names and descriptions",
    {
      query: z.string().describe("Search keyword"),
      type: z
        .enum(["tool", "prompt", "procedure", "agent", "skill"])
        .optional()
        .describe("Filter by entry type"),
    },
    async (params) => bridgeTool("registry_search", params),
  );

  server.tool(
    "registry_get",
    "Get full details of a specific registered item from the knowledge base",
    {
      name: z.string().describe("Entry identifier (page name)"),
      type: z
        .enum(["tool", "prompt", "procedure", "agent", "skill"])
        .describe("Entry type"),
    },
    async (params) => bridgeTool("registry_get", params),
  );

  server.tool(
    "registry_refresh",
    "Trigger a full registry rescan of the Logseq knowledge base",
    {},
    async () => {
      const result = await bridgeTool("registry_refresh", {});
      // After refresh, sync dynamic tools if dynamic registry is available
      const ctx = getContext();
      if (ctx.dynamicRegistry) {
        try {
          await ctx.dynamicRegistry.syncFromBridge();
        } catch (err: any) {
          console.warn("Dynamic registry sync failed:", err.message);
        }
      }
      return result;
    },
  );
}
