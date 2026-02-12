import { describe, it, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb } from "./helpers";
import { handleTelegramWebhook } from "../src/routes/webhooks/telegram";
import { loadConfig } from "../src/config";
import { getMessages } from "../src/db/messages";
import { listContacts } from "../src/db/contacts";

describe("Telegram Webhook", () => {
  let db: Database;
  const config = loadConfig();

  beforeEach(() => {
    db = createTestDb();
  });

  it("should store incoming Telegram message", async () => {
    const payload = {
      update_id: 123456789,
      message: {
        message_id: 1,
        from: {
          id: 42,
          first_name: "Jane",
          last_name: "Doe",
          username: "janedoe",
        },
        chat: { id: 42 },
        date: 1700000000,
        text: "Hello from Telegram!",
      },
    };

    const req = new Request("http://localhost/webhook/telegram", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });

    const res = await handleTelegramWebhook(req, config, db);
    expect(res.status).toBe(200);

    const contacts = listContacts(db, "telegram");
    expect(contacts.length).toBe(1);
    expect(contacts[0].displayName).toBe("Jane Doe");
    expect(contacts[0].platformUserId).toBe("42");

    const result = getMessages(db, { contactId: "telegram:42" });
    expect(result.messages.length).toBe(1);
    expect(result.messages[0].content).toBe("Hello from Telegram!");
  });

  it("should handle update without message gracefully", async () => {
    const payload = { update_id: 999 };

    const req = new Request("http://localhost/webhook/telegram", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });

    const res = await handleTelegramWebhook(req, config, db);
    expect(res.status).toBe(200);

    const result = getMessages(db);
    expect(result.total).toBe(0);
  });
});
