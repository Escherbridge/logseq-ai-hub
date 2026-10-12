import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

export function registerJobTools(server: McpServer, getContext: () => McpToolContext): void {
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
    "job_create",
    "Create a new job in the Logseq job runner",
    {
      name: z.string().describe("Job name"),
      type: z
        .enum(["autonomous", "manual", "scheduled", "event-driven"])
        .describe("Job type. autonomous = run as soon as the job runner picks it up"),
      skill: z.string().optional().describe("Skill page to execute, e.g. 'Skills/summarize'"),
      priority: z.number().min(1).max(5).optional().describe("Priority 1-5 (1=highest)"),
      schedule: z.string().optional().describe("Cron schedule (required for scheduled type)"),
      input: z.record(z.string(), z.unknown()).optional().describe("Input parameters for the job"),
    },
    async (params) => bridgeTool("create_job", params),
  );

  server.tool(
    "job_list",
    "List jobs with optional filters",
    {
      status: z.string().optional().describe("Filter by status (queued, running, completed, failed, cancelled, paused)"),
      limit: z.number().optional().describe("Maximum jobs to return (default 50)"),
      offset: z.number().optional().describe("Offset for pagination"),
    },
    async (params) => bridgeTool("list_jobs", params),
  );

  server.tool(
    "job_get",
    "Get detailed information about a specific job",
    { jobId: z.string().describe("Job ID (e.g. 'Jobs/my-job' or just 'my-job')") },
    async (params) => bridgeTool("get_job", params),
  );

  server.tool(
    "job_start",
    "Start or enqueue a job for execution",
    { jobId: z.string().describe("Job ID to start") },
    async (params) => bridgeTool("start_job", params),
  );

  server.tool(
    "job_cancel",
    "Cancel a running or queued job",
    { jobId: z.string().describe("Job ID to cancel") },
    async (params) => bridgeTool("cancel_job", params),
  );

  server.tool(
    "job_pause",
    "Pause a running job",
    { jobId: z.string().describe("Job ID to pause") },
    async (params) => bridgeTool("pause_job", params),
  );

  server.tool(
    "job_resume",
    "Resume a paused job",
    { jobId: z.string().describe("Job ID to resume") },
    async (params) => bridgeTool("resume_job", params),
  );

  server.tool(
    "skill_list",
    "List all available skills in the Logseq job runner",
    {},
    async () => bridgeTool("list_skills", {}),
  );

  server.tool(
    "skill_get",
    "Get detailed information about a specific skill",
    { skillId: z.string().describe("Skill ID (e.g. 'Skills/my-skill' or just 'my-skill')") },
    async (params) => bridgeTool("get_skill", params),
  );

  server.tool(
    "skill_create",
    "Create a skill (Skills/<name>) with its steps. Steps run in order; templates use {{input-name}} and {{step-N-result}}",
    {
      name: z.string().describe("Skill name"),
      type: z.enum(["llm-chain", "tool-chain", "composite", "mcp-tool"]).describe("Skill type"),
      description: z.string().describe("Skill description"),
      inputs: z.array(z.string()).optional().describe("Expected input parameter names"),
      outputs: z.array(z.string()).optional().describe("Expected output parameter names"),
      steps: z
        .array(
          z.object({
            order: z.number().int().min(1).describe("Execution order, starting at 1"),
            action: z
              .enum([
                "graph-query", "llm-call", "block-insert", "block-update", "page-create", "mcp-tool",
                "mcp-resource", "transform", "conditional", "sub-skill", "legacy-task",
              ])
              .describe("Step action"),
            prompt_template: z.string().optional().describe("llm-call prompt, e.g. 'Summarise: {{text}}'"),
            model: z.string().optional().describe("Model override for llm-call"),
            config: z.record(z.string(), z.unknown()).optional().describe("Action config, e.g. {query} for graph-query"),
            mcp_server: z.string().optional().describe("mcp-tool: server id"),
            mcp_tool: z.string().optional().describe("mcp-tool: tool name"),
          }),
        )
        .optional()
        .describe("Steps, written as blocks on the skill page"),
    },
    async (params) => bridgeTool("create_skill", params),
  );
}
