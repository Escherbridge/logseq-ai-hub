import { ResourceTemplate } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import { listContacts } from "../../db/contacts";

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
}
