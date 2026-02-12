import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import { upsertContact } from "../../db/contacts";
import { insertMessage } from "../../db/messages";
import { sseManager } from "../../services/sse";
import { parseTelegramUpdate } from "../../services/telegram";

export async function handleTelegramWebhook(
  req: Request,
  config: Config,
  db: Database
): Promise<Response> {
  const body = await req.json();
  const update = parseTelegramUpdate(body);

  if (!update?.message?.text) {
    return new Response("OK", { status: 200 });
  }

  const msg = update.message;
  const userId = String(msg.from.id);
  const displayName = [msg.from.first_name, msg.from.last_name]
    .filter(Boolean)
    .join(" ");

  const contact = upsertContact(db, "telegram", userId, displayName);

  const stored = insertMessage(db, {
    externalId: String(update.update_id),
    contactId: contact.id,
    platform: "telegram",
    direction: "incoming",
    content: msg.text,
    mediaType: "text",
    status: "received",
    rawPayload: JSON.stringify(body),
  });

  if (stored) {
    sseManager.broadcast({
      type: "new_message",
      data: {
        message: {
          id: stored.id,
          externalId: stored.externalId,
          platform: "telegram",
          direction: "incoming",
          contact: {
            id: contact.id,
            displayName: contact.displayName,
            platformUserId: contact.platformUserId,
          },
          content: stored.content,
          mediaType: stored.mediaType,
          status: stored.status,
          createdAt: stored.createdAt,
        },
      },
    });
  }

  return new Response("OK", { status: 200 });
}
