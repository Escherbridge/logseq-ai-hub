import { describe, it, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb, seedTestContact, seedTestMessage } from "./helpers";
import { handleGetMessages } from "../src/routes/api/messages";
import { loadConfig } from "../src/config";

describe("Messages API", () => {
  let db: Database;
  const config = { ...loadConfig(), pluginApiToken: "test-secret" };

  beforeEach(() => {
    db = createTestDb();
  });

  it("should reject unauthorized requests", async () => {
    const req = new Request("http://localhost/api/messages");
    const res = handleGetMessages(req, config, db);
    expect(res.status).toBe(401);
  });

  it("should return messages", async () => {
    const contactId = seedTestContact(db);
    seedTestMessage(db, contactId, "Hello");
    seedTestMessage(db, contactId, "World");

    const req = new Request("http://localhost/api/messages", {
      headers: { Authorization: "Bearer test-secret" },
    });
    const res = handleGetMessages(req, config, db);
    expect(res.status).toBe(200);

    const body = await res.json();
    expect(body.success).toBe(true);
    expect(body.data.messages.length).toBe(2);
    expect(body.data.total).toBe(2);
  });

  it("should filter by contact_id", async () => {
    const contact1 = seedTestContact(db, "whatsapp", "111", "User 1");
    const contact2 = seedTestContact(db, "telegram", "222", "User 2");
    seedTestMessage(db, contact1, "WA msg");
    seedTestMessage(db, contact2, "TG msg");

    const req = new Request(
      `http://localhost/api/messages?contact_id=${contact1}`,
      {
        headers: { Authorization: "Bearer test-secret" },
      }
    );
    const res = handleGetMessages(req, config, db);
    const body = await res.json();
    expect(body.data.messages.length).toBe(1);
    expect(body.data.messages[0].content).toBe("WA msg");
  });

  it("should support pagination", async () => {
    const contactId = seedTestContact(db);
    for (let i = 0; i < 5; i++) {
      seedTestMessage(db, contactId, `Msg ${i}`);
    }

    const req = new Request("http://localhost/api/messages?limit=2&offset=0", {
      headers: { Authorization: "Bearer test-secret" },
    });
    const res = handleGetMessages(req, config, db);
    const body = await res.json();
    expect(body.data.messages.length).toBe(2);
    expect(body.data.hasMore).toBe(true);
  });
});
