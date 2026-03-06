import { describe, test, expect, beforeEach, mock } from "bun:test";
import { Database } from "bun:sqlite";
import type { HubEvent } from "../src/types";
import type { RouteContext } from "../src/router";
import type { Config } from "../src/config";
import { initializeSchema } from "../src/db/schema";

// -- Inline event store functions (same pattern as event-bus.test.ts) --

function insertEvent(db: Database, event: HubEvent): HubEvent {
  db.run(
    `INSERT INTO events (id, type, source, data, metadata, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
    [
      event.id,
      event.type,
      event.source,
      JSON.stringify(event.data),
      event.metadata ? JSON.stringify(event.metadata) : null,
      event.timestamp,
    ]
  );
  return event;
}

function queryEvents(
  db: Database,
  opts: { type?: string; source?: string; since?: string; limit?: number; offset?: number }
): { events: HubEvent[]; total: number } {
  const conditions: string[] = [];
  const params: unknown[] = [];

  if (opts.type) { conditions.push("type = ?"); params.push(opts.type); }
  if (opts.source) { conditions.push("source = ?"); params.push(opts.source); }
  if (opts.since) { conditions.push("created_at >= ?"); params.push(opts.since); }

  const where = conditions.length > 0 ? `WHERE ${conditions.join(" AND ")}` : "";
  const countRow = db.query(`SELECT COUNT(*) as cnt FROM events ${where}`).get(...params) as { cnt: number };
  const total = countRow.cnt;

  const limit = opts.limit || 50;
  const offset = opts.offset || 0;
  const rows = db.query(`SELECT * FROM events ${where} ORDER BY created_at DESC LIMIT ? OFFSET ?`).all(...params, limit, offset) as Array<{
    id: string; type: string; source: string; data: string; metadata: string | null; created_at: string;
  }>;

  const events: HubEvent[] = rows.map((r) => ({
    id: r.id,
    type: r.type,
    source: r.source,
    timestamp: r.created_at,
    data: JSON.parse(r.data),
    ...(r.metadata ? { metadata: JSON.parse(r.metadata) } : {}),
  }));

  return { events, total };
}

function pruneEvents(db: Database, retentionDays: number): number {
  const cutoff = new Date(Date.now() - retentionDays * 24 * 60 * 60 * 1000).toISOString();
  const result = db.run(`DELETE FROM events WHERE created_at < ?`, [cutoff]);
  return result.changes;
}

function countEvents(db: Database, opts?: { type?: string; source?: string }): number {
  const conditions: string[] = [];
  const params: unknown[] = [];
  if (opts?.type) { conditions.push("type = ?"); params.push(opts.type); }
  if (opts?.source) { conditions.push("source = ?"); params.push(opts.source); }
  const where = conditions.length > 0 ? `WHERE ${conditions.join(" AND ")}` : "";
  const row = db.query(`SELECT COUNT(*) as cnt FROM events ${where}`).get(...params) as { cnt: number };
  return row.cnt;
}

mock.module("../src/db/events", () => ({
  insertEvent,
  queryEvents,
  pruneEvents,
  countEvents,
  getEventById: (db: Database, id: string) => {
    const row = db.query(`SELECT id, type, source, data, metadata, created_at FROM events WHERE id = ?`).get(id) as any;
    if (!row) return null;
    return { id: row.id, type: row.type, source: row.source, timestamp: row.created_at, data: JSON.parse(row.data), ...(row.metadata ? { metadata: JSON.parse(row.metadata) } : {}) };
  },
}));

// Import after mocking
const { EventBus } = await import("../src/services/event-bus");
const { handlePublishEvent, handleQueryEvents } = await import("../src/routes/api/events");

const TEST_TOKEN = "test-api-token";

function createTestConfig(): Config {
  return {
    port: 3000,
    whatsappVerifyToken: "",
    whatsappAccessToken: "",
    whatsappPhoneNumberId: "",
    telegramBotToken: "",
    pluginApiToken: TEST_TOKEN,
    databasePath: ":memory:",
    llmApiKey: "",
    llmEndpoint: "",
    agentModel: "",
    agentRequestTimeout: 30000,
    sessionMessageLimit: 50,
    eventRetentionDays: 30,
    httpAllowlist: [],
  };
}

function createTestDb(): Database {
  const db = new Database(":memory:");
  db.exec("PRAGMA foreign_keys = ON");
  initializeSchema(db);
  return db;
}

function createTestContext(db: Database): RouteContext {
  const mockSse = { broadcast: () => {} };
  const eventBus = new EventBus(db, mockSse);
  return {
    config: createTestConfig(),
    db,
    eventBus,
  };
}

function authRequest(url: string, opts: RequestInit = {}): Request {
  return new Request(url, {
    ...opts,
    headers: {
      ...((opts.headers as Record<string, string>) || {}),
      Authorization: `Bearer ${TEST_TOKEN}`,
    },
  });
}

function noAuthRequest(url: string, opts: RequestInit = {}): Request {
  return new Request(url, opts);
}

describe("handlePublishEvent", () => {
  let db: Database;
  let ctx: RouteContext;

  beforeEach(() => {
    db = createTestDb();
    ctx = createTestContext(db);
  });

  test("publishes event with valid auth and stores it", async () => {
    const req = authRequest("http://localhost/api/events/publish", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        type: "test.created",
        source: "plugin",
        data: { key: "value" },
      }),
    });

    const res = await handlePublishEvent(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(200);
    expect(body.success).toBe(true);
    expect(body.eventId).toBeDefined();
    expect(typeof body.eventId).toBe("string");

    // Verify stored in DB
    const stored = ctx.eventBus!.query({ type: "test.created" });
    expect(stored.total).toBe(1);
    expect(stored.events[0].id).toBe(body.eventId);
  });

  test("returns 401 without auth", async () => {
    const req = noAuthRequest("http://localhost/api/events/publish", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        type: "test.event",
        source: "plugin",
        data: {},
      }),
    });

    const res = await handlePublishEvent(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(401);
    expect(body.success).toBe(false);
    expect(body.error).toBe("Unauthorized");
  });

  test("returns 400 with missing type", async () => {
    const req = authRequest("http://localhost/api/events/publish", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        source: "plugin",
        data: { key: "value" },
      }),
    });

    const res = await handlePublishEvent(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(400);
    expect(body.success).toBe(false);
    expect(body.error).toContain("type");
  });

  test("returns 400 with missing source", async () => {
    const req = authRequest("http://localhost/api/events/publish", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        type: "test.event",
        data: { key: "value" },
      }),
    });

    const res = await handlePublishEvent(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(400);
    expect(body.success).toBe(false);
    expect(body.error).toContain("source");
  });

  test("returns 400 with missing data", async () => {
    const req = authRequest("http://localhost/api/events/publish", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        type: "test.event",
        source: "plugin",
      }),
    });

    const res = await handlePublishEvent(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(400);
    expect(body.success).toBe(false);
    expect(body.error).toContain("data");
  });

  test("preserves metadata when provided", async () => {
    const req = authRequest("http://localhost/api/events/publish", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        type: "test.meta",
        source: "plugin",
        data: { info: "test" },
        metadata: { severity: "warning", tags: ["important"] },
      }),
    });

    const res = await handlePublishEvent(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(200);
    expect(body.success).toBe(true);

    const stored = ctx.eventBus!.query({ type: "test.meta" });
    expect(stored.events[0].metadata).toBeDefined();
    expect(stored.events[0].metadata!.severity).toBe("warning");
    expect(stored.events[0].metadata!.tags).toEqual(["important"]);
  });
});

describe("handleQueryEvents", () => {
  let db: Database;
  let ctx: RouteContext;

  beforeEach(() => {
    db = createTestDb();
    ctx = createTestContext(db);

    // Seed events with small delays in timestamps to ensure ordering
    ctx.eventBus!.publish({ type: "job.created", source: "runner", data: { n: 1 } });
    ctx.eventBus!.publish({ type: "job.completed", source: "runner", data: { n: 2 } });
    ctx.eventBus!.publish({ type: "webhook.received", source: "webhook:github", data: { n: 3 } });
    ctx.eventBus!.publish({ type: "job.created", source: "runner", data: { n: 4 } });
    ctx.eventBus!.publish({ type: "webhook.received", source: "webhook:stripe", data: { n: 5 } });
  });

  test("returns paginated results", async () => {
    const req = authRequest("http://localhost/api/events?limit=2&offset=0");
    const res = await handleQueryEvents(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(200);
    expect(body.success).toBe(true);
    expect(body.events).toHaveLength(2);
    expect(body.total).toBe(5);
  });

  test("filters by type", async () => {
    const req = authRequest("http://localhost/api/events?type=job.created");
    const res = await handleQueryEvents(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(200);
    expect(body.success).toBe(true);
    expect(body.total).toBe(2);
    expect(body.events.every((e: HubEvent) => e.type === "job.created")).toBe(true);
  });

  test("filters by source", async () => {
    const req = authRequest("http://localhost/api/events?source=webhook:github");
    const res = await handleQueryEvents(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(200);
    expect(body.success).toBe(true);
    expect(body.total).toBe(1);
    expect(body.events[0].source).toBe("webhook:github");
  });

  test("respects limit and offset", async () => {
    const req = authRequest("http://localhost/api/events?limit=2&offset=2");
    const res = await handleQueryEvents(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(200);
    expect(body.success).toBe(true);
    expect(body.events).toHaveLength(2);
    expect(body.total).toBe(5);
  });

  test("clamps limit to max 200", async () => {
    const req = authRequest("http://localhost/api/events?limit=999");
    const res = await handleQueryEvents(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(200);
    expect(body.success).toBe(true);
    // Should return all 5, since 5 < 200
    expect(body.events).toHaveLength(5);
  });

  test("returns 401 without auth", async () => {
    const req = noAuthRequest("http://localhost/api/events");
    const res = await handleQueryEvents(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(401);
    expect(body.success).toBe(false);
  });

  test("returns empty result when no events match filter", async () => {
    const req = authRequest("http://localhost/api/events?type=nonexistent");
    const res = await handleQueryEvents(req, ctx);
    const body = await res.json();

    expect(res.status).toBe(200);
    expect(body.success).toBe(true);
    expect(body.total).toBe(0);
    expect(body.events).toEqual([]);
  });
});
