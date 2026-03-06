import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

export function registerWorkTools(server: McpServer, getContext: () => McpToolContext): void {
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
    "work_claim",
    "Claim exclusive ownership of a file path for a session to prevent concurrent agent conflicts",
    {
      sessionId: z.string().describe("The session ID claiming the path"),
      path: z.string().describe("The file path to claim"),
      description: z.string().describe("Description of the work being done on this path"),
    },
    async (params) => {
      const ctx = getContext();
      if (!ctx.workStore) {
        return { content: [{ type: "text" as const, text: "Error: Work store not available" }], isError: true as const };
      }
      try {
        const result = await ctx.workStore.claim(params.sessionId, params.path, params.description);
        return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
      }
    },
  );

  server.tool(
    "work_release",
    "Release a previously claimed file path, allowing other sessions to claim it",
    {
      sessionId: z.string().describe("The session ID releasing the path"),
      path: z.string().describe("The file path to release"),
    },
    async (params) => {
      const ctx = getContext();
      if (!ctx.workStore) {
        return { content: [{ type: "text" as const, text: "Error: Work store not available" }], isError: true as const };
      }
      try {
        const result = await ctx.workStore.release(params.sessionId, params.path);
        return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
      }
    },
  );

  server.tool(
    "work_list_claims",
    "List all active work claims across all sessions",
    {},
    async () => {
      const ctx = getContext();
      if (!ctx.workStore) {
        return { content: [{ type: "text" as const, text: "Error: Work store not available" }], isError: true as const };
      }
      try {
        const result = await ctx.workStore.listClaims();
        return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
      }
    },
  );

  server.tool(
    "work_log",
    "Log a work action for a project to the Logseq knowledge base",
    {
      project: z.string().describe("The project name"),
      action: z.string().describe("The action performed (e.g., 'implemented', 'fixed', 'reviewed')"),
      details: z.string().describe("Details about the work done"),
    },
    async (params) => bridgeTool("work_log", params),
  );
}
