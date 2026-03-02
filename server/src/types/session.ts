/**
 * Session status lifecycle: active -> paused -> archived.
 * Only active sessions are included in default queries.
 */
export type SessionStatus = "active" | "paused" | "archived";

/**
 * Preferences that control agent behavior within a session.
 */
export interface SessionPreferences {
  verbosity?: "concise" | "normal" | "verbose";
  auto_approve?: boolean;
}

/**
 * A key-value entry in the session's working memory scratchpad.
 * Agents can read/write these to persist ephemeral state across turns.
 */
export interface WorkingMemoryEntry {
  key: string;
  value: string;
  addedAt: string;
  source?: "manual" | "auto";
}

/**
 * Mutable context carried by a session. Stores the agent's working state
 * including current focus, relevant pages, working memory, and preferences.
 */
export interface SessionContext {
  focus?: string;
  relevant_pages?: string[];
  working_memory?: WorkingMemoryEntry[];
  preferences?: SessionPreferences;
}

/**
 * A persistent agent session backed by SQLite.
 * Sessions maintain conversation history and mutable working context.
 */
export interface Session {
  id: string;
  name: string | null;
  agent_id: string;
  status: SessionStatus;
  context: SessionContext;
  created_at: string;
  updated_at: string;
  last_active_at: string;
}

/**
 * A single message within a session's conversation history.
 * Supports system, user, assistant, and tool roles.
 */
export interface SessionMessage {
  id: number;
  session_id: string;
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  tool_calls: any[] | null;
  tool_call_id: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

/**
 * Parameters for creating a new session.
 */
export interface CreateSessionParams {
  name?: string;
  agent_id: string;
  context?: SessionContext;
}

/**
 * Options for listing sessions. All fields are optional filters.
 */
export interface ListSessionsOptions {
  status?: SessionStatus;
  limit?: number;
  offset?: number;
}

/**
 * Mutable fields that can be updated on a session.
 * All fields are optional -- only provided fields are changed.
 */
export interface UpdateSessionParams {
  name?: string;
  status?: SessionStatus;
  context?: SessionContext;
  last_active_at?: string;
}

/**
 * Parameters for adding a message to a session.
 */
export interface AddMessageParams {
  session_id: string;
  role: SessionMessage["role"];
  content: string;
  tool_calls?: any[];
  tool_call_id?: string;
  metadata?: Record<string, unknown>;
}
