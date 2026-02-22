import type { AgentBridge } from "./agent-bridge";

const AGENT_OPERATIONS = [
  // ── Job Runner ──────────────────────────────────────────────────────────────
  { name: "create_job", description: "Create a new job in the Logseq graph", params: { name: "string (required)", type: "autonomous|manual|scheduled|event-driven (required)", priority: "number 1-5 (optional, default 3)", schedule: "cron expression (required if type=scheduled)", skill: "skill page name (optional)", input: "object (optional)", dependsOn: "string[] (optional)" } },
  { name: "list_jobs", description: "List all jobs", params: { status: "filter by status (optional)", limit: "number (default 50)", offset: "number (default 0)" } },
  { name: "get_job", description: "Get details of a specific job", params: { jobId: "string (required)" } },
  { name: "start_job", description: "Start/enqueue a job", params: { jobId: "string (required)" } },
  { name: "cancel_job", description: "Cancel a running or queued job", params: { jobId: "string (required)" } },
  { name: "pause_job", description: "Pause a running job", params: { jobId: "string (required)" } },
  { name: "resume_job", description: "Resume a paused job", params: { jobId: "string (required)" } },
  { name: "list_skills", description: "List all available skills", params: {} },
  { name: "get_skill", description: "Get details of a specific skill", params: { skillId: "string (required)" } },
  { name: "create_skill", description: "Create a new skill definition", params: { name: "string", type: "llm-chain|tool-chain|composite|mcp-tool", description: "string", inputs: "string[]", outputs: "string[]", steps: "array of step definitions" } },
  { name: "list_mcp_servers", description: "List connected MCP servers", params: {} },
  { name: "list_mcp_tools", description: "List tools from an MCP server", params: { serverId: "string (required)" } },
  { name: "list_mcp_resources", description: "List resources from an MCP server", params: { serverId: "string (required)" } },

  // ── Graph Operations ────────────────────────────────────────────────────────
  { name: "graph_query", description: "Run a Datalog query against the Logseq graph. Returns raw query results.", params: { query: "string - Datalog query (required)" } },
  { name: "graph_search", description: "Full-text search across all Logseq pages. Returns matching blocks with page context.", params: { query: "string - search query (required)", limit: "number - max results (optional, default 50)" } },
  { name: "page_read", description: "Read the full content of a Logseq page as a block tree", params: { name: "string - page name (required)" } },
  { name: "page_create", description: "Create a new Logseq page with optional initial content and properties", params: { name: "string - page name (required)", content: "string - initial page content (optional)", properties: "object - key-value page properties (optional)" } },
  { name: "page_list", description: "List Logseq pages matching a pattern (case-insensitive substring match)", params: { pattern: "string - filter pattern (optional)", limit: "number - max pages to return (optional, default 100)" } },
  { name: "block_append", description: "Append a new block to a Logseq page", params: { page: "string - page name (required)", content: "string - block content in markdown (required)", properties: "object - block properties (optional)" } },
  { name: "block_update", description: "Update an existing block's content by UUID", params: { uuid: "string - block UUID (required)", content: "string - new block content (required)" } },

  // ── Memory Operations ───────────────────────────────────────────────────────
  { name: "store_memory", description: "Store a memory entry under a tag in the Logseq knowledge base. Memories are stored as blocks on AI-Memory/* pages.", params: { tag: "string - memory tag/category (required)", content: "string - memory content to store (required)" } },
  { name: "recall_memory", description: "Recall all memories stored under a specific tag", params: { tag: "string - memory tag to recall (required)" } },
  { name: "search_memory", description: "Search across all memories for matching content", params: { query: "string - search query (required)", limit: "number - max results (optional, default 20)" } },
  { name: "list_memory_tags", description: "List all memory tags that have stored entries", params: {} },
];

export function buildSystemPrompt(): string {
  const opsDescription = AGENT_OPERATIONS.map(op => {
    const paramStr = Object.entries(op.params)
      .map(([k, v]) => `    - ${k}: ${v}`)
      .join("\n");
    return `- **${op.name}**: ${op.description}${paramStr ? "\n  Parameters:\n" + paramStr : ""}`;
  }).join("\n");

  return `You are an AI assistant for the Logseq AI Hub. You help users manage their Logseq knowledge base, job runner, memory system, and MCP connections through natural language.

You can read and write to the Logseq graph, search across pages, store and recall memories, manage jobs and skills, and interact with MCP servers. When the user asks you to perform an action, use the available tools. Chain multiple tool calls when needed — for example, search the graph then read a specific page, or recall memories before creating a new page.

Available operations:
${opsDescription}

Guidelines:
- **Graph operations**: Use graph_search for finding content across pages. Use page_read to get the full block tree of a page. Use graph_query for advanced Datalog queries when graph_search is insufficient. Logseq page names are case-insensitive.
- **Memory operations**: Memories are stored on pages prefixed with "AI-Memory/" (e.g., tag "work" → page "AI-Memory/work"). Use store_memory to save information for later recall. Use recall_memory to retrieve all entries for a tag. Use search_memory for cross-tag full-text search. Use list_memory_tags to discover what's been stored.
- **Job runner**: Job names should use kebab-case. Skill pages are prefixed with "Skills/" and job pages with "Jobs/". For scheduled jobs, use standard 5-field cron expressions. When creating jobs, ask for the skill reference if not provided.
- **Chaining**: You can chain multiple operations in a single conversation turn. For example: search_memory → page_read → block_append to find relevant context, read a page, then add a block to it.
- When listing items, provide a clean summary in natural language.
- If unsure about the user's intent, ask a clarifying question instead of guessing.`;
}

export function buildTools(): any[] {
  return AGENT_OPERATIONS.map(op => ({
    type: "function",
    function: {
      name: op.name,
      description: op.description,
      parameters: {
        type: "object",
        properties: Object.fromEntries(
          Object.entries(op.params).map(([key, desc]) => [
            key,
            {
              type: desc.includes("number") ? "number" :
                    desc.includes("string[]") || desc.includes("array") ? "array" :
                    desc.includes("object") ? "object" : "string",
              description: desc,
            },
          ])
        ),
        required: Object.entries(op.params)
          .filter(([_, desc]) => desc.includes("required"))
          .map(([key]) => key),
      },
    },
  }));
}

export async function executeOperation(
  operation: string,
  params: Record<string, unknown>,
  bridge: AgentBridge,
  traceId?: string
): Promise<{ success: boolean; result: unknown; error?: string }> {
  try {
    const result = await bridge.sendRequest(operation, params, traceId);
    return { success: true, result };
  } catch (err: any) {
    return { success: false, result: null, error: err.message || "Operation failed" };
  }
}
