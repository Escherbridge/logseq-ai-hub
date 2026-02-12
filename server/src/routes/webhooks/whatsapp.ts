import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import { upsertContact } from "../../db/contacts";
import { insertMessage } from "../../db/messages";
import { sseManager } from "../../services/sse";
import {
  parseWhatsAppWebhook,
  extractContactName,
} from "../../services/whatsapp";

export function handleWhatsAppVerify(req: Request, config: Config): Response {
  const url = new URL(req.url);
  const mode = url.searchParams.get("hub.mode");
  const token = url.searchParams.get("hub.verify_token");
  const challenge = url.searchParams.get("hub.challenge");

  if (mode === "subscribe" && token === config.whatsappVerifyToken) {
    return new Response(challenge || "", { status: 200 });
  }

  return Response.json({ error: "Verification failed" }, { status: 403 });
}

export async function handleWhatsAppWebhook(
  req: Request,
  config: Config,
  db: Database
): Promise<Response> {
  const body = await req.json();
  const messages = parseWhatsAppWebhook(body);

  for (const msg of messages) {
    if (!msg.text?.body) continue; // Only handle text messages for now

    const contactName = extractContactName(body, msg.from);
    const contact = upsertContact(db, "whatsapp", msg.from, contactName);

    const stored = insertMessage(db, {
      externalId: msg.id,
      contactId: contact.id,
      platform: "whatsapp",
      direction: "incoming",
      content: msg.text.body,
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
            platform: "whatsapp",
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
  }

  return new Response("OK", { status: 200 });
}
