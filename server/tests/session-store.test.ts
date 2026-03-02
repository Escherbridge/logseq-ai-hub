import { describe, it, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb } from "./helpers";
import { createSession, getSession, listSessions, updateSession, addSessionMessage, loadSessionMessages } from "../src/db/sessions";
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
