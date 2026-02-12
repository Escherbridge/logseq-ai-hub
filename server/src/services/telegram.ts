import type { Config } from "../config";

export async function sendTelegramMessage(
  config: Config,
  chatId: string,
  text: string
): Promise<{ messageId: number }> {
  const url = `https://api.telegram.org/bot${config.telegramBotToken}/sendMessage`;
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      chat_id: chatId,
      text: text,
    }),
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Telegram API error: ${response.status} - ${error}`);
  }

  const data = (await response.json()) as { result: { message_id: number } };
  return { messageId: data.result.message_id };
}

export async function setTelegramWebhook(
  config: Config,
  webhookUrl: string
): Promise<void> {
  const url = `https://api.telegram.org/bot${config.telegramBotToken}/setWebhook`;
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url: webhookUrl }),
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Telegram setWebhook error: ${response.status} - ${error}`);
  }
}

export interface TelegramUpdate {
  update_id: number;
  message?: {
    message_id: number;
    from: {
      id: number;
      first_name: string;
      last_name?: string;
      username?: string;
    };
    chat: { id: number };
    date: number;
    text?: string;
  };
}

export function parseTelegramUpdate(body: any): TelegramUpdate | null {
  if (!body || typeof body.update_id !== "number") return null;
  return body as TelegramUpdate;
}
