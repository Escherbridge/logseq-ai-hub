import { Database } from "bun:sqlite";
import { initializeSchema } from "../src/db/schema";

export function createTestDb(): Database {
  const db = new Database(":memory:");
  db.exec("PRAGMA foreign_keys = ON");
  initializeSchema(db);
  return db;
}

export function seedTestContact(db: Database, platform = "whatsapp", userId = "15551234567", name = "Test User") {
  const id = `${platform}:${userId}`;
  db.run(
    `INSERT INTO contacts (id, platform, platform_user_id, display_name) VALUES (?, ?, ?, ?)`,
    [id, platform, userId, name]
  );
  return id;
}

export function seedTestMessage(db: Database, contactId: string, content = "Hello", direction = "incoming") {
  const result = db.run(
    `INSERT INTO messages (contact_id, platform, direction, content) VALUES (?, ?, ?, ?)`,
    [contactId, contactId.split(":")[0], direction, content]
  );
  return Number(result.lastInsertRowid);
}
