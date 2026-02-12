import { describe, it, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb, seedTestContact } from "./helpers";
import {
  handleWhatsAppVerify,
  handleWhatsAppWebhook,
} from "../src/routes/webhooks/whatsapp";
import { loadConfig } from "../src/config";
import { getMessages } from "../src/db/messages";
import { listContacts } from "../src/db/contacts";

describe("WhatsApp Webhook", () => {
  let db: Database;
  const config = { ...loadConfig(), whatsappVerifyToken: "test-token" };

  beforeEach(() => {
    db = createTestDb();
  });

  describe("Verification", () => {
    it("should verify with correct token", () => {
      const req = new Request(
        "http://localhost/webhook/whatsapp?hub.mode=subscribe&hub.verify_token=test-token&hub.challenge=challenge123"
      );
      const res = handleWhatsAppVerify(req, config);
      expect(res.status).toBe(200);
    });

    it("should reject with wrong token", () => {
      const req = new Request(
        "http://localhost/webhook/whatsapp?hub.mode=subscribe&hub.verify_token=wrong&hub.challenge=challenge123"
      );
      const res = handleWhatsAppVerify(req, config);
      expect(res.status).toBe(403);
    });

    it("should echo the challenge string", async () => {
      const req = new Request(
        "http://localhost/webhook/whatsapp?hub.mode=subscribe&hub.verify_token=test-token&hub.challenge=my-challenge"
      );
      const res = handleWhatsAppVerify(req, config);
      const text = await res.text();
      expect(text).toBe("my-challenge");
    });
  });

  describe("Message ingestion", () => {
    it("should store incoming WhatsApp message", async () => {
      const payload = {
        entry: [
          {
            changes: [
              {
                field: "messages",
                value: {
                  contacts: [
                    {
                      wa_id: "15551234567",
                      profile: { name: "Test User" },
                    },
                  ],
                  messages: [
                    {
                      from: "15551234567",
                      id: "wamid_test123",
                      timestamp: "1700000000",
                      type: "text",
                      text: { body: "Hello from WhatsApp!" },
                    },
                  ],
                },
              },
            ],
          },
        ],
      };

      const req = new Request("http://localhost/webhook/whatsapp", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      const res = await handleWhatsAppWebhook(req, config, db);
      expect(res.status).toBe(200);

      const contacts = listContacts(db, "whatsapp");
      expect(contacts.length).toBe(1);
      expect(contacts[0].displayName).toBe("Test User");

      const result = getMessages(db, { contactId: "whatsapp:15551234567" });
      expect(result.messages.length).toBe(1);
      expect(result.messages[0].content).toBe("Hello from WhatsApp!");
    });

    it("should deduplicate messages by external_id", async () => {
      const payload = {
        entry: [
          {
            changes: [
              {
                field: "messages",
                value: {
                  messages: [
                    {
                      from: "15551234567",
                      id: "wamid_dup",
                      timestamp: "1700000000",
                      type: "text",
                      text: { body: "Hello!" },
                    },
                  ],
                },
              },
            ],
          },
        ],
      };

      const req1 = new Request("http://localhost/webhook/whatsapp", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const req2 = new Request("http://localhost/webhook/whatsapp", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      await handleWhatsAppWebhook(req1, config, db);
      await handleWhatsAppWebhook(req2, config, db);

      const result = getMessages(db);
      expect(result.total).toBe(1);
    });
  });
});
