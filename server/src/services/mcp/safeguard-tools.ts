import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import type { SafeguardService } from "../safeguard-service";

type ContextWithSafeguard = McpToolContext & { safeguardService?: SafeguardService };

export function registerSafeguardTools(server: McpServer, getContext: () => McpToolContext): void {
  // Helper for bridge operations
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

  // safeguard_check — Evaluate operation against project policy
  server.tool(
    "safeguard_check",
    "Check if an operation is allowed under the project's safeguard policy. Returns allow, block, or approve-required with reason. Use this BEFORE performing potentially dangerous operations.",
    {
      project: z.string().describe("Project name"),
      operation: z.string().describe("Operation being performed (e.g., 'force push', 'deploy to production', 'delete file')"),
      agent: z.string().describe("Agent or session performing the operation"),
      details: z.string().describe("Additional details about the operation"),
    },
    async ({ project, operation, agent, details }) => {
      const ctx = getContext() as ContextWithSafeguard;
      // Use SafeguardService if available, otherwise fallback to bridge
      if (ctx.safeguardService) {
        try {
          const result = await ctx.safeguardService.evaluatePolicy(project, operation, agent, details, ctx.traceId);
          // Also log the check
          await ctx.safeguardService.logAudit(project, operation, agent, result.action, details, ctx.traceId);
          return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
        } catch (err: any) {
          return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
        }
      }
      // Fallback: just return allow if no service configured
      return { content: [{ type: "text" as const, text: JSON.stringify({ action: "allow", reason: "Safeguard service not configured" }, null, 2) }] };
    },
  );

  // safeguard_request — Request approval for a blocked operation
  server.tool(
    "safeguard_request",
    "Request human approval for an operation that requires it. Sends approval request via the configured contact channel and waits for response.",
    {
      project: z.string().describe("Project name"),
      operation: z.string().describe("Operation requiring approval"),
      agent: z.string().describe("Agent requesting approval"),
      details: z.string().describe("Details about why approval is needed"),
      contact: z.string().optional().describe("Override contact for approval (format: platform:userId). Uses policy default if omitted."),
    },
    async ({ project, operation, agent, details, contact }) => {
      const ctx = getContext() as ContextWithSafeguard;
      if (ctx.safeguardService) {
        try {
          const result = await ctx.safeguardService.requestApproval(project, operation, agent, details, contact, ctx.traceId);
          return { content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }] };
        } catch (err: any) {
          return { content: [{ type: "text" as const, text: `Error: ${err.message}` }], isError: true as const };
        }
      }
      return { content: [{ type: "text" as const, text: "Error: Safeguard service not configured" }], isError: true as const };
    },
  );

  // safeguard_policy_get — Get active policy for project
  server.tool(
    "safeguard_policy_get",
    "Get the active safeguard policy for a project. Shows the protection level, rules, and contact configuration.",
    {
      project: z.string().describe("Project name"),
    },
    async (params) => bridgeTool("safeguard_policy_get", params),
  );

  // safeguard_policy_update — Update policy rules (requires approval at level >= 2)
  server.tool(
    "safeguard_policy_update",
    "Update safeguard policy rules for a project. Changes to level 2+ policies require approval through the same system.",
    {
      project: z.string().describe("Project name"),
      rules: z.string().describe("Updated rules in the safeguard page format (one per line: '- ACTION: description')"),
    },
    async ({ project, rules }) => {
      const ctx = getContext() as ContextWithSafeguard;
      // Check if current policy level requires approval for changes
      if (ctx.safeguardService) {
        try {
          const policy = await ctx.safeguardService.getPolicy(project, ctx.traceId);
          if (policy.level >= 2) {
            // Require approval first
            const approval = await ctx.safeguardService.requestApproval(
              project,
              "safeguard_policy_update",
              "system",
              `Modifying safeguard policy rules for ${project}`,
              undefined,
              ctx.traceId,
            );
            if (!approval.approved) {
              return { content: [{ type: "text" as const, text: "Policy update denied — approval required for level 2+ policies" }], isError: true as const };
            }
          }
        } catch (err: any) {
          return { content: [{ type: "text" as const, text: `Error checking policy: ${err.message}` }], isError: true as const };
        }
      }
      return bridgeTool("safeguard_policy_update", { project, rules });
    },
  );

  // safeguard_audit_log — Retrieve audit log
  server.tool(
    "safeguard_audit_log",
    "Retrieve the safeguard audit log for a project. Shows all recorded agent operations, approvals, and denials.",
    {
      project: z.string().describe("Project name"),
      since: z.string().optional().describe("Filter entries after this ISO timestamp"),
      operation: z.string().optional().describe("Filter by operation type"),
      agent: z.string().optional().describe("Filter by agent name"),
    },
    async (params) => bridgeTool("safeguard_audit_log", params),
  );
}
