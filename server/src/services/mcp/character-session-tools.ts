import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import { getCharacter, getCharacterByName } from "../../db/characters";
import {
  listCharacterSessions,
  getCharacterSession,
  deleteCharacterSession,
} from "../../db/character-sessions";

function resolveCharacter(ctx: McpToolContext, idOrName: string) {
  return getCharacter(ctx.db, idOrName) ?? getCharacterByName(ctx.db, idOrName);
}

function err(text: string) {
  return { content: [{ type: "text" as const, text }], isError: true as const };
}

function ok(data: unknown) {
  return { content: [{ type: "text" as const, text: JSON.stringify(data, null, 2) }] };
}

const PREVIEW_LENGTH = 200;

export function registerCharacterSessionTools(server: McpServer, getContext: () => McpToolContext): void {
  server.tool(
    "character_session_list",
    "List sessions for a character (by character ID or name). Returns summaries with last message preview.",
    { characterId: z.string().describe("Character ID or name") },
    async ({ characterId }) => {
      const ctx = getContext();
      const character = resolveCharacter(ctx, characterId);
      if (!character) return err(`Character "${characterId}" not found`);
      const sessions = listCharacterSessions(ctx.db, character.id);
      const summaries = sessions.map((s) => {
        const last = s.messages.length > 0 ? s.messages[s.messages.length - 1] : null;
        const lastContent =
          typeof last?.content === "string" ? last.content.slice(0, PREVIEW_LENGTH) : null;
        return {
          id: s.id,
          characterId: s.character_id,
          createdAt: s.created_at,
          updatedAt: s.updated_at,
          lastRole: last?.role ?? null,
          lastContent,
        };
      });
      return ok(summaries);
    }
  );

  server.tool(
    "character_session_get",
    "Get a character session by ID (full messages).",
    { id: z.string().describe("Session ID") },
    async ({ id }) => {
      const ctx = getContext();
      const session = getCharacterSession(ctx.db, id);
      return session ? ok(session) : err(`Character session "${id}" not found`);
    }
  );

  server.tool(
    "character_session_delete",
    "Delete a character session by ID.",
    { id: z.string().describe("Session ID") },
    async ({ id }) => {
      const ctx = getContext();
      const existing = getCharacterSession(ctx.db, id);
      if (!existing) return err(`Character session "${id}" not found`);
      deleteCharacterSession(ctx.db, id);
      return ok({ deleted: true, id });
    }
  );
}
