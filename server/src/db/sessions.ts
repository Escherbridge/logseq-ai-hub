import { Database } from "bun:sqlite";
import type {
  Session,
  SessionContext,
  SessionMessage,
  CreateSessionParams,
  ListSessionsOptions,
  UpdateSessionParams,
  AddMessageParams,
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

// ---------------------------------------------------------------------------
// Message Data Access
// ---------------------------------------------------------------------------

/**
 * Raw row shape from SQLite for session_messages.
 * tool_calls and metadata are JSON strings (or null).
 */
interface MessageRow {
  id: number;
  session_id: string;
  role: string;
  content: string;
  tool_calls: string | null;
  tool_call_id: string | null;
  metadata: string | null;
  created_at: string;
}

/**
 * Parse a raw SQLite message row into a typed SessionMessage.
 * Deserializes tool_calls and metadata JSON strings.
 */
function parseMessageRow(row: MessageRow): SessionMessage {
  return {
    ...row,
    role: row.role as SessionMessage["role"],
    tool_calls: row.tool_calls ? JSON.parse(row.tool_calls) : null,
    metadata: row.metadata ? JSON.parse(row.metadata) : null,
  };
}

/**
 * Options for loading session messages.
 */
export interface LoadMessagesOptions {
  /** Maximum number of messages to return (default 50). Returns the LAST N messages in ASC order. */
  limit?: number;
}

/**
 * Add a message to a session's conversation history.
 * Serializes tool_calls and metadata as JSON strings.
 * Also updates the session's last_active_at to the current time.
 */
export function addSessionMessage(
  db: Database,
  params: AddMessageParams
): SessionMessage {
  const toolCallsJson = params.tool_calls
    ? JSON.stringify(params.tool_calls)
    : null;
  const metadataJson = params.metadata
    ? JSON.stringify(params.metadata)
    : null;

  const result = db.run(
    `INSERT INTO session_messages (session_id, role, content, tool_calls, tool_call_id, metadata)
     VALUES (?, ?, ?, ?, ?, ?)`,
    [
      params.session_id,
      params.role,
      params.content,
      toolCallsJson,
      params.tool_call_id ?? null,
      metadataJson,
    ]
  );

  const id = Number(result.lastInsertRowid);

  // Update the session's last_active_at to reflect new activity
  db.run(
    `UPDATE sessions SET last_active_at = datetime('now'), updated_at = datetime('now') WHERE id = ?`,
    [params.session_id]
  );

  // Retrieve the inserted row to return it with all default-populated fields
  const row = db
    .query(`SELECT * FROM session_messages WHERE id = ?`)
    .get(id) as MessageRow;

  return parseMessageRow(row);
}

/**
 * Load messages for a session, ordered by id ASC.
 * When limit is specified, returns the LAST N messages in ascending order
 * using a subquery: SELECT * FROM (SELECT ... ORDER BY id DESC LIMIT ?) ORDER BY id ASC.
 * Default limit is 50.
 */
export function loadSessionMessages(
  db: Database,
  sessionId: string,
  opts?: LoadMessagesOptions
): SessionMessage[] {
  const limit = opts?.limit ?? 50;

  const sql = `SELECT * FROM (
    SELECT * FROM session_messages
    WHERE session_id = ?
    ORDER BY id DESC
    LIMIT ?
  ) ORDER BY id ASC`;

  const rows = db.query(sql).all(sessionId, limit) as MessageRow[];
  return rows.map(parseMessageRow);
}
