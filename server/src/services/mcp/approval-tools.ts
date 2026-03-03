import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { Database } from "bun:sqlite";
import type { McpToolContext } from "../../types/mcp";
import type { ApprovalStore } from "../approval-store";

/**
 * Resolve a contact identifier to a contact ID string.
 * Accepts either a "platform:userId" format or a display name.
 */
export function resolveContact(db: Database, contactIdentifier: string): string {
  // If it looks like a platform:id format (has a colon with content on both sides)
  const colonIndex = contactIdentifier.indexOf(":");
  if (colonIndex > 0 && colonIndex < contactIdentifier.length - 1) {
    // Direct ID lookup
    const row = db
      .query(
        `SELECT id FROM contacts WHERE id = ?`,
      )
      .get(contactIdentifier) as { id: string } | null;

    if (!row) {
      throw new Error(`Contact not found: ${contactIdentifier}`);
    }
    return row.id;
  }

  // Display name lookup (case-insensitive)
  const rows = db
    .query(
      `SELECT id, display_name as displayName FROM contacts WHERE LOWER(display_name) = LOWER(?)`,
    )
    .all(contactIdentifier) as { id: string; displayName: string }[];

  if (rows.length === 0) {
    throw new Error(`Contact not found: ${contactIdentifier}`);
  }
  if (rows.length > 1) {
    throw new Error(`Multiple contacts match "${contactIdentifier}" - use a platform:id format instead`);
  }

  return rows[0].id;
}

/**
 * Convert a timeout in seconds to a human-readable string.
 */
function formatTimeout(seconds: number): string {
  if (seconds < 60) {
    return `${seconds} second${seconds === 1 ? "" : "s"}`;
  }
  const minutes = Math.round(seconds / 60);
  return `${minutes} minute${minutes === 1 ? "" : "s"}`;
}

/**
 * Format the outbound approval message to be sent to the contact.
 */
export function formatApprovalMessage(
  question: string,
  options?: string[],
  timeoutSeconds?: number,
): string {
  const lines: string[] = [];
  lines.push("🤖 Automated Request");
  lines.push("");
  lines.push(question);

  if (options && options.length > 0) {
    lines.push("");
    lines.push(`Reply with one of: ${options.join(", ")}`);
  }

  const timeout = timeoutSeconds ?? 300;
  lines.push(`⏱ Please reply within ${formatTimeout(timeout)}`);

  return lines.join("\n");
}

/**
 * Register the `ask_human` MCP tool on the given server.
 */
export function registerApprovalTools(
  server: McpServer,
  getContext: () => McpToolContext,
): void {
  server.tool(
    "ask_human",
    "Send a message to a human contact and wait for their reply (approval/rejection). Blocks until a response is received or the timeout expires.",
    {
      contact: z
        .string()
        .describe(
          "Contact to ask - either a platform:id (e.g. 'whatsapp:15551234567') or a display name",
        ),
      question: z.string().describe("The question or request to send to the human"),
      options: z
        .array(z.string())
        .optional()
        .describe("Optional list of valid response options (e.g. ['approve', 'reject'])"),
      timeout_seconds: z
        .number()
        .optional()
        .describe("How long to wait for a reply in seconds (default 300, max 3600)"),
    },
    async ({ contact, question, options, timeout_seconds }) => {
      const ctx = getContext();
      const approvalStore = ctx.approvalStore;
      if (!approvalStore) {
        return { content: [{ type: "text", text: "Error: Approval store not initialized" }], isError: true };
      }
      try {
        // Clamp timeout
        const timeout = Math.min(timeout_seconds ?? 300, 3600);

        // Resolve contact
        let contactId: string;
        try {
          contactId = await resolveContact(ctx.db, contact);
        } catch (err: any) {
          return {
            content: [{ type: "text", text: `Error: ${err.message}` }],
            isError: true,
          };
        }

        // Determine platform from contactId
        const platform = contactId.split(":")[0];

        // Format the message
        const messageContent = formatApprovalMessage(question, options, timeout);

        // Send the message via internal API
        const sendUrl = `http://localhost:${ctx.config.port}/api/send`;
        const sendResponse = await fetch(sendUrl, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${ctx.config.pluginApiToken}`,
          },
          body: JSON.stringify({
            platform,
            recipient: contactId.split(":")[1],
            content: messageContent,
          }),
        });

        if (!sendResponse.ok) {
          const sendResult = await sendResponse.json().catch(() => ({}));
          return {
            content: [
              {
                type: "text",
                text: `Error: Failed to send message - ${(sendResult as any).error || sendResponse.statusText}`,
              },
            ],
            isError: true,
          };
        }

        // Create approval and wait
        const { promise } = approvalStore.create({
          contactId,
          question,
          options,
          timeout,
        });

        const result = await promise;

        return {
          content: [
            {
              type: "text",
              text: JSON.stringify(
                {
                  status: result.status,
                  response: result.response,
                  resolvedBy: result.resolvedBy,
                },
                null,
                2,
              ),
            },
          ],
        };
      } catch (err: any) {
        return {
          content: [{ type: "text", text: `Error: ${err.message}` }],
          isError: true,
        };
      }
    },
  );
}
