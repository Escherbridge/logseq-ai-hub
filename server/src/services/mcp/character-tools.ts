import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import {
  createCharacter,
  getCharacter,
  getCharacterByName,
  listCharacters,
  updateCharacter,
  deleteCharacter,
} from "../../db/characters";

function resolve(ctx: McpToolContext, id: string) {
  return getCharacter(ctx.db, id) ?? getCharacterByName(ctx.db, id);
}

function err(text: string) {
  return { content: [{ type: "text" as const, text }], isError: true as const };
}

function ok(data: unknown) {
  return { content: [{ type: "text" as const, text: JSON.stringify(data, null, 2) }] };
}

export function registerCharacterTools(server: McpServer, getContext: () => McpToolContext): void {
  server.tool(
    "character_list",
    "List all characters",
    {},
    async () => ok(listCharacters(getContext().db)),
  );

  server.tool(
    "character_get",
    "Get a character by ID or name",
    { id: z.string().describe("Character ID or name") },
    async ({ id }) => {
      const character = resolve(getContext(), id);
      return character ? ok(character) : err(`Character "${id}" not found`);
    },
  );

  server.tool(
    "character_create",
    "Create a new character",
    {
      name: z.string().describe("Unique character name"),
      persona: z.string().optional().describe("Logseq page path (e.g. Characters/Arwen)"),
      system_prompt: z.string().optional().describe("Personality and behavior instructions"),
      model: z.string().optional().describe("LLM model override"),
      skills: z.array(z.string()).optional().describe("Skill page names this character can use"),
      metadata: z.record(z.unknown()).optional().describe("Arbitrary metadata (stats, state, etc.)"),
    },
    async (params) => {
      try {
        return ok(createCharacter(getContext().db, params));
      } catch (e: any) {
        return err(`Error: ${e.message}`);
      }
    },
  );

  server.tool(
    "character_update",
    "Update a character's properties",
    {
      id: z.string().describe("Character ID or name"),
      name: z.string().optional(),
      persona: z.string().nullable().optional(),
      system_prompt: z.string().nullable().optional(),
      model: z.string().nullable().optional(),
      skills: z.array(z.string()).optional(),
      metadata: z.record(z.unknown()).optional(),
    },
    async ({ id, ...updates }) => {
      const ctx = getContext();
      const existing = resolve(ctx, id);
      if (!existing) return err(`Character "${id}" not found`);
      try {
        return ok(updateCharacter(ctx.db, existing.id, updates));
      } catch (e: any) {
        return err(`Error: ${e.message}`);
      }
    },
  );

  server.tool(
    "character_delete",
    "Delete a character",
    { id: z.string().describe("Character ID or name") },
    async ({ id }) => {
      const ctx = getContext();
      const existing = resolve(ctx, id);
      if (!existing) return err(`Character "${id}" not found`);
      deleteCharacter(ctx.db, existing.id);
      return ok({ deleted: true, id: existing.id });
    },
  );

  server.tool(
    "character_memory_store",
    "Store a memory scoped to a specific character",
    {
      id: z.string().describe("Character ID or name"),
      content: z.string().describe("Memory content"),
    },
    async ({ id, content }) => {
      const ctx = getContext();
      const character = resolve(ctx, id);
      if (!character) return err(`Character "${id}" not found`);
      if (!ctx.bridge?.isPluginConnected()) return err("Error: Logseq plugin not connected");
      try {
        const result = await ctx.bridge.sendRequest(
          "store_memory",
          { tag: character.memory_tag, content },
          ctx.traceId
        );
        return ok(result);
      } catch (e: any) {
        return err(`Error: ${e.message}`);
      }
    },
  );

  server.tool(
    "character_memory_recall",
    "Recall all memories for a specific character",
    { id: z.string().describe("Character ID or name") },
    async ({ id }) => {
      const ctx = getContext();
      const character = resolve(ctx, id);
      if (!character) return err(`Character "${id}" not found`);
      if (!ctx.bridge?.isPluginConnected()) return err("Error: Logseq plugin not connected");
      try {
        const result = await ctx.bridge.sendRequest(
          "recall_memory",
          { tag: character.memory_tag },
          ctx.traceId
        );
        return ok(result);
      } catch (e: any) {
        return err(`Error: ${e.message}`);
      }
    },
  );

  server.tool(
    "character_page_sync",
    "Sync a character's definition to its Logseq page",
    { id: z.string().describe("Character ID or name") },
    async ({ id }) => {
      const ctx = getContext();
      const character = resolve(ctx, id);
      if (!character) return err(`Character "${id}" not found`);
      if (!ctx.bridge?.isPluginConnected()) return err("Error: Logseq plugin not connected");

      const pageName = character.persona ?? `Characters/${character.name}`;
      const properties: Record<string, string> = {
        "character-id": character.id,
        "memory-tag": character.memory_tag,
      };
      if (character.model) properties["model"] = character.model;
      if (character.skills.length > 0) properties["skills"] = character.skills.join(", ");

      try {
        await ctx.bridge.sendRequest("page_create", { name: pageName, properties }, ctx.traceId);
        if (character.system_prompt) {
          await ctx.bridge.sendRequest(
            "block_append",
            { page: pageName, content: character.system_prompt },
            ctx.traceId
          );
        }
        return ok({ synced: true, page: pageName });
      } catch (e: any) {
        return err(`Error: ${e.message}`);
      }
    },
  );
}
