import { describe, it, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb } from "./helpers";
import { handleSendMessage } from "../src/routes/api/send";
import { loadConfig } from "../src/config";

describe("Send API", () => {
  let db: Database;
  const config = { ...loadConfig(), pluginApiToken: "test-secret" };

  beforeEach(() => {
    db = createTestDb();
  });

  it("should reject unauthorized requests", async () => {
    const req = new Request("http://localhost/api/send", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        platform: "whatsapp",
        recipient: "123",
        content: "hi",
      }),
    });
    const res = await handleSendMessage(req, config, db);
    expect(res.status).toBe(401);
  });

  it("should reject missing fields", async () => {
    const req = new Request("http://localhost/api/send", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer test-secret",
      },
      body: JSON.stringify({ platform: "whatsapp" }),
    });
    const res = await handleSendMessage(req, config, db);
    expect(res.status).toBe(400);
  });

  it("should reject invalid platform", async () => {
    const req = new Request("http://localhost/api/send", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer test-secret",
      },
      body: JSON.stringify({
        platform: "discord",
        recipient: "123",
        content: "hi",
      }),
    });
    const res = await handleSendMessage(req, config, db);
    expect(res.status).toBe(400);
  });
});
