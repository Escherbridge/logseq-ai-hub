import { describe, test, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb } from "./helpers";
import {
  insertEvent,
  getEventById,
  queryEvents,
  pruneEvents,
  countEvents,
} from "../src/db/events";
import type { HubEvent } from "../src/types";

function makeEvent(overrides: Partial<HubEvent> = {}): HubEvent {
  return {
    id: overrides.id ?? `evt-${Math.random().toString(36).slice(2, 10)}`,
    type: overrides.type ?? "test.event",
    source: overrides.source ?? "test-source",
    timestamp: overrides.timestamp ?? new Date().toISOString(),
    data: overrides.data ?? { key: "value" },
    ...(overrides.metadata !== undefined ? { metadata: overrides.metadata } : {}),
  };
}

describe("Event Store", () => {
  let db: Database;

  beforeEach(() => {
    db = createTestDb();
  });

  test("insert and retrieve event by ID", () => {
    const event = makeEvent({
      id: "evt-001",
      type: "job.completed",
      source: "runner",
      data: { jobId: "j1", result: "success" },
      metadata: { severity: "info", tags: ["jobs"] },
    });

    const inserted = insertEvent(db, event);
    expect(inserted.id).toBe("evt-001");
    expect(inserted.type).toBe("job.completed");
    expect(inserted.source).toBe("runner");
    expect(inserted.data).toEqual({ jobId: "j1", result: "success" });
    expect(inserted.metadata).toEqual({ severity: "info", tags: ["jobs"] });

    const retrieved = getEventById(db, "evt-001");
    expect(retrieved).not.toBeNull();
    expect(retrieved!.id).toBe("evt-001");
    expect(retrieved!.type).toBe("job.completed");
    expect(retrieved!.data).toEqual({ jobId: "j1", result: "success" });
    expect(retrieved!.metadata).toEqual({ severity: "info", tags: ["jobs"] });
  });

  test("query by type filter", () => {
    insertEvent(db, makeEvent({ id: "e1", type: "job.created" }));
    insertEvent(db, makeEvent({ id: "e2", type: "job.completed" }));
    insertEvent(db, makeEvent({ id: "e3", type: "job.created" }));

    const result = queryEvents(db, { type: "job.created" });
    expect(result.events.length).toBe(2);
    expect(result.total).toBe(2);
    expect(result.events.every((e) => e.type === "job.created")).toBe(true);
  });

  test("query by source filter", () => {
    insertEvent(db, makeEvent({ id: "e1", source: "webhook:github" }));
    insertEvent(db, makeEvent({ id: "e2", source: "webhook:stripe" }));
    insertEvent(db, makeEvent({ id: "e3", source: "webhook:github" }));

    const result = queryEvents(db, { source: "webhook:github" });
    expect(result.events.length).toBe(2);
    expect(result.total).toBe(2);
    expect(result.events.every((e) => e.source === "webhook:github")).toBe(true);
  });

  test("query by since filter", () => {
    // Insert events with specific timestamps
    insertEvent(
      db,
      makeEvent({ id: "old", timestamp: "2025-01-01T00:00:00.000Z" })
    );
    insertEvent(
      db,
      makeEvent({ id: "new1", timestamp: "2026-03-01T00:00:00.000Z" })
    );
    insertEvent(
      db,
      makeEvent({ id: "new2", timestamp: "2026-03-02T00:00:00.000Z" })
    );

    const result = queryEvents(db, { since: "2026-01-01T00:00:00.000Z" });
    expect(result.events.length).toBe(2);
    expect(result.total).toBe(2);
    const ids = result.events.map((e) => e.id);
    expect(ids).toContain("new1");
    expect(ids).toContain("new2");
  });

  test("pagination with limit and offset returns correct total", () => {
    for (let i = 0; i < 10; i++) {
      insertEvent(
        db,
        makeEvent({
          id: `e${i.toString().padStart(2, "0")}`,
          timestamp: `2026-03-01T00:00:${i.toString().padStart(2, "0")}.000Z`,
        })
      );
    }

    const page1 = queryEvents(db, { limit: 3, offset: 0 });
    expect(page1.events.length).toBe(3);
    expect(page1.total).toBe(10);

    const page2 = queryEvents(db, { limit: 3, offset: 3 });
    expect(page2.events.length).toBe(3);
    expect(page2.total).toBe(10);

    const page4 = queryEvents(db, { limit: 3, offset: 9 });
    expect(page4.events.length).toBe(1);
    expect(page4.total).toBe(10);
  });

  test("prune removes old events", () => {
    // Insert an old event by directly using SQL with a past date
    db.run(
      `INSERT INTO events (id, type, source, data, created_at) VALUES (?, ?, ?, ?, ?)`,
      ["old-evt", "test.old", "source", '{"old":true}', "2020-01-01T00:00:00.000Z"]
    );
    insertEvent(db, makeEvent({ id: "recent-evt" }));

    const removed = pruneEvents(db, 30);
    expect(removed).toBe(1);

    expect(getEventById(db, "old-evt")).toBeNull();
    expect(getEventById(db, "recent-evt")).not.toBeNull();
  });

  test("prune keeps recent events", () => {
    insertEvent(db, makeEvent({ id: "recent1" }));
    insertEvent(db, makeEvent({ id: "recent2" }));

    const removed = pruneEvents(db, 30);
    expect(removed).toBe(0);

    expect(getEventById(db, "recent1")).not.toBeNull();
    expect(getEventById(db, "recent2")).not.toBeNull();
  });

  test("count with no filters", () => {
    insertEvent(db, makeEvent({ id: "e1" }));
    insertEvent(db, makeEvent({ id: "e2" }));
    insertEvent(db, makeEvent({ id: "e3" }));

    expect(countEvents(db)).toBe(3);
  });

  test("count with type filter", () => {
    insertEvent(db, makeEvent({ id: "e1", type: "job.created" }));
    insertEvent(db, makeEvent({ id: "e2", type: "job.completed" }));
    insertEvent(db, makeEvent({ id: "e3", type: "job.created" }));

    expect(countEvents(db, { type: "job.created" })).toBe(2);
    expect(countEvents(db, { type: "job.completed" })).toBe(1);
  });

  test("count with source filter", () => {
    insertEvent(db, makeEvent({ id: "e1", source: "runner" }));
    insertEvent(db, makeEvent({ id: "e2", source: "webhook:gh" }));

    expect(countEvents(db, { source: "runner" })).toBe(1);
  });

  test("getEventById returns null for missing event", () => {
    expect(getEventById(db, "nonexistent")).toBeNull();
  });

  test("event without metadata stores and retrieves correctly", () => {
    const event = makeEvent({ id: "no-meta", data: { simple: true } });
    const inserted = insertEvent(db, event);
    expect(inserted.metadata).toBeUndefined();

    const retrieved = getEventById(db, "no-meta");
    expect(retrieved).not.toBeNull();
    expect(retrieved!.metadata).toBeUndefined();
    expect(retrieved!.data).toEqual({ simple: true });
  });
});
