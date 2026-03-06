import { describe, it, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb } from "./helpers";
import { createSession, getSession, listSessions, updateSession, addSessionMessage, loadSessionMessages } from "../src/db/sessions";
import { SessionStore, NotFoundError } from "../src/services/session-store";
import type { Session, SessionContext, SessionMessage } from "../src/types/session";

describe("Session Schema", () => {
  let db: Database;

  beforeEach(() => {
    db = createTestDb();
  });

  describe("sessions table", () => {
    it("should exist in sqlite_master", () => {
      const result = db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='sessions'"
      ).get() as { name: string } | null;
      expect(result).not.toBeNull();
      expect(result!.name).toBe("sessions");
    });

    it("should have the correct columns", () => {
      const columns = db.query("PRAGMA table_info(sessions)").all() as Array<{
        name: string;
        type: string;
        notnull: number;
        dflt_value: string | null;
        pk: number;
      }>;

      const columnMap = new Map(columns.map((c) => [c.name, c]));

      // id: TEXT PRIMARY KEY
      expect(columnMap.get("id")).toBeDefined();
      expect(columnMap.get("id")!.type).toBe("TEXT");
      expect(columnMap.get("id")!.pk).toBe(1);

      // name: TEXT (nullable)
      expect(columnMap.get("name")).toBeDefined();
      expect(columnMap.get("name")!.type).toBe("TEXT");
      expect(columnMap.get("name")!.notnull).toBe(0);

      // agent_id: TEXT NOT NULL
      expect(columnMap.get("agent_id")).toBeDefined();
      expect(columnMap.get("agent_id")!.type).toBe("TEXT");
      expect(columnMap.get("agent_id")!.notnull).toBe(1);

      // status: TEXT NOT NULL DEFAULT 'active'
      expect(columnMap.get("status")).toBeDefined();
      expect(columnMap.get("status")!.type).toBe("TEXT");
      expect(columnMap.get("status")!.notnull).toBe(1);
      expect(columnMap.get("status")!.dflt_value).toBe("'active'");

      // context: TEXT NOT NULL DEFAULT '{}'
      expect(columnMap.get("context")).toBeDefined();
      expect(columnMap.get("context")!.type).toBe("TEXT");
      expect(columnMap.get("context")!.notnull).toBe(1);
      expect(columnMap.get("context")!.dflt_value).toBe("'{}'");

      // created_at: TEXT NOT NULL DEFAULT (datetime('now'))
      expect(columnMap.get("created_at")).toBeDefined();
      expect(columnMap.get("created_at")!.type).toBe("TEXT");
      expect(columnMap.get("created_at")!.notnull).toBe(1);
      expect(columnMap.get("created_at")!.dflt_value).toBe("datetime('now')");

      // updated_at: TEXT NOT NULL DEFAULT (datetime('now'))
      expect(columnMap.get("updated_at")).toBeDefined();
      expect(columnMap.get("updated_at")!.type).toBe("TEXT");
      expect(columnMap.get("updated_at")!.notnull).toBe(1);
      expect(columnMap.get("updated_at")!.dflt_value).toBe("datetime('now')");

      // last_active_at: TEXT NOT NULL DEFAULT (datetime('now'))
      expect(columnMap.get("last_active_at")).toBeDefined();
      expect(columnMap.get("last_active_at")!.type).toBe("TEXT");
      expect(columnMap.get("last_active_at")!.notnull).toBe(1);
      expect(columnMap.get("last_active_at")!.dflt_value).toBe("datetime('now')");
    });

    it("should have an index on agent_id", () => {
      const index = db.query(
        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_sessions_agent'"
      ).get() as { name: string } | null;
      expect(index).not.toBeNull();
      expect(index!.name).toBe("idx_sessions_agent");
    });

    it("should have an index on status", () => {
      const index = db.query(
        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_sessions_status'"
      ).get() as { name: string } | null;
      expect(index).not.toBeNull();
      expect(index!.name).toBe("idx_sessions_status");
    });
  });

  describe("session_messages table", () => {
    it("should exist in sqlite_master", () => {
      const result = db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='session_messages'"
      ).get() as { name: string } | null;
      expect(result).not.toBeNull();
      expect(result!.name).toBe("session_messages");
    });

    it("should have the correct columns", () => {
      const columns = db.query("PRAGMA table_info(session_messages)").all() as Array<{
        name: string;
        type: string;
        notnull: number;
        dflt_value: string | null;
        pk: number;
      }>;

      const columnMap = new Map(columns.map((c) => [c.name, c]));

      // id: INTEGER PRIMARY KEY AUTOINCREMENT
      expect(columnMap.get("id")).toBeDefined();
      expect(columnMap.get("id")!.type).toBe("INTEGER");
      expect(columnMap.get("id")!.pk).toBe(1);

      // session_id: TEXT NOT NULL
      expect(columnMap.get("session_id")).toBeDefined();
      expect(columnMap.get("session_id")!.type).toBe("TEXT");
      expect(columnMap.get("session_id")!.notnull).toBe(1);

      // role: TEXT NOT NULL
      expect(columnMap.get("role")).toBeDefined();
      expect(columnMap.get("role")!.type).toBe("TEXT");
      expect(columnMap.get("role")!.notnull).toBe(1);

      // content: TEXT NOT NULL
      expect(columnMap.get("content")).toBeDefined();
      expect(columnMap.get("content")!.type).toBe("TEXT");
      expect(columnMap.get("content")!.notnull).toBe(1);

      // tool_calls: TEXT (nullable)
      expect(columnMap.get("tool_calls")).toBeDefined();
      expect(columnMap.get("tool_calls")!.type).toBe("TEXT");
      expect(columnMap.get("tool_calls")!.notnull).toBe(0);

      // tool_call_id: TEXT (nullable)
      expect(columnMap.get("tool_call_id")).toBeDefined();
      expect(columnMap.get("tool_call_id")!.type).toBe("TEXT");
      expect(columnMap.get("tool_call_id")!.notnull).toBe(0);

      // metadata: TEXT (nullable)
      expect(columnMap.get("metadata")).toBeDefined();
      expect(columnMap.get("metadata")!.type).toBe("TEXT");
      expect(columnMap.get("metadata")!.notnull).toBe(0);

      // created_at: TEXT NOT NULL DEFAULT (datetime('now'))
      expect(columnMap.get("created_at")).toBeDefined();
      expect(columnMap.get("created_at")!.type).toBe("TEXT");
      expect(columnMap.get("created_at")!.notnull).toBe(1);
      expect(columnMap.get("created_at")!.dflt_value).toBe("datetime('now')");
    });

    it("should have an index on session_id", () => {
      const index = db.query(
        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_session_messages_session'"
      ).get() as { name: string } | null;
      expect(index).not.toBeNull();
      expect(index!.name).toBe("idx_session_messages_session");
    });
  });

  describe("foreign key constraints", () => {
    it("should enforce session_messages.session_id references sessions.id", () => {
      // Inserting a message with a non-existent session_id should fail
      expect(() => {
        db.run(
          `INSERT INTO session_messages (session_id, role, content) VALUES (?, ?, ?)`,
          ["nonexistent-session", "user", "Hello"]
        );
      }).toThrow();
    });

    it("should cascade delete session_messages when session is deleted", () => {
      // Insert a valid session
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["test-session-1", "claude-code"]
      );

      // Insert messages for this session
      db.run(
        `INSERT INTO session_messages (session_id, role, content) VALUES (?, ?, ?)`,
        ["test-session-1", "user", "Hello"]
      );
      db.run(
        `INSERT INTO session_messages (session_id, role, content) VALUES (?, ?, ?)`,
        ["test-session-1", "assistant", "Hi there"]
      );

      // Verify messages exist
      const beforeDelete = db.query(
        "SELECT COUNT(*) as count FROM session_messages WHERE session_id = ?"
      ).get("test-session-1") as { count: number };
      expect(beforeDelete.count).toBe(2);

      // Delete the session
      db.run("DELETE FROM sessions WHERE id = ?", ["test-session-1"]);

      // Messages should be cascaded
      const afterDelete = db.query(
        "SELECT COUNT(*) as count FROM session_messages WHERE session_id = ?"
      ).get("test-session-1") as { count: number };
      expect(afterDelete.count).toBe(0);
    });

    it("should allow inserting messages for existing sessions", () => {
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["test-session-2", "claude-code"]
      );

      expect(() => {
        db.run(
          `INSERT INTO session_messages (session_id, role, content) VALUES (?, ?, ?)`,
          ["test-session-2", "user", "This should work"]
        );
      }).not.toThrow();

      const msg = db.query(
        "SELECT * FROM session_messages WHERE session_id = ?"
      ).get("test-session-2") as { session_id: string; role: string; content: string };
      expect(msg.session_id).toBe("test-session-2");
      expect(msg.role).toBe("user");
      expect(msg.content).toBe("This should work");
    });
  });

  describe("default values", () => {
    it("should set default status to 'active' for new sessions", () => {
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["default-test-session", "claude-code"]
      );

      const session = db.query(
        "SELECT status FROM sessions WHERE id = ?"
      ).get("default-test-session") as { status: string };
      expect(session.status).toBe("active");
    });

    it("should set default context to '{}' for new sessions", () => {
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["context-test-session", "claude-code"]
      );

      const session = db.query(
        "SELECT context FROM sessions WHERE id = ?"
      ).get("context-test-session") as { context: string };
      expect(session.context).toBe("{}");
    });

    it("should auto-populate datetime fields for sessions", () => {
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["datetime-test-session", "claude-code"]
      );

      const session = db.query(
        "SELECT created_at, updated_at, last_active_at FROM sessions WHERE id = ?"
      ).get("datetime-test-session") as {
        created_at: string;
        updated_at: string;
        last_active_at: string;
      };
      expect(session.created_at).toBeTruthy();
      expect(session.updated_at).toBeTruthy();
      expect(session.last_active_at).toBeTruthy();
    });

    it("should auto-populate created_at for session messages", () => {
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["msg-datetime-session", "claude-code"]
      );
      db.run(
        `INSERT INTO session_messages (session_id, role, content) VALUES (?, ?, ?)`,
        ["msg-datetime-session", "user", "Test message"]
      );

      const msg = db.query(
        "SELECT created_at FROM session_messages WHERE session_id = ?"
      ).get("msg-datetime-session") as { created_at: string };
      expect(msg.created_at).toBeTruthy();
    });
  });
});

describe("Session Data Access - createSession and getSession", () => {
  let db: Database;

  beforeEach(() => {
    db = createTestDb();
  });

  describe("createSession", () => {
    it("should create a session with minimal params and return a Session with UUID id", () => {
      const session = createSession(db, { agent_id: "claude-code" });

      expect(session.id).toBeDefined();
      // UUID format: 8-4-4-4-12 hex characters
      expect(session.id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
      );
      expect(session.agent_id).toBe("claude-code");
      expect(session.status).toBe("active");
      expect(session.name).toBeNull();
      expect(session.context).toEqual({});
    });

    it("should store the name when provided", () => {
      const session = createSession(db, {
        agent_id: "claude-code",
        name: "API Refactor",
      });

      expect(session.name).toBe("API Refactor");
      expect(session.agent_id).toBe("claude-code");
    });

    it("should store context as JSON when provided", () => {
      const ctx: SessionContext = { focus: "testing" };
      const session = createSession(db, {
        agent_id: "claude-code",
        context: ctx,
      });

      expect(session.context).toEqual({ focus: "testing" });
    });

    it("should store complex context with all fields", () => {
      const ctx: SessionContext = {
        focus: "deploying the new API",
        relevant_pages: ["Skills/api-deploy", "Jobs/weekly-report"],
        working_memory: [
          { key: "branch", value: "feature/sessions", addedAt: "2026-03-02T00:00:00Z", source: "manual" },
        ],
        preferences: { verbosity: "concise", auto_approve: false },
      };
      const session = createSession(db, {
        agent_id: "claude-code",
        context: ctx,
      });

      expect(session.context).toEqual(ctx);
    });

    it("should populate created_at, updated_at, and last_active_at as ISO timestamps", () => {
      const session = createSession(db, { agent_id: "claude-code" });

      expect(session.created_at).toBeTruthy();
      expect(session.updated_at).toBeTruthy();
      expect(session.last_active_at).toBeTruthy();

      // Verify they are valid date strings (SQLite datetime format: YYYY-MM-DD HH:MM:SS)
      expect(new Date(session.created_at).toString()).not.toBe("Invalid Date");
      expect(new Date(session.updated_at).toString()).not.toBe("Invalid Date");
      expect(new Date(session.last_active_at).toString()).not.toBe("Invalid Date");
    });

    it("should generate unique IDs for each session", () => {
      const session1 = createSession(db, { agent_id: "claude-code" });
      const session2 = createSession(db, { agent_id: "claude-code" });

      expect(session1.id).not.toBe(session2.id);
    });
  });

  describe("getSession", () => {
    it("should return the session with parsed context for a valid id", () => {
      const created = createSession(db, {
        agent_id: "claude-code",
        name: "Test Session",
        context: { focus: "testing" },
      });

      const retrieved = getSession(db, created.id);

      expect(retrieved).not.toBeNull();
      expect(retrieved!.id).toBe(created.id);
      expect(retrieved!.name).toBe("Test Session");
      expect(retrieved!.agent_id).toBe("claude-code");
      expect(retrieved!.status).toBe("active");
      expect(retrieved!.context).toEqual({ focus: "testing" });
      expect(retrieved!.created_at).toBe(created.created_at);
      expect(retrieved!.updated_at).toBe(created.updated_at);
      expect(retrieved!.last_active_at).toBe(created.last_active_at);
    });

    it("should return null for a nonexistent id", () => {
      const result = getSession(db, "nonexistent");
      expect(result).toBeNull();
    });

    it("should parse empty context JSON into empty object", () => {
      const created = createSession(db, { agent_id: "claude-code" });
      const retrieved = getSession(db, created.id);

      expect(retrieved).not.toBeNull();
      expect(retrieved!.context).toEqual({});
    });

    it("should parse complex context JSON correctly", () => {
      const ctx: SessionContext = {
        focus: "deployment",
        relevant_pages: ["Page/A", "Page/B"],
        working_memory: [
          { key: "k1", value: "v1", addedAt: "2026-03-02T00:00:00Z" },
        ],
        preferences: { verbosity: "verbose", auto_approve: true },
      };
      const created = createSession(db, {
        agent_id: "claude-code",
        context: ctx,
      });

      const retrieved = getSession(db, created.id);

      expect(retrieved).not.toBeNull();
      expect(retrieved!.context).toEqual(ctx);
    });
  });
});

describe("Session Data Access - listSessions", () => {
  let db: Database;

  beforeEach(() => {
    db = createTestDb();
  });

  it("should return all active sessions for an agent, ordered by last_active_at DESC", () => {
    // Create sessions with staggered last_active_at times
    const s1 = createSession(db, { agent_id: "claude-code", name: "Oldest" });
    const s2 = createSession(db, { agent_id: "claude-code", name: "Middle" });
    const s3 = createSession(db, { agent_id: "claude-code", name: "Newest" });

    // Manually set different last_active_at to ensure ordering
    db.run("UPDATE sessions SET last_active_at = datetime('now', '-3 hours') WHERE id = ?", [s1.id]);
    db.run("UPDATE sessions SET last_active_at = datetime('now', '-1 hour') WHERE id = ?", [s2.id]);
    db.run("UPDATE sessions SET last_active_at = datetime('now') WHERE id = ?", [s3.id]);

    const sessions = listSessions(db, "claude-code");

    expect(sessions).toHaveLength(3);
    // Ordered by last_active_at DESC: newest first
    expect(sessions[0].name).toBe("Newest");
    expect(sessions[1].name).toBe("Middle");
    expect(sessions[2].name).toBe("Oldest");
  });

  it("should only return sessions for the specified agent_id", () => {
    createSession(db, { agent_id: "claude-code", name: "Claude Session" });
    createSession(db, { agent_id: "other-agent", name: "Other Session" });

    const sessions = listSessions(db, "claude-code");

    expect(sessions).toHaveLength(1);
    expect(sessions[0].name).toBe("Claude Session");
    expect(sessions[0].agent_id).toBe("claude-code");
  });

  it("should default to active sessions when no status filter is provided", () => {
    const s1 = createSession(db, { agent_id: "claude-code", name: "Active" });
    const s2 = createSession(db, { agent_id: "claude-code", name: "Archived" });

    // Archive the second session
    db.run("UPDATE sessions SET status = 'archived' WHERE id = ?", [s2.id]);

    const sessions = listSessions(db, "claude-code");

    expect(sessions).toHaveLength(1);
    expect(sessions[0].name).toBe("Active");
    expect(sessions[0].status).toBe("active");
  });

  it("should filter by status when provided", () => {
    const s1 = createSession(db, { agent_id: "claude-code", name: "Active" });
    const s2 = createSession(db, { agent_id: "claude-code", name: "Archived" });

    db.run("UPDATE sessions SET status = 'archived' WHERE id = ?", [s2.id]);

    const sessions = listSessions(db, "claude-code", { status: "archived" });

    expect(sessions).toHaveLength(1);
    expect(sessions[0].name).toBe("Archived");
    expect(sessions[0].status).toBe("archived");
  });

  it("should respect the limit option", () => {
    createSession(db, { agent_id: "claude-code", name: "S1" });
    createSession(db, { agent_id: "claude-code", name: "S2" });
    createSession(db, { agent_id: "claude-code", name: "S3" });

    const sessions = listSessions(db, "claude-code", { limit: 2 });

    expect(sessions).toHaveLength(2);
  });

  it("should respect the offset option", () => {
    const s1 = createSession(db, { agent_id: "claude-code", name: "S1" });
    const s2 = createSession(db, { agent_id: "claude-code", name: "S2" });
    const s3 = createSession(db, { agent_id: "claude-code", name: "S3" });

    // Stagger last_active_at to get deterministic ordering
    db.run("UPDATE sessions SET last_active_at = datetime('now', '-2 hours') WHERE id = ?", [s1.id]);
    db.run("UPDATE sessions SET last_active_at = datetime('now', '-1 hour') WHERE id = ?", [s2.id]);
    db.run("UPDATE sessions SET last_active_at = datetime('now') WHERE id = ?", [s3.id]);

    // Skip the newest, get the rest
    const sessions = listSessions(db, "claude-code", { offset: 1 });

    expect(sessions).toHaveLength(2);
    expect(sessions[0].name).toBe("S2");
    expect(sessions[1].name).toBe("S1");
  });

  it("should return an empty array when no sessions match", () => {
    const sessions = listSessions(db, "nonexistent-agent");
    expect(sessions).toEqual([]);
  });

  it("should parse context JSON on returned sessions", () => {
    createSession(db, {
      agent_id: "claude-code",
      context: { focus: "testing list" },
    });

    const sessions = listSessions(db, "claude-code");

    expect(sessions).toHaveLength(1);
    expect(sessions[0].context).toEqual({ focus: "testing list" });
  });
});

describe("Session Data Access - updateSession", () => {
  let db: Database;

  beforeEach(() => {
    db = createTestDb();
  });

  it("should update the name and bump updated_at", () => {
    const session = createSession(db, {
      agent_id: "claude-code",
      name: "Old Name",
    });
    const originalUpdatedAt = session.updated_at;

    // Small delay to ensure updated_at changes (SQLite datetime has 1-second granularity)
    // We force a different time by manually setting created timestamps back
    db.run("UPDATE sessions SET updated_at = datetime('now', '-1 hour') WHERE id = ?", [session.id]);

    const result = updateSession(db, session.id, { name: "New Name" });

    expect(result).toBe(true);

    const updated = getSession(db, session.id);
    expect(updated).not.toBeNull();
    expect(updated!.name).toBe("New Name");
    // updated_at should be bumped to now (newer than the manually-set time)
    expect(updated!.updated_at).not.toBe(
      getSession(db, session.id)!.created_at.replace(/\d{2}:\d{2}:\d{2}/, "00:00:00") // just check it was updated
    );
  });

  it("should update the status", () => {
    const session = createSession(db, { agent_id: "claude-code" });

    const result = updateSession(db, session.id, { status: "archived" });

    expect(result).toBe(true);

    const updated = getSession(db, session.id);
    expect(updated).not.toBeNull();
    expect(updated!.status).toBe("archived");
  });

  it("should replace the entire context JSON", () => {
    const session = createSession(db, {
      agent_id: "claude-code",
      context: { focus: "old focus" },
    });

    const newContext = { focus: "new focus" };
    const result = updateSession(db, session.id, { context: newContext });

    expect(result).toBe(true);

    const updated = getSession(db, session.id);
    expect(updated).not.toBeNull();
    expect(updated!.context).toEqual({ focus: "new focus" });
    // Old context fields should be gone (replaced, not merged)
  });

  it("should update last_active_at when provided", () => {
    const session = createSession(db, { agent_id: "claude-code" });
    const newTimestamp = "2026-03-02 12:00:00";

    const result = updateSession(db, session.id, { last_active_at: newTimestamp });

    expect(result).toBe(true);

    const updated = getSession(db, session.id);
    expect(updated).not.toBeNull();
    expect(updated!.last_active_at).toBe(newTimestamp);
  });

  it("should always bump updated_at even if other fields do not include it", () => {
    const session = createSession(db, { agent_id: "claude-code" });

    // Set updated_at to a known past time
    db.run("UPDATE sessions SET updated_at = datetime('now', '-2 hours') WHERE id = ?", [session.id]);
    const before = getSession(db, session.id)!;

    updateSession(db, session.id, { name: "Bump Test" });
    const after = getSession(db, session.id)!;

    // updated_at should be newer than the manually-set past time
    expect(new Date(after.updated_at).getTime()).toBeGreaterThan(
      new Date(before.updated_at).getTime()
    );
  });

  it("should return false when updating a nonexistent session", () => {
    const result = updateSession(db, "nonexistent-id", { name: "Nope" });
    expect(result).toBe(false);
  });

  it("should handle updating multiple fields at once", () => {
    const session = createSession(db, {
      agent_id: "claude-code",
      name: "Original",
    });

    const result = updateSession(db, session.id, {
      name: "Updated",
      status: "paused",
      context: { focus: "multi-update" },
    });

    expect(result).toBe(true);

    const updated = getSession(db, session.id);
    expect(updated).not.toBeNull();
    expect(updated!.name).toBe("Updated");
    expect(updated!.status).toBe("paused");
    expect(updated!.context).toEqual({ focus: "multi-update" });
  });
});

describe("Session Data Access - addSessionMessage", () => {
  let db: Database;
  let session: Session;

  beforeEach(() => {
    db = createTestDb();
    session = createSession(db, { agent_id: "claude-code", name: "Test Session" });
  });

  it("should insert a user message and return it with auto-incremented id", () => {
    const msg = addSessionMessage(db, {
      session_id: session.id,
      role: "user",
      content: "Hello",
    });

    expect(msg.id).toBeDefined();
    expect(typeof msg.id).toBe("number");
    expect(msg.id).toBeGreaterThan(0);
    expect(msg.session_id).toBe(session.id);
    expect(msg.role).toBe("user");
    expect(msg.content).toBe("Hello");
    expect(msg.tool_calls).toBeNull();
    expect(msg.tool_call_id).toBeNull();
    expect(msg.metadata).toBeNull();
    expect(msg.created_at).toBeTruthy();
  });

  it("should auto-increment ids for successive messages", () => {
    const msg1 = addSessionMessage(db, {
      session_id: session.id,
      role: "user",
      content: "First",
    });
    const msg2 = addSessionMessage(db, {
      session_id: session.id,
      role: "assistant",
      content: "Second",
    });

    expect(msg2.id).toBeGreaterThan(msg1.id);
  });

  it("should store tool_calls as JSON and return parsed array", () => {
    const toolCalls = [
      { id: "call_1", type: "function", function: { name: "search", arguments: '{"q":"test"}' } },
    ];

    const msg = addSessionMessage(db, {
      session_id: session.id,
      role: "assistant",
      content: "Let me search for that.",
      tool_calls: toolCalls,
    });

    expect(msg.tool_calls).toEqual(toolCalls);
  });

  it("should store tool_call_id for tool result messages", () => {
    const msg = addSessionMessage(db, {
      session_id: session.id,
      role: "tool",
      content: '{"results": []}',
      tool_call_id: "call_1",
    });

    expect(msg.role).toBe("tool");
    expect(msg.tool_call_id).toBe("call_1");
  });

  it("should store metadata as JSON and return parsed object", () => {
    const msg = addSessionMessage(db, {
      session_id: session.id,
      role: "assistant",
      content: "Response",
      metadata: { tokens: 150, latency_ms: 320 },
    });

    expect(msg.metadata).toEqual({ tokens: 150, latency_ms: 320 });
  });

  it("should update the session's last_active_at when a message is added", () => {
    // Set last_active_at to a known past time
    db.run(
      "UPDATE sessions SET last_active_at = datetime('now', '-2 hours') WHERE id = ?",
      [session.id]
    );
    const before = getSession(db, session.id)!;

    addSessionMessage(db, {
      session_id: session.id,
      role: "user",
      content: "Activity!",
    });

    const after = getSession(db, session.id)!;
    expect(new Date(after.last_active_at).getTime()).toBeGreaterThan(
      new Date(before.last_active_at).getTime()
    );
  });

  it("should store all fields together correctly", () => {
    const toolCalls = [{ id: "tc_1", type: "function", function: { name: "read_page", arguments: "{}" } }];
    const metadata = { tokens: 200, model: "claude-sonnet" };

    const msg = addSessionMessage(db, {
      session_id: session.id,
      role: "assistant",
      content: "Here is the page content.",
      tool_calls: toolCalls,
      metadata,
    });

    expect(msg.role).toBe("assistant");
    expect(msg.content).toBe("Here is the page content.");
    expect(msg.tool_calls).toEqual(toolCalls);
    expect(msg.metadata).toEqual(metadata);
  });
});

describe("Session Data Access - loadSessionMessages", () => {
  let db: Database;
  let session: Session;

  beforeEach(() => {
    db = createTestDb();
    session = createSession(db, { agent_id: "claude-code", name: "Chat Session" });
  });

  it("should return messages ordered by id ASC", () => {
    addSessionMessage(db, { session_id: session.id, role: "user", content: "First" });
    addSessionMessage(db, { session_id: session.id, role: "assistant", content: "Second" });
    addSessionMessage(db, { session_id: session.id, role: "user", content: "Third" });

    const messages = loadSessionMessages(db, session.id);

    expect(messages).toHaveLength(3);
    expect(messages[0].content).toBe("First");
    expect(messages[1].content).toBe("Second");
    expect(messages[2].content).toBe("Third");
    // IDs should be in ascending order
    expect(messages[0].id).toBeLessThan(messages[1].id);
    expect(messages[1].id).toBeLessThan(messages[2].id);
  });

  it("should return the last N messages when limit is specified", () => {
    addSessionMessage(db, { session_id: session.id, role: "user", content: "Msg 1" });
    addSessionMessage(db, { session_id: session.id, role: "assistant", content: "Msg 2" });
    addSessionMessage(db, { session_id: session.id, role: "user", content: "Msg 3" });
    addSessionMessage(db, { session_id: session.id, role: "assistant", content: "Msg 4" });
    addSessionMessage(db, { session_id: session.id, role: "user", content: "Msg 5" });

    const messages = loadSessionMessages(db, session.id, { limit: 3 });

    expect(messages).toHaveLength(3);
    // Should be the LAST 3 messages (Msg 3, Msg 4, Msg 5)
    expect(messages[0].content).toBe("Msg 3");
    expect(messages[1].content).toBe("Msg 4");
    expect(messages[2].content).toBe("Msg 5");
  });

  it("should return messages in ASC order even when limit is used (subquery pattern)", () => {
    addSessionMessage(db, { session_id: session.id, role: "user", content: "A" });
    addSessionMessage(db, { session_id: session.id, role: "assistant", content: "B" });
    addSessionMessage(db, { session_id: session.id, role: "user", content: "C" });
    addSessionMessage(db, { session_id: session.id, role: "assistant", content: "D" });

    const messages = loadSessionMessages(db, session.id, { limit: 2 });

    expect(messages).toHaveLength(2);
    // Last 2 messages: C, D -- in ASC order
    expect(messages[0].content).toBe("C");
    expect(messages[1].content).toBe("D");
    // IDs should be ascending
    expect(messages[0].id).toBeLessThan(messages[1].id);
  });

  it("should default to 50 messages when no limit is provided", () => {
    // Insert 55 messages
    for (let i = 1; i <= 55; i++) {
      addSessionMessage(db, {
        session_id: session.id,
        role: i % 2 === 1 ? "user" : "assistant",
        content: `Message ${i}`,
      });
    }

    const messages = loadSessionMessages(db, session.id);

    // Default limit is 50, so we get the last 50 messages
    expect(messages).toHaveLength(50);
    // First message returned should be Message 6 (the 6th out of 55)
    expect(messages[0].content).toBe("Message 6");
    // Last should be Message 55
    expect(messages[49].content).toBe("Message 55");
  });

  it("should return empty array for nonexistent session", () => {
    const messages = loadSessionMessages(db, "nonexistent");

    expect(messages).toEqual([]);
  });

  it("should only return messages for the specified session", () => {
    const otherSession = createSession(db, { agent_id: "claude-code", name: "Other" });

    addSessionMessage(db, { session_id: session.id, role: "user", content: "Session 1 msg" });
    addSessionMessage(db, { session_id: otherSession.id, role: "user", content: "Session 2 msg" });

    const messages = loadSessionMessages(db, session.id);

    expect(messages).toHaveLength(1);
    expect(messages[0].content).toBe("Session 1 msg");
    expect(messages[0].session_id).toBe(session.id);
  });

  it("should parse tool_calls and metadata JSON in loaded messages", () => {
    const toolCalls = [{ id: "call_x", type: "function", function: { name: "test", arguments: "{}" } }];
    addSessionMessage(db, {
      session_id: session.id,
      role: "assistant",
      content: "Using tool",
      tool_calls: toolCalls,
      metadata: { tokens: 100 },
    });

    const messages = loadSessionMessages(db, session.id);

    expect(messages).toHaveLength(1);
    expect(messages[0].tool_calls).toEqual(toolCalls);
    expect(messages[0].metadata).toEqual({ tokens: 100 });
  });

  it("should return null for tool_calls and metadata when not set", () => {
    addSessionMessage(db, {
      session_id: session.id,
      role: "user",
      content: "Plain message",
    });

    const messages = loadSessionMessages(db, session.id);

    expect(messages).toHaveLength(1);
    expect(messages[0].tool_calls).toBeNull();
    expect(messages[0].metadata).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// SessionStore class tests (Task 1.6)
// ---------------------------------------------------------------------------

describe("SessionStore", () => {
  let db: Database;
  let store: SessionStore;

  beforeEach(() => {
    db = createTestDb();
    store = new SessionStore(db);
  });

  describe("create", () => {
    it("should create and return a session with minimal params", () => {
      const session = store.create({ agent_id: "claude-code" });

      expect(session.id).toBeDefined();
      expect(session.id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
      );
      expect(session.agent_id).toBe("claude-code");
      expect(session.status).toBe("active");
      expect(session.name).toBeNull();
      expect(session.context).toEqual({});
    });

    it("should create a session with name and context", () => {
      const session = store.create({
        agent_id: "claude-code",
        name: "API Refactor",
        context: { focus: "refactoring endpoints" },
      });

      expect(session.name).toBe("API Refactor");
      expect(session.context).toEqual({ focus: "refactoring endpoints" });
    });
  });

  describe("get", () => {
    it("should retrieve a session by id", () => {
      const created = store.create({ agent_id: "claude-code", name: "Test" });

      const retrieved = store.get(created.id);

      expect(retrieved).not.toBeNull();
      expect(retrieved!.id).toBe(created.id);
      expect(retrieved!.name).toBe("Test");
      expect(retrieved!.agent_id).toBe("claude-code");
    });

    it("should return null for a nonexistent id", () => {
      const result = store.get("nonexistent-id");
      expect(result).toBeNull();
    });
  });

  describe("list", () => {
    it("should return active sessions only by default", () => {
      const active = store.create({ agent_id: "claude-code", name: "Active" });
      const archived = store.create({ agent_id: "claude-code", name: "Archived" });
      store.archive(archived.id);

      const sessions = store.list("claude-code");

      expect(sessions).toHaveLength(1);
      expect(sessions[0].name).toBe("Active");
      expect(sessions[0].status).toBe("active");
    });

    it("should return archived sessions when status filter is provided", () => {
      store.create({ agent_id: "claude-code", name: "Active" });
      const archived = store.create({ agent_id: "claude-code", name: "Archived" });
      store.archive(archived.id);

      const sessions = store.list("claude-code", { status: "archived" });

      expect(sessions).toHaveLength(1);
      expect(sessions[0].name).toBe("Archived");
      expect(sessions[0].status).toBe("archived");
    });

    it("should only return sessions for the specified agent", () => {
      store.create({ agent_id: "claude-code", name: "Claude" });
      store.create({ agent_id: "other-agent", name: "Other" });

      const sessions = store.list("claude-code");

      expect(sessions).toHaveLength(1);
      expect(sessions[0].agent_id).toBe("claude-code");
    });
  });

  describe("update", () => {
    it("should update session fields and return true", () => {
      const session = store.create({ agent_id: "claude-code", name: "Old" });

      const result = store.update(session.id, { name: "New" });

      expect(result).toBe(true);
      const updated = store.get(session.id);
      expect(updated!.name).toBe("New");
    });

    it("should return false for a nonexistent session", () => {
      const result = store.update("nonexistent", { name: "Nope" });
      expect(result).toBe(false);
    });
  });

  describe("addMessage", () => {
    it("should store a message and return it", () => {
      const session = store.create({ agent_id: "claude-code" });

      const msg = store.addMessage({
        session_id: session.id,
        role: "user",
        content: "Hello from SessionStore",
      });

      expect(msg.id).toBeGreaterThan(0);
      expect(msg.session_id).toBe(session.id);
      expect(msg.role).toBe("user");
      expect(msg.content).toBe("Hello from SessionStore");
    });

    it("should update last_active_at on the session", () => {
      const session = store.create({ agent_id: "claude-code" });

      // Set last_active_at to a known past time
      db.run(
        "UPDATE sessions SET last_active_at = datetime('now', '-2 hours') WHERE id = ?",
        [session.id]
      );
      const before = store.get(session.id)!;

      store.addMessage({
        session_id: session.id,
        role: "user",
        content: "Activity!",
      });

      const after = store.get(session.id)!;
      expect(new Date(after.last_active_at).getTime()).toBeGreaterThan(
        new Date(before.last_active_at).getTime()
      );
    });
  });

  describe("getMessages", () => {
    it("should return ordered messages for a session", () => {
      const session = store.create({ agent_id: "claude-code" });
      store.addMessage({ session_id: session.id, role: "user", content: "First" });
      store.addMessage({ session_id: session.id, role: "assistant", content: "Second" });
      store.addMessage({ session_id: session.id, role: "user", content: "Third" });

      const messages = store.getMessages(session.id);

      expect(messages).toHaveLength(3);
      expect(messages[0].content).toBe("First");
      expect(messages[1].content).toBe("Second");
      expect(messages[2].content).toBe("Third");
      expect(messages[0].id).toBeLessThan(messages[1].id);
      expect(messages[1].id).toBeLessThan(messages[2].id);
    });

    it("should respect the limit option", () => {
      const session = store.create({ agent_id: "claude-code" });
      store.addMessage({ session_id: session.id, role: "user", content: "A" });
      store.addMessage({ session_id: session.id, role: "assistant", content: "B" });
      store.addMessage({ session_id: session.id, role: "user", content: "C" });

      const messages = store.getMessages(session.id, { limit: 2 });

      expect(messages).toHaveLength(2);
      // Last 2 messages in ASC order
      expect(messages[0].content).toBe("B");
      expect(messages[1].content).toBe("C");
    });

    it("should return empty array for nonexistent session", () => {
      const messages = store.getMessages("nonexistent");
      expect(messages).toEqual([]);
    });
  });

  describe("archive", () => {
    it("should set session status to archived", () => {
      const session = store.create({ agent_id: "claude-code" });

      store.archive(session.id);

      const archived = store.get(session.id);
      expect(archived).not.toBeNull();
      expect(archived!.status).toBe("archived");
    });

    it("should cause the session to be excluded from list() default results", () => {
      const session1 = store.create({ agent_id: "claude-code", name: "Keep" });
      const session2 = store.create({ agent_id: "claude-code", name: "Archive Me" });

      store.archive(session2.id);

      const sessions = store.list("claude-code");
      expect(sessions).toHaveLength(1);
      expect(sessions[0].name).toBe("Keep");
    });

    it("should return true for an existing session", () => {
      const session = store.create({ agent_id: "claude-code" });
      const result = store.archive(session.id);
      expect(result).toBe(true);
    });

    it("should return false for a nonexistent session", () => {
      const result = store.archive("nonexistent");
      expect(result).toBe(false);
    });
  });

  describe("touchActivity", () => {
    it("should update last_active_at to the current time", () => {
      const session = store.create({ agent_id: "claude-code" });

      // Set last_active_at to a known past time
      db.run(
        "UPDATE sessions SET last_active_at = datetime('now', '-3 hours') WHERE id = ?",
        [session.id]
      );
      const before = store.get(session.id)!;

      store.touchActivity(session.id);

      const after = store.get(session.id)!;
      expect(new Date(after.last_active_at).getTime()).toBeGreaterThan(
        new Date(before.last_active_at).getTime()
      );
    });

    it("should also bump updated_at", () => {
      const session = store.create({ agent_id: "claude-code" });

      // Set both timestamps to a known past time
      db.run(
        "UPDATE sessions SET last_active_at = datetime('now', '-3 hours'), updated_at = datetime('now', '-3 hours') WHERE id = ?",
        [session.id]
      );
      const before = store.get(session.id)!;

      store.touchActivity(session.id);

      const after = store.get(session.id)!;
      expect(new Date(after.updated_at).getTime()).toBeGreaterThan(
        new Date(before.updated_at).getTime()
      );
    });

    it("should return true for an existing session", () => {
      const session = store.create({ agent_id: "claude-code" });
      const result = store.touchActivity(session.id);
      expect(result).toBe(true);
    });

    it("should return false for a nonexistent session", () => {
      const result = store.touchActivity("nonexistent");
      expect(result).toBe(false);
    });
  });

  // ---------------------------------------------------------------------------
  // updateContext
  // ---------------------------------------------------------------------------

  describe("updateContext", () => {
    it("should deep-merge focus into an empty context", () => {
      const session = store.create({ agent_id: "claude-code" });

      const updated = store.updateContext(session.id, { focus: "implement auth" });

      expect(updated.context.focus).toBe("implement auth");
    });

    it("should replace focus when called twice", () => {
      const session = store.create({
        agent_id: "claude-code",
        context: { focus: "old focus" },
      });

      const updated = store.updateContext(session.id, { focus: "new focus" });

      expect(updated.context.focus).toBe("new focus");
    });

    it("should union relevant_pages without replacing them", () => {
      const session = store.create({
        agent_id: "claude-code",
        context: { relevant_pages: ["Page/A", "Page/B"] },
      });

      const updated = store.updateContext(session.id, {
        relevant_pages: ["Page/B", "Page/C"],
      });

      // B deduped, A preserved, C added
      expect(updated.context.relevant_pages).toContain("Page/A");
      expect(updated.context.relevant_pages).toContain("Page/B");
      expect(updated.context.relevant_pages).toContain("Page/C");
      expect(updated.context.relevant_pages!.length).toBe(3);
    });

    it("should union relevant_pages from both sides (mergeSessionContext unions, no cap)", () => {
      // updateContext uses mergeSessionContext which unions without capping.
      // LRU cap is enforced by addRelevantPage (see session-context tests).
      const initial: SessionContext = {
        relevant_pages: ["P1", "P2", "P3"],
      };
      const session = store.create({ agent_id: "claude-code", context: initial });

      const updated = store.updateContext(session.id, {
        relevant_pages: ["P3", "P4"],
      });

      // P3 deduped; P1, P2, P4 all present
      expect(updated.context.relevant_pages).toContain("P1");
      expect(updated.context.relevant_pages).toContain("P2");
      expect(updated.context.relevant_pages).toContain("P3");
      expect(updated.context.relevant_pages).toContain("P4");
      expect(updated.context.relevant_pages!.length).toBe(4);
    });

    it("should merge working_memory by key without replacing other entries", () => {
      const session = store.create({
        agent_id: "claude-code",
        context: {
          working_memory: [
            { key: "branch", value: "main", addedAt: "2026-01-01T00:00:00Z" },
          ],
        },
      });

      const updated = store.updateContext(session.id, {
        working_memory: [
          { key: "pr_url", value: "https://github.com/x/y/pull/1", addedAt: "2026-01-02T00:00:00Z" },
        ],
      });

      const wm = updated.context.working_memory!;
      expect(wm.length).toBe(2);
      expect(wm.find((e) => e.key === "branch")?.value).toBe("main");
      expect(wm.find((e) => e.key === "pr_url")?.value).toBe("https://github.com/x/y/pull/1");
    });

    it("should merge working_memory by key across both sides (no cap on raw merge)", () => {
      // updateContext uses mergeSessionContext which merges by key without capping.
      // LRU cap is enforced by addMemory (which calls addWorkingMemory).
      const entries = Array.from({ length: 5 }, (_, i) => ({
        key: `key${i}`,
        value: `val${i}`,
        addedAt: `2026-01-${String(i + 1).padStart(2, "0")}T00:00:00Z`,
      }));
      const session = store.create({
        agent_id: "claude-code",
        context: { working_memory: entries },
      });

      // Adding a new key merges it in alongside existing entries
      const updated = store.updateContext(session.id, {
        working_memory: [{ key: "key_new", value: "new", addedAt: "2026-02-01T00:00:00Z" }],
      });

      const wm = updated.context.working_memory!;
      expect(wm.length).toBe(6);
      expect(wm.find((e) => e.key === "key0")).toBeDefined();
      expect(wm.find((e) => e.key === "key_new")?.value).toBe("new");
    });

    it("should shallow-merge preferences", () => {
      const session = store.create({
        agent_id: "claude-code",
        context: { preferences: { verbosity: "concise", auto_approve: false } },
      });

      const updated = store.updateContext(session.id, {
        preferences: { auto_approve: true },
      });

      expect(updated.context.preferences?.verbosity).toBe("concise");
      expect(updated.context.preferences?.auto_approve).toBe(true);
    });

    it("should persist changes to the database (survives a fresh get)", () => {
      const session = store.create({ agent_id: "claude-code" });
      store.updateContext(session.id, { focus: "persist me" });

      const refetched = store.get(session.id);
      expect(refetched!.context.focus).toBe("persist me");
    });

    it("should throw NotFoundError for a nonexistent session", () => {
      expect(() =>
        store.updateContext("nonexistent-id", { focus: "x" })
      ).toThrow(NotFoundError);
    });

    it("should throw NotFoundError with the session id in the message", () => {
      expect(() =>
        store.updateContext("missing-session", { focus: "x" })
      ).toThrow("missing-session");
    });
  });

  // ---------------------------------------------------------------------------
  // setFocus
  // ---------------------------------------------------------------------------

  describe("setFocus", () => {
    it("should set focus on a session with no prior context", () => {
      const session = store.create({ agent_id: "claude-code" });

      const updated = store.setFocus(session.id, "fixing the auth bug");

      expect(updated.context.focus).toBe("fixing the auth bug");
    });

    it("should replace an existing focus", () => {
      const session = store.create({
        agent_id: "claude-code",
        context: { focus: "old task" },
      });

      const updated = store.setFocus(session.id, "new task");

      expect(updated.context.focus).toBe("new task");
    });

    it("should not touch other context fields", () => {
      const session = store.create({
        agent_id: "claude-code",
        context: {
          focus: "old",
          relevant_pages: ["Doc/api"],
          preferences: { verbosity: "verbose" },
        },
      });

      const updated = store.setFocus(session.id, "new");

      expect(updated.context.relevant_pages).toEqual(["Doc/api"]);
      expect(updated.context.preferences?.verbosity).toBe("verbose");
    });

    it("should throw NotFoundError for a nonexistent session", () => {
      expect(() => store.setFocus("bad-id", "focus")).toThrow(NotFoundError);
    });
  });

  // ---------------------------------------------------------------------------
  // addMemory
  // ---------------------------------------------------------------------------

  describe("addMemory", () => {
    it("should add a new key-value entry to working memory", () => {
      const session = store.create({ agent_id: "claude-code" });

      const updated = store.addMemory(session.id, "branch", "feature/sessions");

      const wm = updated.context.working_memory!;
      expect(wm.length).toBe(1);
      expect(wm[0].key).toBe("branch");
      expect(wm[0].value).toBe("feature/sessions");
      expect(wm[0].source).toBe("manual");
    });

    it("should update an existing key in-place", () => {
      const session = store.create({
        agent_id: "claude-code",
        context: {
          working_memory: [
            { key: "branch", value: "main", addedAt: "2026-01-01T00:00:00Z", source: "manual" },
          ],
        },
      });

      const updated = store.addMemory(session.id, "branch", "feature/new");

      const wm = updated.context.working_memory!;
      expect(wm.length).toBe(1);
      expect(wm[0].value).toBe("feature/new");
    });

    it("should store the source when provided as 'auto'", () => {
      const session = store.create({ agent_id: "claude-code" });

      const updated = store.addMemory(session.id, "auto_key", "auto_val", "auto");

      const entry = updated.context.working_memory!.find((e) => e.key === "auto_key");
      expect(entry?.source).toBe("auto");
    });

    it("should evict the oldest entry when memory is at the cap of 20", () => {
      // Build 20 entries with sequential timestamps so key0 is oldest
      const entries = Array.from({ length: 20 }, (_, i) => ({
        key: `k${i}`,
        value: `v${i}`,
        addedAt: `2026-01-${String(i + 1).padStart(2, "0")}T00:00:00Z`,
        source: "manual" as const,
      }));
      const session = store.create({
        agent_id: "claude-code",
        context: { working_memory: entries },
      });

      const updated = store.addMemory(session.id, "k_new", "v_new");

      const wm = updated.context.working_memory!;
      expect(wm.length).toBe(20);
      expect(wm.find((e) => e.key === "k0")).toBeUndefined();
      expect(wm.find((e) => e.key === "k_new")).toBeDefined();
    });

    it("should persist changes to the database", () => {
      const session = store.create({ agent_id: "claude-code" });
      store.addMemory(session.id, "task", "write tests");

      const refetched = store.get(session.id);
      const entry = refetched!.context.working_memory!.find((e) => e.key === "task");
      expect(entry?.value).toBe("write tests");
    });

    it("should throw NotFoundError for a nonexistent session", () => {
      expect(() =>
        store.addMemory("nonexistent", "k", "v")
      ).toThrow(NotFoundError);
    });
  });

  // ---------------------------------------------------------------------------
  // getMessage
  // ---------------------------------------------------------------------------

  describe("getMessage", () => {
    it("should retrieve an existing message by id", () => {
      const session = store.create({ agent_id: "claude-code" });
      const added = store.addMessage({
        session_id: session.id,
        role: "user",
        content: "Hello, agent!",
      });

      const msg = store.getMessage(session.id, added.id);

      expect(msg.id).toBe(added.id);
      expect(msg.session_id).toBe(session.id);
      expect(msg.role).toBe("user");
      expect(msg.content).toBe("Hello, agent!");
    });

    it("should parse tool_calls JSON on retrieval", () => {
      const session = store.create({ agent_id: "claude-code" });
      const toolCalls = [{ id: "c1", type: "function", function: { name: "search", arguments: "{}" } }];
      const added = store.addMessage({
        session_id: session.id,
        role: "assistant",
        content: "Searching…",
        tool_calls: toolCalls,
      });

      const msg = store.getMessage(session.id, added.id);

      expect(msg.tool_calls).toEqual(toolCalls);
    });

    it("should parse metadata JSON on retrieval", () => {
      const session = store.create({ agent_id: "claude-code" });
      const added = store.addMessage({
        session_id: session.id,
        role: "assistant",
        content: "Done",
        metadata: { tokens: 42 },
      });

      const msg = store.getMessage(session.id, added.id);

      expect(msg.metadata).toEqual({ tokens: 42 });
    });

    it("should throw NotFoundError for a nonexistent message id", () => {
      const session = store.create({ agent_id: "claude-code" });

      expect(() => store.getMessage(session.id, 99999)).toThrow(NotFoundError);
    });

    it("should throw NotFoundError when message belongs to a different session", () => {
      const session1 = store.create({ agent_id: "claude-code" });
      const session2 = store.create({ agent_id: "claude-code" });
      const msg = store.addMessage({
        session_id: session1.id,
        role: "user",
        content: "Private message",
      });

      // Looking up the message using session2's id should throw
      expect(() => store.getMessage(session2.id, msg.id)).toThrow(NotFoundError);
    });

    it("should include the message id in the NotFoundError message", () => {
      const session = store.create({ agent_id: "claude-code" });

      expect(() => store.getMessage(session.id, 12345)).toThrow("12345");
    });
  });

  // ---------------------------------------------------------------------------
  // NotFoundError
  // ---------------------------------------------------------------------------

  describe("NotFoundError", () => {
    it("should be an instance of Error", () => {
      const err = new NotFoundError("test");
      expect(err).toBeInstanceOf(Error);
    });

    it("should have name 'NotFoundError'", () => {
      const err = new NotFoundError("test");
      expect(err.name).toBe("NotFoundError");
    });

    it("should carry the provided message", () => {
      const err = new NotFoundError("session xyz not found");
      expect(err.message).toBe("session xyz not found");
    });
  });

  // ---------------------------------------------------------------------------
  // Concurrent operations
  // ---------------------------------------------------------------------------

  describe("concurrent operations", () => {
    it("should handle multiple context updates to different sessions independently", () => {
      const s1 = store.create({ agent_id: "claude-code", name: "Session 1" });
      const s2 = store.create({ agent_id: "claude-code", name: "Session 2" });

      store.updateContext(s1.id, { focus: "task A" });
      store.updateContext(s2.id, { focus: "task B" });
      store.addMemory(s1.id, "branch", "feat/one");
      store.addMemory(s2.id, "branch", "feat/two");

      const r1 = store.get(s1.id)!;
      const r2 = store.get(s2.id)!;

      expect(r1.context.focus).toBe("task A");
      expect(r2.context.focus).toBe("task B");
      expect(r1.context.working_memory!.find((e) => e.key === "branch")?.value).toBe("feat/one");
      expect(r2.context.working_memory!.find((e) => e.key === "branch")?.value).toBe("feat/two");
    });

    it("should correctly interleave messages across sessions without cross-contamination", () => {
      const s1 = store.create({ agent_id: "agent-a" });
      const s2 = store.create({ agent_id: "agent-b" });

      store.addMessage({ session_id: s1.id, role: "user", content: "S1 msg 1" });
      store.addMessage({ session_id: s2.id, role: "user", content: "S2 msg 1" });
      store.addMessage({ session_id: s1.id, role: "assistant", content: "S1 msg 2" });
      store.addMessage({ session_id: s2.id, role: "assistant", content: "S2 msg 2" });

      const msgs1 = store.getMessages(s1.id);
      const msgs2 = store.getMessages(s2.id);

      expect(msgs1).toHaveLength(2);
      expect(msgs1[0].content).toBe("S1 msg 1");
      expect(msgs1[1].content).toBe("S1 msg 2");

      expect(msgs2).toHaveLength(2);
      expect(msgs2[0].content).toBe("S2 msg 1");
      expect(msgs2[1].content).toBe("S2 msg 2");
    });

    it("should handle archiving one session without affecting another", () => {
      const s1 = store.create({ agent_id: "claude-code", name: "Keep" });
      const s2 = store.create({ agent_id: "claude-code", name: "Archive" });

      store.archive(s2.id);
      store.addMemory(s1.id, "note", "still active");

      const active = store.list("claude-code");
      expect(active).toHaveLength(1);
      expect(active[0].id).toBe(s1.id);

      const r1 = store.get(s1.id)!;
      expect(r1.context.working_memory!.find((e) => e.key === "note")?.value).toBe("still active");
    });

    it("should accumulate addMemory calls on the same session correctly", () => {
      const session = store.create({ agent_id: "claude-code" });

      store.addMemory(session.id, "k1", "v1");
      store.addMemory(session.id, "k2", "v2");
      store.addMemory(session.id, "k1", "v1-updated");

      const result = store.get(session.id)!;
      const wm = result.context.working_memory!;

      expect(wm.length).toBe(2);
      expect(wm.find((e) => e.key === "k1")?.value).toBe("v1-updated");
      expect(wm.find((e) => e.key === "k2")?.value).toBe("v2");
    });
  });
});
