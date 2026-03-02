import { Database } from "bun:sqlite";
import type {
  Session,
  SessionContext,
  CreateSessionParams,
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
