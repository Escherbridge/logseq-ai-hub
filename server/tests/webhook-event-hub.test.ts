import { describe, test, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { initializeSchema } from "../src/db/schema";
import { EventBus } from "../src/services/event-bus";
import type { RouteContext } from "../src/router";
import type { Config } from "../src/config";
import {
  handleEventWebhook,
  handleEventWebhookVerify,
  _resetRateLimits,
} from "../src/routes/webhooks/event-hub";

function createTestDb(): Database {
  const db = new Database(":memory:");
  db.exec("PRAGMA foreign_keys = ON");
  initializeSchema(db);
  return db;
}

function createMockCtx(db: Database): RouteContext {
  const broadcasts: Array<{ type: string; data: Record<string, unknown> }> = [];
  const mockSse = {
    broadcast: (event: { type: string; data: Record<string, unknown> }) => {
      broadcasts.push(event);
    },
  };
  const eventBus = new EventBus(db, mockSse);
  return {
    config: {} as Config,
    db,
    eventBus,
  };
}

function makePostRequest(
  source: string,
  body: unknown,
  headers?: Record<string, string>
): Request {
  const bodyStr = typeof body === "string" ? body : JSON.stringify(body);
  return new Request(`http://localhost/webhook/event/${source}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...headers,
    },
    body: bodyStr,
  });
}

function makeGetRequest(source: string, queryParams?: string): Request {
  const qs = queryParams ? `?${queryParams}` : "";
  return new Request(`http://localhost/webhook/event/${source}${qs}`, {
    method: "GET",
  });
}

describe("Event Hub Webhook", () => {
  let db: Database;
  let ctx: RouteContext;

  beforeEach(() => {
    db = createTestDb();
    ctx = createMockCtx(db);
    _resetRateLimits();
  });

  describe("handleEventWebhook (POST)", () => {
    test("valid POST stores event and returns eventId", async () => {
      const req = makePostRequest("github", { action: "push", repo: "test" });
      const res = await handleEventWebhook(req, ctx, { source: "github" });

      expect(res.status).toBe(200);
      const json = (await res.json()) as { success: boolean; eventId: string };
      expect(json.success).toBe(true);
      expect(json.eventId).toBeDefined();
      expect(json.eventId).toMatch(/^[0-9a-f]{8}-/);

      // Verify event was stored in the database
      const row = db
        .query("SELECT * FROM events WHERE id = ?")
        .get(json.eventId) as {
        id: string;
        type: string;
        source: string;
        data: string;
      };
      expect(row).not.toBeNull();
      expect(row.type).toBe("webhook.received");
      expect(row.source).toBe("webhook:github");
      expect(JSON.parse(row.data)).toEqual({ action: "push", repo: "test" });
    });

    test("invalid source name with special chars returns 400", async () => {
      const req = makePostRequest("bad_source!", { data: "test" });
      const res = await handleEventWebhook(req, ctx, {
        source: "bad_source!",
      });

      expect(res.status).toBe(400);
      const json = (await res.json()) as { success: boolean; error: string };
      expect(json.success).toBe(false);
      expect(json.error).toContain("Invalid source name");
    });

    test("valid source names with hyphens work", async () => {
      const req = makePostRequest("my-webhook-source", { ok: true });
      const res = await handleEventWebhook(req, ctx, {
        source: "my-webhook-source",
      });

      expect(res.status).toBe(200);
      const json = (await res.json()) as { success: boolean; eventId: string };
      expect(json.success).toBe(true);
      expect(json.eventId).toBeDefined();

      // Verify stored with correct source prefix
      const row = db
        .query("SELECT source FROM events WHERE id = ?")
        .get(json.eventId) as { source: string };
      expect(row.source).toBe("webhook:my-webhook-source");
    });

    test("rate limit: 101st request within 60s returns 429", async () => {
      // Send 100 requests (all should succeed)
      for (let i = 0; i < 100; i++) {
        const req = makePostRequest("rate-test", { i });
        const res = await handleEventWebhook(req, ctx, {
          source: "rate-test",
        });
        expect(res.status).toBe(200);
      }

      // 101st request should be rate limited
      const req = makePostRequest("rate-test", { i: 100 });
      const res = await handleEventWebhook(req, ctx, { source: "rate-test" });

      expect(res.status).toBe(429);
      const json = (await res.json()) as { success: boolean; error: string };
      expect(json.success).toBe(false);
      expect(json.error).toContain("Rate limit");
      expect(res.headers.get("Retry-After")).toBeDefined();
      const retryAfter = parseInt(res.headers.get("Retry-After")!, 10);
      expect(retryAfter).toBeGreaterThan(0);
      expect(retryAfter).toBeLessThanOrEqual(60);
    });

    test("malformed JSON body returns 400", async () => {
      const req = new Request("http://localhost/webhook/event/github", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{ not valid json",
      });
      const res = await handleEventWebhook(req, ctx, { source: "github" });

      expect(res.status).toBe(400);
      const json = (await res.json()) as { success: boolean; error: string };
      expect(json.success).toBe(false);
      expect(json.error).toContain("Invalid JSON");
    });

    test("oversized content-length returns 413", async () => {
      const req = makePostRequest("github", { data: "test" }, {
        "content-length": "2000000",
      });
      const res = await handleEventWebhook(req, ctx, { source: "github" });

      expect(res.status).toBe(413);
      const json = (await res.json()) as { success: boolean; error: string };
      expect(json.success).toBe(false);
      expect(json.error).toContain("too large");
    });

    test("source with underscores is rejected", async () => {
      const req = makePostRequest("bad_source", { data: "test" });
      const res = await handleEventWebhook(req, ctx, {
        source: "bad_source",
      });

      expect(res.status).toBe(400);
    });

    test("source with uppercase letters is accepted", async () => {
      const req = makePostRequest("GitHub", { data: "test" });
      const res = await handleEventWebhook(req, ctx, { source: "GitHub" });

      expect(res.status).toBe(200);
    });

    test("rate limit applies per source independently", async () => {
      // Fill up source-a
      for (let i = 0; i < 100; i++) {
        const req = makePostRequest("source-a", { i });
        await handleEventWebhook(req, ctx, { source: "source-a" });
      }

      // source-a should be rate limited
      const reqA = makePostRequest("source-a", { i: 100 });
      const resA = await handleEventWebhook(reqA, ctx, { source: "source-a" });
      expect(resA.status).toBe(429);

      // source-b should still work
      const reqB = makePostRequest("source-b", { i: 0 });
      const resB = await handleEventWebhook(reqB, ctx, { source: "source-b" });
      expect(resB.status).toBe(200);
    });
  });

  describe("handleEventWebhookVerify (GET)", () => {
    test("GET with hub.challenge echoes it back", async () => {
      const req = makeGetRequest("github", "hub.challenge=abc123");
      const res = handleEventWebhookVerify(req, ctx, { source: "github" });

      expect(res.status).toBe(200);
      const json = (await res.json()) as { challenge: string };
      expect(json.challenge).toBe("abc123");
    });

    test("GET without challenge returns { status: 'ok' }", async () => {
      const req = makeGetRequest("github");
      const res = handleEventWebhookVerify(req, ctx, { source: "github" });

      expect(res.status).toBe(200);
      const json = (await res.json()) as { status: string };
      expect(json.status).toBe("ok");
    });
  });
});
