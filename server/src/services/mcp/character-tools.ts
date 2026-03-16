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
import {
  setRelationship,
  listRelationships,
  deleteRelationship,
} from "../../db/character-relationships";
import { runCharacterTurn } from "../character-runtime";

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
    "character_react_to_event",
    "Have a character process and react to a hub event. Updates their session and state.",
    {
      id: z.string().describe("Character ID or name"),
      eventType: z.string().describe("Event type string (e.g. 'npc.attacked', 'quest.completed')"),
      payload: z.record(z.unknown()).optional().describe("Event payload object"),
      source: z.string().optional().describe("Event source"),
      sessionId: z.string().optional().describe("Existing session to continue; creates new if absent"),
    },
    async ({ id, eventType, payload, source, sessionId }) => {
      const ctx = getContext();
      if (!ctx.config.llmApiKey) return err("LLM API key not configured");
      const character = resolve(ctx, id);
      if (!character) return err(`Character "${id}" not found`);

      const type = typeof eventType === "string" ? eventType.trim() : "";
      if (!type) return err("Missing or empty eventType");

      const sourceLine = source ? `\nSource: ${source}` : "";
      const payloadText = payload ? JSON.stringify(payload, null, 2) : "(no payload)";
      const message = `[Event: ${type}]${sourceLine}\n${payloadText}`;

      try {
        const result = await runCharacterTurn(
          message,
          character,
          sessionId,
          ctx.config,
          ctx.db,
          ctx.bridge,
          ctx.traceId
        );
        return ok(result);
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : String(e);
        return err(`Error: ${msg}`);
      }
    }
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

  server.tool(
    "character_relationship_set",
    "Set or update a directed relationship between two characters. Strength ranges from -100 (hostile) to +100 (devoted).",
    {
      fromId: z.string().describe("Source character ID or name"),
      toId: z.string().describe("Target character ID or name"),
      type: z.string().describe("Relationship type (e.g. 'friend', 'enemy', 'rival', 'mentor')"),
      strength: z
        .number()
        .int()
        .min(-100)
        .max(100)
        .optional()
        .describe("Relationship strength from -100 to +100"),
      notes: z.string().nullable().optional().describe("Optional context notes"),
    },
    async ({ fromId, toId, type, strength, notes }) => {
      const ctx = getContext();
      const from = resolve(ctx, fromId);
      if (!from) return err(`Character "${fromId}" not found`);
      const to = resolve(ctx, toId);
      if (!to) return err(`Character "${toId}" not found`);
      if (from.id === to.id) return err("A character cannot have a relationship with itself");
      try {
        const rel = setRelationship(ctx.db, from.id, to.id, {
          type: type.trim(),
          strength,
          notes: notes ?? null,
        });
        return ok(rel);
      } catch (e: any) {
        return err(`Error: ${e.message}`);
      }
    },
  );

  server.tool(
    "character_relationship_list",
    "List all relationships for a character (both outgoing and incoming).",
    { id: z.string().describe("Character ID or name") },
    async ({ id }) => {
      const ctx = getContext();
      const character = resolve(ctx, id);
      if (!character) return err(`Character "${id}" not found`);
      return ok(listRelationships(ctx.db, character.id));
    },
  );

  server.tool(
    "character_relationship_delete",
    "Delete a directed relationship between two characters.",
    {
      fromId: z.string().describe("Source character ID or name"),
      toId: z.string().describe("Target character ID or name"),
    },
    async ({ fromId, toId }) => {
      const ctx = getContext();
      const from = resolve(ctx, fromId);
      if (!from) return err(`Character "${fromId}" not found`);
      const to = resolve(ctx, toId);
      if (!to) return err(`Character "${toId}" not found`);
      const deleted = deleteRelationship(ctx.db, from.id, to.id);
      return deleted ? ok({ deleted: true }) : err("Relationship not found");
    },
  );

  const MAX_SCENE_PARTICIPANTS = 20;

  server.tool(
    "character_scene_react",
    "Run a scene: send one description to multiple characters in parallel and collect their reactions.",
    {
      description: z.string().describe("Scene description or event all characters will react to"),
      characterIds: z
        .array(z.string())
        .min(1)
        .max(MAX_SCENE_PARTICIPANTS)
        .describe("Character IDs or names to include"),
      sessionIds: z
        .record(z.string())
        .optional()
        .describe("Optional map of character ID/name → existing session ID to continue"),
    },
    async ({ description, characterIds, sessionIds }) => {
      const ctx = getContext();
      if (!ctx.config.llmApiKey) return err("LLM API key not configured");

      const tasks = characterIds.map(async (idOrName) => {
        const character = resolve(ctx, idOrName);
        if (!character) {
          return { character: { id: idOrName, name: idOrName }, sessionId: "", response: "", error: `Character "${idOrName}" not found` };
        }
        const sessionId =
          sessionIds?.[character.id] ?? sessionIds?.[character.name] ?? sessionIds?.[idOrName];
        try {
          const result = await runCharacterTurn(
            description,
            character,
            sessionId,
            ctx.config,
            ctx.db,
            ctx.bridge,
            ctx.traceId
          );
          return { character: result.character, sessionId: result.sessionId, response: result.response };
        } catch (e: unknown) {
          const msg = e instanceof Error ? e.message : String(e);
          return { character: { id: character.id, name: character.name }, sessionId: sessionId ?? "", response: "", error: msg };
        }
      });

      const results = await Promise.allSettled(tasks);
      const reactions = results.map((r) =>
        r.status === "fulfilled"
          ? r.value
          : { character: { id: "", name: "" }, sessionId: "", response: "", error: String(r.reason) }
      );

      return ok({ description, reactions });
    },
  );
}
