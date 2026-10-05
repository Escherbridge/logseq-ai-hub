import { Database } from "bun:sqlite";
import { initializeSchema } from "../src/db/schema";
import { createSession, addSessionMessage } from "../src/db/sessions";
import type { Session, SessionContext, SessionMessage } from "../src/types/session";
import type { Config } from "../src/config";

/**
 * Complete, inert Config for tests. Pass overrides for the fields a given
 * test actually cares about -- every other field gets a safe default, so
 * adding a field to Config breaks this one factory instead of every test
 * file that hand-rolls a Config literal.
 */
export function makeTestConfig(overrides: Partial<Config> = {}): Config {
  return {
    port: 3000,
    whatsappVerifyToken: "",
    whatsappAccessToken: "",
    whatsappPhoneNumberId: "",
    telegramBotToken: "",
    pluginApiToken: "test-token",
    databasePath: ":memory:",
    llmApiKey: "",
    llmEndpoint: "",
    agentModel: "",
    agentRequestTimeout: 30000,
    sessionMessageLimit: 50,
    eventRetentionDays: 30,
    httpAllowlist: [],
    listLimitMax: 100,
    webhookSecret: "",
    llmHttpReferer: "",
    llmTitle: "",
    // "" so callers fall back to their own http://localhost:${port} default
    // (see tests/mcp-transport.test.ts, which asserts exactly that fallback).
    baseUrl: "",
    ...overrides,
  };
}

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

export function seedTestSession(
  db: Database,
  agentId = "test-agent",
  name = "Test Session",
  context?: SessionContext
): Session {
  return createSession(db, { agent_id: agentId, name, context });
}

export function seedTestSessionMessage(
  db: Database,
  sessionId: string,
  role: SessionMessage["role"] = "user",
  content = "Test message"
): SessionMessage {
  return addSessionMessage(db, { session_id: sessionId, role, content });
}
