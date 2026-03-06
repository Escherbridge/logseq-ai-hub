import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

export function registerPiDevTools(server: McpServer, getContext: () => McpToolContext): void {
  // ── piDevCheck ─────────────────────────────────────────────────────────────
  const piDevCheck = () => {
    const ctx = getContext();
    if (!ctx.piDevManager) {
      return {
        error: true,
        result: {
          content: [{ type: "text" as const, text: "Error: Pi.dev integration not available" }],
          isError: true as const,
        },
      };
    }
    if (!ctx.piDevManager.isEnabled()) {
      return {
        error: true,
        result: {
          content: [
            {
              type: "text" as const,
              text: "Error: Pi.dev integration is disabled. Enable it in plugin settings.",
            },
          ],
          isError: true as const,
        },
      };
    }
    return { error: false, manager: ctx.piDevManager };
  };

  // ── bridgeTool ─────────────────────────────────────────────────────────────
  const bridgeTool = async (operation: string, params: Record<string, unknown>) => {
    const ctx = getContext();
    if (!ctx.bridge?.isPluginConnected()) {
      return {
        content: [{ type: "text" as const, text: "Error: Logseq plugin not connected" }],
        isError: true as const,
      };
    }
    try {
      const result = await ctx.bridge.sendRequest(operation, params, ctx.traceId);
      return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
    } catch (err: any) {
      return {
        content: [{ type: "text" as const, text: `Error: ${err.message}` }],
        isError: true as const,
      };
    }
  };

  // ── pi_spawn ───────────────────────────────────────────────────────────────

  server.tool(
    "pi_spawn",
    "Spawn a new pi.dev agent session for a project and task",
    {
      project: z.string().describe("Project identifier"),
      task: z.string().describe("Task description for the agent"),
      agentProfile: z.string().optional().describe("Agent profile name to use"),
      model: z.string().optional().describe("LLM model override"),
      workingDir: z.string().optional().describe("Working directory for the agent session"),
    },
    async (params) => {
      const check = piDevCheck();
      if (check.error) return check.result!;
      const manager = check.manager!;
      try {
        const session = await manager.spawn(params.project, params.task, {
          agentProfile: params.agentProfile,
          model: params.model,
          workingDir: params.workingDir,
        });
        return { content: [{ type: "text" as const, text: JSON.stringify(session, null, 2) }] };
      } catch (err: any) {
        return {
          content: [{ type: "text" as const, text: `Error: ${err.message}` }],
          isError: true as const,
        };
      }
    },
  );

  // ── pi_send ────────────────────────────────────────────────────────────────

  server.tool(
    "pi_send",
    "Send a message to a running pi.dev agent session",
    {
      sessionId: z.string().describe("Pi.dev session ID"),
      message: z.string().describe("Message to send to the agent"),
      steering: z.string().optional().describe("Optional steering instruction"),
    },
    async (params) => {
      const check = piDevCheck();
      if (check.error) return check.result!;
      const manager = check.manager!;
      try {
        const result = await manager.send(params.sessionId, params.message, params.steering);
        return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        return {
          content: [{ type: "text" as const, text: `Error: ${err.message}` }],
          isError: true as const,
        };
      }
    },
  );

  // ── pi_status ──────────────────────────────────────────────────────────────

  server.tool(
    "pi_status",
    "Get the status of a pi.dev agent session",
    {
      sessionId: z.string().describe("Pi.dev session ID"),
    },
    async (params) => {
      const check = piDevCheck();
      if (check.error) return check.result!;
      const manager = check.manager!;
      const session = manager.status(params.sessionId);
      if (!session) {
        return {
          content: [{ type: "text" as const, text: `Error: Session not found: ${params.sessionId}` }],
          isError: true as const,
        };
      }
      return { content: [{ type: "text" as const, text: JSON.stringify(session, null, 2) }] };
    },
  );

  // ── pi_stop ────────────────────────────────────────────────────────────────

  server.tool(
    "pi_stop",
    "Stop a running pi.dev agent session",
    {
      sessionId: z.string().describe("Pi.dev session ID to stop"),
    },
    async (params) => {
      const check = piDevCheck();
      if (check.error) return check.result!;
      const manager = check.manager!;
      try {
        const result = await manager.stop(params.sessionId);
        return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        return {
          content: [{ type: "text" as const, text: `Error: ${err.message}` }],
          isError: true as const,
        };
      }
    },
  );

  // ── pi_list_sessions ───────────────────────────────────────────────────────

  server.tool(
    "pi_list_sessions",
    "List all active pi.dev agent sessions",
    {},
    async () => {
      const check = piDevCheck();
      if (check.error) return check.result!;
      const manager = check.manager!;
      const sessions = manager.listSessions();
      return { content: [{ type: "text" as const, text: JSON.stringify(sessions, null, 2) }] };
    },
  );

  // ── pi_agent_create ────────────────────────────────────────────────────────

  server.tool(
    "pi_agent_create",
    "Create a new pi.dev agent profile in the Logseq graph",
    {
      name: z.string().describe("Agent profile name"),
      project: z.string().describe("Project this agent belongs to"),
      model: z.string().optional().describe("LLM model for this agent"),
      description: z.string().optional().describe("Agent description"),
      systemInstructions: z.string().optional().describe("System prompt / instructions"),
      skills: z.string().optional().describe("Comma-separated list of skills"),
      allowedTools: z.string().optional().describe("Comma-separated list of allowed tools"),
      restrictedOperations: z.string().optional().describe("Comma-separated restricted operations"),
    },
    async (params) => {
      const check = piDevCheck();
      if (check.error) return check.result!;
      return bridgeTool("pi_agent_create", params);
    },
  );

  // ── pi_agent_list ──────────────────────────────────────────────────────────

  server.tool(
    "pi_agent_list",
    "List pi.dev agent profiles, optionally filtered by project",
    {
      project: z.string().optional().describe("Filter agents by project"),
    },
    async (params) => {
      const check = piDevCheck();
      if (check.error) return check.result!;
      return bridgeTool("pi_agent_list", params);
    },
  );

  // ── pi_agent_get ───────────────────────────────────────────────────────────

  server.tool(
    "pi_agent_get",
    "Get details of a specific pi.dev agent profile",
    {
      name: z.string().describe("Agent profile name"),
    },
    async (params) => {
      const check = piDevCheck();
      if (check.error) return check.result!;
      return bridgeTool("pi_agent_get", params);
    },
  );

  // ── pi_agent_update ────────────────────────────────────────────────────────

  server.tool(
    "pi_agent_update",
    "Update properties of a pi.dev agent profile",
    {
      name: z.string().describe("Agent profile name to update"),
      model: z.string().optional().describe("New LLM model"),
      description: z.string().optional().describe("New description"),
      project: z.string().optional().describe("New project assignment"),
    },
    async (params) => {
      const check = piDevCheck();
      if (check.error) return check.result!;
      return bridgeTool("pi_agent_update", params);
    },
  );
}
