import { describe, test, expect, beforeEach, mock } from "bun:test";
import { Database } from "bun:sqlite";
import type { HubEvent } from "../src/types";
import { initializeSchema } from "../src/db/schema";

// -- Inline event store functions (mirrors the contract from db/events.ts) --
// These are used both as the mock implementations and for verification queries.

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

  if (opts.type) {
    conditions.push("type = ?");
    params.push(opts.type);
  }
  if (opts.source) {
    conditions.push("source = ?");
    params.push(opts.source);
  }
  if (opts.since) {
    conditions.push("created_at >= ?");
    params.push(opts.since);
  }

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
  if (opts?.type) {
    conditions.push("type = ?");
    params.push(opts.type);
  }
  if (opts?.source) {
    conditions.push("source = ?");
    params.push(opts.source);
  }
  const where = conditions.length > 0 ? `WHERE ${conditions.join(" AND ")}` : "";
  const row = db.query(`SELECT COUNT(*) as cnt FROM events ${where}`).get(...params) as { cnt: number };
  return row.cnt;
}

// Mock the db/events module so EventBus resolves its imports
// Include getEventById to avoid breaking event-store.test.ts which imports from the same module
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

// Import EventBus AFTER mocking
const { EventBus } = await import("../src/services/event-bus");

function createTestDb(): Database {
  const db = new Database(":memory:");
  db.exec("PRAGMA foreign_keys = ON");
  initializeSchema(db);
  return db;
}

describe("EventBus", () => {
  let db: Database;
  let broadcasts: Array<{ type: string; data: Record<string, unknown> }>;
  let mockSse: { broadcast: (event: { type: string; data: Record<string, unknown> }) => void };

  beforeEach(() => {
    db = createTestDb();
    broadcasts = [];
    mockSse = {
      broadcast: (event) => {
        broadcasts.push(event);
      },
    };
  });

  describe("publish", () => {
    test("stores event and returns it with id and timestamp", () => {
      const bus = new EventBus(db, mockSse);
      const result = bus.publish({
        type: "test.event",
        source: "unit-test",
        data: { key: "value" },
      });

      expect(result.id).toBeDefined();
      expect(result.id).toMatch(/^[0-9a-f]{8}-/);
      expect(result.type).toBe("test.event");
      expect(result.source).toBe("unit-test");
      expect(result.timestamp).toBeDefined();
      expect(result.data).toEqual({ key: "value" });
    });

    test("event is retrievable via query after publish", () => {
      const bus = new EventBus(db, mockSse);
      const published = bus.publish({
        type: "test.stored",
        source: "unit-test",
        data: { stored: true },
      });

      const result = bus.query({ type: "test.stored" });
      expect(result.total).toBe(1);
      expect(result.events[0].id).toBe(published.id);
      expect(result.events[0].data).toEqual({ stored: true });
    });

    test("broadcasts SSE with correct format: { type: 'hub_event', data: { payload: ... } }", () => {
      const bus = new EventBus(db, mockSse);
      bus.publish({
        type: "test.broadcast",
        source: "unit-test",
        data: { msg: "hello" },
      });

      expect(broadcasts).toHaveLength(1);
      expect(broadcasts[0].type).toBe("hub_event");
      expect(broadcasts[0].data).toHaveProperty("payload");
      const payload = broadcasts[0].data.payload as HubEvent;
      expect(payload.type).toBe("test.broadcast");
      expect(payload.source).toBe("unit-test");
      expect(payload.data).toEqual({ msg: "hello" });
    });

    test("assigns default type and source when not provided", () => {
      const bus = new EventBus(db, mockSse);
      const result = bus.publish({ data: { x: 1 } });
      expect(result.type).toBe("unknown");
      expect(result.source).toBe("unknown");
    });

    test("preserves metadata when provided", () => {
      const bus = new EventBus(db, mockSse);
      const result = bus.publish({
        type: "test.meta",
        source: "unit-test",
        data: {},
        metadata: { severity: "warning", tags: ["test"] },
      });

      expect(result.metadata).toEqual({ severity: "warning", tags: ["test"] });
    });
  });

  describe("chain depth guard", () => {
    test("stores but does NOT broadcast when chain_depth >= 5", () => {
      const bus = new EventBus(db, mockSse);
      const result = bus.publish({
        type: "chain.deep",
        source: "unit-test",
        data: { step: 5 },
        metadata: { chain_depth: 5 },
      });

      // Should be stored
      expect(result.id).toBeDefined();
      const stored = bus.query({ type: "chain.deep" });
      expect(stored.total).toBe(1);

      // Should NOT be broadcast
      expect(broadcasts).toHaveLength(0);
    });

    test("stores but does NOT broadcast when chain_depth > 5", () => {
      const bus = new EventBus(db, mockSse);
      bus.publish({
        type: "chain.deeper",
        source: "unit-test",
        data: {},
        metadata: { chain_depth: 10 },
      });

      expect(broadcasts).toHaveLength(0);
    });

    test("broadcasts normally when chain_depth < 5", () => {
      const bus = new EventBus(db, mockSse);
      bus.publish({
        type: "chain.ok",
        source: "unit-test",
        data: {},
        metadata: { chain_depth: 4 },
      });

      expect(broadcasts).toHaveLength(1);
    });

    test("broadcasts normally when chain_depth is 0", () => {
      const bus = new EventBus(db, mockSse);
      bus.publish({
        type: "chain.zero",
        source: "unit-test",
        data: {},
        metadata: { chain_depth: 0 },
      });

      expect(broadcasts).toHaveLength(1);
    });

    test("broadcasts normally when no metadata", () => {
      const bus = new EventBus(db, mockSse);
      bus.publish({
        type: "no.meta",
        source: "unit-test",
        data: {},
      });

      expect(broadcasts).toHaveLength(1);
    });
  });

  describe("duplicate detection", () => {
    test("second publish with same data within 1s is deduplicated", () => {
      const bus = new EventBus(db, mockSse);

      const first = bus.publish({
        type: "dup.test",
        source: "unit-test",
        data: { key: "same" },
      });

      const second = bus.publish({
        type: "dup.test",
        source: "unit-test",
        data: { key: "same" },
      });

      // First should store and broadcast
      expect(first.id).toBeDefined();
      expect((first as any)._deduplicated).toBeUndefined();

      // Second should be deduplicated
      expect((second as any)._deduplicated).toBe(true);

      // Only one broadcast
      expect(broadcasts).toHaveLength(1);

      // Only one stored event
      const stored = bus.query({ type: "dup.test" });
      expect(stored.total).toBe(1);
    });

    test("different data is NOT deduplicated", () => {
      const bus = new EventBus(db, mockSse);

      bus.publish({
        type: "dup.diff",
        source: "unit-test",
        data: { key: "value1" },
      });

      bus.publish({
        type: "dup.diff",
        source: "unit-test",
        data: { key: "value2" },
      });

      expect(broadcasts).toHaveLength(2);

      const stored = bus.query({ type: "dup.diff" });
      expect(stored.total).toBe(2);
    });

    test("duplicate detection uses sorted keys (order independent)", () => {
      const bus = new EventBus(db, mockSse);

      bus.publish({
        type: "dup.order",
        source: "unit-test",
        data: { a: 1, b: 2 },
      });

      bus.publish({
        type: "dup.order",
        source: "unit-test",
        data: { b: 2, a: 1 },
      });

      // Should be deduplicated because sorted keys produce the same hash
      expect(broadcasts).toHaveLength(1);
    });

    test("duplicate allowed after window expires", async () => {
      // We test this by creating a bus, publishing, then manually manipulating
      // the dedup map timestamp to simulate window expiration
      const bus = new EventBus(db, mockSse);

      bus.publish({
        type: "dup.expire",
        source: "unit-test",
        data: { key: "timed" },
      });

      // Access internal dedup map and backdate the timestamp
      const dedupeMap = (bus as any).dedupeMap as Map<string, number>;
      for (const [key] of dedupeMap) {
        dedupeMap.set(key, Date.now() - 2000); // 2s ago, past the 1s window
      }

      const second = bus.publish({
        type: "dup.expire",
        source: "unit-test",
        data: { key: "timed" },
      });

      expect((second as any)._deduplicated).toBeUndefined();
      expect(broadcasts).toHaveLength(2);
    });
  });

  describe("prune", () => {
    test("delegates to pruneEvents and returns count", () => {
      const bus = new EventBus(db, mockSse);

      // Insert an event with a timestamp far in the past
      db.run(
        `INSERT INTO events (id, type, source, data, created_at) VALUES (?, ?, ?, ?, ?)`,
        ["old-1", "old.event", "test", "{}", "2020-01-01T00:00:00.000Z"]
      );
      db.run(
        `INSERT INTO events (id, type, source, data, created_at) VALUES (?, ?, ?, ?, ?)`,
        ["old-2", "old.event", "test", "{}", "2020-01-02T00:00:00.000Z"]
      );

      // Publish a fresh one
      bus.publish({ type: "fresh.event", source: "test", data: {} });

      const pruned = bus.prune(30);
      expect(pruned).toBe(2);

      // Fresh event should remain
      const remaining = bus.query({});
      expect(remaining.total).toBe(1);
      expect(remaining.events[0].type).toBe("fresh.event");
    });
  });

  describe("query", () => {
    test("delegates to queryEvents with filters", () => {
      const bus = new EventBus(db, mockSse);

      bus.publish({ type: "alpha", source: "src-a", data: { n: 1 } });
      bus.publish({ type: "beta", source: "src-b", data: { n: 2 } });
      bus.publish({ type: "alpha", source: "src-a", data: { n: 3 } });

      const byType = bus.query({ type: "alpha" });
      expect(byType.total).toBe(2);
      expect(byType.events.every((e) => e.type === "alpha")).toBe(true);

      const bySource = bus.query({ source: "src-b" });
      expect(bySource.total).toBe(1);
      expect(bySource.events[0].type).toBe("beta");
    });

    test("returns empty result when no events match", () => {
      const bus = new EventBus(db, mockSse);
      const result = bus.query({ type: "nonexistent" });
      expect(result.total).toBe(0);
      expect(result.events).toEqual([]);
    });
  });

  describe("count", () => {
    test("delegates to countEvents", () => {
      const bus = new EventBus(db, mockSse);

      bus.publish({ type: "count.a", source: "test", data: { n: 1 } });
      bus.publish({ type: "count.b", source: "test", data: { n: 2 } });
      bus.publish({ type: "count.a", source: "test2", data: { n: 3 } });

      expect(bus.count()).toBe(3);
      expect(bus.count({ type: "count.a" })).toBe(2);
      expect(bus.count({ source: "test2" })).toBe(1);
    });
  });

  describe("constructor", () => {
    test("uses default sseManager when no sse param provided", () => {
      // Just verify construction doesn't throw
      const bus = new EventBus(db);
      expect(bus).toBeDefined();
    });
  });
});
