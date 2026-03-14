import { ResourceTemplate } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import { listContacts } from "../../db/contacts";
import { listCharacters, getCharacter, getCharacterByName } from "../../db/characters";
import { listCharacterSessions } from "../../db/character-sessions";

export function registerResources(server: McpServer, getContext: () => McpToolContext): void {
  // logseq://pages/{name} - Read a specific page
  server.resource(
    "logseq-page",
    new ResourceTemplate("logseq://pages/{name}", { list: undefined }),
    { description: "Content of a specific Logseq page", mimeType: "application/json" },
    async (uri, variables) => {
      const ctx = getContext();
      const name = variables.name as string;
      if (!ctx.bridge?.isPluginConnected()) {
        return { contents: [{ uri: uri.href, text: "Error: Logseq plugin not connected", mimeType: "text/plain" }] };
      }
      try {
        const result = await ctx.bridge.sendRequest("page_read", { name }, ctx.traceId);
        return { contents: [{ uri: uri.href, text: JSON.stringify(result, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://jobs - Current job statuses
  server.resource(
    "logseq-jobs",
    "logseq://jobs",
    { description: "Current job statuses summary", mimeType: "application/json" },
    async (uri) => {
      const ctx = getContext();
      if (!ctx.bridge?.isPluginConnected()) {
        return { contents: [{ uri: uri.href, text: "Error: Logseq plugin not connected", mimeType: "text/plain" }] };
      }
      try {
        const result = await ctx.bridge.sendRequest("list_jobs", {}, ctx.traceId);
        return { contents: [{ uri: uri.href, text: JSON.stringify(result, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://skills - Available skills
  server.resource(
    "logseq-skills",
    "logseq://skills",
    { description: "Available skill definitions", mimeType: "application/json" },
    async (uri) => {
      const ctx = getContext();
      if (!ctx.bridge?.isPluginConnected()) {
        return { contents: [{ uri: uri.href, text: "Error: Logseq plugin not connected", mimeType: "text/plain" }] };
      }
      try {
        const result = await ctx.bridge.sendRequest("list_skills", {}, ctx.traceId);
        return { contents: [{ uri: uri.href, text: JSON.stringify(result, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://memory/{tag} - Memories by tag
  server.resource(
    "logseq-memory",
    new ResourceTemplate("logseq://memory/{tag}", { list: undefined }),
    { description: "Memories stored under a specific tag", mimeType: "application/json" },
    async (uri, variables) => {
      const ctx = getContext();
      const tag = variables.tag as string;
      if (!ctx.bridge?.isPluginConnected()) {
        return { contents: [{ uri: uri.href, text: "Error: Logseq plugin not connected", mimeType: "text/plain" }] };
      }
      try {
        const result = await ctx.bridge.sendRequest("recall_memory", { tag }, ctx.traceId);
        return { contents: [{ uri: uri.href, text: JSON.stringify(result, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://projects/{name} - Project details with architecture context
  server.resource(
    "logseq-project",
    new ResourceTemplate("logseq://projects/{name}", { list: undefined }),
    { description: "Code project details including architecture context", mimeType: "application/json" },
    async (uri, variables) => {
      const ctx = getContext();
      const name = variables.name as string;
      if (!ctx.bridge?.isPluginConnected()) {
        return { contents: [{ uri: uri.href, text: "Error: Logseq plugin not connected", mimeType: "text/plain" }] };
      }
      try {
        const result = await ctx.bridge.sendRequest("project_get", { name }, ctx.traceId);
        return { contents: [{ uri: uri.href, text: JSON.stringify(result, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://contacts - Known contacts (server-side, no bridge needed)
  server.resource(
    "logseq-contacts",
    "logseq://contacts",
    { description: "Known contacts list", mimeType: "application/json" },
    async (uri) => {
      const ctx = getContext();
      try {
        const contacts = listContacts(ctx.db);
        return { contents: [{ uri: uri.href, text: JSON.stringify({ contacts, count: contacts.length }, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://characters - All characters (server-side)
  server.resource(
    "logseq-characters",
    "logseq://characters",
    { description: "All character definitions", mimeType: "application/json" },
    async (uri) => {
      const ctx = getContext();
      try {
        const characters = listCharacters(ctx.db);
        return { contents: [{ uri: uri.href, text: JSON.stringify({ characters, count: characters.length }, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://characters/{id} - Single character by ID or name
  server.resource(
    "logseq-character",
    new ResourceTemplate("logseq://characters/{id}", { list: undefined }),
    { description: "Single character by ID or name", mimeType: "application/json" },
    async (uri, variables) => {
      const ctx = getContext();
      const idOrName = variables.id as string;
      try {
        const character = getCharacter(ctx.db, idOrName) ?? getCharacterByName(ctx.db, idOrName);
        if (!character) {
          return { contents: [{ uri: uri.href, text: JSON.stringify({ error: "Character not found", idOrName }, null, 2), mimeType: "application/json" }] };
        }
        return { contents: [{ uri: uri.href, text: JSON.stringify(character, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://character-sessions/{characterId} - Sessions for a character
  server.resource(
    "logseq-character-sessions",
    new ResourceTemplate("logseq://character-sessions/{characterId}", { list: undefined }),
    { description: "Character conversation sessions by character ID or name", mimeType: "application/json" },
    async (uri, variables) => {
      const ctx = getContext();
      const characterIdOrName = variables.characterId as string;
      try {
        const character = getCharacter(ctx.db, characterIdOrName) ?? getCharacterByName(ctx.db, characterIdOrName);
        if (!character) {
          return { contents: [{ uri: uri.href, text: JSON.stringify({ error: "Character not found", characterIdOrName }, null, 2), mimeType: "application/json" }] };
        }
        const sessions = listCharacterSessions(ctx.db, character.id);
        return { contents: [{ uri: uri.href, text: JSON.stringify({ characterId: character.id, sessions, count: sessions.length }, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://projects/{name}/adrs - Architecture Decision Records for a project
  server.resource(
    "logseq-project-adrs",
    new ResourceTemplate("logseq://projects/{name}/adrs", { list: undefined }),
    { description: "Architecture Decision Records for a code project", mimeType: "application/json" },
    async (uri, variables) => {
      const ctx = getContext();
      const name = variables.name as string;
      if (!ctx.bridge?.isPluginConnected()) {
        return { contents: [{ uri: uri.href, text: "Error: Logseq plugin not connected", mimeType: "text/plain" }] };
      }
      try {
        const result = await ctx.bridge.sendRequest("adr_list", { project: name }, ctx.traceId);
        return { contents: [{ uri: uri.href, text: JSON.stringify(result, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://projects/{name}/lessons - Lessons learned for a project
  server.resource(
    "logseq-project-lessons",
    new ResourceTemplate("logseq://projects/{name}/lessons", { list: undefined }),
    { description: "Lessons learned for a code project", mimeType: "application/json" },
    async (uri, variables) => {
      const ctx = getContext();
      const name = variables.name as string;
      if (!ctx.bridge?.isPluginConnected()) {
        return { contents: [{ uri: uri.href, text: "Error: Logseq plugin not connected", mimeType: "text/plain" }] };
      }
      try {
        const result = await ctx.bridge.sendRequest("lesson_search", { project: name }, ctx.traceId);
        return { contents: [{ uri: uri.href, text: JSON.stringify(result, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://projects/{name}/tracks - Tracks/tasks for a project
  server.resource(
    "logseq-project-tracks",
    new ResourceTemplate("logseq://projects/{name}/tracks", { list: undefined }),
    { description: "Tracks and tasks for a code project", mimeType: "application/json" },
    async (uri, variables) => {
      const ctx = getContext();
      const name = variables.name as string;
      if (!ctx.bridge?.isPluginConnected()) {
        return { contents: [{ uri: uri.href, text: "Error: Logseq plugin not connected", mimeType: "text/plain" }] };
      }
      try {
        const result = await ctx.bridge.sendRequest("track_list", { project: name }, ctx.traceId);
        return { contents: [{ uri: uri.href, text: JSON.stringify(result, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );

  // logseq://projects/{name}/safeguards - Safeguard policies for a project
  server.resource(
    "logseq-project-safeguards",
    new ResourceTemplate("logseq://projects/{name}/safeguards", { list: undefined }),
    { description: "Safeguard policies for a code project", mimeType: "application/json" },
    async (uri, variables) => {
      const ctx = getContext();
      const name = variables.name as string;
      if (!ctx.bridge?.isPluginConnected()) {
        return { contents: [{ uri: uri.href, text: "Error: Logseq plugin not connected", mimeType: "text/plain" }] };
      }
      try {
        const result = await ctx.bridge.sendRequest("safeguard_policy_get", { project: name }, ctx.traceId);
        return { contents: [{ uri: uri.href, text: JSON.stringify(result, null, 2), mimeType: "application/json" }] };
      } catch (err: any) {
        return { contents: [{ uri: uri.href, text: `Error: ${err.message}`, mimeType: "text/plain" }] };
      }
    },
  );
}
