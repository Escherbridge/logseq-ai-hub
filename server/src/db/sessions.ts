import { Database } from "bun:sqlite";
import type {
  Session,
  SessionContext,
  CreateSessionParams,
  ListSessionsOptions,
  UpdateSessionParams,
} from "../types/session";

/**
 * Raw row shape from SQLite -- context is a JSON string, not parsed.
 */
interface SessionRow {
  id: string;
  name: string | null;
  agent_id: string;
  status: string;
  context: string;
  created_at: string;
  updated_at: string;
  last_active_at: string;
}

/**
 * Parse a raw SQLite row into a typed Session object.
 * Deserializes the context JSON string into a SessionContext.
 */
function parseSessionRow(row: SessionRow): Session {
  return {
    ...row,
    status: row.status as Session["status"],
    context: JSON.parse(row.context) as SessionContext,
  };
}

/**
 * Create a new session in the database.
 * Generates a UUID for the id, stores context as a JSON string.
 */
export function createSession(
  db: Database,
  params: CreateSessionParams
): Session {
  const id = crypto.randomUUID();
  const contextJson = JSON.stringify(params.context ?? {});

  db.run(
    `INSERT INTO sessions (id, name, agent_id, context) VALUES (?, ?, ?, ?)`,
    [id, params.name ?? null, params.agent_id, contextJson]
  );

  return getSession(db, id)!;
}

/**
 * Get a session by id. Returns null if not found.
 * Parses the context JSON string into a SessionContext object.
 */
export function getSession(db: Database, id: string): Session | null {
  const row = db
    .query(`SELECT * FROM sessions WHERE id = ?`)
    .get(id) as SessionRow | null;

  return row ? parseSessionRow(row) : null;
}

/**
 * List sessions for a given agent_id with optional filtering.
 * Defaults to active sessions, ordered by last_active_at DESC.
 */
export function listSessions(
  db: Database,
  agentId: string,
  opts?: ListSessionsOptions
): Session[] {
  const status = opts?.status ?? "active";
  const limit = opts?.limit;
  const offset = opts?.offset;

  let sql = `SELECT * FROM sessions WHERE agent_id = ? AND status = ? ORDER BY last_active_at DESC`;
  const params: (string | number)[] = [agentId, status];

  if (limit !== undefined) {
    sql += ` LIMIT ?`;
    params.push(limit);
  }

  if (offset !== undefined) {
    // LIMIT is required for OFFSET in SQLite; use -1 for unlimited
    if (limit === undefined) {
      sql += ` LIMIT -1`;
    }
    sql += ` OFFSET ?`;
    params.push(offset);
  }

  const rows = db.query(sql).all(...params) as SessionRow[];
  return rows.map(parseSessionRow);
}

/**
 * Update mutable fields on a session. Returns true if a row was affected.
 * Always bumps updated_at to the current time.
 */
export function updateSession(
  db: Database,
  id: string,
  updates: UpdateSessionParams
): boolean {
  const setClauses: string[] = [];
  const params: (string | null)[] = [];

  if (updates.name !== undefined) {
    setClauses.push("name = ?");
    params.push(updates.name);
  }

  if (updates.status !== undefined) {
    setClauses.push("status = ?");
    params.push(updates.status);
  }

  if (updates.context !== undefined) {
    setClauses.push("context = ?");
    params.push(JSON.stringify(updates.context));
  }

  if (updates.last_active_at !== undefined) {
    setClauses.push("last_active_at = ?");
    params.push(updates.last_active_at);
  }

  // Always bump updated_at regardless of which fields are provided
  setClauses.push("updated_at = datetime('now')");

  const sql = `UPDATE sessions SET ${setClauses.join(", ")} WHERE id = ?`;
  params.push(id);

  const result = db.run(sql, params);
  return result.changes > 0;
}
