import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import type { SendRequest } from "../../types";
import { upsertContact } from "../../db/contacts";
import { insertMessage } from "../../db/messages";
import { sseManager } from "../../services/sse";
import { sendWhatsAppMessage } from "../../services/whatsapp";
import { sendTelegramMessage } from "../../services/telegram";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";

export async function handleSendMessage(
  req: Request,
  config: Config,
  db: Database
): Promise<Response> {
  if (!authenticate(req, config)) {
    return unauthorizedResponse();
  }

  let body: SendRequest;
  try {
    body = (await req.json()) as SendRequest;
  } catch {
    return Response.json(
      { success: false, error: "Invalid JSON body" },
      { status: 400 }
    );
  }

  if (!body.platform || !body.recipient || !body.content) {
    return Response.json(
      {
        success: false,
        error: "Missing required fields: platform, recipient, content",
      },
      { status: 400 }
    );
  }

  if (body.platform !== "whatsapp" && body.platform !== "telegram") {
    return Response.json(
      { success: false, error: "Platform must be 'whatsapp' or 'telegram'" },
      { status: 400 }
    );
  }

  try {
    let externalId: string;

    if (body.platform === "whatsapp") {
      const result = await sendWhatsAppMessage(
        config,
        body.recipient,
        body.content
      );
      externalId = result.messageId;
    } else {
      const result = await sendTelegramMessage(
        config,
        body.recipient,
        body.content
      );
      externalId = String(result.messageId);
    }

    // Ensure contact exists
    const contact = upsertContact(db, body.platform, body.recipient);

    // Store outgoing message
    const stored = insertMessage(db, {
      externalId,
      contactId: contact.id,
      platform: body.platform,
      direction: "outgoing",
      content: body.content,
      mediaType: "text",
      status: "sent",
    });

    if (stored) {
      sseManager.broadcast({
        type: "message_sent",
        data: {
          message: {
            id: stored.id,
            externalId: stored.externalId,
            platform: body.platform,
            direction: "outgoing",
            contact: {
              id: contact.id,
              displayName: contact.displayName,
              platformUserId: contact.platformUserId,
            },
            content: stored.content,
            status: stored.status,
            createdAt: stored.createdAt,
          },
        },
      });
    }

    return Response.json({
      success: true,
      data: { messageId: stored?.id, externalId },
    });
  } catch (error: any) {
    return Response.json(
      { success: false, error: error.message || "Failed to send message" },
      { status: 500 }
    );
  }
}
