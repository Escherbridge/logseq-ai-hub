import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import { listContacts } from "../../db/contacts";
import { getMessages } from "../../db/messages";

export function registerMessagingTools(server: McpServer, getContext: () => McpToolContext): void {
  server.tool(
    "message_send",
    "Send a message via WhatsApp or Telegram",
    {
      platform: z.enum(["whatsapp", "telegram"]).describe("Messaging platform"),
      recipient: z.string().describe("Recipient identifier (phone number for WhatsApp, chat ID for Telegram)"),
      content: z.string().describe("Message content"),
    },
    async ({ platform, recipient, content }) => {
      const ctx = getContext();
      try {
        // Use the existing send logic via internal fetch to our own API
        const url = `http://localhost:${ctx.config.port}/api/send`;
        const response = await fetch(url, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${ctx.config.pluginApiToken}`,
          },
          body: JSON.stringify({ platform, recipient, content }),
        });
        const result = await response.json();
        if (!response.ok) {
          return { content: [{ type: "text", text: `Error: ${(result as any).error || "Send failed"}` }], isError: true };
        }
        return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text", text: `Error: ${err.message}` }], isError: true };
      }
    },
  );

  server.tool(
    "message_list",
    "Query message history from the database",
    {
      contact_id: z.string().optional().describe("Filter by contact ID"),
      limit: z.number().optional().describe("Maximum messages to return (default 50)"),
      since: z.string().optional().describe("Only messages after this ISO timestamp"),
    },
    async ({ contact_id, limit, since }) => {
      const ctx = getContext();
      try {
        const result = getMessages(ctx.db, { contactId: contact_id, limit, since });
        return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text", text: `Error: ${err.message}` }], isError: true };
      }
    },
  );

  server.tool(
    "contact_list",
    "List known contacts",
    {
      platform: z.string().optional().describe("Filter by platform (whatsapp, telegram)"),
    },
    async ({ platform }) => {
      const ctx = getContext();
      try {
        const contacts = listContacts(ctx.db, platform);
        return { content: [{ type: "text", text: JSON.stringify({ contacts, count: contacts.length }, null, 2) }] };
      } catch (err: any) {
        return { content: [{ type: "text", text: `Error: ${err.message}` }], isError: true };
      }
    },
  );
}
