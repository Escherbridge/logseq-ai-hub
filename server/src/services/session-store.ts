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
import type {
  Session,
  CreateSessionParams,
  ListSessionsOptions,
  UpdateSessionParams,
  AddMessageParams,
  SessionMessage,
} from "../types/session";

/**
 * High-level session management class that wraps the data-access layer.
 * Holds a Database instance and exposes a clean API for session CRUD,
 * message storage, archival, and activity tracking.
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
