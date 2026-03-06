import { Database } from "bun:sqlite";
import {
  createSession,
  getSession,
  listSessions,
  updateSession,
  addSessionMessage,
  loadSessionMessages,
  type LoadMessagesOptions,
} from "../db/sessions";
import {
  mergeSessionContext,
  addWorkingMemory,
} from "./session-context";
import type {
  Session,
  SessionContext,
  CreateSessionParams,
  ListSessionsOptions,
  UpdateSessionParams,
  AddMessageParams,
  SessionMessage,
} from "../types/session";

/**
 * Thrown when a session or message is not found by id.
 */
export class NotFoundError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotFoundError";
  }
}

/**
 * Raw row shape from SQLite for a single session_message lookup.
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

function parseMessageRow(row: MessageRow): SessionMessage {
  return {
    ...row,
    role: row.role as SessionMessage["role"],
    tool_calls: row.tool_calls ? JSON.parse(row.tool_calls) : null,
    metadata: row.metadata ? JSON.parse(row.metadata) : null,
  };
}

/**
 * High-level session management class that wraps the data-access layer.
 * Holds a Database instance and exposes a clean API for session CRUD,
 * message storage, archival, context management, and activity tracking.
 */
export class SessionStore {
  constructor(private db: Database) {}

  /**
   * Create a new session with the given parameters.
   * Returns the fully-populated Session object.
   */
  create(params: CreateSessionParams): Session {
    return createSession(this.db, params);
  }

  /**
   * Retrieve a session by its UUID. Returns null if not found.
   */
  get(id: string): Session | null {
    return getSession(this.db, id);
  }

  /**
   * List sessions for a given agent_id with optional filtering.
   * Defaults to active sessions ordered by last_active_at DESC.
   */
  list(agentId: string, opts?: ListSessionsOptions): Session[] {
    return listSessions(this.db, agentId, opts);
  }

  /**
   * Update mutable fields on a session. Returns true if the session existed.
   * Always bumps updated_at.
   */
  update(id: string, updates: UpdateSessionParams): boolean {
    return updateSession(this.db, id, updates);
  }

  /**
   * Deep-merge context updates into the session's existing context.
   * - focus: replaced if provided
   * - relevant_pages: unioned with case-insensitive deduplication, capped at 10 (LRU eviction)
   * - working_memory: merged by key, capped at 20 (LRU eviction)
   * - preferences: shallow-merged
   * Throws NotFoundError if the session does not exist.
   */
  updateContext(sessionId: string, contextUpdates: SessionContext): Session {
    const session = this.get(sessionId);
    if (!session) {
      throw new NotFoundError(`Session not found: ${sessionId}`);
    }
    const merged = mergeSessionContext(session.context, contextUpdates);
    updateSession(this.db, sessionId, { context: merged });
    return this.get(sessionId)!;
  }

  /**
   * Set the current focus string for a session.
   * Convenience wrapper around updateContext({ focus }).
   * Throws NotFoundError if the session does not exist.
   */
  setFocus(sessionId: string, focus: string): Session {
    return this.updateContext(sessionId, { focus });
  }

  /**
   * Add or update a key-value entry in the session's working memory.
   * If the key already exists its value is updated in-place.
   * Evicts the oldest entry (by addedAt) when at the cap of 20.
   * Throws NotFoundError if the session does not exist.
   */
  addMemory(
    sessionId: string,
    key: string,
    value: string,
    source: "manual" | "auto" = "manual"
  ): Session {
    const session = this.get(sessionId);
    if (!session) {
      throw new NotFoundError(`Session not found: ${sessionId}`);
    }
    const newContext = addWorkingMemory(session.context, key, value, source);
    updateSession(this.db, sessionId, { context: newContext });
    return this.get(sessionId)!;
  }

  /**
   * Retrieve a single message by its integer id within a session.
   * Throws NotFoundError if the message does not exist or belongs to a
   * different session.
   */
  getMessage(sessionId: string, messageId: number): SessionMessage {
    const row = this.db
      .query(`SELECT * FROM session_messages WHERE id = ? AND session_id = ?`)
      .get(messageId, sessionId) as MessageRow | null;

    if (!row) {
      throw new NotFoundError(
        `Message ${messageId} not found in session ${sessionId}`
      );
    }

    return parseMessageRow(row);
  }

  /**
   * Add a message to a session's conversation history.
   * Also updates the session's last_active_at timestamp.
   */
  addMessage(params: AddMessageParams): SessionMessage {
    return addSessionMessage(this.db, params);
  }

  /**
   * Load messages for a session, ordered by id ASC.
   * Supports a limit option (default 50) that returns the last N messages.
   */
  getMessages(sessionId: string, opts?: LoadMessagesOptions): SessionMessage[] {
    return loadSessionMessages(this.db, sessionId, opts);
  }

  /**
   * Archive a session -- convenience method that sets status to "archived".
   * Returns true if the session existed and was archived.
   */
  archive(id: string): boolean {
    return this.update(id, { status: "archived" });
  }

  /**
   * Touch a session's last_active_at to the current time.
   * Uses SQLite's datetime('now') directly for consistency with other timestamp
   * updates in the data access layer (e.g., addSessionMessage).
   * Returns true if the session existed.
   */
  touchActivity(id: string): boolean {
    const result = this.db.run(
      `UPDATE sessions SET last_active_at = datetime('now'), updated_at = datetime('now') WHERE id = ?`,
      [id]
    );
    return result.changes > 0;
  }
}
