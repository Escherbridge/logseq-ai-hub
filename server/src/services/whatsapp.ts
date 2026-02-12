import type { Config } from "../config";

export async function sendWhatsAppMessage(
  config: Config,
  recipientPhone: string,
  text: string
): Promise<{ messageId: string }> {
  const url = `https://graph.facebook.com/v21.0/${config.whatsappPhoneNumberId}/messages`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${config.whatsappAccessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      messaging_product: "whatsapp",
      to: recipientPhone,
      type: "text",
      text: { body: text },
    }),
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`WhatsApp API error: ${response.status} - ${error}`);
  }

  const data = (await response.json()) as { messages: Array<{ id: string }> };
  return { messageId: data.messages[0].id };
}

export interface WhatsAppWebhookMessage {
  from: string;
  id: string;
  timestamp: string;
  type: string;
  text?: { body: string };
}

export function parseWhatsAppWebhook(body: any): WhatsAppWebhookMessage[] {
  const messages: WhatsAppWebhookMessage[] = [];

  if (!body?.entry) return messages;

  for (const entry of body.entry) {
    for (const change of entry.changes || []) {
      if (change.field !== "messages") continue;
      const value = change.value;
      if (!value?.messages) continue;

      for (const msg of value.messages) {
        messages.push({
          from: msg.from,
          id: msg.id,
          timestamp: msg.timestamp,
          type: msg.type,
          text: msg.text,
        });
      }
    }
  }

  return messages;
}

export function extractContactName(body: any, phone: string): string | null {
  if (!body?.entry) return null;
  for (const entry of body.entry) {
    for (const change of entry.changes || []) {
      const contacts = change.value?.contacts || [];
      for (const contact of contacts) {
        if (contact.wa_id === phone) {
          return contact.profile?.name || null;
        }
      }
    }
  }
  return null;
}
