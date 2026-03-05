import { Database } from "bun:sqlite";

export interface EventSubscription {
  id: string;
  event_type: string;
  character_id: string | null;
  job_skill: string;
  job_name_prefix: string;
  priority: number;
  enabled: boolean;
  created_at: string;
}

interface EventSubscriptionRow {
  id: string;
  event_type: string;
  character_id: string | null;
  job_skill: string;
  job_name_prefix: string;
  priority: number;
  enabled: number;
  created_at: string;
}

function toSubscription(row: EventSubscriptionRow): EventSubscription {
  return { ...row, enabled: row.enabled === 1 };
}

export function createEventSubscription(
  db: Database,
  data: {
    eventType: string;
    characterId?: string | null;
    jobSkill: string;
    jobNamePrefix: string;
    priority?: number;
    enabled?: boolean;
  },
): EventSubscription {
  const id = crypto.randomUUID();
  db.run(
    `INSERT INTO event_subscriptions (id, event_type, character_id, job_skill, job_name_prefix, priority, enabled)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    [
      id,
      data.eventType,
      data.characterId ?? null,
      data.jobSkill,
      data.jobNamePrefix,
      data.priority ?? 3,
      data.enabled === false ? 0 : 1,
    ],
  );
  return getEventSubscription(db, id)!;
}

export function getEventSubscription(db: Database, id: string): EventSubscription | null {
  const row = db.query(`SELECT * FROM event_subscriptions WHERE id = ?`).get(id) as
    | EventSubscriptionRow
    | null;
  return row ? toSubscription(row) : null;
}

export function listEventSubscriptions(
  db: Database,
  options: { eventType?: string; characterId?: string; enabled?: boolean } = {},
): EventSubscription[] {
  const conditions: string[] = [];
  const params: (string | number)[] = [];

  if (options.eventType) {
    conditions.push("event_type = ?");
    params.push(options.eventType);
  }
  if (options.characterId) {
    conditions.push("character_id = ?");
    params.push(options.characterId);
  }
  if (options.enabled !== undefined) {
    conditions.push("enabled = ?");
    params.push(options.enabled ? 1 : 0);
  }

  const where = conditions.length ? `WHERE ${conditions.join(" AND ")}` : "";
  const rows = db.query(`SELECT * FROM event_subscriptions ${where} ORDER BY created_at DESC`).all(...params) as
    EventSubscriptionRow[];
  return rows.map(toSubscription);
}

export function findMatchingSubscriptions(
  db: Database,
  eventType: string,
  characterId: string | null,
): EventSubscription[] {
  const rows = db
    .query(
      `SELECT *
       FROM event_subscriptions
       WHERE enabled = 1
         AND event_type = ?
         AND (character_id IS NULL OR character_id = ?)`,
    )
    .all(eventType, characterId) as EventSubscriptionRow[];
  return rows.map(toSubscription);
}

export function deleteEventSubscription(db: Database, id: string): boolean {
  const result = db.run(`DELETE FROM event_subscriptions WHERE id = ?`, [id]);
  return result.changes > 0;
}

