import { Database } from "bun:sqlite";

export interface HubEvent {
  id: string;
  event_type: string;
  payload: Record<string, unknown>;
  character_id: string | null;
  source: string | null;
  created_at: string;
}

export function createHubEvent(
  db: Database,
  data: {
    eventType: string;
    payload?: Record<string, unknown>;
    characterId?: string | null;
    source?: string | null;
  }
): HubEvent {
  const id = crypto.randomUUID();
  const query = db.query(
    `INSERT INTO hub_events (id, event_type, payload, character_id, source) VALUES (?, ?, ?, ?, ?) RETURNING *`
  );
  const row = query.get(
    id,
    data.eventType,
    JSON.stringify(data.payload ?? {}),
    data.characterId ?? null,
    data.source ?? null,
  ) as HubEventRow;
  return toHubEvent(row);
}

interface HubEventRow {
  id: string;
  event_type: string;
  payload: string;
  character_id: string | null;
  source: string | null;
  created_at: string;
}

function toHubEvent(row: HubEventRow): HubEvent {
  return {
    ...row,
    payload: JSON.parse(row.payload),
  };
}

export function getHubEvent(db: Database, id: string): HubEvent | null {
  const row = db.query(`SELECT * FROM hub_events WHERE id = ?`).get(id) as HubEventRow | null;
  return row ? toHubEvent(row) : null;
}

export function listHubEvents(
  db: Database,
  options: { eventType?: string; characterId?: string; limit?: number; since?: string } = {}
): HubEvent[] {
  const { eventType, characterId, limit = 50, since } = options;
  const conditions: string[] = [];
  const params: (string | number)[] = [];

  if (eventType) {
    conditions.push("event_type = ?");
    params.push(eventType);
  }
  if (characterId) {
    conditions.push("character_id = ?");
    params.push(characterId);
  }
  if (since) {
    conditions.push("created_at > ?");
    params.push(since);
  }

  const where = conditions.length > 0 ? `WHERE ${conditions.join(" AND ")}` : "";
  params.push(limit);

  const rows = db.query(
    `SELECT * FROM hub_events ${where} ORDER BY created_at DESC LIMIT ?`
  ).all(...params) as HubEventRow[];

  return rows.map(toHubEvent);
}
