import { describe, it, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb, seedTestContact } from "./helpers";
import { upsertContact, getContact, listContacts } from "../src/db/contacts";
import { insertMessage, getMessage, getMessages } from "../src/db/messages";

describe("Database: Contacts", () => {
  let db: Database;

  beforeEach(() => {
    db = createTestDb();
  });

  it("should upsert a new contact", () => {
    const contact = upsertContact(db, "whatsapp", "15551234567", "John Doe");
    expect(contact.id).toBe("whatsapp:15551234567");
    expect(contact.platform).toBe("whatsapp");
    expect(contact.displayName).toBe("John Doe");
  });

  it("should update existing contact on upsert", () => {
    upsertContact(db, "whatsapp", "15551234567", "John");
    const updated = upsertContact(db, "whatsapp", "15551234567", "John Doe");
    expect(updated.displayName).toBe("John Doe");

    const all = listContacts(db);
    expect(all.length).toBe(1);
  });

  it("should return null for non-existent contact", () => {
    const contact = getContact(db, "whatsapp:nonexistent");
    expect(contact).toBeNull();
  });

  it("should list contacts filtered by platform", () => {
    upsertContact(db, "whatsapp", "111", "WA User");
    upsertContact(db, "telegram", "222", "TG User");

    const whatsappContacts = listContacts(db, "whatsapp");
    expect(whatsappContacts.length).toBe(1);
    expect(whatsappContacts[0].displayName).toBe("WA User");
  });
});

describe("Database: Messages", () => {
  let db: Database;
  let contactId: string;

  beforeEach(() => {
    db = createTestDb();
    contactId = seedTestContact(db);
  });

  it("should insert a message", () => {
    const msg = insertMessage(db, {
      contactId,
      platform: "whatsapp",
      direction: "incoming",
      content: "Hello!",
    });
    expect(msg).not.toBeNull();
    expect(msg!.content).toBe("Hello!");
    expect(msg!.direction).toBe("incoming");
  });

  it("should deduplicate by external_id", () => {
    insertMessage(db, {
      externalId: "wa_123",
      contactId,
      platform: "whatsapp",
      direction: "incoming",
      content: "Hello!",
    });
    const dup = insertMessage(db, {
      externalId: "wa_123",
      contactId,
      platform: "whatsapp",
      direction: "incoming",
      content: "Hello again!",
    });
    expect(dup).toBeNull();
  });

  it("should get messages with pagination", () => {
    for (let i = 0; i < 5; i++) {
      insertMessage(db, {
        contactId,
        platform: "whatsapp",
        direction: "incoming",
        content: `Message ${i}`,
      });
    }

    const result = getMessages(db, { limit: 2, offset: 0 });
    expect(result.messages.length).toBe(2);
    expect(result.total).toBe(5);
    expect(result.hasMore).toBe(true);
  });

  it("should filter messages by contact", () => {
    const contact2 = seedTestContact(db, "telegram", "999", "Other");
    insertMessage(db, { contactId, platform: "whatsapp", direction: "incoming", content: "WA msg" });
    insertMessage(db, { contactId: contact2, platform: "telegram", direction: "incoming", content: "TG msg" });

    const result = getMessages(db, { contactId });
    expect(result.messages.length).toBe(1);
    expect(result.messages[0].content).toBe("WA msg");
  });
});
