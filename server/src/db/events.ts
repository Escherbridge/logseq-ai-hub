import { Database } from "bun:sqlite";
import type { HubEvent } from "../types";

export function insertEvent(db: Database, event: HubEvent): HubEvent {
  db.run(
    `INSERT INTO events (id, type, source, data, metadata, created_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
    [
      event.id,
      event.type,
      event.source,
      JSON.stringify(event.data),
      event.metadata ? JSON.stringify(event.metadata) : null,
      event.timestamp,
    ]
  );

  return getEventById(db, event.id)!;
}

interface QueryEventsOpts {
  type?: string;
  source?: string;
  since?: string;
  limit?: number;
  offset?: number;
}

export function queryEvents(
  db: Database,
  opts: QueryEventsOpts = {}
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

  const whereClause =
    conditions.length > 0 ? `WHERE ${conditions.join(" AND ")}` : "";

  const countRow = db
    .query(`SELECT COUNT(*) as count FROM events ${whereClause}`)
    .get(...params) as { count: number };
  const total = countRow.count;

  const limit = opts.limit ?? 50;
  const offset = opts.offset ?? 0;

  const rows = db
    .query(
      `SELECT id, type, source, data, metadata, created_at
       FROM events ${whereClause}
       ORDER BY created_at DESC
       LIMIT ? OFFSET ?`
    )
    .all(...params, limit, offset) as Array<{
    id: string;
    type: string;
    source: string;
    data: string;
    metadata: string | null;
    created_at: string;
  }>;

  const events = rows.map(rowToHubEvent);
  return { events, total };
}

export function pruneEvents(db: Database, retentionDays: number): number {
  const result = db.run(
    `DELETE FROM events WHERE created_at < datetime('now', '-' || ? || ' days')`,
    [retentionDays]
  );
  return result.changes;
}

export function countEvents(
  db: Database,
  opts?: { type?: string; source?: string }
): number {
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

  const whereClause =
    conditions.length > 0 ? `WHERE ${conditions.join(" AND ")}` : "";

  const row = db
    .query(`SELECT COUNT(*) as count FROM events ${whereClause}`)
    .get(...params) as { count: number };

  return row.count;
}

export function getEventById(db: Database, id: string): HubEvent | null {
  const row = db
    .query(
      `SELECT id, type, source, data, metadata, created_at
       FROM events WHERE id = ?`
    )
    .get(id) as {
    id: string;
    type: string;
    source: string;
    data: string;
    metadata: string | null;
    created_at: string;
  } | null;

  if (!row) return null;
  return rowToHubEvent(row);
}

function rowToHubEvent(row: {
  id: string;
  type: string;
  source: string;
  data: string;
  metadata: string | null;
  created_at: string;
}): HubEvent {
  return {
    id: row.id,
    type: row.type,
    source: row.source,
    timestamp: row.created_at,
    data: (() => { try { return JSON.parse(row.data); } catch { return {}; } })(),
    ...(row.metadata ? { metadata: (() => { try { return JSON.parse(row.metadata || "{}"); } catch { return {}; } })() } : {}),
  };
}
