import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

export function registerTaskTools(server: McpServer, getContext: () => McpToolContext): void {
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
    "track_create",
    "Create a new work track for a project to organize related tasks",
    {
      project: z.string().describe("The project name"),
      trackId: z.string().describe("Unique identifier for this track (e.g., 'feature-auth', 'bug-fix-123')"),
      description: z.string().describe("Description of what this track covers"),
      type: z.string().optional().describe("Track type (e.g., 'feature', 'bugfix', 'chore', 'refactor')"),
      priority: z.string().optional().describe("Track priority (e.g., 'high', 'medium', 'low')"),
      branch: z.string().optional().describe("Git branch associated with this track"),
      assignedAgent: z.string().optional().describe("Agent assigned to work on this track"),
    },
    async (params) => bridgeTool("track_create", params),
  );

  server.tool(
    "track_list",
    "List all work tracks for a project, optionally filtered by status or type",
    {
      project: z.string().describe("The project name"),
      status: z.string().optional().describe("Filter by track status (e.g., 'active', 'completed', 'blocked')"),
      type: z.string().optional().describe("Filter by track type (e.g., 'feature', 'bugfix')"),
    },
    async (params) => bridgeTool("track_list", params),
  );

  server.tool(
    "track_update",
    "Update the status, priority, branch, or assigned agent for an existing track",
    {
      project: z.string().describe("The project name"),
      trackId: z.string().describe("The track ID to update"),
      status: z.string().optional().describe("New status for the track"),
      priority: z.string().optional().describe("New priority for the track"),
      branch: z.string().optional().describe("New or updated git branch for the track"),
      assignedAgent: z.string().optional().describe("Agent to assign to this track"),
    },
    async (params) => bridgeTool("track_update", params),
  );

  server.tool(
    "task_add",
    "Add a new task to an existing work track",
    {
      project: z.string().describe("The project name"),
      trackId: z.string().describe("The track ID to add the task to"),
      description: z.string().describe("Description of the task to add"),
      agent: z.string().optional().describe("Agent responsible for this specific task"),
    },
    async (params) => bridgeTool("task_add", params),
  );

  server.tool(
    "task_update",
    "Update the status or assigned agent for a specific task within a track",
    {
      project: z.string().describe("The project name"),
      trackId: z.string().describe("The track ID containing the task"),
      taskIndex: z.number().describe("Zero-based index of the task to update"),
      status: z.enum(["todo", "doing", "done"]).optional().describe("New status for the task"),
      agent: z.string().optional().describe("Agent to assign to this task"),
    },
    async (params) => bridgeTool("task_update", params),
  );

  server.tool(
    "task_list",
    "List all tasks within a specific work track, optionally filtered by status",
    {
      project: z.string().describe("The project name"),
      trackId: z.string().describe("The track ID to list tasks for"),
      status: z.string().optional().describe("Filter tasks by status (e.g., 'todo', 'doing', 'done')"),
    },
    async (params) => bridgeTool("task_list", params),
  );

  server.tool(
    "project_dashboard",
    "Get a high-level dashboard overview of all tracks and task progress for a project",
    {
      project: z.string().describe("The project name to get the dashboard for"),
    },
    async (params) => bridgeTool("project_dashboard", params),
  );
}
