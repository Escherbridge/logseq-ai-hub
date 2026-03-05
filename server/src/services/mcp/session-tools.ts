import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import { NotFoundError } from "../session-store";

function err(text: string) {
  return { content: [{ type: "text" as const, text }], isError: true as const };
}

function ok(data: unknown) {
  return { content: [{ type: "text" as const, text: JSON.stringify(data, null, 2) }] };
}

export function registerSessionTools(server: McpServer, getContext: () => McpToolContext): void {
  const getStore = () => {
    const ctx = getContext();
    if (!ctx.sessionStore) {
      throw new Error("SessionStore not available");
    }
    return ctx.sessionStore;
  };

  // ── session_create ──────────────────────────────────────────────────────────

  server.tool(
    "session_create",
    "Create a new named agent session",
    {
      name: z.string().optional().describe("Human-readable session name"),
      context: z
        .object({
          focus: z.string().optional(),
          relevant_pages: z.array(z.string()).optional(),
          working_memory: z.array(z.object({ key: z.string(), value: z.string() })).optional(),
        })
        .optional()
        .describe("Initial session context"),
    },
    async (params) => {
      const store = getStore();
      const session = store.create({
        agent_id: "claude-code",
        name: params.name,
        context: params.context,
      });
      return ok(session);
    },
  );

  // ── session_get ─────────────────────────────────────────────────────────────

  server.tool(
    "session_get",
    "Retrieve a session by ID along with its recent messages",
    {
      sessionId: z.string().describe("Session UUID"),
      messageLimit: z.number().optional().describe("Max number of messages to return (default 50)"),
    },
    async (params) => {
      const store = getStore();
      const session = store.get(params.sessionId);
      if (!session) {
        return err("Error: Session not found");
      }
      const messages = store.getMessages(params.sessionId, { limit: params.messageLimit });
      return ok({ session, messages });
    },
  );

  // ── session_list ────────────────────────────────────────────────────────────

  server.tool(
    "session_list",
    "List sessions for the current agent, optionally filtered by status",
    {
      status: z.enum(["active", "paused", "archived"]).optional().describe("Filter by session status"),
      limit: z.number().optional().describe("Maximum number of sessions to return"),
    },
    async (params) => {
      const store = getStore();
      const sessions = store.list("claude-code", { status: params.status, limit: params.limit });
      return ok(sessions);
    },
  );

  // ── session_update_context ──────────────────────────────────────────────────

  server.tool(
    "session_update_context",
    "Deep-merge context updates into an existing session's context",
    {
      sessionId: z.string().describe("Session UUID"),
      context: z
        .object({
          focus: z.string().optional(),
          relevant_pages: z.array(z.string()).optional(),
          working_memory: z.array(z.object({ key: z.string(), value: z.string() })).optional(),
          preferences: z
            .object({
              verbosity: z.enum(["concise", "normal", "verbose"]).optional(),
              auto_approve: z.boolean().optional(),
            })
            .optional(),
        })
        .describe("Context updates to merge"),
    },
    async (params) => {
      const store = getStore();
      try {
        const updated = store.updateContext(params.sessionId, params.context);
        return ok(updated);
      } catch (e) {
        if (e instanceof NotFoundError) {
          return err(`Error: ${e.message}`);
        }
        throw e;
      }
    },
  );

  // ── session_set_focus ───────────────────────────────────────────────────────

  server.tool(
    "session_set_focus",
    "Set the current focus string for a session",
    {
      sessionId: z.string().describe("Session UUID"),
      focus: z.string().describe("Current focus or task description"),
    },
    async (params) => {
      const store = getStore();
      try {
        const updated = store.setFocus(params.sessionId, params.focus);
        return ok(updated);
      } catch (e) {
        if (e instanceof NotFoundError) {
          return err(`Error: ${e.message}`);
        }
        throw e;
      }
    },
  );

  // ── session_add_memory ──────────────────────────────────────────────────────

  server.tool(
    "session_add_memory",
    "Add or update a key-value entry in the session's working memory",
    {
      sessionId: z.string().describe("Session UUID"),
      key: z.string().describe("Memory entry key"),
      value: z.string().describe("Memory entry value"),
    },
    async (params) => {
      const store = getStore();
      try {
        const updated = store.addMemory(params.sessionId, params.key, params.value);
        return ok(updated);
      } catch (e) {
        if (e instanceof NotFoundError) {
          return err(`Error: ${e.message}`);
        }
        throw e;
      }
    },
  );

  // ── session_archive ─────────────────────────────────────────────────────────

  server.tool(
    "session_archive",
    "Archive a session, removing it from active listings",
    {
      sessionId: z.string().describe("Session UUID"),
    },
    async (params) => {
      const store = getStore();
      const success = store.archive(params.sessionId);
      if (!success) {
        return err("Error: Session not found");
      }
      return ok({ archived: true });
    },
  );
}
