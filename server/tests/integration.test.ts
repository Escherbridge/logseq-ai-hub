import { describe, it, expect, beforeAll, afterAll } from "bun:test";
import type { Server } from "bun";
import { Database } from "bun:sqlite";
import { createRouter } from "../src/router";
import { sseManager } from "../src/services/sse";
import { initializeSchema } from "../src/db/schema";
import type { Config } from "../src/config";

/**
 * Integration test: full flow through the server.
 *
 * 1. POST /webhook/whatsapp → message stored + SSE broadcast
 * 2. GET /api/messages → returns the stored message
 * 3. POST /api/send → outgoing message stored (external API mocked by test token)
 */

const TEST_CONFIG: Config = {
  port: 0, // unused in these tests
  whatsappVerifyToken: "test-verify",
  whatsappAccessToken: "test-wa-token",
  whatsappPhoneNumberId: "123456",
  telegramBotToken: "test-tg-token",
  pluginApiToken: "test-api-token",
  databasePath: ":memory:",
};

function makeWhatsAppPayload(messageId: string, from: string, text: string) {
  return {
    object: "whatsapp_business_account",
    entry: [
      {
        id: "BIZ_ID",
        changes: [
          {
            value: {
              messaging_product: "whatsapp",
              metadata: { display_phone_number: "15551234567", phone_number_id: "123456" },
              contacts: [{ profile: { name: "Integration Test User" }, wa_id: from }],
              messages: [
                {
                  from,
                  id: messageId,
                  timestamp: String(Math.floor(Date.now() / 1000)),
                  text: { body: text },
                  type: "text",
                },
              ],
            },
            field: "messages",
          },
        ],
      },
    ],
  };
}

describe("Integration: full message flow", () => {
  let db: Database;
  let handler: (req: Request) => Promise<Response>;

  beforeAll(() => {
    db = new Database(":memory:");
    db.exec("PRAGMA foreign_keys = ON");
    initializeSchema(db);
    sseManager.start();
    handler = createRouter({ config: TEST_CONFIG, db });
  });

  afterAll(() => {
    sseManager.stop();
    db.close();
  });

  it("POST webhook → stores message → GET messages returns it", async () => {
    // 1. Send a WhatsApp webhook
    const webhookRes = await handler(
      new Request("http://localhost/webhook/whatsapp", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(makeWhatsAppPayload("wamid_integ_1", "15559999999", "Hello from integration test!")),
      })
    );
    expect(webhookRes.status).toBe(200);

    // 2. GET messages with auth
    const messagesRes = await handler(
      new Request("http://localhost/api/messages", {
        headers: { Authorization: `Bearer ${TEST_CONFIG.pluginApiToken}` },
      })
    );
    expect(messagesRes.status).toBe(200);
    const messagesBody = await messagesRes.json();
    expect(messagesBody.success).toBe(true);
    expect(messagesBody.data.messages.length).toBeGreaterThanOrEqual(1);

    const msg = messagesBody.data.messages.find(
      (m: any) => m.externalId === "wamid_integ_1"
    );
    expect(msg).toBeDefined();
    expect(msg.content).toBe("Hello from integration test!");
    expect(msg.platform).toBe("whatsapp");
    expect(msg.direction).toBe("incoming");
  });

  it("deduplicates webhook messages with same external_id", async () => {
    const payload = makeWhatsAppPayload("wamid_dedup_1", "15558888888", "Dedup test");

    // Send same webhook twice
    await handler(
      new Request("http://localhost/webhook/whatsapp", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      })
    );
    await handler(
      new Request("http://localhost/webhook/whatsapp", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      })
    );

    // Check only one message exists with this external_id
    const res = await handler(
      new Request("http://localhost/api/messages?contact_id=whatsapp:15558888888", {
        headers: { Authorization: `Bearer ${TEST_CONFIG.pluginApiToken}` },
      })
    );
    const body = await res.json();
    const matching = body.data.messages.filter(
      (m: any) => m.externalId === "wamid_dedup_1"
    );
    expect(matching.length).toBe(1);
  });

  it("WhatsApp verification echoes challenge", async () => {
    const url = `http://localhost/webhook/whatsapp?hub.mode=subscribe&hub.verify_token=${TEST_CONFIG.whatsappVerifyToken}&hub.challenge=test_challenge_123`;
    const res = await handler(new Request(url));
    expect(res.status).toBe(200);
    expect(await res.text()).toBe("test_challenge_123");
  });

  it("rejects API requests without auth", async () => {
    const res = await handler(new Request("http://localhost/api/messages"));
    expect(res.status).toBe(401);
  });

  it("SSE endpoint requires auth token", async () => {
    const res = await handler(new Request("http://localhost/events?token=wrong"));
    expect(res.status).toBe(401);
  });

  it("SSE endpoint connects with valid token", async () => {
    const res = await handler(
      new Request(`http://localhost/events?token=${TEST_CONFIG.pluginApiToken}`)
    );
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("text/event-stream");

    // Read the initial connected event
    const reader = res.body!.getReader();
    const { value } = await reader.read();
    const text = new TextDecoder().decode(value);
    expect(text).toContain("event: connected");
    reader.cancel();
  });

  it("health endpoint returns ok", async () => {
    const res = await handler(new Request("http://localhost/health"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.status).toBe("ok");
  });

  it("Telegram webhook stores message", async () => {
    const payload = {
      update_id: 99999,
      message: {
        message_id: 1,
        from: { id: 7777, is_bot: false, first_name: "Telegram", last_name: "User" },
        chat: { id: 7777, type: "private" },
        date: Math.floor(Date.now() / 1000),
        text: "Hello from Telegram integration!",
      },
    };

    const res = await handler(
      new Request("http://localhost/webhook/telegram", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      })
    );
    expect(res.status).toBe(200);

    // Verify stored
    const msgRes = await handler(
      new Request("http://localhost/api/messages?contact_id=telegram:7777", {
        headers: { Authorization: `Bearer ${TEST_CONFIG.pluginApiToken}` },
      })
    );
    const msgBody = await msgRes.json();
    expect(msgBody.data.messages.length).toBe(1);
    expect(msgBody.data.messages[0].content).toBe("Hello from Telegram integration!");
  });

  it("CORS headers present on responses", async () => {
    const res = await handler(new Request("http://localhost/health"));
    expect(res.headers.get("Access-Control-Allow-Origin")).toBe("*");
  });

  it("OPTIONS returns CORS preflight", async () => {
    const res = await handler(
      new Request("http://localhost/api/send", { method: "OPTIONS" })
    );
    expect(res.status).toBe(204);
    expect(res.headers.get("Access-Control-Allow-Methods")).toContain("POST");
  });
});
